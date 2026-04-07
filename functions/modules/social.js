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
// NEW: toggleFollow — atomic follow/unfollow
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that follows or unfollows another user and keeps follower/following counters
 * consistent.
 */
exports.toggleFollow = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const followerId = request.auth.uid;
    const { targetUserId, action } = request.data || {};

    if (!targetUserId || typeof targetUserId !== "string") {
        throw new HttpsError("invalid-argument", "targetUserId is required.");
    }
    if (action !== "follow" && action !== "unfollow") {
        throw new HttpsError("invalid-argument", "action must be 'follow' or 'unfollow'.");
    }
    if (followerId === targetUserId) {
        throw new HttpsError("invalid-argument", "Cannot follow yourself.");
    }

    const followerRef = db.collection("users").doc(followerId);
    const targetRef = db.collection("users").doc(targetUserId);
    const followingDocRef = followerRef.collection("following").doc(targetUserId);
    const followerDocRef = targetRef.collection("followers").doc(followerId);

    const result = await db.runTransaction(async (t) => {
        const [followingDoc, followerSnap, targetSnap, followRateLimitState] = await Promise.all([
            t.get(followingDocRef),
            t.get(followerRef),
            t.get(targetRef),
            readUserRateLimitState(t, followerId, "followToggle", USER_RATE_LIMITS.followToggle, {
                targetUserId,
                action,
            }),
        ]);

        commitUserRateLimitState(t, followRateLimitState);

        if (!followerSnap.exists || !targetSnap.exists) {
            throw new HttpsError("not-found", "User profile not found.");
        }

        let changed = false;
        if (action === "follow" && !followingDoc.exists) {
            t.set(followingDocRef, { timestamp: admin.firestore.FieldValue.serverTimestamp() });
            t.set(followerDocRef, { timestamp: admin.firestore.FieldValue.serverTimestamp() });
            t.update(followerRef, { followingCount: admin.firestore.FieldValue.increment(1) });
            t.update(targetRef, { followerCount: admin.firestore.FieldValue.increment(1) });
            changed = true;
        } else if (action === "unfollow" && followingDoc.exists) {
            t.delete(followingDocRef);
            t.delete(followerDocRef);
            t.update(followerRef, { followingCount: admin.firestore.FieldValue.increment(-1) });
            t.update(targetRef, { followerCount: admin.firestore.FieldValue.increment(-1) });
            changed = true;
        }

        return { success: true, action, following: action === "follow", changed };
    });

    return result;
});

// ======================================================
// NEW: getLeaderboard — server-side top 20 by points
// ======================================================
/**
 * Retrieves data from Firestore or an external service and normalizes it for the caller.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that returns leaderboard data derived from user totals.
 */
exports.getLeaderboard = secureOnCall(async (request) => {
    try {
        // This is where the function touches Firestore documents/collections for the requested action.
        const snapshot = await db.collection("users")
            .orderBy("totalPoints", "desc")
            .limit(20)
            .get();

        return snapshot.docs.map(doc => ({
            id: doc.id,
            username: doc.data().username,
            totalPoints: doc.data().totalPoints,
            profilePictureUrl: doc.data().profilePictureUrl || null
        }));
    } catch (error) {
        logger.error("Error fetching leaderboard:", error);
        throw new HttpsError("internal", "Failed to fetch leaderboard.");
    }
});

// ======================================================
// HELPER: (for the function below) tracked bird notification fan-out
// ======================================================
function haversineMiles(lat1, lon1, lat2, lon2) {
    const toRad = (deg) => deg * Math.PI / 180;
    const R = 3958.8; // Earth radius in miles

    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);

    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

// ======================================================
// HELPER: tracked bird notification fan-out
// ======================================================
async function notifyTrackedBirdWatchers({
    sightingId,
    birdId,
    commonName,
    scientificName,
    latitude,
    longitude,
    localityName,
    source,
    sourceUserId
}) {
    if (!birdId || !sightingId) return null;

    const trackedSnap = await db.collectionGroup("trackedBirds")
        .where("birdId", "==", birdId)
        .get();

    if (trackedSnap.empty) return null;

    await Promise.all(trackedSnap.docs.map(async (trackedDoc) => {
        try {
            const userRef = trackedDoc.ref.parent.parent;
            if (!userRef) return null;

            const recipientUserId = userRef.id;
            if (!recipientUserId) return null;

            // Do not notify someone about their own user-created sighting.
            if (sourceUserId && recipientUserId === sourceUserId) return null;

            const trackedData = trackedDoc.data() || {};
            if (trackedData.lastNotifiedSightingId === sightingId) return null;

            const recipientDoc = await userRef.get();
            if (!recipientDoc.exists) return null;

            const recipientData = recipientDoc.data() || {};
            const {
                fcmToken,
                notificationsEnabled = true,
                trackedBirdsNotificationsEnabled = true,
                trackedBirdsCooldownHours = 0,
                trackedBirdsMaxDistanceMiles = -1,
                lastKnownLatitude = null,
                lastKnownLongitude = null
            } = recipientData;

            if (!notificationsEnabled || !trackedBirdsNotificationsEnabled || !fcmToken) {
                return null;
            }

            // Distance filter: -1 means any distance, 150 means within 150 miles.
            if (
                trackedBirdsMaxDistanceMiles > 0 &&
                typeof latitude === "number" &&
                typeof longitude === "number"
            ) {
                if (
                    typeof lastKnownLatitude !== "number" ||
                    typeof lastKnownLongitude !== "number"
                ) {
                    return null;
                }

                const distanceMiles = haversineMiles(
                    lastKnownLatitude,
                    lastKnownLongitude,
                    latitude,
                    longitude
                );

                if (distanceMiles > trackedBirdsMaxDistanceMiles) {
                    return null;
                }
            }

            const lastNotifiedAt = trackedData.lastNotifiedAt;
            if (
                trackedBirdsCooldownHours > 0 &&
                lastNotifiedAt &&
                (Date.now() - lastNotifiedAt.toDate().getTime()) < trackedBirdsCooldownHours * 60 * 60 * 1000
            ) {
                return null;
            }

            const eventLogRef = db.collection("processedEvents")
                .doc(`TRACKED_BIRD_NOTIFY_${recipientUserId}_${sightingId}`);

            let shouldSend = false;

            await db.runTransaction(async (t) => {
                const eventDoc = await t.get(eventLogRef);
                if (eventDoc.exists) return;

                t.set(eventLogRef, {
                    processedAt: admin.firestore.FieldValue.serverTimestamp(),
                    type: "TRACKED_BIRD_NOTIFY",
                    recipientUserId,
                    sightingId,
                    birdId,
                    source: source || "unknown"
                });

                shouldSend = true;
            });

            if (!shouldSend) return null;

            const title = "Tracked bird spotted!";
            const localitySuffix = localityName ? ` near ${localityName}` : " nearby";
            const body = `${commonName || scientificName || birdId} was reported${localitySuffix}.`;

            try {
                await messaging.send({
                    token: fcmToken,
                    notification: { title, body },
                    data: {
                        type: "tracked_bird",
                        birdId: String(birdId),
                        commonName: String(commonName || ""),
                        scientificName: String(scientificName || ""),
                        sightingId: String(sightingId),
                        source: String(source || "unknown"),
                        latitude: latitude != null ? String(latitude) : "",
                        longitude: longitude != null ? String(longitude) : "",
                        localityName: String(localityName || "")
                    }
                });

                await trackedDoc.ref.set({
                    lastNotifiedAt: admin.firestore.FieldValue.serverTimestamp(),
                    lastNotifiedSightingId: sightingId,
                    lastNotificationSource: source || "unknown"
                }, { merge: true });
            } catch (error) {
                if (error.code === "messaging/registration-token-not-registered") {
                    await userRef.update({
                        fcmToken: admin.firestore.FieldValue.delete()
                    });
                } else {
                    logger.error("Error sending tracked bird notification:", error);
                }

                await eventLogRef.delete().catch(() => null);
            }
        } catch (error) {
            logger.error("notifyTrackedBirdWatchers recipient failed:", error);
        }

        return null;
    }));

    return null;
}

// ======================================================
// onUserBirdSightingCreated — notify tracked bird watchers
// ======================================================
exports.onUserBirdSightingCreated = onDocumentCreated("userBirdSightings/{sightingId}", async (event) => {
    try {
        const data = event.data?.data();
        if (!data) return null;

        await notifyTrackedBirdWatchers({
            sightingId: event.params.sightingId,
            birdId: data.birdId,
            commonName: data.commonName,
            scientificName: data.scientificName,
            latitude: typeof data.latitude === "number" ? data.latitude : (data.location?.latitude ?? null),
            longitude: typeof data.longitude === "number" ? data.longitude : (data.location?.longitude ?? null),
            localityName: data.locality || data.location?.localityName || "",
            source: "userBirdSightings",
            sourceUserId: data.userId || data.user_sighting?.userId || null
        });
    } catch (error) {
        logger.error("Error in onUserBirdSightingCreated:", error);
    }
    return null;
});

// ======================================================
// onEBirdApiSightingCreated — notify tracked bird watchers
// ======================================================
exports.onEBirdApiSightingCreated = onDocumentCreated("eBirdApiSightings/{sightingId}", async (event) => {
    try {
        const data = event.data?.data();
        if (!data) return null;

        await notifyTrackedBirdWatchers({
            sightingId: event.params.sightingId,
            birdId: data.speciesCode,
            commonName: data.commonName,
            scientificName: data.scientificName,
            latitude: data.location?.latitude ?? null,
            longitude: data.location?.longitude ?? null,
            localityName: data.location?.localityName || "",
            source: "eBirdApiSightings",
            sourceUserId: null
        });
    } catch (error) {
        logger.error("Error in onEBirdApiSightingCreated:", error);
    }
    return null;
});

