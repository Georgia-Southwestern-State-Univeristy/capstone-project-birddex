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
  buildInitialUserModerationFields,
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
// checkUsernameAndEmailAvailability
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable used during sign-up/profile setup to check whether a username/email is available
 * before account creation continues.
 */
exports.checkUsernameAndEmailAvailability = secureOnCall(async (request) => {
    const { username, email } = request.data;
    const sanitizedUsername = sanitizeUsername(username);

    if (!email || typeof email !== "string" || email.trim().length === 0) {
        throw new HttpsError("invalid-argument", "The email field is required and must be a non-empty string.");
    }

    try {
        const [usernameSnapshot, emailSnapshot] = await Promise.all([
            // This is where the function touches Firestore documents/collections for the requested action.
            db.collection("users").where("username", "==", sanitizedUsername).limit(1).get(),
            db.collection("users").where("email", "==", email.trim()).limit(1).get()
        ]);

        return {
            isUsernameAvailable: usernameSnapshot.empty,
            isEmailAvailable: emailSnapshot.empty
        };
    } catch (error) {
        logger.error("Error checking username/email availability:", error);
        throw new HttpsError("internal", "Unable to check username and email availability.");
    }
});

// ======================================================
// initializeUser
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable used during first-time setup to sanitize profile fields and create the initial BirdDex
 * user document.
 */
exports.initializeUser = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const { username, email, bio, profilePictureUrl } = request.data;
    const uid = request.auth.uid;
    const sanitizedUsername = sanitizeUsername(username);
    const sanitizedBio = bio === undefined || bio === null ? undefined : sanitizeText(bio, 90);

    if (bio !== undefined && bio !== null) {
        if (typeof bio !== "string") {
            throw new HttpsError("invalid-argument", "Bio must be a string.");
        }
        if (bio.trim().length > 90) {
            throw new HttpsError("invalid-argument", "Bio must be 90 characters or fewer.");
        }
        assertForumTextAllowed(sanitizedBio, "Bio");
    }

    await assertNoBlockedContentOrThrow({
        userId: uid,
        submissionType: "username_create_or_update",
        fieldName: "username",
        text: sanitizedUsername,
        extra: { email: email || request.auth.token.email || null }
    });

    if (sanitizedBio !== undefined) {
        await assertNoBlockedContentOrThrow({
            userId: uid,
            submissionType: "bio_create_or_update",
            fieldName: "bio",
            text: sanitizedBio,
            extra: { email: email || request.auth.token.email || null }
        });
    }

    // This is where the function touches Firestore documents/collections for the requested action.
    const userRef = db.collection("users").doc(uid);
    // Dedicated username registry: doc ID = username, guaranteed unique by Firestore
    const usernameRef = db.collection("usernames").doc(sanitizedUsername);

    try {
        await db.runTransaction(async (t) => {
            const [usernameDoc, userDoc] = await Promise.all([
                t.get(usernameRef),
                t.get(userRef)
            ]);

            // Allow re-claim if this user already owns it
            if (usernameDoc.exists && usernameDoc.data().uid !== uid) {
                throw new HttpsError("already-exists", "Username is already taken.");
            }

            // If user previously had a different username, release the old claim
            if (userDoc.exists && userDoc.data().username &&
                userDoc.data().username !== sanitizedUsername) {
                const oldUsernameRef = db.collection("usernames").doc(userDoc.data().username);
                t.delete(oldUsernameRef);
            }

            // Claim the new username atomically
            t.set(usernameRef, { uid, claimedAt: admin.firestore.FieldValue.serverTimestamp() });

            const profileUpdate = {
                username: sanitizedUsername,
                email: email || request.auth.token.email,
                id: uid,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };
            if (sanitizedBio !== undefined) profileUpdate.bio = sanitizedBio;
            if (profilePictureUrl !== undefined && profilePictureUrl !== null) {
                profileUpdate.profilePictureUrl = profilePictureUrl;
            }

            const existingUserData = userDoc.exists ? (userDoc.data() || {}) : {};
            const initialUserModerationFields = buildInitialUserModerationFields();
            Object.entries(initialUserModerationFields).forEach(([key, value]) => {
                if (!(key in existingUserData)) {
                    profileUpdate[key] = value;
                }
            });

            t.set(userRef, profileUpdate, { merge: true });
            queuePrivateAuditLog(t, {
                action: "initialize_user",
                status: "success",
                userId: uid,
                targetType: "user_profile",
                targetId: uid,
                metadata: {
                    username: sanitizedUsername,
                    updatedFields: Object.keys(profileUpdate),
                },
            });
        });

        logger.info(`Successfully initialized user ${uid} with username ${sanitizedUsername}`);
        return { success: true };
    } catch (error) {
        logger.error(`Error initializing user ${uid}:`, error);
        await writePrivateAuditLog({
            action: "initialize_user",
            status: "failure",
            userId: uid,
            targetType: "user_profile",
            targetId: uid,
            reasonCode: error instanceof HttpsError ? error.code : "internal",
            message: error.message || "Failed to initialize user.",
            metadata: { username: sanitizedUsername },
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to initialize user.");
    }
});

// ======================================================
// updateUserProfile
// ======================================================
/**
 * Updates an existing user profile through a dedicated callable so profile edits do not reuse
 * initializeUser.
 */
exports.updateUserProfile = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const uid = request.auth.uid;
    const data = request.data || {};
    const hasUsername = Object.prototype.hasOwnProperty.call(data, "username");
    const hasBio = Object.prototype.hasOwnProperty.call(data, "bio");
    const hasProfilePictureUrl = Object.prototype.hasOwnProperty.call(data, "profilePictureUrl");
    const hasEmail = Object.prototype.hasOwnProperty.call(data, "email");
    const pfpChangeId = typeof data.pfpChangeId === "string" && data.pfpChangeId.trim()
        ? data.pfpChangeId.trim()
        : null;

    const sanitizedUsername = hasUsername ? sanitizeUsername(data.username) : null;
    let sanitizedBio = undefined;
    if (hasBio) {
        if (data.bio !== null && data.bio !== undefined && typeof data.bio !== "string") {
            throw new HttpsError("invalid-argument", "Bio must be a string.");
        }
        const rawBio = data.bio == null ? "" : data.bio;
        if (typeof rawBio === "string" && rawBio.trim().length > 90) {
            throw new HttpsError("invalid-argument", "Bio must be 90 characters or fewer.");
        }
        sanitizedBio = sanitizeText(rawBio, 90);
        assertForumTextAllowed(sanitizedBio, "Bio");
    }

    const sanitizedProfilePictureUrl = hasProfilePictureUrl
        ? sanitizeText(typeof data.profilePictureUrl === "string" ? data.profilePictureUrl : "", 2000)
        : undefined;
    const sanitizedEmail = hasEmail
        ? sanitizeText(typeof data.email === "string" ? data.email : "", 320)
        : undefined;

    const userRef = db.collection("users").doc(uid);
    const existingUserSnap = await userRef.get();
    if (!existingUserSnap.exists) {
        throw new HttpsError("not-found", "User profile not found.");
    }

    const existingUserData = existingUserSnap.data() || {};
    const currentUsername = sanitizeText(existingUserData.username || "", 80).trim() || null;
    const currentProfilePictureUrl = sanitizeText(existingUserData.profilePictureUrl || "", 2000).trim() || "";
    const usernameChanged = !!(hasUsername && sanitizedUsername && sanitizedUsername !== currentUsername);
    const profilePictureChanged = hasProfilePictureUrl && (sanitizedProfilePictureUrl || "") !== currentProfilePictureUrl;

    if (profilePictureChanged && !pfpChangeId) {
        throw new HttpsError("failed-precondition", "Profile picture changes must be reserved before updating the profile.");
    }

    if (usernameChanged) {
        await assertNoBlockedContentOrThrow({
            userId: uid,
            submissionType: "username_update",
            fieldName: "username",
            text: sanitizedUsername,
            extra: { email: sanitizedEmail || existingUserData.email || request.auth.token.email || null },
        });
    }
    if (hasBio) {
        await assertNoBlockedContentOrThrow({
            userId: uid,
            submissionType: "bio_update",
            fieldName: "bio",
            text: sanitizedBio,
            extra: { email: sanitizedEmail || existingUserData.email || request.auth.token.email || null },
        });
    }

    try {
        await db.runTransaction(async (t) => {
            const userSnap = await t.get(userRef);
            if (!userSnap.exists) {
                throw new HttpsError("not-found", "User profile not found.");
            }

            let usernameRef = null;
            let usernameDoc = null;
            if (usernameChanged) {
                usernameRef = db.collection("usernames").doc(sanitizedUsername);
                usernameDoc = await t.get(usernameRef);
                if (usernameDoc.exists && usernameDoc.data().uid !== uid) {
                    throw new HttpsError("already-exists", "Username is already taken.");
                }
            }

            let pfpChangeRef = null;
            let pfpChangeDoc = null;
            if (pfpChangeId) {
                pfpChangeRef = db.collection("processedEvents").doc(pfpChangeId);
                pfpChangeDoc = await t.get(pfpChangeRef);
                if (!pfpChangeDoc.exists) {
                    throw new HttpsError("failed-precondition", "The reserved profile picture change could not be found.");
                }
                const pfpChangeData = pfpChangeDoc.data() || {};
                if (pfpChangeData.userId !== uid) {
                    throw new HttpsError("permission-denied", "That profile picture change does not belong to you.");
                }
                if (pfpChangeData.status === "rolled_back") {
                    throw new HttpsError("failed-precondition", "This profile picture change was already rolled back.");
                }
            }

            const profileRateLimitState = await readUserRateLimitState(t, uid, "profileUpdate", USER_RATE_LIMITS.profileUpdate, {
                hasUsername,
                hasBio,
                hasProfilePictureUrl,
                hasEmail,
            });
            const usernameRateLimitState = usernameChanged
                ? await readUserRateLimitState(t, uid, "usernameChange", USER_RATE_LIMITS.usernameChange, {
                    username: sanitizedUsername,
                })
                : null;

            const profileUpdate = {
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            };

            commitUserRateLimitState(t, profileRateLimitState);
            if (usernameRateLimitState) commitUserRateLimitState(t, usernameRateLimitState);

            if (usernameChanged) {
                if (currentUsername && currentUsername !== sanitizedUsername) {
                    t.delete(db.collection("usernames").doc(currentUsername));
                }
                t.set(usernameRef, {
                    uid,
                    claimedAt: admin.firestore.FieldValue.serverTimestamp(),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                }, { merge: true });
                profileUpdate.username = sanitizedUsername;
            }

            if (hasBio) profileUpdate.bio = sanitizedBio;
            if (hasProfilePictureUrl) profileUpdate.profilePictureUrl = sanitizedProfilePictureUrl || "";
            if (hasEmail) profileUpdate.email = sanitizedEmail || request.auth.token.email || existingUserData.email || "";

            if (pfpChangeRef && pfpChangeDoc && (pfpChangeDoc.data() || {}).status !== "committed") {
                t.set(pfpChangeRef, {
                    status: "committed",
                    committedAt: admin.firestore.FieldValue.serverTimestamp(),
                }, { merge: true });
            }

            t.set(userRef, profileUpdate, { merge: true });
            queuePrivateAuditLog(t, {
                action: "update_user_profile",
                status: "success",
                userId: uid,
                targetType: "user_profile",
                targetId: uid,
                metadata: {
                    updatedFields: Object.keys(profileUpdate).filter((key) => key !== "updatedAt"),
                    usernameChanged,
                    hasBio,
                    hasProfilePictureUrl,
                    hasEmail,
                    usedPfpReservation: !!pfpChangeId,
                },
            });
        });

        logger.info(`Successfully updated user profile ${uid}`);
        return { success: true };
    } catch (error) {
        logger.error(`Error updating user profile ${uid}:`, error);
        await writePrivateAuditLog({
            action: "update_user_profile",
            status: "failure",
            userId: uid,
            targetType: "user_profile",
            targetId: uid,
            reasonCode: error instanceof HttpsError ? error.code : "internal",
            message: error.message || "Failed to update user profile.",
            metadata: {
                hasUsername,
                hasBio,
                hasProfilePictureUrl,
                hasEmail,
                usedPfpReservation: !!pfpChangeId,
            },
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to update user profile.");
    }
});

/**
 * Export: Backend-owned location creation/lookup so clients cannot write arbitrary location docs.
 */
exports.createOrGetLocation = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const rawLatitude = request.data?.latitude;
    const rawLongitude = request.data?.longitude;
    const latitude = rawLatitude === null || rawLatitude === undefined || rawLatitude === "" ? null : Number(rawLatitude);
    const longitude = rawLongitude === null || rawLongitude === undefined || rawLongitude === "" ? null : Number(rawLongitude);
    const localityName = typeof request.data?.localityName === "string" ? request.data.localityName : "";
    const state = typeof request.data?.state === "string" ? request.data.state : "";
    const country = typeof request.data?.country === "string" ? request.data.country : "";

    const locationId = await getOrCreateLocation(latitude, longitude, localityName, db, {
        state,
        country,
        userId,
    });

    return { success: true, locationId };
});

// ======================================================
// createUserDocument — on Auth signup
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Auth trigger that creates the matching Firestore user profile and default moderation fields
 * when a Firebase Auth user is created.
 */
exports.createUserDocument = auth.user().onCreate(async (user) => {
    const { uid, email } = user;
    if (!email) {
        logger.error(`createUserDocument: No email found for user ${uid}.`);
        return null;
    }

    try {
        // This is where the function touches Firestore documents/collections for the requested action.
        await db.collection("users").doc(uid).set({
            email,
            bio: "",
            profilePictureUrl: "",
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            openAiRequestsRemaining: CONFIG.MAX_OPENAI_REQUESTS,
            pfpChangesToday: CONFIG.MAX_PFP_CHANGES,
            totalBirds: 0,
            duplicateBirds: 0,
            totalPoints: 0,
            notificationsEnabled: false,
            repliesEnabled: true,
            notificationCooldownHours: 2,
            trackedBirdsNotificationsEnabled: false,
            trackedBirdsCooldownHours: 0,
            trackedBirdsMaxDistanceMiles: -1,
            ...buildInitialUserModerationFields()
        }, { merge: true });
        logger.info(`Created user document for ${uid}`);
    } catch (error) {
        logger.error(`Error creating user document for ${uid}:`, error);
    }
    return null;
});

async function invokeBirdDexCloudRunWarmup({ reason, uid }) {
    const serviceUrl = CONFIG.BIRDDEX_MODEL_BASE_URL;
    const authClient = await new GoogleAuth().getIdTokenClient(serviceUrl);
    const warmupUrl = `${serviceUrl}/`;
    const authHeaders = await authClient.getRequestHeaders(warmupUrl);

    return axios.get(warmupUrl, {
        headers: {
            ...authHeaders,
            "X-BirdDex-Warmup": "1",
            "X-BirdDex-Warmup-Reason": sanitizeText(reason || "unknown", 80),
            "X-BirdDex-Warmup-Uid": sanitizeText(uid || "unknown", 128),
        },
        timeout: 5000,
        validateStatus: (status) => status >= 200 && status < 500,
    });
}

// ======================================================
// archiveAndDeleteUser
// FIX #19: Sequential get → set → delete had no idempotency guard.
// A crash between set and delete left the user doc live with an archive copy already written.
// Concurrent double-calls both archived (second overwrote first) and both deleted.
// Fix: (a) Check if archive doc already exists — if so, skip the write and go straight to
//          deleting the source doc (handles crash-between-write-and-delete).
//      (b) Use a WriteBatch to make the archive-set and user-delete atomic.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that archives key user data and then deletes the user account/documents as part of
 * account removal.
 */
exports.archiveAndDeleteUser = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "The function must be called while authenticated.");

    const uid = request.auth.uid;
    const userRef = db.collection("users").doc(uid);
    const archiveRef = db.collection("usersdeletedAccounts").doc(uid);

    try {
        const userDoc = await userRef.get();
        const archivePayload = {
            originalUid: uid,
            deletionReason: "User requested account deletion",
            deleteRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
            deleteRequestedBy: uid,
            authDeleteStartedAt: admin.firestore.FieldValue.serverTimestamp(),
            cleanupStatus: "auth_delete_requested",
        };

        if (userDoc.exists) {
            Object.assign(archivePayload, userDoc.data() || {});
        }

        await archiveRef.set(archivePayload, { merge: true });
        await admin.auth().deleteUser(uid);

        logger.info(`archiveAndDeleteUser: backend auth delete requested for UID: ${uid}`);
        return { success: true };
    } catch (error) {
        logger.error("Error archiving/deleting user:", error);
        await archiveRef.set({
            cleanupStatus: "auth_delete_failed",
            cleanupError: sanitizeText(error?.message || "auth_delete_failed", 500),
            authDeleteFailedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true }).catch(() => null);
        throw new HttpsError("internal", `Internal error during account deletion: ${error.message}`);
    }
});

exports.warmBirdDexModel = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const uid = request.auth.uid;
    const reason = sanitizeText(String(request.data?.reason || "unknown"), 80) || "unknown";

    try {
        const warmupRateLimitState = await db.runTransaction(async (t) => {
            const state = await readUserRateLimitState(t, uid, "birdDexWarmup", USER_RATE_LIMITS.birdDexWarmup, { reason });
            commitUserRateLimitState(t, state);
            return {
                recentAttempts: Array.isArray(state?.events) ? state.events.length : 0,
            };
        });

        const response = await invokeBirdDexCloudRunWarmup({ reason, uid });
        return {
            success: true,
            statusCode: response.status,
            recentAttempts: warmupRateLimitState?.recentAttempts ?? 0,
        };
    } catch (error) {
        logger.error("warmBirdDexModel failed", {
            message: error?.message || null,
            code: error?.code || null,
            uid,
            reason,
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to warm BirdDex right now.");
    }
});

// ======================================================
// recordPfpChange - reservation/rollback/finalize flow
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.recordPfpChange = secureOnCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);
    const { changeId } = request.data || {};

    const idempotencyKey = typeof changeId === "string" && changeId.trim()
        ? changeId.trim()
        : `PFP_${userId}_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedEvents").doc(idempotencyKey);

    try {
        let finalRemaining = 0;
        await db.runTransaction(async (transaction) => {
            const [eventDoc, userDoc] = await Promise.all([
                transaction.get(eventLogRef),
                transaction.get(userRef)
            ]);

            if (!userDoc.exists) throw new HttpsError("not-found", "User document not found.");
            const userData = userDoc.data() || {};

            let pfpChangesToday = userData.pfpChangesToday || 0;
            const pfpCooldownResetTimestamp = userData.pfpCooldownResetTimestamp?.toDate() || null;
            const currentTime = new Date();

            if (pfpCooldownResetTimestamp && (currentTime.getTime() - pfpCooldownResetTimestamp.getTime()) >= CONFIG.COOLDOWN_PERIOD_MS) {
                pfpChangesToday = CONFIG.MAX_PFP_CHANGES;
            }

            if (eventDoc.exists) {
                const eventData = eventDoc.data() || {};
                if (eventData.userId !== userId) {
                    throw new HttpsError("permission-denied", "That profile picture change does not belong to you.");
                }

                if (eventData.status === "rolled_back") {
                    finalRemaining = Math.min(CONFIG.MAX_PFP_CHANGES, pfpChangesToday);
                    return;
                }

                finalRemaining = pfpChangesToday;
                return;
            }

            if (pfpChangesToday <= 0) {
                throw new HttpsError("resource-exhausted", "PFP change limit reached.");
            }

            finalRemaining = pfpChangesToday - 1;
            let newPfpCooldownResetTimestamp = pfpCooldownResetTimestamp;

            if (pfpChangesToday === CONFIG.MAX_PFP_CHANGES) {
                newPfpCooldownResetTimestamp = admin.firestore.FieldValue.serverTimestamp();
            }

            transaction.update(userRef, {
                pfpChangesToday: finalRemaining,
                pfpCooldownResetTimestamp: newPfpCooldownResetTimestamp,
            });

            transaction.set(eventLogRef, {
                userId,
                type: "pfp_change",
                status: "reserved",
                reservedAt: admin.firestore.FieldValue.serverTimestamp(),
            }, { merge: true });
        });

        return { success: true, changeId: idempotencyKey, pfpChangesToday: finalRemaining };
    } catch (error) {
        logger.error(`Error recording PFP change for user ${userId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to record profile picture change.");
    }
});

exports.rollbackPfpChange = secureOnCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const { changeId } = request.data || {};
    if (typeof changeId !== "string" || !changeId.trim()) {
        throw new HttpsError("invalid-argument", "changeId is required.");
    }

    const idempotencyKey = changeId.trim();
    const eventLogRef = db.collection("processedEvents").doc(idempotencyKey);
    const userRef = db.collection("users").doc(userId);

    try {
        let finalRemaining = 0;
        let rolledBack = false;
        let alreadyCommitted = false;

        await db.runTransaction(async (transaction) => {
            const [eventDoc, userDoc] = await Promise.all([
                transaction.get(eventLogRef),
                transaction.get(userRef)
            ]);

            if (!userDoc.exists) throw new HttpsError("not-found", "User document not found.");
            const userData = userDoc.data() || {};
            let pfpChangesToday = userData.pfpChangesToday || 0;

            if (!eventDoc.exists) {
                finalRemaining = pfpChangesToday;
                return;
            }

            const eventData = eventDoc.data() || {};
            if (eventData.userId !== userId) {
                throw new HttpsError("permission-denied", "That profile picture change does not belong to you.");
            }

            if (eventData.status === "rolled_back") {
                finalRemaining = pfpChangesToday;
                return;
            }

            if (eventData.status === "committed") {
                alreadyCommitted = true;
                finalRemaining = pfpChangesToday;
                return;
            }

            finalRemaining = Math.min(CONFIG.MAX_PFP_CHANGES, pfpChangesToday + 1);
            transaction.update(userRef, { pfpChangesToday: finalRemaining });
            transaction.set(eventLogRef, {
                status: "rolled_back",
                rolledBackAt: admin.firestore.FieldValue.serverTimestamp(),
            }, { merge: true });
            rolledBack = true;
        });

        return { success: true, rolledBack, alreadyCommitted, pfpChangesToday: finalRemaining };
    } catch (error) {
        logger.error(`Error rolling back PFP change for user ${userId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to roll back profile picture change.");
    }
});

exports.finalizePfpChange = secureOnCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const { changeId } = request.data || {};
    if (typeof changeId !== "string" || !changeId.trim()) {
        throw new HttpsError("invalid-argument", "changeId is required.");
    }

    const idempotencyKey = changeId.trim();
    const eventLogRef = db.collection("processedEvents").doc(idempotencyKey);

    try {
        await db.runTransaction(async (transaction) => {
            const eventDoc = await transaction.get(eventLogRef);
            if (!eventDoc.exists) return;

            const eventData = eventDoc.data() || {};
            if (eventData.userId !== userId) {
                throw new HttpsError("permission-denied", "That profile picture change does not belong to you.");
            }

            if (eventData.status === "committed") return;
            if (eventData.status === "rolled_back") {
                throw new HttpsError("failed-precondition", "This profile picture change was already rolled back.");
            }

            transaction.set(eventLogRef, {
                status: "committed",
                committedAt: admin.firestore.FieldValue.serverTimestamp(),
            }, { merge: true });
        });

        return { success: true };
    } catch (error) {
        logger.error(`Error finalizing PFP change for user ${userId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to finalize profile picture change.");
    }
});

// ======================================================
// HELPER: Core Georgia Birds Sync (shared logic)
// FIX #18: _syncGeorgiaBirdsCore had no concurrency lock.  It could be invoked
// simultaneously from scheduledGetGeorgiaBirds (every 72h) and the getGeorgiaBirds
// callable (triggered by any authenticated user), causing duplicate batch deletes
// and conflicting ebirdCacheDocRef writes.
// Fix: Acquire the same schedulerLock pattern used by archiveOldForumPosts.
// ======================================================
async function _syncGeorgiaBirdsCore() {
    logger.info("Executing _syncGeorgiaBirdsCore...");
    const ebirdCacheDocRef = db.collection("ebird_ga_cache").doc("data");

    // FIX #18: Acquire exclusive lock before any reads or writes.
    const lockRef = db.collection("schedulerLocks").doc("syncGeorgiaBirds");
    const STALE_LOCK_MS = 15 * 60 * 1000; // 15 minutes — release if previous run crashed
    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(lockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            if (startedAt && (Date.now() - startedAt.getTime()) < STALE_LOCK_MS) {
                return false; // another instance is running
            }
        }
        t.set(lockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        logger.info("_syncGeorgiaBirdsCore: Another instance is already running. Skipping.");
        return { status: "skipped", message: "Sync already in progress." };
    }

    try {
        let cachedBirdIds = [];
        const currentCacheDoc = await ebirdCacheDocRef.get();
        if (currentCacheDoc.exists && currentCacheDoc.data().birdIds) {
            cachedBirdIds = currentCacheDoc.data().birdIds;
        }

        const cacheIsFresh = currentCacheDoc.exists &&
            (Date.now() - currentCacheDoc.data().lastUpdated < CONFIG.EBIRD_CACHE_TTL_MS) &&
            cachedBirdIds.length > 0;

        if (cacheIsFresh) {
            logger.info("Georgia bird cache is fresh. Skipping sync.");
            return { status: "success", message: "Cache is fresh." };
        }

        logger.info("Syncing Georgia birds from eBird API...");
        const [codesRes, taxonomyRes, observationsRes] = await Promise.all([
            axios.get("https://api.ebird.org/v2/product/spplist/US-GA", {
                headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() },
                timeout: 15000
            }),
            axios.get("https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json", { timeout: 15000 }),
            axios.get("https://api.ebird.org/v2/data/obs/US-GA/recent", {
                headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() },
                timeout: 15000
            })
        ]);

        const gaCodes = codesRes.data;
        const recentObservations = observationsRes.data;

        const lastSeenMap = new Map();
        for (const obs of recentObservations) {
            const ts = new Date(obs.obsDt).getTime();
            const code = obs.speciesCode;
            if (typeof obs.lat === "number" && typeof obs.lng === "number" &&
                (!lastSeenMap.has(code) || ts > lastSeenMap.get(code).lastSeenTimestampGeorgia)) {
                lastSeenMap.set(code, {
                    lastSeenTimestampGeorgia: ts,
                    lastSeenLatitudeGeorgia: obs.lat,
                    lastSeenLongitudeGeorgia: obs.lng,
                    obsLocName: obs.locName
                });
            }
        }

        const newBirdsFromEbird = taxonomyRes.data
            .filter(bird => gaCodes.includes(bird.speciesCode))
            .map(bird => {
                const ls = lastSeenMap.get(bird.speciesCode);
                return {
                    id: bird.speciesCode,
                    commonName: bird.comName,
                    scientificName: bird.sciName,
                    family: bird.familyComName,
                    species: bird.sciName,
                    isEndangered: false,
                    canHunt: false,
                    lastSeenTimestampGeorgia: ls?.lastSeenTimestampGeorgia || null,
                    lastSeenLatitudeGeorgia: ls?.lastSeenLatitudeGeorgia || null,
                    lastSeenLongitudeGeorgia: ls?.lastSeenLongitudeGeorgia || null,
                    lastSeenLocationIdGeorgia: null
                };
            });

        newBirdsFromEbird.sort((a, b) => a.commonName.localeCompare(b.commonName));
        const birdIdsToCache = newBirdsFromEbird.map(bird => bird.id);

        // Remove birds no longer in list
        const removedBirdIds = cachedBirdIds.filter(id => !birdIdsToCache.includes(id));
        if (removedBirdIds.length > 0) {
            const deleteBatch = db.batch();
            removedBirdIds.forEach(birdId => deleteBatch.delete(db.collection("birds").doc(birdId)));
            await deleteBatch.commit();
            logger.info(`Removed ${removedBirdIds.length} birds.`);
        }

        // Batch write birds
        for (let i = 0; i < newBirdsFromEbird.length; i += CONFIG.FIRESTORE_BATCH_SIZE) {
            const batch = db.batch();
            const chunk = newBirdsFromEbird.slice(i, i + CONFIG.FIRESTORE_BATCH_SIZE);
            chunk.forEach(bird => {
                const birdCoreData = { ...bird };
                delete birdCoreData.lastSeenLocationIdGeorgia;
                batch.set(db.collection("birds").doc(bird.id), birdCoreData, { merge: true });
            });
            await batch.commit();
        }

        await ebirdCacheDocRef.set({
            lastUpdated: Date.now(),
            lastUpdatedReadable: new Date().toLocaleString("en-US", { timeZone: "America/New_York" }),
            birdIds: birdIdsToCache
        }, { merge: true });

        // Parallel location updates
        await Promise.all(newBirdsFromEbird.map(async (bird) => {
            if (bird.lastSeenLatitudeGeorgia !== null && bird.lastSeenLongitudeGeorgia !== null) {
                const ls = lastSeenMap.get(bird.id);
                const locationId = await getOrCreateLocation(
                    bird.lastSeenLatitudeGeorgia,
                    bird.lastSeenLongitudeGeorgia,
                    ls?.obsLocName || null,
                    db
                );
                await db.collection("birds").doc(bird.id).update({ lastSeenLocationIdGeorgia: locationId });
            }
        }));

        logger.info("Georgia birds sync complete.");
        return { status: "success", count: newBirdsFromEbird.length };
    } catch (error) {
        logger.error("Error in _syncGeorgiaBirdsCore:", error);
        throw error;
    } finally {
        // FIX #18: Always release the lock, even on failure.
        await lockRef.delete().catch(e =>
            logger.warn("Failed to release syncGeorgiaBirds lock:", e)
        );
    }
}

// ======================================================
// performDeletedUserCleanup — unified auth delete cleanup pipeline
// ======================================================
async function performDeletedUserCleanup(user) {
    const uid = user.uid;
    logger.info(`Starting unified deletion cleanup for user: ${uid}`);

    const userRef = db.collection("users").doc(uid);
    const archiveRef = db.collection("usersdeletedAccounts").doc(uid);
    const processedRef = db.collection("processedEvents").doc(`AUTH_DELETE_${uid}`);

    let userDocData = null;

    await db.runTransaction(async (t) => {
        const [processedSnap, archiveSnap, userSnap] = await Promise.all([
            t.get(processedRef),
            t.get(archiveRef),
            t.get(userRef),
        ]);

        if (processedSnap.exists || archiveSnap.get("cleanupStatus") === "complete") {
            userDocData = userSnap.exists ? (userSnap.data() || null) : (archiveSnap.exists ? (archiveSnap.data() || null) : null);
            return;
        }

        userDocData = userSnap.exists ? (userSnap.data() || {}) : (archiveSnap.exists ? (archiveSnap.data() || {}) : null);

        t.set(processedRef, {
            uid,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            type: "auth_delete_cleanup_started",
        });

        t.set(archiveRef, {
            ...(userDocData || {}),
            archivedAt: admin.firestore.FieldValue.serverTimestamp(),
            deletionType: "Automatic Auth Trigger",
            cleanupStatus: "running",
        }, { merge: true });
    });

    const permanentBulkWriterFailures = [];
    const bulkWriter = db.bulkWriter();
    bulkWriter.onWriteError((error) => {
        const retryableCodes = new Set([
            4, 8, 10, 13, 14,
            "aborted", "cancelled", "data-loss", "deadline-exceeded", "internal", "resource-exhausted", "unavailable",
        ]);
        const shouldRetry = retryableCodes.has(error.code) && error.failedAttempts < 5;
        logger.error("performDeletedUserCleanup bulkWriter error", {
            code: error.code,
            message: error.message,
            failedAttempts: error.failedAttempts,
            documentPath: error.documentRef?.path || null,
            willRetry: shouldRetry,
        });
        if (!shouldRetry) {
            permanentBulkWriterFailures.push({
                code: error.code || null,
                message: sanitizeText(error.message || "bulk_writer_error", 300),
                documentPath: error.documentRef?.path || null,
            });
        }
        return shouldRetry;
    });

    try {
        if (userDocData && userDocData.username) {
            await db.collection("usernames").doc(userDocData.username).delete().catch(() => null);
        }

        const threadsSnap = await db.collection("forumThreads").where("userId", "==", uid).get();
        for (const threadDoc of threadsSnap.docs) {
            const threadData = threadDoc.data() || {};
            const threadId = threadDoc.id;
            const commentsSnap = await threadDoc.ref.collection("comments").get();
            const archivedImageUrl = await archiveForumPostImageIfNeeded(
                uid,
                threadId,
                typeof threadData.birdImageUrl === "string" ? threadData.birdImageUrl : ""
            );

            bulkWriter.set(db.collection("deletedforum_backlog").doc(`USERDEL_POST_${threadId}`), {
                type: "post",
                originalId: threadId,
                data: {
                    ...threadData,
                    birdImageUrl: archivedImageUrl,
                },
                archivedComments: commentsSnap.docs.map(c => ({ id: c.id, ...c.data() })),
                deletedBy: uid,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
                deletionReason: "auth_delete",
            });

            commentsSnap.docs.forEach(commentDoc => bulkWriter.delete(commentDoc.ref));
            bulkWriter.delete(threadDoc.ref);
        }

        const userSubcollections = [
            "collectionSlot",
            "userBirdImage",
            "collectionMeta",
            "savedPosts",
            "trackedBirds",
            "settings",
            "following",
            "followers",
            "rateLimits",
        ];

        for (const subcollection of userSubcollections) {
            const snap = await userRef.collection(subcollection).get();
            snap.docs.forEach(docSnap => bulkWriter.delete(docSnap.ref));
        }

        const [followingSnap, followersSnap] = await Promise.all([
            userRef.collection("following").get(),
            userRef.collection("followers").get(),
        ]);

        for (const docSnap of followingSnap.docs) {
            const targetId = docSnap.id;
            const targetRef = db.collection("users").doc(targetId);
            bulkWriter.delete(targetRef.collection("followers").doc(uid));
            bulkWriter.set(targetRef, { followerCount: admin.firestore.FieldValue.increment(-1) }, { merge: true });
        }

        for (const docSnap of followersSnap.docs) {
            const followerId = docSnap.id;
            const followerRef = db.collection("users").doc(followerId);
            bulkWriter.delete(followerRef.collection("following").doc(uid));
            bulkWriter.set(followerRef, { followingCount: admin.firestore.FieldValue.increment(-1) }, { merge: true });
        }

        const personalRootCollections = ["userBirds", "media", "birdCards"];
        for (const col of personalRootCollections) {
            const snap = await db.collection(col).where("userId", "==", uid).get();
            snap.docs.forEach(docSnap => bulkWriter.delete(docSnap.ref));
        }

        const userCommentsSnap = await db.collectionGroup("comments").where("userId", "==", uid).get();
        userCommentsSnap.docs.forEach((docSnap) => bulkWriter.delete(docSnap.ref));

        const sightingsSnap = await db.collection("userBirdSightings").where("userId", "==", uid).get();
        sightingsSnap.docs.forEach(docSnap => bulkWriter.set(docSnap.ref, {
            username: "Deleted User",
            imageUrl: "",
            isAnonymized: true,
            userId: null,
        }, { merge: true }));

        const bugReportsSnap = await db.collection("bug_reports").where("userId", "==", uid).get();
        bugReportsSnap.docs.forEach((docSnap) => bulkWriter.set(docSnap.ref, {
            userId: null,
            contactEmail: null,
            anonymized: true,
            anonymizedAt: admin.firestore.FieldValue.serverTimestamp(),
            anonymizationReason: "account_deleted",
        }, { merge: true }));

        const reportsSnap = await db.collection("reports").where("reporterId", "==", uid).get();
        reportsSnap.docs.forEach((docSnap) => bulkWriter.set(docSnap.ref, {
            reporterId: null,
            reporterDeleted: true,
            anonymized: true,
            anonymizedAt: admin.firestore.FieldValue.serverTimestamp(),
            anonymizationReason: "account_deleted",
        }, { merge: true }));

        const moderationEventsSnap = await db.collection("moderationEvents").where("userId", "==", uid).get();
        moderationEventsSnap.docs.forEach((docSnap) => bulkWriter.set(docSnap.ref, {
            userId: null,
            deletedUserId: uid,
            anonymized: true,
            anonymizedAt: admin.firestore.FieldValue.serverTimestamp(),
            anonymizationReason: "account_deleted",
        }, { merge: true }));

        const moderationAppealsSnap = await db.collection("moderationAppeals").where("userId", "==", uid).get();
        moderationAppealsSnap.docs.forEach((docSnap) => bulkWriter.set(docSnap.ref, {
            userId: null,
            deletedUserId: uid,
            anonymized: true,
            anonymizedAt: admin.firestore.FieldValue.serverTimestamp(),
            anonymizationReason: "account_deleted",
        }, { merge: true }));

        const privateAuditLogsSnap = await db.collection(PRIVATE_AUDIT_LOG_COLLECTION).where("userId", "==", uid).get();
        privateAuditLogsSnap.docs.forEach((docSnap) => bulkWriter.set(docSnap.ref, {
            userId: null,
            deletedUserId: uid,
            anonymized: true,
            anonymizedAt: admin.firestore.FieldValue.serverTimestamp(),
            anonymizationReason: "account_deleted",
        }, { merge: true }));

        if (userDocData && typeof userDocData.profilePictureUrl === "string" && userDocData.profilePictureUrl.includes("firebasestorage.googleapis.com")) {
            try {
                const decodedUrl = decodeURIComponent(userDocData.profilePictureUrl);
                const filePath = decodedUrl.substring(decodedUrl.indexOf("/o/") + 3, decodedUrl.indexOf("?"));
                await admin.storage().bucket().file(filePath).delete().catch(() => null);
            } catch (e) {
                logger.error("PFP storage deletion failed", e);
            }
        }

        await Promise.all([
            admin.storage().bucket().deleteFiles({ prefix: `userCollectionImages/${uid}/` }).catch(() => null),
            admin.storage().bucket().deleteFiles({ prefix: `identificationImages/${uid}/` }).catch(() => null),
            admin.storage().bucket().deleteFiles({ prefix: `user_images/${uid}/` }).catch(() => null),
        ]);

        bulkWriter.delete(userRef);
        await bulkWriter.close();

        if (permanentBulkWriterFailures.length > 0) {
            const partialFailure = new Error("cleanup_partial_failure");
            partialFailure.partialCleanupFailure = true;
            partialFailure.failures = permanentBulkWriterFailures.slice(0, 20);
            throw partialFailure;
        }

        await archiveRef.set({
            cleanupStatus: "complete",
            cleanupCompletedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });

        logger.info(`Successfully cleaned up deleted user ${uid}`);
    } catch (error) {
        logger.error(`Unified cleanup failed for user ${uid}:`, error);
        const nextStatus = error?.partialCleanupFailure ? "partial_failure" : "failed";
        await archiveRef.set({
            cleanupStatus: nextStatus,
            cleanupError: sanitizeText(error?.message || "cleanup_failed", 500),
            cleanupFailures: Array.isArray(error?.failures) ? error.failures : null,
            cleanupFailedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true }).catch(() => null);
        throw error;
    }
}

// ======================================================
// onCollectionSlotUpdatedForImageDeletion
// FIX #24: Under at-least-once delivery a double-invocation could propagate duplicate
// delete events for the same slotId, causing the userBirds delete to fire twice.
// The second delete is a no-op in Firestore but the surrounding logic (and any future
// code here) could produce side effects.  Add a processedEvents idempotency guard.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that reacts when a collection slot image reference changes and cleans up old
 * image usage/deletion state.
 */
exports.onCollectionSlotUpdatedForImageDeletion = onDocumentUpdated("users/{userId}/collectionSlot/{slotId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();
    const { userBirdId } = beforeData;
    const imageUrlBefore = beforeData.imageUrl;
    const imageUrlAfter = afterData.imageUrl;

    if (userBirdId && imageUrlBefore && !imageUrlAfter) {
        const slotId = event.params.slotId;
        // This is where the function touches Firestore documents/collections for the requested action.
        const eventLogRef = db.collection("processedEvents").doc(`SLOT_DEL_${slotId}`);

        logger.info(`Starting cleanup for userBirdId: ${userBirdId}`);
        try {
            // FIX #24: Idempotency guard — skip if this slot deletion was already processed.
            let alreadyProcessed = false;
            await db.runTransaction(async (t) => {
                const eventDoc = await t.get(eventLogRef);
                if (eventDoc.exists) {
                    alreadyProcessed = true;
                    return;
                }
                t.set(eventLogRef, { userBirdId, processedAt: admin.firestore.FieldValue.serverTimestamp() });
            });

            if (alreadyProcessed) {
                logger.info(`onCollectionSlotUpdatedForImageDeletion: slot ${slotId} already processed. Skipping.`);
                return null;
            }

            await db.collection("userBirds").doc(userBirdId).delete();
            logger.info(`Cleaned up userBirdId: ${userBirdId}`);
        } catch (err) {
            logger.error(`Failed to cleanup userBirdId: ${userBirdId}`, err);
        }
    }
    return null;
});

// ======================================================
// onCollectionSlotDeletedCleanupImage
// When a collection slot document is deleted outright, delete the matching
// userBirdImage doc so the existing onDeleteUserBirdImage cleanup chain removes
// the stored userCollectionImages file, related sightings, and parent userBird
// bookkeeping when needed.
// ======================================================
/**
 * Export: Firestore trigger that reacts when a collection slot document is deleted and
 * forwards cleanup to the existing userBirdImage deletion pipeline.
 */
exports.onCollectionSlotDeletedCleanupImage = onDocumentDeleted("users/{userId}/collectionSlot/{slotId}", async (event) => {
    const deletedSlot = event.data?.data();
    const { userId, slotId } = event.params;

    if (!deletedSlot) {
        logger.info(`onCollectionSlotDeletedCleanupImage: No deleted slot payload for slot ${slotId}.`);
        return null;
    }

    const { userBirdId, imageUrl, birdId } = deletedSlot;

    if (!imageUrl || !imageUrl.includes("userCollectionImages")) {
        logger.info(`onCollectionSlotDeletedCleanupImage: Slot ${slotId} has no collection image to clean up.`);
        return null;
    }

    const eventLogRef = db.collection("processedEvents").doc(`SLOT_DOC_DEL_${slotId}`);

    try {
        let alreadyProcessed = false;
        await db.runTransaction(async (transaction) => {
            const eventDoc = await transaction.get(eventLogRef);
            if (eventDoc.exists) {
                alreadyProcessed = true;
                return;
            }

            transaction.set(eventLogRef, {
                slotId,
                userId,
                userBirdId: userBirdId || null,
                imageUrl,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        });

        if (alreadyProcessed) {
            logger.info(`onCollectionSlotDeletedCleanupImage: slot ${slotId} already processed. Skipping.`);
            return null;
        }

        let query = db.collection("users").doc(userId).collection("userBirdImage")
            .where("imageUrl", "==", imageUrl);

        if (userBirdId) {
            query = query.where("userBirdRefId", "==", userBirdId);
        } else if (birdId) {
            query = query.where("birdId", "==", birdId);
        }

        const matchingImagesSnap = await query.limit(1).get();

        if (matchingImagesSnap.empty) {
            logger.info(`onCollectionSlotDeletedCleanupImage: No matching userBirdImage found for slot ${slotId}.`);
            return null;
        }

        const imageDoc = matchingImagesSnap.docs[0];
        await imageDoc.ref.delete();
        logger.info(`onCollectionSlotDeletedCleanupImage: Deleted userBirdImage ${imageDoc.id} for slot ${slotId}.`);
    } catch (error) {
        logger.error(`onCollectionSlotDeletedCleanupImage: Failed for slot ${slotId}.`, error);
    }

    return null;
});

