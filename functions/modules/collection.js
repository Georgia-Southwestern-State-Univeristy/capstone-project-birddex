const {
  secureOnCall,
  clamp01,
  sanitizeCaptureSource,
  sanitizeCaptureGuardPayload,
  buildPointAwardDecision,
  resolvePointAwardEligibility,
  assertForumTextAllowed,
  sanitizeUsername,
  sanitizeText,
  getUserRateLimitRef,
  createPrivateAuditLogRef,
  sanitizeAuditMetadata,
  buildPrivateAuditLogPayload,
  queuePrivateAuditLog,
  writePrivateAuditLog,
  assertAndConsumeUserRateLimit,
  readUserRateLimitState,
  commitUserRateLimitState,
  normalizeContentFilterText,
  escapeRegex,
  checkBlockedWordMatch,
  checkBlockedWordWithBypass,
  buildModeratorTaggedSource,
  isBirdWhitelistMatch,
  getBlockedContentReason,
  logFilteredContent,
  assertNoBlockedContentOrThrow,
  roundCoordinateForStorage,
  buildRoundedLocationId,
  calculateHotspotBucketId,
  getOrCreateLocation,
  commitBatchOperations,
  normalizeHotspotBirdKeySegment,
  buildHotspotBirdKey,
  normalizeHotspotVoteValue,
  getHotspotVotesCollectionRef,
  getHotspotBirdSummaryRef,
  getHotspotSummaryRef,
  getUserBirdSightingRowsForHotspot,
  buildHotspotBirdAggregatesFromSightings,
  recomputeHotspotBirdSummary,
  recomputeHotspotSummary,
  recomputeHotspotVoteSummaryForHotspot,
  delay,
  callOpenAIWithRetry,
  generateAndSaveBirdFacts,
  generateAndSaveHunterFacts,
  getOrCreateAndSaveBirdFacts,
  functions,
  auth,
  admin,
  axios,
  crypto,
  db,
  storage,
  messaging,
  OPENAI_API_KEY,
  EBIRD_API_KEY,
  NUTHATCH_API_KEY,
  BIRDDEX_MODEL_API_KEY,
  CONFIG,
  PRIVATE_AUDIT_LOG_COLLECTION,
  USER_RATE_LIMITS,
  HYBRID_ID_CONFIG,
  IDENTIFICATION_FEEDBACK_CONFIG,
  CAPTURE_GUARD_CONFIG,
  FILTERED_CONTENT_LOG_COLLECTION,
  SERVER_NSFW_WORDS,
  SERVER_BIRD_WHITELIST,
  EMAIL_PATTERN,
  PHONE_PATTERN,
  URL_PATTERN,
  SPAM_REPETITION_PATTERN,
  CREDIT_CARD_PATTERN,
  ZALGO_PATTERN,
  logFilteredContentAttempt,
  logger,
  HttpsError,
  onCall,
  onSchedule,
  onDocumentCreated,
  onDocumentDeleted,
  onDocumentUpdated,
  defineSecret,
  GoogleAuth,
  FieldValue,
  Timestamp
} = require('./_shared');

// ======================================================
// HELPER: Update user totals (IDEMPOTENT version)
// ======================================================
async function _updateUserTotals(userId, eventId, totalBirdsChange, duplicateBirdsChange, totalPointsChange) {
    const userRef = db.collection("users").doc(userId);
    const eventLogRef = db.collection("processedEvents").doc(eventId);

    try {
        await db.runTransaction(async (transaction) => {
            const eventDoc = await transaction.get(eventLogRef);
            if (eventDoc.exists) {
                logger.info(`Event ${eventId} already processed. Skipping stats update.`);
                return;
            }

            const userDoc = await transaction.get(userRef);
            if (!userDoc.exists) {
                logger.error(`_updateUserTotals: User ${userId} not found.`);
                return;
            }

            const { totalBirds = 0, duplicateBirds = 0, totalPoints = 0 } = userDoc.data();

            transaction.update(userRef, {
                totalBirds: Math.max(0, totalBirds + totalBirdsChange),
                duplicateBirds: Math.max(0, duplicateBirds + duplicateBirdsChange),
                totalPoints: Math.max(0, totalPoints + totalPointsChange),
            });

            // Mark event as processed
            transaction.set(eventLogRef, { userId, processedAt: admin.firestore.FieldValue.serverTimestamp() });
        });
    } catch (error) {
        logger.error(`Failed to update totals for user ${userId}:`, error);
    }
}

// ======================================================
// onUserBirdCreated
// FIX #21: isDuplicate and pointsEarned were client-supplied and blindly trusted.
// A malicious client could send isDuplicate=false and a large pointsEarned to inflate scores,
// or set isDuplicate=true to suppress legitimate duplicate detection.
// Fix: determine isDuplicate server-side by checking whether another userBird doc already
// exists for this (userId, birdSpeciesId) pair (excluding this new document).
// Compute pointsEarned server-side from the bird's rarity field in the birds collection,
// ignoring the client-provided value entirely.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that runs after a bird save. This is the final server-side points/totals gate
 * for saved captures.
 */
exports.onUserBirdCreated = onDocumentCreated("userBirds/{uploadId}", async (event) => {
    // Read the new userBird document that was just created.
    const userBirdData = event.data.data();

    // userId = who saved/identified the bird
    // birdSpeciesId = which species this bird is
    // awardPoints = whether this save is even allowed to earn points
    // (for example, your gallery-upload flow can set this to false)
    const {
        userId,
        birdSpeciesId,
        awardPoints = true,
        identificationId = null,
        identificationLogId = null,
    } = userBirdData;

    // Safety check: if there is no userId, we cannot update totals correctly.
    if (!userId) {
        logger.error("onUserBirdCreated: No userId in document.");
        return null;
    }

    // This is the cooldown length for earning points on the SAME species.
    // 5 minutes = 5 * 60 * 1000 milliseconds.
    const POINT_COOLDOWN_MS = 5 * 60 * 1000;

    // Current server timestamp.
    const nowTs = admin.firestore.Timestamp.now();
    const pointEligibility = awardPoints
        ? await resolvePointAwardEligibility({ identificationId, identificationLogId })
        : {
            allowPointAward: false,
            reason: "client_award_points_disabled",
            captureSource: "unknown",
            suspicionScore: 0,
        };

    // Default values before we inspect this user's history for this species.
    let isDuplicate = false;          // true if this user already had this species before
    let pointsEarned = 0;             // how many points THIS identification gives
    let pointAwardedAt = null;        // when this identification actually gave a point
    let pointCooldownBlocked = false; // true if it was blocked by the 5-minute cooldown
    let pointAwardBlockedReason = awardPoints ? (pointEligibility.reason || null) : "client_award_points_disabled";

    // Only do species-based logic if the document has a birdSpeciesId.
    if (birdSpeciesId) {
        // Pull all userBird docs for this SAME user and SAME species.
        // We use this for two things:
        // 1) figure out whether this species is a duplicate for the user
        // 2) find the most recent time this species actually gave a point
        const sameSpeciesSnap = await db.collection("userBirds")
            .where("userId", "==", userId)
            .where("birdSpeciesId", "==", birdSpeciesId)
            .get();

        // Remove the document that just triggered this function from the list,
        // because we only want to compare against older entries.
        const otherDocs = sameSpeciesSnap.docs.filter(doc => doc.id !== event.params.uploadId);

        // If any older doc for this species exists, mark it as duplicate.
        isDuplicate = otherDocs.length > 0;

        // Track the most recent time this species earned points.
        let latestPointAwardMs = null;

        for (const doc of otherDocs) {
            const data = doc.data();

            // Read how many points that older identification earned.
            // Only old entries with pointsEarned > 0 should start a cooldown.
            const prevPointsEarned = Number(data.pointsEarned || 0);

            // Read the timestamp of when that old identification earned its point.
            const prevPointAwardedAt = data.pointAwardedAt;

            // Only consider older entries that actually awarded points and have a valid timestamp.
            if (prevPointsEarned > 0 && prevPointAwardedAt && typeof prevPointAwardedAt.toMillis === "function") {
                const prevMs = prevPointAwardedAt.toMillis();

                // Keep the newest point-award time we find.
                if (latestPointAwardMs === null || prevMs > latestPointAwardMs) {
                    latestPointAwardMs = prevMs;
                }
            }
        }

        // Only attempt to give points if this identification is allowed to award points.
        if (awardPoints && pointEligibility.allowPointAward) {
            // If the same species earned a point less than 5 minutes ago,
            // block the new point.
            const isWithinCooldown =
                latestPointAwardMs !== null &&
                (nowTs.toMillis() - latestPointAwardMs) < POINT_COOLDOWN_MS;

            if (isWithinCooldown) {
                // Same species was already rewarded too recently, so give 0 points.
                pointsEarned = 0;
                pointCooldownBlocked = true;
                pointAwardedAt = null;
                pointAwardBlockedReason = "species_point_cooldown";
            } else {
                // Cooldown has passed (or no previous point-award exists),
                // so give exactly 1 point for this identification.
                pointsEarned = 1;
                pointCooldownBlocked = false;
                pointAwardedAt = nowTs;
                pointAwardBlockedReason = null;
            }
        }
    }

    // Save the server-computed fields back onto the userBird document
    // so the app can see:
    // - whether it is a duplicate
    // - whether it earned a point
    // - whether the cooldown blocked it
    // - when the point was awarded
    await event.data.ref.update({
        isDuplicate,
        pointsEarned,
        pointAwardedAt,
        pointCooldownBlocked,
        pointAwardCaptureEligible: pointEligibility.allowPointAward === true,
        pointAwardBlockedReason: pointAwardBlockedReason || null,
        captureSource: pointEligibility.captureSource || null,
        captureGuardSuspicionScore: pointEligibility.suspicionScore ?? null,
        captureGuardScreenArtifactScore: pointEligibility.screenArtifactScore ?? null,
    }).catch(e =>
        logger.warn(
            `onUserBirdCreated: Failed to patch computed fields on ${event.params.uploadId}:`,
            e
        )
    );

    // Update the user's totals in an idempotent way.
    // +1 totalBirds because a new userBird was created
    // +1 duplicateBirds only if this species already existed for the user
    // +pointsEarned adds either 1 or 0 depending on the cooldown
    await _updateUserTotals(
        userId,
        `CREATED_${event.params.uploadId}`,
        1,
        isDuplicate ? 1 : 0,
        pointsEarned
    );

    return null;
});

// ======================================================
// onUserBirdDeleted
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Firestore trigger that reverses totals/counters and cleanup when a saved user bird is deleted.
 */
exports.onUserBirdDeleted = onDocumentDeleted("userBirds/{uploadId}", async (event) => {
    const userBirdData = event.data.data();
    const { userId, pointsEarned = 0, isDuplicate = false } = userBirdData;
    if (!userId) {
        logger.error("onUserBirdDeleted: No userId in document.");
        return null;
    }
    // Use the document ID (uploadId) as the unique event key for idempotency
    await _updateUserTotals(
        userId,
        `DELETED_${event.params.uploadId}`,
        -1,
        isDuplicate ? -1 : 0,
        0
    );
    return null;
});

// ======================================================
// upgradeCollectionSlotRarity — server-side rarity upgrade with point spending
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeCardRarity(rarity) {
    const value = String(rarity || "").trim().toLowerCase();
    switch (value) {
        case "common":
            return "Common";
        case "uncommon":
            return "Uncommon";
        case "rare":
            return "Rare";
        case "epic":
            return "Epic";
        case "legendary":
            return "Legendary";
        case "mythic":
            return "Mythic";
        default:
            throw new HttpsError("invalid-argument", `Invalid rarity: ${rarity}`);
    }
}

/**
 * Helper: Loads or returns the requested value/entity in a backend-safe format.
 */
function getCardUpgradeCost(currentRarity, targetRarity) {
    const rarityOrder = ["Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"];
    const upgradeCosts = {
        "Common->Uncommon": 10,
        "Uncommon->Rare": 20,
        "Rare->Epic": 30,
        "Epic->Legendary": 50,
        "Legendary->Mythic": 100,
    };
    const currentIndex = rarityOrder.indexOf(currentRarity);
    const targetIndex = rarityOrder.indexOf(targetRarity);
    if (currentIndex === -1 || targetIndex === -1) {
        throw new HttpsError(
            "invalid-argument",
            `Invalid rarity transition: ${currentRarity} -> ${targetRarity}`
        );
    }
    if (targetIndex <= currentIndex) {
        throw new HttpsError(
            "failed-precondition",
            `Target rarity must be higher than current rarity. Current: ${currentRarity}, Target: ${targetRarity}`
        );
    }
    let totalCost = 0;
    for (let i = currentIndex; i < targetIndex; i++) {
        const stepKey = `${rarityOrder[i]}->${rarityOrder[i + 1]}`;
        const stepCost = upgradeCosts[stepKey];
        if (typeof stepCost !== "number") {
            throw new HttpsError(
                "internal",
                `Missing upgrade cost for ${stepKey}`
            );
        }
        totalCost += stepCost;
    }
    return totalCost;
}

/**
 * Export: Callable that spends points to upgrade a collection card rarity tier.
 */
exports.upgradeCollectionSlotRarity = secureOnCall(async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Login required.");
    }
    const userId = request.auth.uid;
    const rawSlotId = request.data && typeof request.data.slotId === "string"
        ? request.data.slotId.trim()
        : "";
    const rawBirdId = request.data && typeof request.data.birdId === "string"
        ? request.data.birdId.trim()
        : "";
    const rawTargetRarity = request.data && typeof request.data.targetRarity === "string"
        ? request.data.targetRarity
        : null;
    if (!rawTargetRarity) {
        throw new HttpsError("invalid-argument", "targetRarity is required.");
    }
    const targetRarity = normalizeCardRarity(rawTargetRarity);
    const slotId = rawSlotId || (rawBirdId ? `${userId}_${rawBirdId}` : "");
    if (!slotId) {
        throw new HttpsError("invalid-argument", "slotId or birdId is required.");
    }
    const userRef = db.collection("users").doc(userId);
    const slotRef = db.collection("users").doc(userId).collection("collectionSlot").doc(slotId);
    try {
        const result = await db.runTransaction(async (transaction) => {
            const [userSnap, slotSnap] = await Promise.all([
                transaction.get(userRef),
                transaction.get(slotRef),
            ]);
            if (!userSnap.exists) {
                throw new HttpsError("not-found", "User profile not found.");
            }
            if (!slotSnap.exists) {
                throw new HttpsError("not-found", "Collection slot not found.");
            }
            const userData = userSnap.data() || {};
            const slotData = slotSnap.data() || {};
            const currentRarity = normalizeCardRarity(slotData.rarity);
            const upgradeCost = getCardUpgradeCost(currentRarity, targetRarity);
            const currentPoints = Number(userData.totalPoints || 0);
            if (!Number.isFinite(currentPoints)) {
                throw new HttpsError("internal", "User points are invalid.");
            }
            if (currentPoints < upgradeCost) {
                throw new HttpsError(
                    "failed-precondition",
                    `Not enough points. Need ${upgradeCost}, but only have ${currentPoints}.`
                );
            }
            const remainingPoints = currentPoints - upgradeCost;
            transaction.update(userRef, {
                totalPoints: remainingPoints,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            transaction.update(slotRef, {
                rarity: targetRarity,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            return {
                slotId,
                oldRarity: currentRarity,
                newRarity: targetRarity,
                pointsSpent: upgradeCost,
                remainingPoints,
            };
        });
        logger.info(
            `upgradeCollectionSlotRarity: userId=${userId} slotId=${result.slotId} ${result.oldRarity} -> ${result.newRarity} spent=${result.pointsSpent} remaining=${result.remainingPoints}`
        );
        return {
            success: true,
            ...result,
        };
    } catch (error) {
        logger.error("upgradeCollectionSlotRarity failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to upgrade card: ${error.message}`);
    }
});

// ======================================================
// HELPER: Calculate Downgrade Refund (Matches Java CardRarityHelper)
// ======================================================
function getCardDowngradeRefund(currentRarity, targetRarity) {
    const rarityOrder = ["Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"];
    const upgradeCosts = {
        "Common->Uncommon": 10,
        "Uncommon->Rare": 20,
        "Rare->Epic": 30,
        "Epic->Legendary": 50,
        "Legendary->Mythic": 100,
    };

    const currentIndex = rarityOrder.indexOf(currentRarity);
    const targetIndex = rarityOrder.indexOf(targetRarity);

    if (currentIndex === -1 || targetIndex === -1 || targetIndex >= currentIndex) {
        throw new HttpsError("invalid-argument", "Invalid downgrade path.");
    }

    let totalSpent = 0;
    // Calculate total cost spent between target and current
    for (let i = targetIndex; i < currentIndex; i++) {
        const stepKey = `${rarityOrder[i]}->${rarityOrder[i + 1]}`;
        totalSpent += upgradeCosts[stepKey];
    }

    // Return 75% refund (rounded down)
    return Math.floor(totalSpent * 0.75);
}

// ======================================================
// revertCollectionSlotRarity — server-side rarity downgrade with refund
// ======================================================
exports.revertCollectionSlotRarity = secureOnCall(async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Login required.");
    }

    const userId = request.auth.uid;
    const slotId = request.data?.slotId;
    const rawTargetRarity = request.data?.targetRarity;

    if (!slotId || !rawTargetRarity) {
        throw new HttpsError("invalid-argument", "slotId and targetRarity are required.");
    }

    const targetRarity = normalizeCardRarity(rawTargetRarity);
    const userRef = db.collection("users").doc(userId);
    const slotRef = db.collection("users").doc(userId).collection("collectionSlot").doc(slotId);

    try {
        const result = await db.runTransaction(async (transaction) => {
            const [userSnap, slotSnap] = await Promise.all([
                transaction.get(userRef),
                transaction.get(slotRef),
            ]);

            if (!userSnap.exists || !slotSnap.exists) {
                throw new HttpsError("not-found", "User or Card Slot not found.");
            }

            const slotData = slotSnap.data();
            const currentRarity = normalizeCardRarity(slotData.rarity);

            // Calculate refund amount
            const refundAmount = getCardDowngradeRefund(currentRarity, targetRarity);
            const currentPoints = Number(userSnap.data().totalPoints || 0);

            // Update Database
            transaction.update(userRef, {
                totalPoints: currentPoints + refundAmount,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            transaction.update(slotRef, {
                rarity: targetRarity,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            return { refundAmount, newTotal: currentPoints + refundAmount };
        });

        logger.info(`User ${userId} reverted card ${slotId} to ${targetRarity}. Refunded: ${result.refundAmount}`);
        return { success: true, ...result };

    } catch (error) {
        logger.error("revertCollectionSlotRarity failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", error.message);
    }
});

