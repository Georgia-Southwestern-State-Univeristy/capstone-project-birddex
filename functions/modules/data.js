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
// getGeorgiaBirds (Callable version)
// ======================================================
/**
 * Retrieves data from Firestore or an external service and normalizes it for the caller.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that returns the cached Georgia bird list, fetching/refreshing data when needed.
 */
exports.getGeorgiaBirds = secureOnCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    try {
        await _syncGeorgiaBirdsCore();

        // Fetch and return the list (to maintain backward compatibility with client expectations)
        // This is where the function touches Firestore documents/collections for the requested action.
        const cacheDoc = await db.collection("ebird_ga_cache").doc("data").get();
        const birdIds = cacheDoc.data()?.birdIds || [];

        const gaCoreBirds = (await Promise.all(
            birdIds.map(id => db.collection("birds").doc(id).get().then(d => d.exists ? d.data() : null))
        )).filter(Boolean);

        return { birds: gaCoreBirds };
    } catch (error) {
        logger.error("Callable getGeorgiaBirds failed:", error);
        throw new HttpsError("internal", `Sync failed: ${error.message}`);
    }
});

// ======================================================
// scheduledGetGeorgiaBirds (Scheduled version - Every 72h)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Scheduled refresh that keeps the Georgia bird list cache warm without needing a user request.
 */
exports.scheduledGetGeorgiaBirds = onSchedule({
    schedule: "every 72 hours",
    secrets: [EBIRD_API_KEY],
    timeoutSeconds: 300
}, async (event) => {
    logger.info("Scheduled Georgia birds sync starting.");
    try {
        await _syncGeorgiaBirdsCore();
    } catch (error) {
        logger.error("Scheduled Georgia birds sync failed:", error);
    }
    return null;
});

// ======================================================
// HELPER: Core eBird notable sightings fetch/store
// ======================================================
async function _fetchAndStoreEBirdDataCore() {
    logger.info("Executing _fetchAndStoreEBirdDataCore...");
    const REGION_CODE = "US-GA";

    try {
        const response = await axios.get(`https://api.ebird.org/v2/data/obs/${REGION_CODE}/recent/notable`, {
            headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() },
            timeout: 15000
        });

        const ebirdSightings = response.data;
        if (!ebirdSightings || ebirdSightings.length === 0) {
            logger.info("No new eBird sightings found.");
            return { status: "success", message: "No new eBird sightings found." };
        }

        logger.info(`Found ${ebirdSightings.length} sightings from eBird API.`);
        const batch = db.batch();
        let sightingsAdded = 0;

        for (const sighting of ebirdSightings) {
            const docId = sighting.subId;
            if (!docId) continue;
            batch.set(db.collection("eBirdApiSightings").doc(docId), {
                speciesCode: sighting.speciesCode,
                commonName: sighting.comName,
                scientificName: sighting.sciName,
                observationDate: new Date(sighting.obsDt),
                location: {
                    latitude: sighting.lat,
                    longitude: sighting.lng,
                    localityName: sighting.locName,
                },
                howMany: sighting.howMany || 1,
                isReviewed: sighting.obsReviewed,
            });
            sightingsAdded++;
        }

        if (sightingsAdded > 0) await batch.commit();
        logger.info(`Successfully stored ${sightingsAdded} eBird sightings.`);
        return { status: "success", message: `Successfully added or updated ${sightingsAdded} eBird sightings.` };
    } catch (error) {
        logger.error("Error fetching eBird data:", error);
        throw new HttpsError("internal", `eBird data processing failed: ${error.message}`);
    }
}

// ======================================================
// fetchAndStoreEBirdData (scheduled)
// ======================================================
/**
 * Retrieves data from Firestore or an external service and normalizes it for the caller.
 */
/**
 * Export: Scheduled job that pulls fresh eBird sighting data into Firestore.
 */
exports.fetchAndStoreEBirdData = onSchedule({
    schedule: "every 72 hours",
    secrets: [EBIRD_API_KEY],
    timeoutSeconds: 300
}, async (event) => {
    logger.info("Scheduled eBird data fetch starting.");
    try {
        await _fetchAndStoreEBirdDataCore();
    } catch (error) {
        logger.error("Scheduled eBird fetch failed:", error);
    }
    return null;
});

// ======================================================
// triggerEbirdDataFetch (callable, for app warmup)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Callable admin/manual trigger that runs the eBird fetch logic on demand.
 */
exports.triggerEbirdDataFetch = secureOnCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
    logger.info("Callable eBird data fetch triggered.");
    try {
        return await _fetchAndStoreEBirdDataCore();
    } catch (error) {
        logger.error("Callable eBird fetch failed:", error);
        throw error;
    }
});

// ======================================================
// cleanupUnverifiedUsers (scheduled, every 24h)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Scheduled cleanup for unverified accounts that aged past the configured TTL.
 */
exports.cleanupUnverifiedUsers = onSchedule({
    schedule: "every 24 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 60
}, async (event) => {
    logger.info("Starting scheduled cleanup of unverified users...");
    const cutoff = Date.now() - CONFIG.UNVERIFIED_USER_TTL_MS;

    try {
        let nextPageToken;
        let deletedCount = 0;
        do {
            const listUsersResult = await admin.auth().listUsers(1000, nextPageToken);
            nextPageToken = listUsersResult.pageToken;
            await Promise.all(listUsersResult.users.map(async (userRecord) => {
                if (!userRecord.emailVerified &&
                    new Date(userRecord.metadata.creationTime).getTime() < cutoff) {
                    await admin.auth().deleteUser(userRecord.uid);
                    deletedCount++;
                }
            }));
        } while (nextPageToken);

        logger.info(`Unverified user cleanup complete. Deleted: ${deletedCount}`);
    } catch (error) {
        logger.error("Error during unverified user cleanup:", error);
    }
    return null;
});

// ======================================================
// cleanupStaleIdentificationImages (scheduled, every 24h)
// ======================================================
/**
 * Helper: Deletes old orphaned identification images left behind in Storage if the app/client
 * failed to remove them earlier in the identification flow.
 */
async function deleteStaleIdentificationImagesJob() {
    const bucket = admin.storage().bucket();
    const cutoff = Date.now() - CONFIG.IDENTIFICATION_IMAGE_TTL_MS;

    let deletedCount = 0;
    let skippedCount = 0;
    let checkedCount = 0;

    try {
        const [files] = await bucket.getFiles({ prefix: "identificationImages/" });

        for (const file of files) {
            try {
                // Skip folder placeholders / invalid entries.
                if (!file || !file.name || file.name.endsWith("/")) {
                    continue;
                }

                checkedCount++;

                const metadata = file.metadata || {};
                const createdAtRaw = metadata.timeCreated || metadata.updated;

                if (!createdAtRaw) {
                    logger.warn(`cleanupStaleIdentificationImages: Missing timestamp for ${file.name}, skipping.`);
                    skippedCount++;
                    continue;
                }

                const createdAtMs = new Date(createdAtRaw).getTime();
                if (Number.isNaN(createdAtMs)) {
                    logger.warn(`cleanupStaleIdentificationImages: Invalid timestamp for ${file.name}, skipping.`);
                    skippedCount++;
                    continue;
                }

                if (createdAtMs >= cutoff) {
                    skippedCount++;
                    continue;
                }

                await file.delete().catch((err) => {
                    // Ignore already-gone style races.
                    if (err && (err.code === 404 || err.code === 412)) {
                        logger.info(`cleanupStaleIdentificationImages: File already gone: ${file.name}`);
                        return;
                    }
                    throw err;
                });

                deletedCount++;
                logger.info(`cleanupStaleIdentificationImages: Deleted stale file ${file.name}`);
            } catch (fileErr) {
                logger.error(`cleanupStaleIdentificationImages: Failed for file ${file?.name || "unknown"}`, fileErr);
            }
        }

        logger.info("cleanupStaleIdentificationImages complete.", {
            checkedCount,
            deletedCount,
            skippedCount
        });
    } catch (error) {
        logger.error("cleanupStaleIdentificationImages job failed.", error);
    }

    return null;
}

/**
 * Export: Scheduled Storage cleanup for stale identification images that were uploaded but never
 * successfully kept/saved by the user flow.
 */
exports.cleanupStaleIdentificationImages = onSchedule({
    schedule: "every 24 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 300
}, async (event) => {
    const lockRef = db.collection("schedulerLocks").doc("cleanupStaleIdentificationImages");
    const now = Date.now();
    const lockTimeoutMs = 15 * 60 * 1000; // 15 minutes

    try {
        let alreadyRunning = false;

        await db.runTransaction(async (transaction) => {
            const lockDoc = await transaction.get(lockRef);

            if (lockDoc.exists) {
                const lockData = lockDoc.data() || {};
                const lockedAt = lockData.lockedAt?.toMillis?.() || 0;

                if (lockedAt && (now - lockedAt) < lockTimeoutMs) {
                    alreadyRunning = true;
                    return;
                }
            }

            transaction.set(lockRef, {
                lockedAt: admin.firestore.FieldValue.serverTimestamp()
            }, { merge: true });
        });

        if (alreadyRunning) {
            logger.info("cleanupStaleIdentificationImages: Existing lock found, skipping this run.");
            return null;
        }

        logger.info("cleanupStaleIdentificationImages: Starting scheduled cleanup.");
        await deleteStaleIdentificationImagesJob();
    } catch (error) {
        logger.error("cleanupStaleIdentificationImages: Scheduled run failed.", error);
    } finally {
        await lockRef.delete().catch((err) => {
            logger.warn("cleanupStaleIdentificationImages: Failed to clear scheduler lock.", err);
        });
    }

    return null;
});

// ======================================================
// onDeleteUserBirdImage
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Storage/Firestore cleanup trigger for deleted user bird images.
 */
exports.onDeleteUserBirdImage = onDocumentDeleted("users/{userId}/userBirdImage/{userBirdImageId}", async (event) => {
    const deletedImage = event.data.data();
    const userId = event.params.userId;
    const { userBirdRefId, imageUrl, birdId } = deletedImage;

    if (!userBirdRefId) {
        logger.info("onDeleteUserBirdImage: No userBirdRefId found, skipping.");
        return null;
    }

    try {
        const batch = db.batch();

        // 1. Delete from storage
        if (imageUrl && imageUrl.includes("userCollectionImages")) {
            try {
                const decodedUrl = decodeURIComponent(imageUrl);
                const filePath = decodedUrl.substring(decodedUrl.indexOf("/o/") + 3, decodedUrl.indexOf("?alt=media"));
                await admin.storage().bucket().file(filePath).delete();
            } catch (storageErr) {
                logger.error(`Failed to delete image from storage: ${imageUrl}`, storageErr);
            }
        }

        // 2. Update collectionSlot
        if (birdId) {
            // This is where the function touches Firestore documents/collections for the requested action.
            const collectionSlotSnap = await db.collection("users").doc(userId).collection("collectionSlot")
                .where("birdId", "==", birdId).get();

            for (const slotDoc of collectionSlotSnap.docs) {
                if (slotDoc.data().imageUrl === imageUrl) {
                    const otherImagesSnap = await db.collection("users").doc(userId).collection("userBirdImage")
                        .where("birdId", "==", birdId).limit(1).get();

                    if (!otherImagesSnap.empty) {
                        const replacement = otherImagesSnap.docs[0].data();
                        batch.update(slotDoc.ref, { imageUrl: replacement.imageUrl, userBirdId: replacement.userBirdRefId });
                    } else {
                        batch.delete(slotDoc.ref);
                    }
                }
            }
        }

        // 3. Delete associated sightings
        const sightingsSnap = await db.collection("userBirdSightings")
            .where("userId", "==", userId).where("imageUrl", "==", imageUrl).get();
        sightingsSnap.forEach(doc => batch.delete(doc.ref));

        await batch.commit();

        // 4. ATOMIC CLEANUP OF PARENT USERBIRD (Fixed race condition)
        const userBirdRef = db.collection("userBirds").doc(userBirdRefId);

        let shouldDeleteAndDecrement = false;
        let capturedPointsEarned = 0;
        let capturedIsDuplicate = false;

        await db.runTransaction(async (transaction) => {
            const userBirdDoc = await transaction.get(userBirdRef);
            if (!userBirdDoc.exists) return;

            const { imageCount = 1, pointsEarned = 0, isDuplicate = false } = userBirdDoc.data();
            const newImageCount = imageCount - 1;

            if (newImageCount <= 0) {
                // Capture values — we'll use them after the transaction commits
                shouldDeleteAndDecrement = true;
                capturedPointsEarned = pointsEarned;
                capturedIsDuplicate = isDuplicate;
                transaction.delete(userBirdRef);
            } else {
                transaction.update(userBirdRef, { imageCount: newImageCount });
            }
        });

        // FIX #4: Use the same eventId format as onUserBirdDeleted (`DELETED_${id}`).
        // Both triggers write to processedEvents with this key.  Whichever fires second
        // finds the key already present and exits — preventing the double-decrement.
        if (shouldDeleteAndDecrement) {
            await _updateUserTotals(
            userId,
            `DELETED_${userBirdRefId}`,
            -1,
            capturedIsDuplicate ? -1 : 0,
            0
        );
    }
    } catch (error) {
        logger.error(`Failed to cleanup for deleted image ${userBirdRefId}:`, error);
    }
    return null;
});

// ======================================================
// archiveStaleEBirdSightings (scheduled, every 6 hours)
// ======================================================
async function _archiveStaleEBirdSightingsCore() {
    logger.info("_archiveStaleEBirdSightingsCore: starting.");

    const lockRef = db.collection("schedulerLocks").doc("archiveStaleEBirdSightings");
    const STALE_LOCK_MS = 10 * 60 * 1000;
    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(lockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            if (startedAt && (Date.now() - startedAt.getTime()) < STALE_LOCK_MS) return false;
        }
        t.set(lockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        logger.info("_archiveStaleEBirdSightingsCore: another instance is running. Skipping.");
        return { status: "skipped", message: "Archive already in progress." };
    }

    try {
        const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
        const cutoffDate = new Date(Date.now() - THREE_DAYS_MS);

        const allSnap = await db.collection("eBirdApiSightings").get();
        if (allSnap.empty) {
            logger.info("_archiveStaleEBirdSightingsCore: no sightings found.");
            return { status: "success", message: "No sightings to process." };
        }

        // Group by speciesCode, track newest observationDate per species
        const speciesMap = new Map();
        for (const doc of allSnap.docs) {
            const data = doc.data();
            const speciesCode = data.speciesCode;
            if (!speciesCode) continue;

            let obsDate = null;
            if (data.observationDate) {
                obsDate = data.observationDate.toDate
                    ? data.observationDate.toDate()
                    : new Date(data.observationDate);
            }

            if (!speciesMap.has(speciesCode)) {
                speciesMap.set(speciesCode, { newestDate: null, docs: [] });
            }
            const entry = speciesMap.get(speciesCode);
            entry.docs.push({ id: doc.id, ref: doc.ref, data });
            if (obsDate && (!entry.newestDate || obsDate > entry.newestDate)) {
                entry.newestDate = obsDate;
            }
        }

        // Only archive species whose newest sighting is older than 3 days
        const staleDocs = [];
        for (const [code, entry] of speciesMap.entries()) {
            if (!entry.newestDate || entry.newestDate < cutoffDate) {
                staleDocs.push(...entry.docs);
            }
        }

        if (staleDocs.length === 0) {
            logger.info("_archiveStaleEBirdSightingsCore: all species are fresh.");
            return { status: "success", message: "All sightings are fresh. Nothing archived." };
        }

        let archivedCount = 0;
        for (let i = 0; i < staleDocs.length; i += CONFIG.FIRESTORE_BATCH_SIZE) {
            const chunk = staleDocs.slice(i, i + CONFIG.FIRESTORE_BATCH_SIZE);
            const batch = db.batch();
            for (const { id, ref, data } of chunk) {
                batch.set(db.collection("eBirdApiSightings_backlog").doc(id), {
                    ...data,
                    archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                });
                batch.delete(ref);
            }
            await batch.commit();
            archivedCount += chunk.length;
            logger.info(`_archiveStaleEBirdSightingsCore: archived ${archivedCount}/${staleDocs.length}.`);
        }

        const summary = `Archived ${archivedCount} stale eBird sightings.`;
        logger.info(`_archiveStaleEBirdSightingsCore: done. ${summary}`);
        return { status: "success", message: summary };

    } finally {
        await lockRef.delete().catch(e =>
            logger.error("_archiveStaleEBirdSightingsCore: failed to release lock.", e)
        );
    }
}

/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Scheduled cleanup that archives old eBird sightings so only fresh map data stays active.
 */
exports.archiveStaleEBirdSightings = onSchedule({
    schedule: "every 6 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 300,
}, async (event) => {
    logger.info("Scheduled archiveStaleEBirdSightings starting.");
    try { await _archiveStaleEBirdSightingsCore(); }
    catch (error) { logger.error("Scheduled archiveStaleEBirdSightings failed:", error); }
    return null;
});

/**
 * Main backend logic block for this Firebase Functions file.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable/manual trigger for the stale eBird sighting archival job.
 */
exports.triggerArchiveStaleEBirdSightings = secureOnCall(async (request) => {
    logger.info("Callable archiveStaleEBirdSightings triggered.");
    try { return await _archiveStaleEBirdSightingsCore(); }
    catch (error) {
        logger.error("Callable archiveStaleEBirdSightings failed:", error);
        throw new HttpsError("internal", `Archive failed: ${error.message}`);
    }
});

// ======================================================
// archiveStaleUserBirdSightings (scheduled, every 6 hours)
// ======================================================
async function _archiveStaleUserBirdSightingsCore() {
    logger.info("_archiveStaleUserBirdSightingsCore: starting.");

    const lockRef = db.collection("schedulerLocks").doc("archiveStaleUserBirdSightings");
    const STALE_LOCK_MS = 10 * 60 * 1000;
    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(lockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            if (startedAt && (Date.now() - startedAt.getTime()) < STALE_LOCK_MS) return false;
        }
        t.set(lockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        logger.info("_archiveStaleUserBirdSightingsCore: another instance is running. Skipping.");
        return { status: "skipped", message: "Archive already in progress." };
    }

    try {
        const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
        const cutoffDate = new Date(Date.now() - THREE_DAYS_MS);

        const staleSnap = await db.collection("userBirdSightings")
            .where("timestamp", "<", cutoffDate)
            .get();

        if (staleSnap.empty) {
            logger.info("_archiveStaleUserBirdSightingsCore: no stale sightings found.");
            return { status: "success", message: "No stale user sightings to archive." };
        }

        logger.info(`_archiveStaleUserBirdSightingsCore: found ${staleSnap.size} stale docs.`);

        let archivedCount = 0;
        const docs = staleSnap.docs;
        for (let i = 0; i < docs.length; i += CONFIG.FIRESTORE_BATCH_SIZE) {
            const chunk = docs.slice(i, i + CONFIG.FIRESTORE_BATCH_SIZE);
            const batch = db.batch();
            for (const doc of chunk) {
                batch.set(db.collection("userBirdSightings_backlog").doc(doc.id), {
                    ...doc.data(),
                    archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                });
                batch.delete(doc.ref);
            }
            await batch.commit();
            archivedCount += chunk.length;
            logger.info(`_archiveStaleUserBirdSightingsCore: archived ${archivedCount}/${docs.length}.`);
        }

        const summary = `Archived ${archivedCount} stale user sightings to userBirdSightings_backlog.`;
        logger.info(`_archiveStaleUserBirdSightingsCore: done. ${summary}`);
        return { status: "success", message: summary };

    } finally {
        await lockRef.delete().catch(e =>
            logger.error("_archiveStaleUserBirdSightingsCore: failed to release lock.", e)
        );
    }
}

/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Scheduled cleanup that archives old user-submitted sighting pins.
 */
exports.archiveStaleUserBirdSightings = onSchedule({
    schedule: "every 6 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 300,
}, async (event) => {
    logger.info("Scheduled archiveStaleUserBirdSightings starting.");
    try { await _archiveStaleUserBirdSightingsCore(); }
    catch (error) { logger.error("Scheduled archiveStaleUserBirdSightings failed:", error); }
    return null;
});

/**
 * Main backend logic block for this Firebase Functions file.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable/manual trigger for the stale user sighting archival job.
 */
exports.triggerArchiveStaleUserBirdSightings = secureOnCall(async (request) => {
    logger.info("Callable archiveStaleUserBirdSightings triggered.");
    try { return await _archiveStaleUserBirdSightingsCore(); }
    catch (error) {
        logger.error("Callable archiveStaleUserBirdSightings failed:", error);
        throw new HttpsError("internal", `Archive failed: ${error.message}`);
    }
});

// ======================================================
// recordBirdSighting — server-side 1 sighting per user per species per 24h
// ======================================================
// Called from CardMakerActivity instead of the client-side cooldown check.
// Uses a Firestore transaction to atomically:
//   1. Check the cooldown (users/{uid}/settings/heatmapCooldowns)
//   2. Write the sighting to userBirdSightings
//   3. Update the cooldown timestamp
//
// Returns: { recorded: true }  — sighting written
//          { recorded: false, reason: "cooldown" } — within 24h cooldown
//
// Race-condition safety: the cooldown check and sighting write happen
// inside a single transaction so concurrent calls for the same
// user+species cannot both slip through.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that records a user bird sighting/map pin with normalized location data.
 */
exports.recordBirdSighting = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const {
        birdId,
        commonName,
        userBirdId,
        latitude,
        longitude,
        state,
        locality,
        country,
        quantity,
        timestamp: clientTimestamp,
        suspicious,
    } = request.data;

    if (!birdId || typeof birdId !== "string") {
        throw new HttpsError("invalid-argument", "birdId is required.");
    }

    const COOLDOWN_MS = 24 * 60 * 60 * 1000; // 24 hours
    const now = Date.now();
    const sightingTimestamp = (typeof clientTimestamp === "number") ? clientTimestamp : now;
    const roundedLatitude = roundCoordinateForStorage(latitude);
    const roundedLongitude = roundCoordinateForStorage(longitude);

    const locationId = await getOrCreateLocation(latitude, longitude, locality, db, {
        userId,
        country,
        state,
    });
    const hotspotId = calculateHotspotBucketId(latitude, longitude);
    const birdKey = buildHotspotBirdKey({ birdId, commonName, userBirdId });

    const cooldownRef = db
        // This is where the function touches Firestore documents/collections for the requested action.
        .collection("users").doc(userId)
        .collection("settings").doc("heatmapCooldowns");

    const sightingId = db.collection("userBirdSightings").doc().id;
    const sightingRef = db.collection("userBirdSightings").doc(sightingId);

    try {
        const result = await db.runTransaction(async (t) => {
            const cooldownSnap = await t.get(cooldownRef);

            // Read existing cooldowns map
            const speciesCooldowns = {};
            if (cooldownSnap.exists) {
                const raw = cooldownSnap.data().speciesCooldowns;
                if (raw && typeof raw === "object") {
                    Object.assign(speciesCooldowns, raw);
                }
            }

            // Enforce 24-hour cooldown per species
            const lastUploadMs = typeof speciesCooldowns[birdId] === "number"
                ? speciesCooldowns[birdId]
                : 0;

            if ((now - lastUploadMs) < COOLDOWN_MS) {
                // Still within cooldown window — reject
                return { recorded: false, reason: "cooldown", nextAllowedMs: lastUploadMs + COOLDOWN_MS, hotspotId, birdKey };
            }

            // Update cooldown INSIDE the transaction (prevents race)
            speciesCooldowns[birdId] = now;
            t.set(cooldownRef, {
                speciesCooldowns,
                updatedAt: now,
            }, { merge: true });

            // Write sighting INSIDE the transaction
            t.set(sightingRef, {
                id: sightingId,
                userId,
                birdId,
                birdKey,
                hotspotId,
                locationId,
                commonName: commonName || "",
                userBirdId: userBirdId || "",
                timestamp: new Date(sightingTimestamp),
                latitude: roundedLatitude,
                longitude: roundedLongitude,
                state: state || "",
                locality: locality || "",
                country: country || "US",
                quantity: quantity || "1",
                suspicious: !!suspicious,
            });

            return { recorded: true, hotspotId, locationId: hotspotId, birdKey };
        });

        logger.info(`recordBirdSighting: userId=${userId} birdId=${birdId} recorded=${result.recorded} hotspotId=${hotspotId}`);
        return result;

    } catch (error) {
        logger.error("recordBirdSighting failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to record sighting: ${error.message}`);
    }
});

// ======================================================
// hotspot vote aggregation — per-bird vote docs + hotspot summary docs
// ======================================================
exports.onHotspotVoteWritten = onDocumentCreated("hotspotVotes/{hotspotId}/birds/{birdKey}/votes/{uid}", async (event) => {
    const { hotspotId, birdKey } = event.params;
    await recomputeHotspotBirdSummary(hotspotId, birdKey);
    await recomputeHotspotSummary(hotspotId);
});

exports.onHotspotVoteUpdated = onDocumentUpdated("hotspotVotes/{hotspotId}/birds/{birdKey}/votes/{uid}", async (event) => {
    const { hotspotId, birdKey } = event.params;
    await recomputeHotspotBirdSummary(hotspotId, birdKey);
    await recomputeHotspotSummary(hotspotId);
});

exports.onHotspotVoteDeleted = onDocumentDeleted("hotspotVotes/{hotspotId}/birds/{birdKey}/votes/{uid}", async (event) => {
    const { hotspotId, birdKey } = event.params;
    await recomputeHotspotBirdSummary(hotspotId, birdKey);
    await recomputeHotspotSummary(hotspotId);
});

exports.onHotspotUserBirdSightingCreated = onDocumentCreated("userBirdSightings/{sightingId}", async (event) => {
    try {
        const data = event.data?.data() || {};
        const hotspotId = typeof data.hotspotId === "string" && data.hotspotId.trim()
            ? data.hotspotId.trim()
            : (typeof data.locationId === "string" && data.locationId.trim() ? data.locationId.trim() : null);

        if (!hotspotId) return null;
        await recomputeHotspotVoteSummaryForHotspot(hotspotId);
    } catch (error) {
        logger.error("onHotspotUserBirdSightingCreated failed:", error);
    }
    return null;
});

exports.onHotspotUserBirdSightingDeleted = onDocumentDeleted("userBirdSightings/{sightingId}", async (event) => {
    try {
        const data = event.data?.data() || {};
        const hotspotId = typeof data.hotspotId === "string" && data.hotspotId.trim()
            ? data.hotspotId.trim()
            : (typeof data.locationId === "string" && data.locationId.trim() ? data.locationId.trim() : null);

        if (!hotspotId) return null;
        await recomputeHotspotVoteSummaryForHotspot(hotspotId);
    } catch (error) {
        logger.error("onHotspotUserBirdSightingDeleted failed:", error);
    }
    return null;
});

exports.onHotspotUserBirdSightingUpdated = onDocumentUpdated("userBirdSightings/{sightingId}", async (event) => {
    try {
        const before = event.data?.before?.data() || {};
        const after = event.data?.after?.data() || {};
        const hotspotIds = new Set();

        const beforeHotspotId = typeof before.hotspotId === "string" && before.hotspotId.trim()
            ? before.hotspotId.trim()
            : (typeof before.locationId === "string" && before.locationId.trim() ? before.locationId.trim() : null);
        const afterHotspotId = typeof after.hotspotId === "string" && after.hotspotId.trim()
            ? after.hotspotId.trim()
            : (typeof after.locationId === "string" && after.locationId.trim() ? after.locationId.trim() : null);

        if (beforeHotspotId) hotspotIds.add(beforeHotspotId);
        if (afterHotspotId) hotspotIds.add(afterHotspotId);

        for (const hotspotId of hotspotIds) {
            await recomputeHotspotVoteSummaryForHotspot(hotspotId);
        }
    } catch (error) {
        logger.error("onHotspotUserBirdSightingUpdated failed:", error);
    }
    return null;
});

// ======================================================
// archiveStaleHotspots (scheduled, every 6 hours)
// ======================================================
async function _archiveStaleHotspotsCore() {
    logger.info("_archiveStaleHotspotsCore: starting.");

    const lockRef = db.collection("schedulerLocks").doc("archiveStaleHotspots");
    const STALE_LOCK_MS = 15 * 60 * 1000;
    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(lockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            if (startedAt && (Date.now() - startedAt.getTime()) < STALE_LOCK_MS) return false;
        }
        t.set(lockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        logger.info("_archiveStaleHotspotsCore: another instance is running. Skipping.");
        return { status: "skipped", message: "Archive already in progress." };
    }

    try {
        const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
        const cutoffDate = new Date(Date.now() - THREE_DAYS_MS);

        const staleSummariesSnap = await db.collection("hotspotVoteSummaries")
            .where("updatedAt", "<", cutoffDate)
            .get();

        if (staleSummariesSnap.empty) {
            logger.info("_archiveStaleHotspotsCore: no stale hotspots found.");
            return { status: "success", message: "No stale hotspots to archive." };
        }

        let archivedCount = 0;
        for (const summaryDoc of staleSummariesSnap.docs) {
            const hotspotId = summaryDoc.id;
            const summaryData = summaryDoc.data();

            // Archive the root summary (which contains the aggregated 'birds' map)
            await db.collection("hotspotVoteSummaries_backlog").doc(hotspotId).set({
                ...summaryData,
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            // Archive bird sub-summaries
            const birdsSnap = await summaryDoc.ref.collection("birds").get();
            const batch = db.batch();
            for (const birdDoc of birdsSnap.docs) {
                batch.set(db.collection("hotspotVoteSummaries_backlog").doc(hotspotId)
                    .collection("birds").doc(birdDoc.id), birdDoc.data());
                batch.delete(birdDoc.ref);
            }

            // Archive the votes themselves for data science/location tracking
            const votesSnap = await db.collection("hotspotVotes").doc(hotspotId).collection("birds").get();
            for (const birdVoteDoc of votesSnap.docs) {
                const birdKey = birdVoteDoc.id;
                const individualVotesSnap = await birdVoteDoc.ref.collection("votes").get();
                for (const voteDoc of individualVotesSnap.docs) {
                    batch.set(db.collection("hotspotVotes_backlog").doc(hotspotId)
                        .collection("birds").doc(birdKey).collection("votes").doc(voteDoc.id), voteDoc.data());
                    batch.delete(voteDoc.ref);
                }
                batch.delete(birdVoteDoc.ref);
            }

            batch.delete(summaryDoc.ref);
            batch.delete(db.collection("hotspotVotes").doc(hotspotId));

            await batch.commit();
            archivedCount++;
        }

        const summary = `Archived ${archivedCount} stale hotspots.`;
        logger.info(`_archiveStaleHotspotsCore: done. ${summary}`);
        return { status: "success", message: summary };

    } finally {
        await lockRef.delete().catch(e => logger.error("_archiveStaleHotspotsCore: failed to release lock.", e));
    }
}

exports.archiveStaleHotspots = onSchedule({
    schedule: "every 6 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 540,
}, async (event) => {
    try { await _archiveStaleHotspotsCore(); }
    catch (error) { logger.error("Scheduled archiveStaleHotspots failed:", error); }
    return null;
});

exports.triggerArchiveStaleHotspots = secureOnCall(async (request) => {
    try { return await _archiveStaleHotspotsCore(); }
    catch (error) { throw new HttpsError("internal", error.message); }
});

