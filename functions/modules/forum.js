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
  timestampToMillis,
  createModerationAppealRef,
  buildInitialReportReasonCounts,
  buildInitialModerationFields,
  buildInitialUserModerationFields,
  MODERATION_STATUS_VISIBLE,
  MODERATION_STATUS_UNDER_REVIEW,
  MODERATION_STATUS_HIDDEN,
  MODERATION_STATUS_REMOVED,
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
// archiveOldForumPosts (disabled)
// ======================================================
/**
 * Forum posts now stay visible indefinitely in the social feed.
 * This scheduled function is intentionally a no-op so older posts are not removed from forumThreads.
 */
/**
 * Export: Scheduled cleanup that archives forum posts after the archive threshold passes.
 */
exports.archiveOldForumPosts = onSchedule({
    schedule: "every 24 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 540
}, async (event) => {
    logger.info("archiveOldForumPosts is disabled. Forum posts remain visible in forumThreads.");
    return null;
});

// ======================================================
// expireForumHeatmapPins (scheduled, every 1 hour)
// ======================================================
/**
 * Clears forum post map visibility after 48 hours so older location pins stop cluttering the heat map
 * while the post itself stays visible in the forum feed.
 */
/**
 * Export: Scheduled cleanup that hides forum-map pins after the heatmap TTL while leaving the post itself
 * intact.
 */
exports.expireForumHeatmapPins = onSchedule({
    schedule: "every 1 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 540
}, async (event) => {
    const lockRef = db.collection("schedulerLocks").doc("expireForumHeatmapPins");
    const STALE_LOCK_MS = 10 * 60 * 1000;

    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(lockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            if (startedAt && (Date.now() - startedAt.getTime()) < STALE_LOCK_MS) {
                return false;
            }
        }
        t.set(lockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        logger.info("expireForumHeatmapPins: Another instance is already running. Skipping.");
        return null;
    }

    logger.info("Starting forum heatmap pin expiration cycle...");
    const now = admin.firestore.Timestamp.now();

    try {
        const expiredSnap = await db.collection("forumThreads")
            .where("heatmapExpiresAt", "<=", now)
            .limit(500)
            .get();

        if (expiredSnap.empty) {
            logger.info("No expired forum heatmap pins found.");
            return null;
        }

        logger.info(`Expiring ${expiredSnap.size} forum heatmap pins...`);

        const batch = db.batch();
        expiredSnap.docs.forEach((doc) => {
            const data = doc.data() || {};
            if (data.showLocation === true) {
                batch.update(doc.ref, {
                    showLocation: false,
                    latitude: null,
                    longitude: null,
                });
            }
        });
        await batch.commit();

        logger.info("Forum heatmap pin expiration cycle complete.");
    } catch (error) {
        logger.error("Error during forum heatmap pin expiration:", error);
    } finally {
        await lockRef.delete().catch(e =>
            logger.warn("Failed to release forum heatmap expiration lock:", e)
        );
    }
    return null;
});

// ======================================================
// clearRemovedForumPostCoordinates — when moderationStatus becomes removed
// ======================================================
/**
 * Hidden posts keep their stored coordinates so the heat map pin can come back if the post is
 * restored to visible later. Truly removed posts clear their saved location data.
 */
/**
 * Export: Scheduled cleanup that clears coordinates only for posts that were removed/permanently hidden.
 */
exports.clearRemovedForumPostCoordinates = onDocumentUpdated("forumThreads/{postId}", async (event) => {
    const beforeData = event.data.before.data() || {};
    const afterData = event.data.after.data() || {};

    const beforeStatus = normalizeModerationStatus(beforeData.moderationStatus);
    const afterStatus = normalizeModerationStatus(afterData.moderationStatus);

    if (afterStatus !== MODERATION_STATUS_REMOVED || beforeStatus === MODERATION_STATUS_REMOVED) {
        return null;
    }

    const hasCoordinates = typeof afterData.latitude === "number" && typeof afterData.longitude === "number";
    if (afterData.showLocation !== true && !hasCoordinates) {
        return null;
    }

    await event.data.after.ref.set({
        showLocation: false,
        latitude: null,
        longitude: null,
    }, { merge: true });

    return null;
});

// ======================================================
// Forum engagement callables — optimistic UI on the client, authoritative writes on the backend
// ======================================================
exports.toggleForumPostLike = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const postId = sanitizeText(request.data?.postId || "", 200).trim();
    const shouldLike = request.data?.liked === true;
    if (!postId) throw new HttpsError("invalid-argument", "postId is required.");

    const postRef = db.collection("forumThreads").doc(postId);

    try {
        const result = await db.runTransaction(async (t) => {
            const postSnap = await t.get(postRef);
            const postRateLimitState = await readUserRateLimitState(t, userId, "forumPostLike", USER_RATE_LIMITS.forumPostLike, {
                postId,
                liked: shouldLike,
            });

            commitUserRateLimitState(t, postRateLimitState);

            const postData = postSnap.data() || {};
            if (!postSnap.exists) {
                throw new HttpsError("not-found", "Forum post not found.");
            }

            const moderationStatus = normalizeModerationStatus(postData.moderationStatus);
            if (!isPublicForumStatus(moderationStatus)) {
                throw new HttpsError("failed-precondition", "This post is not available for engagement.");
            }

            const likedBy = postData.likedBy || {};
            const alreadyLiked = likedBy[userId] === true;
            let changed = false;

            if (shouldLike && !alreadyLiked) {
                t.set(postRef, { likedBy: { [userId]: true } }, { merge: true });
                changed = true;
            } else if (!shouldLike && alreadyLiked) {
                t.update(postRef, { [`likedBy.${userId}`]: admin.firestore.FieldValue.delete() });
                changed = true;
            }

            queuePrivateAuditLog(t, {
                action: "toggle_forum_post_like",
                status: "success",
                userId,
                targetType: "forum_post",
                targetId: postId,
                metadata: { liked: shouldLike, changed },
            });

            return { changed };
        });

        return { success: true, liked: shouldLike, changed: result.changed === true };
    } catch (error) {
        await writePrivateAuditLog({
            action: "toggle_forum_post_like",
            status: "failure",
            userId,
            targetType: "forum_post",
            targetId: postId,
            reasonCode: error instanceof HttpsError ? error.code : "internal",
            message: error.message || "Failed to update post like.",
            metadata: { liked: shouldLike },
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to update post like.");
    }
});

exports.toggleForumCommentLike = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const threadId = sanitizeText(request.data?.threadId || "", 200).trim();
    const commentId = sanitizeText(request.data?.commentId || "", 200).trim();
    const shouldLike = request.data?.liked === true;
    if (!threadId || !commentId) {
        throw new HttpsError("invalid-argument", "threadId and commentId are required.");
    }

    const commentRef = db.collection("forumThreads").doc(threadId).collection("comments").doc(commentId);

    try {
        const result = await db.runTransaction(async (t) => {
            const commentSnap = await t.get(commentRef);
            const commentRateLimitState = await readUserRateLimitState(t, userId, "forumCommentLike", USER_RATE_LIMITS.forumCommentLike, {
                threadId,
                commentId,
                liked: shouldLike,
            });

            commitUserRateLimitState(t, commentRateLimitState);

            const commentData = commentSnap.data() || {};
            if (!commentSnap.exists) {
                throw new HttpsError("not-found", "Comment not found.");
            }

            const moderationStatus = normalizeModerationStatus(commentData.moderationStatus);
            if (!isPublicForumStatus(moderationStatus)) {
                throw new HttpsError("failed-precondition", "This comment is not available for engagement.");
            }

            const likedBy = commentData.likedBy || {};
            const alreadyLiked = likedBy[userId] === true;
            let changed = false;

            if (shouldLike && !alreadyLiked) {
                t.set(commentRef, { likedBy: { [userId]: true } }, { merge: true });
                changed = true;
            } else if (!shouldLike && alreadyLiked) {
                t.update(commentRef, { [`likedBy.${userId}`]: admin.firestore.FieldValue.delete() });
                changed = true;
            }

            queuePrivateAuditLog(t, {
                action: "toggle_forum_comment_like",
                status: "success",
                userId,
                targetType: "forum_comment",
                targetId: commentId,
                metadata: { threadId, liked: shouldLike, changed },
            });

            return { changed };
        });

        return { success: true, liked: shouldLike, changed: result.changed === true };
    } catch (error) {
        await writePrivateAuditLog({
            action: "toggle_forum_comment_like",
            status: "failure",
            userId,
            targetType: "forum_comment",
            targetId: commentId,
            reasonCode: error instanceof HttpsError ? error.code : "internal",
            message: error.message || "Failed to update comment like.",
            metadata: { threadId, liked: shouldLike },
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to update comment like.");
    }
});

exports.recordForumPostView = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    const postId = sanitizeText(request.data?.postId || "", 200).trim();
    if (!postId) throw new HttpsError("invalid-argument", "postId is required.");

    const postRef = db.collection("forumThreads").doc(postId);

    try {
        const result = await db.runTransaction(async (t) => {
            const postSnap = await t.get(postRef);
            const postViewRateLimitState = await readUserRateLimitState(t, userId, "forumPostView", USER_RATE_LIMITS.forumPostView, {
                postId,
            });

            commitUserRateLimitState(t, postViewRateLimitState);

            const postData = postSnap.data() || {};
            if (!postSnap.exists) {
                throw new HttpsError("not-found", "Forum post not found.");
            }

            const moderationStatus = normalizeModerationStatus(postData.moderationStatus);
            if (!isPublicForumStatus(moderationStatus)) {
                return { alreadyViewed: true, changed: false };
            }

            const viewedBy = postData.viewedBy || {};
            const alreadyViewed = viewedBy[userId] === true;
            if (!alreadyViewed) {
                t.set(postRef, { viewedBy: { [userId]: true } }, { merge: true });
            }

            queuePrivateAuditLog(t, {
                action: "record_forum_post_view",
                status: "success",
                userId,
                targetType: "forum_post",
                targetId: postId,
                metadata: { alreadyViewed, changed: !alreadyViewed },
            });

            return { alreadyViewed, changed: !alreadyViewed };
        });

        return { success: true, alreadyViewed: result.alreadyViewed === true, changed: result.changed === true };
    } catch (error) {
        await writePrivateAuditLog({
            action: "record_forum_post_view",
            status: "failure",
            userId,
            targetType: "forum_post",
            targetId: postId,
            reasonCode: error instanceof HttpsError ? error.code : "internal",
            message: error.message || "Failed to record post view.",
        });
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to record post view.");
    }
});

/**
 * Export: Backend-owned bug report intake with server-side rate limiting.
 */
exports.submitBugReport = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const subject = sanitizeText(String(request.data?.subject || ""), 160).trim();
    const description = sanitizeText(String(request.data?.description || ""), 4000).trim();
    const contactEmail = sanitizeText(String(request.data?.contactEmail || ""), 200).trim() || null;

    if (!subject || !description) {
        throw new HttpsError("invalid-argument", "Subject and description are required.");
    }

    const reportRef = db.collection("bug_reports").doc();

    try {
        await db.runTransaction(async (t) => {
            await assertAndConsumeUserRateLimit(t, userId, "bugReport", USER_RATE_LIMITS.bugReport, {
                subject,
            });

            t.set(reportRef, {
                subject,
                description,
                contactEmail,
                deviceModel: sanitizeText(String(request.data?.deviceModel || ""), 120) || null,
                androidVersion: sanitizeText(String(request.data?.androidVersion || ""), 80) || null,
                userId,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                source: "callable",
            });
        });

        return { success: true, reportId: reportRef.id };
    } catch (error) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to submit bug report.");
    }
});

// ======================================================
// IDEMPOTENT Forum Aggregates (Recalculation triggers)
// ======================================================

/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Firestore trigger that maintains thread engagement counters/derived fields after likes/comments
 * change.
 */
exports.onForumThreadEngagementUpdated = onDocumentUpdated("forumThreads/{threadId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    const likedByAfter = afterData.likedBy || {};
    const viewedByAfter = afterData.viewedBy || {};

    const newLikeCount = Object.keys(likedByAfter).length;
    const newViewCount = Object.keys(viewedByAfter).length;

    const updates = {};
    if (newLikeCount !== (afterData.likeCount || 0)) {
        updates.likeCount = newLikeCount;
    }
    if (newViewCount !== (afterData.viewCount || 0)) {
        updates.viewCount = newViewCount;
    }

    if (Object.keys(updates).length > 0) {
        logger.info(`Recalculating engagement for thread ${event.params.threadId}: Likes=${newLikeCount}, Views=${newViewCount}`);
        await event.data.after.ref.update(updates);
    }
    return null;
});

/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Export: Firestore trigger that maintains comment engagement counters/derived fields after likes/replies
 * change.
 */
exports.onCommentEngagementUpdated = onDocumentUpdated("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const afterData = event.data.after.data();
    if (!afterData) {
        logger.info(`Comment engagement updated but no after data for ${event.params.commentId}`);
        return null;
    }
    const likedByAfter = afterData.likedBy || {};
    const newLikeCount = Object.keys(likedByAfter).length;
    const currentLikeCount = afterData.likeCount || 0;
    if (newLikeCount !== currentLikeCount) {
        logger.info(`Recalculating likes for comment ${event.params.commentId} in thread ${event.params.threadId}: New count = ${newLikeCount}`);
        await event.data.after.ref.update({ likeCount: newLikeCount });
    }
    return null;
});

// ======================================================
// onCommentCreated — notify on reply or post activity + IDEMPOTENT COUNT
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that updates counts/metadata when a forum comment is created.
 */
exports.onCommentCreated = onDocumentCreated("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const commentData = event.data.data();
    const threadId = event.params.threadId;
    const commentId = event.params.commentId;
    const parentCommentId = commentData.parentCommentId;

    // --- IDEMPOTENT commentCount INCREMENT ---
    // This is where the function touches Firestore documents/collections for the requested action.
    const threadRef = db.collection("forumThreads").doc(threadId);
    const eventLogRef = db.collection("processedEvents").doc(`COMMENT_INC_${commentId}`);

    try {
        await db.runTransaction(async (t) => {
            const eventDoc = await t.get(eventLogRef);
            if (eventDoc.exists) return;

            t.update(threadRef, { commentCount: admin.firestore.FieldValue.increment(1) });
            t.set(eventLogRef, { processedAt: admin.firestore.FieldValue.serverTimestamp(), type: "COMMENT_INC" });
        });
    } catch (e) {
        logger.error(`Failed to increment commentCount for thread ${threadId}:`, e);
    }

    try {
        let recipientUserId, title, body, postDoc;

        if (parentCommentId) {
            const parentCommentDoc = await db.collection("forumThreads").doc(threadId).collection("comments").doc(parentCommentId).get();
            if (!parentCommentDoc.exists) return null;
            recipientUserId = parentCommentDoc.data().userId;
            title = "New Reply";
            body = `${commentData.username} replied to your comment.`;
            postDoc = await db.collection("forumThreads").doc(threadId).get();
        } else {
            postDoc = await db.collection("forumThreads").doc(threadId).get();
            if (!postDoc.exists) return null;
            recipientUserId = postDoc.data().userId;
            if (postDoc.data().notificationSent === true) return null;
            title = "New Comment";
            body = `${commentData.username} commented on your post.`;
        }

        if (recipientUserId === commentData.userId) return null;

        const recipientDoc = await db.collection("users").doc(recipientUserId).get();
        if (!recipientDoc.exists) return null;

        const recipientData = recipientDoc.data();
        const { fcmToken, notificationsEnabled = true, repliesEnabled = true, notificationCooldownHours = 2 } = recipientData;

        if (!notificationsEnabled) return null;
        if (parentCommentId && !repliesEnabled) return null;

        if (notificationCooldownHours > 0 && postDoc?.exists) {
            const lastViewedAt = postDoc.data().lastViewedAt;
            if (lastViewedAt && (Date.now() - lastViewedAt.toDate().getTime()) < notificationCooldownHours * 60 * 60 * 1000) {
                return null;
            }
        }

        if (!fcmToken) return null;

        // Use transaction for top-level comments to prevent duplicate notifications
        if (!parentCommentId) {
            let shouldSend = false;
            await db.runTransaction(async (transaction) => {
                const threadRefInner = db.collection("forumThreads").doc(threadId);
                const threadDoc = await transaction.get(threadRefInner);
                if (!threadDoc.exists || threadDoc.data().notificationSent === true) return;
                transaction.update(threadRefInner, { notificationSent: true });
                shouldSend = true;
            });
            if (!shouldSend) return null;
        }

        try {
            await messaging.send({ token: fcmToken, notification: { title, body }, data: { postId: threadId } });
        } catch (error) {
            if (error.code === "messaging/registration-token-not-registered") {
                await db.collection("users").doc(recipientUserId).update({ fcmToken: admin.firestore.FieldValue.delete() });
            } else {
                logger.error("Error sending notification:", error);
            }
        }
    } catch (error) {
        logger.error("Error in onCommentCreated:", error);
    }
    return null;
});

// ======================================================
// onCommentDeleted — IDEMPOTENT COUNT DECREMENT
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that updates counts/metadata when a forum comment is deleted.
 */
exports.onCommentDeleted = onDocumentDeleted("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const threadId = event.params.threadId;
    const commentId = event.params.commentId;
    // This is where the function touches Firestore documents/collections for the requested action.
    const threadRef = db.collection("forumThreads").doc(threadId);
    const eventLogRef = db.collection("processedEvents").doc(`COMMENT_DEC_${commentId}`);

    try {
        await db.runTransaction(async (t) => {
            const eventDoc = await t.get(eventLogRef);
            if (eventDoc.exists) return;

            const threadDoc = await t.get(threadRef);
            if (threadDoc.exists) {
                const currentCount = threadDoc.data().commentCount || 0;
                t.update(threadRef, { commentCount: Math.max(0, currentCount - 1) });
            }
            t.set(eventLogRef, { processedAt: admin.firestore.FieldValue.serverTimestamp(), type: "COMMENT_DEC" });
        });
    } catch (e) {
        logger.error(`Failed to decrement commentCount for thread ${threadId}:`, e);
    }
    return null;
});

// ======================================================
// onPostLiked
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that updates post like counts when a like document is created/deleted.
 */
exports.onPostLiked = onDocumentUpdated("forumThreads/{threadId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    if ((afterData.likeCount || 0) <= (beforeData.likeCount || 0)) return null;

    try {
        // Atomically claim the right to send this notification
        let shouldSend = false;
        await db.runTransaction(async (t) => {
            const postDoc = await t.get(event.data.after.ref);
            if (!postDoc.exists || postDoc.data().likeNotificationSent === true) return;
            t.update(event.data.after.ref, { likeNotificationSent: true });
            shouldSend = true;
        });

        if (!shouldSend) return null;

        // This is where the function touches Firestore documents/collections for the requested action.
        const recipientDoc = await db.collection("users").doc(afterData.userId).get();
        if (!recipientDoc.exists) return null;

        const { fcmToken, notificationsEnabled = true, notificationCooldownHours = 2 } = recipientDoc.data();
        if (!fcmToken || !notificationsEnabled) return null;

        if (notificationCooldownHours > 0 && afterData.lastViewedAt &&
            (Date.now() - afterData.lastViewedAt.toDate().getTime()) <
            notificationCooldownHours * 60 * 60 * 1000) return null;

        try {
            await messaging.send({
                token: fcmToken,
                notification: { title: "Post Liked!", body: "Someone liked your post." },
                data: { postId: event.params.threadId, type: "like" }
            });
        } catch (error) {
            if (error.code === "messaging/registration-token-not-registered") {
                await db.collection("users").doc(afterData.userId)
                    .update({ fcmToken: admin.firestore.FieldValue.delete() });
            } else {
                logger.error("Error sending post like notification:", error);
            }
        }
    } catch (error) {
        logger.error("Error in onPostLiked:", error);
    }
    return null;
});

// ======================================================
// onCommentLiked
// FIX #14: likeNotificationSent was read from the stale event snapshot, not from a
// transaction.  Under at-least-once delivery two concurrent invocations both saw false,
// both sent the notification, and both wrote true — resulting in duplicate pushes.
// Fix: mirror the transaction pattern already used in onPostLiked.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that updates comment like counts when a like document is created/deleted.
 */
exports.onCommentLiked = onDocumentUpdated("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    // Quick pre-check on the snapshot to avoid unnecessary Firestore reads on non-like updates.
    if ((afterData.likeCount || 0) <= (beforeData.likeCount || 0)) return null;

    try {
        // FIX #14: Atomically claim the right to send this notification via transaction.
        let shouldSend = false;
        await db.runTransaction(async (t) => {
            const commentDoc = await t.get(event.data.after.ref);
            if (!commentDoc.exists || commentDoc.data().likeNotificationSent === true) return;
            t.update(event.data.after.ref, { likeNotificationSent: true });
            shouldSend = true;
        });

        if (!shouldSend) return null;

        // This is where the function touches Firestore documents/collections for the requested action.
        const recipientDoc = await db.collection("users").doc(afterData.userId).get();
        if (!recipientDoc.exists) return null;

        const { fcmToken, notificationsEnabled = true, notificationCooldownHours = 2 } = recipientDoc.data();
        if (!fcmToken || !notificationsEnabled) return null;

        const postDoc = await db.collection("forumThreads").doc(event.params.threadId).get();
        if (notificationCooldownHours > 0 && postDoc.exists) {
            const lastViewedAt = postDoc.data().lastViewedAt;
            if (lastViewedAt && (Date.now() - lastViewedAt.toDate().getTime()) < notificationCooldownHours * 60 * 60 * 1000) return null;
        }

        try {
            await messaging.send({
                token: fcmToken,
                notification: { title: "Comment Liked!", body: "Someone liked your comment." },
                data: { postId: event.params.threadId, type: "comment_like" }
            });
        } catch (error) {
            if (error.code === "messaging/registration-token-not-registered") {
                await db.collection("users").doc(afterData.userId).update({ fcmToken: admin.firestore.FieldValue.delete() });
            } else {
                logger.error("Error sending comment like notification:", error);
            }
        }
    } catch (error) { logger.error("Error in onCommentLiked:", error); }
    return null;
});

// ======================================================
// onUserProfileUpdated — sync PFP + username to forum content
// FIXED: Added error handling and split collection tasks to prevent total failure
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Firestore trigger that propagates profile changes (such as username/photo) to places that cache
 * user display data.
 */
exports.onUserProfileUpdated = onDocumentUpdated({
    document: "users/{userId}",
    timeoutSeconds: 540
}, async (event) => {
    const newData = event.data.after.data();
    const oldData = event.data.before.data();
    const userId = event.params.userId;

    // FIX #12 + #7: Guard against self-triggering infinite loop.
    // This function writes profileSyncStatus / profileSyncStartedAt / profileSyncCompletedAt
    // back to the same users/{userId} doc it listens on.  When those fields change the trigger
    // fires again.  Detect that case early and exit before doing any real work.
    const SYNC_FIELDS = new Set(["profileSyncStatus", "profileSyncStartedAt", "profileSyncCompletedAt"]);
    const changedKeys = Object.keys(newData).filter(k => {
        const nv = newData[k];
        const ov = oldData[k];
        // Firestore Timestamps: compare via toMillis() to avoid reference inequality
        if (nv && typeof nv.toMillis === "function" && ov && typeof ov.toMillis === "function") {
            return nv.toMillis() !== ov.toMillis();
        }
        return JSON.stringify(nv) !== JSON.stringify(ov);
    });
    const onlySyncFieldsChanged = changedKeys.length > 0 && changedKeys.every(k => SYNC_FIELDS.has(k));
    if (onlySyncFieldsChanged) {
        logger.info(`onUserProfileUpdated: Only profileSync* fields changed for ${userId}. Skipping to prevent loop.`);
        return null;
    }

    const newPfp = newData.profilePictureUrl || "";
    const oldPfp = oldData.profilePictureUrl || "";
    const newUsername = newData.username || "";
    const oldUsername = oldData.username || "";

    // Delete old PFP from storage if changed (unchanged)
    if (oldPfp && oldPfp !== newPfp && oldPfp.includes("firebasestorage.googleapis.com")) {
        try {
            const decodedUrl = decodeURIComponent(oldPfp);
            const filePath = decodedUrl.substring(decodedUrl.indexOf("/o/") + 3, decodedUrl.indexOf("?"));
            const bucket = admin.storage().bucket();
            const file = bucket.file(filePath);
            const [exists] = await file.exists();
            if (exists) await file.delete();
        } catch (error) {
            logger.error(`Error deleting old PFP for user ${userId}:`, error);
        }
    }

    const updates = {};
    if (newPfp !== oldPfp) updates.userProfilePictureUrl = newPfp;
    if (newUsername !== oldUsername) updates.username = newUsername;

    if (Object.keys(updates).length === 0) return null;

    // Mark propagation as in-progress
    await event.data.after.ref.update({
        profileSyncStatus: "in_progress",
        profileSyncStartedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    const BATCH_SIZE = 450;
    const processBatch = async (querySnapshot, batchUpdates) => {
        if (querySnapshot.empty) return 0;
        const docs = querySnapshot.docs;
        let count = 0;
        for (let i = 0; i < docs.length; i += BATCH_SIZE) {
            const batch = db.batch();
            docs.slice(i, i + BATCH_SIZE).forEach(doc => batch.update(doc.ref, batchUpdates));
            await batch.commit();
            count += Math.min(BATCH_SIZE, docs.length - i);
        }
        return count;
    };

    try {
        const [postsSnap, sightingsSnap, commentsSnap] = await Promise.all([
            db.collection("forumThreads").where("userId", "==", userId).get(),
            newUsername !== oldUsername
                ? db.collection("userBirdSightings").where("userId", "==", userId).get()
                : Promise.resolve({ empty: true, docs: [] }),
            db.collectionGroup("comments").where("userId", "==", userId).get()
        ]);

        await Promise.all([
            processBatch(postsSnap, updates),
            newUsername !== oldUsername ? processBatch(sightingsSnap, { username: newUsername }) : Promise.resolve(),
            processBatch(commentsSnap, updates)
        ]);

        if (newUsername !== oldUsername) {
            const repliesSnap = await db.collectionGroup("comments")
                .where("parentUsername", "==", oldUsername).get();
            await processBatch(repliesSnap, { parentUsername: newUsername });
        }

        // Mark propagation complete
        await event.data.after.ref.update({
            profileSyncStatus: "complete",
            profileSyncCompletedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        logger.info(`Profile propagation complete for user ${userId}`);
    } catch (error) {
        // Leave syncStatus as "in_progress" so it can be detected and retried
        logger.error(`Profile propagation failed for user ${userId}:`, error);
    }

    return null;
});

// ======================================================
// onUserAuthDeleted — automatic auth trigger
// Unified delete pipeline: this is now the single auth-delete entry point.
// All cleanup, archival, subcollection deletion, and storage cleanup run through
// performDeletedUserCleanup(...) so account removal is idempotent and easier to audit.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
/**
 * Export: Auth cleanup trigger that removes/archives associated app data when the auth record is deleted.
 */
exports.onUserAuthDeleted = auth.user().onDelete(async (user) => {
    try {
        await performDeletedUserCleanup(user);
    } catch (error) {
        logger.error("Error in onUserAuthDeleted trigger:", error);
    }
});

// ======================================================
// recordForumPost — server-side 3 posts per user per calendar day
// ======================================================
// Called from CreatePostActivity before writing the Firestore post doc.
// Uses a transaction to atomically check + increment the daily post count.
// Resets automatically at midnight UTC.
//
// Returns: { allowed: true, remaining: N }  — post is permitted
//          { allowed: false, remaining: 0 } — daily limit reached

// ======================================================
// recordForumPost — server-side 3 posts per user per calendar day
// ======================================================
// Called from CreatePostActivity before writing the Firestore post doc.
// Uses a transaction to atomically check + increment the daily post count.
// Resets automatically at midnight UTC.
//
// Returns: { allowed: true, remaining: N }  — post is permitted
//          { allowed: false, remaining: 0 } — daily limit reached
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */

// ======================================================
// recordForumPost — only limit posts that SHOW LOCATION ON MAP
// ======================================================
// If showLocation == false:
//   - allow the post immediately
//   - do NOT consume one of the 3 daily map-location post slots
//
// If showLocation == true:
//   - enforce the 3-per-day limit
//   - atomically increment the user's daily map-location post count
// ======================================================
exports.recordForumPost = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const { showLocation = false } = request.data || {};
    const MAX_DAILY_LOCATION_POSTS = 3;

    // If the user is posting WITHOUT location, do not apply the daily limit at all.
    if (!showLocation) {
        const result = await db.runTransaction(async (t) => {
            await assertForumSubmissionCooldownAllowed(t, userId, "post");
            return {
                allowed: true,
                remaining: MAX_DAILY_LOCATION_POSTS,
                locationLimited: false
            };
        });
        return result;
    }

    // Use UTC date key so the count resets each day.
    const today = new Date().toISOString().slice(0, 10);

    const postLimitRef = db
        .collection("users").doc(userId)
        .collection("settings").doc("forumPostLimits");

    try {
        const result = await db.runTransaction(async (t) => {
            await assertForumSubmissionCooldownAllowed(t, userId, "post");
            const snap = await t.get(postLimitRef);

            let locationPostsToday = 0;

            if (snap.exists) {
                const data = snap.data();

                // Only reuse the stored count if it is from today.
                if (data.date === today) {
                    locationPostsToday =
                        typeof data.locationPostsToday === "number"
                            ? data.locationPostsToday
                            : 0;
                }
            }

            // If the user already used all 3 location-post slots today, reject.
            if (locationPostsToday >= MAX_DAILY_LOCATION_POSTS) {
                return {
                    allowed: false,
                    remaining: 0,
                    locationLimited: true
                };
            }

            const newCount = locationPostsToday + 1;

            // Save the updated daily count for location-sharing forum posts only.
            t.set(postLimitRef, {
                date: today,
                locationPostsToday: newCount,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            }, { merge: true });

            return {
                allowed: true,
                remaining: MAX_DAILY_LOCATION_POSTS - newCount,
                locationLimited: true
            };
        });

        logger.info(
            `recordForumPost: userId=${userId} showLocation=${showLocation} allowed=${result.allowed} remaining=${result.remaining}`
        );

        return result;

    } catch (error) {
        logger.error("recordForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to check post limit: ${error.message}`);
    }
});

/**
 * Reverses the daily post increment if the client-side upload or write fails.
 */
exports.rollbackForumPostRecord = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const today = new Date().toISOString().slice(0, 10);
    const postLimitRef = db.collection("users").doc(userId).collection("settings").doc("forumPostLimits");

    try {
        await db.runTransaction(async (t) => {
            const snap = await t.get(postLimitRef);
            if (!snap.exists) return;

            const data = snap.data();
            if (data.date !== today) return;

            const currentCount = typeof data.locationPostsToday === "number" ? data.locationPostsToday : 0;
            if (currentCount > 0) {
                t.update(postLimitRef, {
                    locationPostsToday: currentCount - 1,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
                });
            }
        });
        return { success: true };
    } catch (error) {
        logger.error("rollbackForumPostRecord failed:", error);
        throw new HttpsError("internal", "Failed to rollback post record.");
    }
});

// ======================================================
// Forum write callables — backend-authoritative create/edit paths
// ======================================================
const FORUM_POST_MAX_LENGTH = 500;
const FORUM_COMMENT_MAX_LENGTH = 300;
const FORUM_EDIT_WINDOW_MS = 5 * 60 * 1000;
const FORUM_SUBMISSION_COOLDOWN_MS = 15 * 1000;


const REPORT_UNDER_REVIEW_THRESHOLD = 3;
const REPORT_HIDE_THRESHOLD = 5;
const MAX_REPORTS_PER_HOUR = 10;
const MODERATION_APPEAL_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;
const MODERATION_WARNING_EXPIRY_MS = 90 * 24 * 60 * 60 * 1000;
const MODERATION_STRIKE_EXPIRY_MS = 180 * 24 * 60 * 60 * 1000;
const MODERATION_SUSPEND_STAGE_ONE_MS = 24 * 60 * 60 * 1000;
const MODERATION_SUSPEND_STAGE_TWO_MS = 7 * 24 * 60 * 60 * 1000;
const REPORT_RATE_LIMIT_KEY = "forumReportRateLimit";

/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeModerationStatus(status) {
    const normalized = typeof status === "string" ? status.trim().toLowerCase() : "";
    if (!normalized) return MODERATION_STATUS_VISIBLE;
    if ([
        MODERATION_STATUS_VISIBLE,
        MODERATION_STATUS_UNDER_REVIEW,
        MODERATION_STATUS_HIDDEN,
        MODERATION_STATUS_REMOVED,
    ].includes(normalized)) {
        return normalized;
    }
    return MODERATION_STATUS_VISIBLE;
}

/**
 * Helper: Predicate helper that answers a yes/no question used by policy/business logic.
 */
function isPublicForumStatus(status) {
    const normalized = normalizeModerationStatus(status);
    return normalized === MODERATION_STATUS_VISIBLE || normalized === MODERATION_STATUS_UNDER_REVIEW;
}

/**
 * Helper: Timestamp/date conversion helper used to keep time comparisons consistent.
 */
function timestampToDate(value) {
    const millis = timestampToMillis(value);
    return millis == null ? null : new Date(millis);
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildModerationRateLimitRef(userId, key = REPORT_RATE_LIMIT_KEY) {
    return db.collection("users").doc(userId).collection("settings").doc(key);
}

/**
 * Helper: Blocks a request when the caller exceeded an hourly backend rate limit.
 */
async function assertHourlyRateLimitOrThrow(transaction, userId, key, limit, actionLabel) {
    const rateRef = buildModerationRateLimitRef(userId, key);
    const bucket = new Date().toISOString().slice(0, 13); // YYYY-MM-DDTHH (UTC hour)
    const snap = await transaction.get(rateRef);

    let currentCount = 0;
    if (snap.exists) {
        const data = snap.data() || {};
        if (data.bucket === bucket && typeof data.count === "number") {
            currentCount = data.count;
        }
    }

    if (currentCount >= limit) {
        throw new HttpsError(
            "resource-exhausted",
            `You have reached the hourly limit for ${actionLabel}. Please try again later.`
        );
    }

    transaction.set(rateRef, {
        bucket,
        count: currentCount + 1,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
}

/**
 * Helper: Checks whether the user is currently allowed to post/comment in the forum.
 */
async function assertUserCanUseForumOrThrow(userId, actionLabel = "perform this forum action") {
    const userRef = db.collection("users").doc(userId);
    const userSnap = await userRef.get();

    if (!userSnap.exists) {
        throw new HttpsError("failed-precondition", "User profile not found.");
    }

    const userData = userSnap.data() || {};
    if (userData.permanentForumBan === true) {
        throw new HttpsError(
            "permission-denied",
            `Your forum access is permanently restricted. You can still sign in and submit an appeal, but you cannot ${actionLabel}.`
        );
    }

    const suspendedUntilMs = timestampToMillis(userData.forumSuspendedUntil);
    if (suspendedUntilMs != null && suspendedUntilMs > Date.now()) {
        const suspendedUntilDate = new Date(suspendedUntilMs);
        throw new HttpsError(
            "permission-denied",
            `Your forum access is temporarily restricted until ${suspendedUntilDate.toLocaleString("en-US")}. You cannot ${actionLabel} until then.`
        );
    }

    return {
        userRef,
        userData,
    };
}

/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeReportReason(reason) {
    const raw = sanitizeText(reason || "", 200).trim().toLowerCase();

    if (!raw) {
        throw new HttpsError("invalid-argument", "A report reason is required.");
    }

    if (raw.includes("image")) return "image";
    if (raw.includes("language")) return "language";
    if (raw.includes("spam")) return "spam";
    if (raw.includes("harass")) return "harassment";
    return "other";
}

/**
 * Helper: Internal helper `extractModerationTargetText` used by the surrounding backend flow.
 */
function extractModerationTargetText(data, maxLength = 1000) {
    if (!data || typeof data !== "object") return null;

    const candidates = [data.message, data.text, data.content];
    for (const candidate of candidates) {
        if (typeof candidate === "string") {
            const cleaned = sanitizeText(candidate, maxLength).trim();
            if (cleaned) return cleaned;
        }
    }

    return null;
}

/**
 * Helper: Loads and validates the moderation target (post/comment/user/etc.) for callable flows.
 */
async function resolveModerationTargetOrThrow(targetType, targetId, threadId = null) {
    const normalizedType = sanitizeText(targetType || "", 50).trim().toLowerCase();
    const normalizedTargetId = sanitizeText(targetId || "", 200).trim();
    const normalizedThreadId = sanitizeText(threadId || "", 200).trim();

    if (!normalizedTargetId) {
        throw new HttpsError("invalid-argument", "targetId is required.");
    }

    if (normalizedType === "post" || normalizedType === "thread") {
        const ref = db.collection("forumThreads").doc(normalizedTargetId);
        const snap = await ref.get();
        if (!snap.exists) {
            throw new HttpsError("not-found", "Post not found.");
        }
        return {
            canonicalType: "post",
            rawType: "post",
            ref,
            snap,
            data: snap.data() || {},
            targetId: snap.id,
            threadId: snap.id,
            ownerUserId: sanitizeText((snap.data() || {}).userId || "", 200).trim() || null,
        };
    }

    if (normalizedType === "comment" || normalizedType === "reply") {
        if (!normalizedThreadId) {
            throw new HttpsError("invalid-argument", "threadId is required for comment and reply reports.");
        }

        const ref = db.collection("forumThreads").doc(normalizedThreadId).collection("comments").doc(normalizedTargetId);
        const snap = await ref.get();

        if (!snap.exists) {
            throw new HttpsError("not-found", "Comment not found.");
        }

        const data = snap.data() || {};
        const rawType = data.parentCommentId ? "reply" : "comment";

        return {
            canonicalType: "comment",
            rawType,
            ref,
            snap,
            data,
            targetId: snap.id,
            threadId: normalizedThreadId,
            ownerUserId: sanitizeText(data.userId || "", 200).trim() || null,
        };
    }

    throw new HttpsError("invalid-argument", "Unsupported report target type.");
}

/**
 * Helper: Creates a reference/payload helper used by write operations.
 */
function createModerationEventRef() {
    return db.collection("moderationEvents").doc();
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildModerationEventPayload({
    eventId,
    userId,
    targetType,
    targetId,
    actionType,
    reasonCode,
    reasonText,
    source,
    createdBy = "system",
    appealable = false,
    expiresAt = null,
    metadata = {},
    evidenceImageUrl = null,
    evidenceText = null,
}) {
    return {
        eventId,
        userId,
        targetType: targetType || "user",
        targetId: targetId || null,
        actionType,
        reasonCode: reasonCode || "other",
        reasonText: sanitizeText(reasonText || "", 500),
        source: source || "system",
        createdBy,
        status: "active",
        appealable,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        expiresAt,
        reviewedAt: null,
        reviewedBy: null,
        reversalReason: null,
        metadata: metadata || {},
        evidenceImageUrl: evidenceImageUrl || null,
        evidenceText: sanitizeText(evidenceText || "", 1000) || null,
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildUserModerationDefaults(userData = {}) {
    return {
        warningCount: typeof userData.warningCount === "number" ? userData.warningCount : 0,
        strikeCount: typeof userData.strikeCount === "number" ? userData.strikeCount : 0,
        permanentForumBan: userData.permanentForumBan === true,
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildContentModerationUpdate(status, source, reasonText, nowTimestamp) {
    const update = {
        moderationStatus: status,
        moderationSource: source || null,
        moderationReason: sanitizeText(reasonText || "", 500) || null,
        moderatedAt: nowTimestamp,
    };

    if (status === MODERATION_STATUS_HIDDEN || status === MODERATION_STATUS_REMOVED) {
        update.hiddenAt = nowTimestamp;
    }

    return update;
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildForumRestrictionCopy(actionLabel) {
    return `You cannot ${actionLabel} because your forum access is currently restricted.`;
}

/**
 * Helper: Loads or returns the requested value/entity in a backend-safe format.
 */
function getModerationReviewerIdentityOrThrow(request) {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Auth required.");
    }

    const token = request.auth.token || {};
    const isReviewer = token.admin === true || token.moderator === true || token.staff === true;
    if (!isReviewer) {
        throw new HttpsError("permission-denied", "Moderator access required.");
    }

    const reviewerId = request.auth.uid;
    const reviewedBy = sanitizeText(token.email || request.auth.uid || "moderator", 200);

    return {
        reviewerId,
        reviewedBy,
        moderatorSourceMeta: {
            uid: reviewerId,
            email: sanitizeText(token.email || "", 200),
            username: sanitizeText(token.name || token.displayName || "", 120),
        },
    };
}

/**
 * Helper: Predicate helper that answers a yes/no question used by policy/business logic.
 */
function isModerationEventCurrentlyActive(eventData, nowMs = Date.now()) {
    if (!eventData || eventData.status !== "active") return false;

    const expiresAtMs = timestampToMillis(eventData.expiresAt);
    if (expiresAtMs != null && expiresAtMs <= nowMs) {
        return false;
    }

    return true;
}

/**
 * Helper: Transaction-safe version of moderation target resolution used inside Firestore transactions.
 */
async function resolveModerationTargetForTransaction(transaction, targetType, targetId, threadId = null) {
    const normalizedType = sanitizeText(targetType || "", 50).trim().toLowerCase();
    const normalizedTargetId = sanitizeText(targetId || "", 200).trim();
    const normalizedThreadId = sanitizeText(threadId || "", 200).trim();
    if (!normalizedTargetId) return null;

    if (normalizedType === "post") {
        const ref = db.collection("forumThreads").doc(normalizedTargetId);
        const snap = await transaction.get(ref);
        return snap.exists ? { ref, snap, canonicalType: "post", rawType: "post", threadId: snap.id } : null;
    }

    if (normalizedType === "comment" || normalizedType === "reply") {
        if (!normalizedThreadId) return null;

        const ref = db.collection("forumThreads").doc(normalizedThreadId).collection("comments").doc(normalizedTargetId);
        const snap = await transaction.get(ref);
        if (!snap.exists) return null;

        const data = snap.data() || {};
        return {
            ref,
            snap,
            canonicalType: "comment",
            rawType: data.parentCommentId ? "reply" : "comment",
            threadId: normalizedThreadId,
        };
    }

    return null;
}

/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeReportSourceContext(value) {
    const normalized = sanitizeText(value || "", 80).trim().toLowerCase();
    if (!normalized) return null;

    if (normalized === "heatmap" || normalized === "nearby_heatmap") return "heatmap";
    if (normalized === "post_detail" || normalized === "postdetail" || normalized === "post-detail") return "post_detail";
    if (normalized === "forum_feed" || normalized === "forum" || normalized === "forum_fragment") return "forum_feed";
    if (normalized === "profile" || normalized === "user_profile" || normalized === "profile_posts") return "profile";

    return normalized;
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildVisionAccessTokenProjectId() {
    return process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || null;
}

/**
 * Helper: Fetches a Google access token from metadata server for Vision/API calls.
 */
async function fetchMetadataServerAccessToken() {
    const response = await axios.get(
        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
        {
            headers: { "Metadata-Flavor": "Google" },
            timeout: 5000,
        }
    );

    const token = response?.data?.access_token;
    if (!token) {
        throw new Error("Metadata server did not return an access token.");
    }
    return token;
}

/**
 * Helper: Calls Google Vision SafeSearch on a Cloud Storage image URL.
 */
async function runVisionSafeSearchOnStorageUrl(imageUrl) {
    const storagePath = getStoragePathFromDownloadUrl(imageUrl);
    if (!storagePath) {
        return { skipped: true, reason: "storage_path_unavailable" };
    }

    const accessToken = await fetchMetadataServerAccessToken();
    const bucket = storage.bucket().name;
    const gsUri = `gs://${bucket}/${storagePath}`;
    const projectId = buildVisionAccessTokenProjectId();

    const response = await axios.post(
        "https://vision.googleapis.com/v1/images:annotate",
        {
            requests: [
                {
                    image: {
                        source: {
                            imageUri: gsUri,
                        },
                    },
                    features: [
                        {
                            type: "SAFE_SEARCH_DETECTION",
                        },
                    ],
                },
            ],
        },
        {
            headers: {
                Authorization: `Bearer ${accessToken}`,
                ...(projectId ? { "x-goog-user-project": projectId } : {}),
                "Content-Type": "application/json; charset=utf-8",
            },
            timeout: 15000,
        }
    );

    const annotation = response?.data?.responses?.[0]?.safeSearchAnnotation || null;
    return {
        skipped: false,
        annotation,
        storagePath,
        gsUri,
    };
}

/**
 * Helper: Decision helper that returns whether a rule/policy should trigger.
 */
function shouldRejectForumImageFromSafeSearch(annotation) {
    if (!annotation || typeof annotation !== "object") return false;

    const adult = String(annotation.adult || "UNKNOWN").toUpperCase();
    const racy = String(annotation.racy || "UNKNOWN").toUpperCase();

    return adult === "VERY_LIKELY" || adult === "LIKELY" || racy === "VERY_LIKELY";
}

/**
 * Helper: Formats a human-readable description from a machine-generated result.
 */
function describeSafeSearchAnnotation(annotation) {
    if (!annotation || typeof annotation !== "object") {
        return "SafeSearch returned no annotation.";
    }

    return [
        `adult=${annotation.adult || "UNKNOWN"}`,
        `racy=${annotation.racy || "UNKNOWN"}`,
        `violence=${annotation.violence || "UNKNOWN"}`,
        `medical=${annotation.medical || "UNKNOWN"}`,
        `spoof=${annotation.spoof || "UNKNOWN"}`,
    ].join(", ");
}

/**
 * Helper: Combines SafeSearch results and policy thresholds into a forum-image moderation decision.
 */
async function evaluateForumPostImageModeration({ userId, postId, imageUrl }) {
    if (typeof imageUrl !== "string" || !imageUrl.trim()) {
        return {
            blocked: false,
            skipped: true,
            annotation: null,
            reasonText: "",
        };
    }

    try {
        const result = await runVisionSafeSearchOnStorageUrl(imageUrl.trim());
        if (result.skipped) {
            return {
                blocked: false,
                skipped: true,
                annotation: null,
                reasonText: result.reason || "",
            };
        }

        const blocked = shouldRejectForumImageFromSafeSearch(result.annotation);
        const reasonText = describeSafeSearchAnnotation(result.annotation);

        if (blocked) {
            const archivedUrl = await archiveForumPostImageIfNeeded(userId, postId, imageUrl.trim());
            return {
                blocked: true,
                skipped: false,
                annotation: result.annotation,
                archivedUrl,
                reasonText,
                gsUri: result.gsUri,
            };
        }

        return {
            blocked: false,
            skipped: false,
            annotation: result.annotation,
            reasonText,
            gsUri: result.gsUri,
        };
    } catch (error) {
        logger.warn("evaluateForumPostImageModeration: Vision SafeSearch failed, allowing post.", error);
        return {
            blocked: false,
            skipped: true,
            annotation: null,
            reasonText: sanitizeText(error?.message || "vision_scan_failed", 200),
        };
    }
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildForumImageModerationNotice(penaltySummary) {
    const penaltyType = penaltySummary?.penaltyType || null;
    const restriction = penaltySummary?.appliedRestriction || null;

    let moderationNoticeType = "moderation_notice";
    let coreMessage = "A moderation action was applied to your account.";

    if (penaltyType === "warning") {
        moderationNoticeType = "warning";
        coreMessage = "You have been issued a warning.";
    } else if (penaltyType === "strike") {
        moderationNoticeType = restriction ? "strike_with_restriction" : "strike";
        coreMessage = "You have been issued a strike.";
    }

    let restrictionSentence = "";
    if (restriction) {
        restrictionSentence = " Your forum access is currently restricted.";
    }

    const userMessage = `This image could not be posted because it was flagged as explicit. ${coreMessage}${restrictionSentence} Check your Moderation History in the settings.`;

    return {
        moderationNoticeType,
        userMessage,
    };
}

/**
 * Helper: Writes warning/strike/restriction changes after an automatic moderation event.
 */
async function applyAutomaticPenaltyForUser({
    transaction,
    userRef,
    userData,
    userId,
    source,
    reasonCode,
    reasonText,
    targetType,
    targetId,
    evidenceText = null,
}) {
    const defaults = buildUserModerationDefaults(userData);
    const currentWarnings = defaults.warningCount;
    const currentStrikes = defaults.strikeCount;
    const now = Date.now();

    if (currentWarnings <= 0 && currentStrikes <= 0) {
        const warningEventRef = createModerationEventRef();
        transaction.set(warningEventRef, buildModerationEventPayload({
            eventId: warningEventRef.id,
            userId,
            targetType: targetType || "user",
            targetId: targetId || null,
            actionType: "warning",
            reasonCode,
            reasonText,
            source,
            appealable: false,
            expiresAt: admin.firestore.Timestamp.fromMillis(now + MODERATION_WARNING_EXPIRY_MS),
            evidenceText,
        }));
        transaction.set(userRef, {
            warningCount: currentWarnings + 1,
            lastViolationAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });

        return {
            penaltyType: "warning",
            linkedModerationEventIds: [warningEventRef.id],
            strikeCountAfter: currentStrikes,
            warningCountAfter: currentWarnings + 1,
            appliedRestriction: null,
        };
    }

    const newStrikeCount = currentStrikes + 1;
    const strikeEventRef = createModerationEventRef();
    transaction.set(strikeEventRef, buildModerationEventPayload({
        eventId: strikeEventRef.id,
        userId,
        targetType: targetType || "user",
        targetId: targetId || null,
        actionType: "strike",
        reasonCode,
        reasonText,
        source,
        appealable: true,
        expiresAt: admin.firestore.Timestamp.fromMillis(now + MODERATION_STRIKE_EXPIRY_MS),
        evidenceText,
    }));

    const userUpdates = {
        strikeCount: newStrikeCount,
        lastViolationAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    let appliedRestriction = null;

    if (newStrikeCount === 1) {
        const until = admin.firestore.Timestamp.fromMillis(now + MODERATION_SUSPEND_STAGE_ONE_MS);
        userUpdates.forumSuspendedUntil = until;
        appliedRestriction = {
            actionType: "forum_suspension",
            expiresAt: until,
            message: "Automatic 24-hour forum suspension after repeated moderation issues.",
        };
    } else if (newStrikeCount === 2) {
        const until = admin.firestore.Timestamp.fromMillis(now + MODERATION_SUSPEND_STAGE_TWO_MS);
        userUpdates.forumSuspendedUntil = until;
        appliedRestriction = {
            actionType: "forum_suspension",
            expiresAt: until,
            message: "Automatic 7-day forum suspension after repeated moderation issues.",
        };
    } else if (newStrikeCount >= 3) {
        userUpdates.permanentForumBan = true;
        userUpdates.forumSuspendedUntil = null;
        appliedRestriction = {
            actionType: "forum_ban",
            expiresAt: null,
            message: "Automatic permanent forum ban after repeated moderation issues.",
        };
    }

    transaction.set(userRef, userUpdates, { merge: true });

    const linkedModerationEventIds = [strikeEventRef.id];

    if (appliedRestriction) {
        const restrictionEventRef = createModerationEventRef();
        transaction.set(restrictionEventRef, buildModerationEventPayload({
            eventId: restrictionEventRef.id,
            userId,
            targetType: "user",
            targetId: userId,
            actionType: appliedRestriction.actionType,
            reasonCode,
            reasonText: appliedRestriction.message,
            source,
            appealable: true,
            expiresAt: appliedRestriction.expiresAt,
            evidenceText,
        }));
        linkedModerationEventIds.push(restrictionEventRef.id);
    }

    return {
        penaltyType: "strike",
        linkedModerationEventIds,
        strikeCountAfter: newStrikeCount,
        warningCountAfter: currentWarnings,
        appliedRestriction,
    };
}

/**
 * Reads the caller's canonical forum identity from users/{uid} so the client cannot spoof it.
 */
/**
 * Helper: Loads the author profile needed for forum writes/notifications.
 */
async function getForumAuthorProfileOrThrow(userId) {
    const userSnap = await db.collection("users").doc(userId).get();
    if (!userSnap.exists) {
        throw new HttpsError("failed-precondition", "User profile not found.");
    }

    const userData = userSnap.data() || {};
    const username = sanitizeText(userData.username || "", 80).trim();
    const userProfilePictureUrl = typeof userData.profilePictureUrl === "string"
        ? userData.profilePictureUrl.trim()
        : "";

    if (!username) {
        throw new HttpsError("failed-precondition", "Username not found for this account.");
    }

    return { username, userProfilePictureUrl };
}

/**
 * Normalizes a numeric coordinate and throws when the caller provides an invalid value.
 */
/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeCoordinate(value, fieldName) {
    const num = typeof value === "number" ? value : Number(value);
    if (!Number.isFinite(num)) {
        throw new HttpsError("invalid-argument", `${fieldName} must be a valid number.`);
    }
    return num;
}

/**
 * Returns a sanitized forum message and enforces the configured max length.
 */
/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeForumText(text, maxLength, emptyMessage) {
    if (text == null) return "";
    if (typeof text !== "string") {
        throw new HttpsError("invalid-argument", "Text must be a string.");
    }
    const trimmed = sanitizeText(text, maxLength).trim();
    if (!trimmed) {
        throw new HttpsError("invalid-argument", emptyMessage);
    }
    if (trimmed.length > maxLength) {
        throw new HttpsError("invalid-argument", `Text exceeds ${maxLength} characters.`);
    }
    return trimmed;
}

/**
 * Ensures the resource is still inside the client-visible 5 minute edit window.
 */
/**
 * Helper: Validates required conditions and throws an error when the request should not continue.
 */
function assertWithinForumEditWindow(timestamp, typeLabel) {
    const createdAt = timestamp && typeof timestamp.toDate === "function"
        ? timestamp.toDate()
        : null;

    if (!createdAt) {
        throw new HttpsError("failed-precondition", `${typeLabel} timestamp is missing.`);
    }

    if ((Date.now() - createdAt.getTime()) > FORUM_EDIT_WINDOW_MS) {
        throw new HttpsError("permission-denied", `The edit window for this ${typeLabel.toLowerCase()} has expired.`);
    }
}



/**
 * Returns the user-scoped forum cooldown document ref.
 */
/**
 * Helper: Loads or returns the requested value/entity in a backend-safe format.
 */
function getForumSubmissionCooldownRef(userId) {
    return db.collection("users").doc(userId).collection("settings").doc("forumSubmissionCooldown");
}


/**
 * Throws a user-facing cooldown error when the previous forum submission is still too recent.
 */
/**
 * Helper: Conditional helper that only throws/writes when the triggering condition is met.
 */
function maybeThrowForumSubmissionCooldownError(lastSubmissionAt, submissionType) {
    const lastSubmissionMs = lastSubmissionAt && typeof lastSubmissionAt.toMillis === "function"
        ? lastSubmissionAt.toMillis()
        : null;

    if (lastSubmissionMs == null) {
        return;
    }

    const elapsedMs = admin.firestore.Timestamp.now().toMillis() - lastSubmissionMs;
    if (elapsedMs < FORUM_SUBMISSION_COOLDOWN_MS) {
        const remainingMs = FORUM_SUBMISSION_COOLDOWN_MS - elapsedMs;
        const remainingSeconds = Math.max(1, Math.ceil(remainingMs / 1000));
        throw new HttpsError(
            "resource-exhausted",
            `Please wait ${remainingSeconds} second${remainingSeconds === 1 ? "" : "s"} before submitting another forum ${submissionType}.`
        );
    }
}

/**
 * Checks whether the user is still inside the forum submission cooldown without consuming it.
 */
/**
 * Helper: Checks whether the user is still on cooldown before a forum submission.
 */
async function assertForumSubmissionCooldownAllowed(transaction, userId, submissionType) {
    const cooldownSnap = await transaction.get(getForumSubmissionCooldownRef(userId));
    if (!cooldownSnap.exists) {
        return;
    }
    maybeThrowForumSubmissionCooldownError(cooldownSnap.get("lastSubmissionAt"), submissionType);
}

/**
 * Enforces the forum submission cooldown and records the new submit timestamp inside the same transaction.
 */
/**
 * Helper: Atomically verifies and records forum submission cooldown usage.
 */
async function assertAndConsumeForumSubmissionCooldown(transaction, userId, submissionType) {
    const cooldownRef = getForumSubmissionCooldownRef(userId);
    await assertForumSubmissionCooldownAllowed(transaction, userId, submissionType);
    const nowTs = admin.firestore.Timestamp.now();

    transaction.set(cooldownRef, {
        lastSubmissionAt: nowTs,
        lastSubmissionType: submissionType,
        updatedAt: nowTs,
    }, { merge: true });
}

/**
 * Converts a Firebase Storage download URL back into the storage object path.
 */
/**
 * Helper: Loads or returns the requested value/entity in a backend-safe format.
 */
function getStoragePathFromDownloadUrl(downloadUrl) {
    if (typeof downloadUrl !== "string" || !downloadUrl.includes("/o/")) {
        return null;
    }

    try {
        const decodedUrl = decodeURIComponent(downloadUrl);
        const pathStart = decodedUrl.indexOf("/o/") + 3;
        if (pathStart < 3) return null;
        const pathEnd = decodedUrl.indexOf("?", pathStart);
        return decodedUrl.substring(pathStart, pathEnd !== -1 ? pathEnd : decodedUrl.length);
    } catch (error) {
        logger.warn("getStoragePathFromDownloadUrl: failed to parse URL", error);
        return null;
    }
}

/**
 * Archives a post image into the same folder structure the Android app previously used.
 */
/**
 * Helper: Moves/archives a forum image when a post is removed or hidden.
 */
async function archiveForumPostImageIfNeeded(userId, postId, imageUrl) {
    if (typeof imageUrl !== "string" || !imageUrl.trim()) {
        return imageUrl || "";
    }

    const oldPath = getStoragePathFromDownloadUrl(imageUrl.trim());
    if (!oldPath) {
        return imageUrl;
    }

    try {
        const bucket = storage.bucket();
        const oldFile = bucket.file(oldPath);
        const [exists] = await oldFile.exists();
        if (!exists) {
            return imageUrl;
        }

        const oldName = oldPath.split("/").pop() || `${postId}.jpg`;
        const newPath = `archive/forum_post_images/${userId}/${postId}_${oldName}`;
        const newFile = bucket.file(newPath);

        await oldFile.copy(newFile);

        let [metadata] = await newFile.getMetadata();
        let token = metadata?.metadata?.firebaseStorageDownloadTokens || "";
        if (!token) {
            token = crypto.randomUUID();
            await newFile.setMetadata({
                metadata: {
                    ...(metadata?.metadata || {}),
                    firebaseStorageDownloadTokens: token,
                },
            });
            [metadata] = await newFile.getMetadata();
        }

        await oldFile.delete().catch((error) => {
            logger.warn(`archiveForumPostImageIfNeeded: could not delete old file ${oldPath}`, error);
        });

        return `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(newPath)}?alt=media&token=${token}`;
    } catch (error) {
        logger.error(`archiveForumPostImageIfNeeded failed for post ${postId}:`, error);
        return imageUrl;
    }
}

/**
 * Creates a forum post through the backend so ownership, timestamps, and author fields cannot be spoofed.
 */
/**
 * Export: Main callable that validates, moderates, and writes a new forum thread.
 */
exports.createForumPost = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const postId = sanitizeText(data.postId || "", 200).trim();
    const message = typeof data.message === "string"
        ? sanitizeText(data.message, FORUM_POST_MAX_LENGTH).trim()
        : "";
    const birdImageUrl = typeof data.birdImageUrl === "string"
        ? data.birdImageUrl.trim()
        : "";
    const showLocation = data.showLocation === true;
    const spotted = data.spotted === true;
    const hunted = data.hunted === true;

    if (!postId) {
        throw new HttpsError("invalid-argument", "postId is required.");
    }
    if (!message && !birdImageUrl) {
        throw new HttpsError("invalid-argument", "Please provide a message or an image.");
    }
    if (message.length > FORUM_POST_MAX_LENGTH) {
        throw new HttpsError("invalid-argument", `Post exceeds ${FORUM_POST_MAX_LENGTH} characters.`);
    }

    await assertUserCanUseForumOrThrow(userId, "create a forum post");

    await assertNoBlockedContentOrThrow({
        userId,
        submissionType: "forum_post_create",
        fieldName: "post",
        text: message,
        threadId: postId,
    });

    const imageModeration = await evaluateForumPostImageModeration({
        userId,
        postId,
        imageUrl: birdImageUrl,
    });

    if (imageModeration.blocked) {
        const moderationState = await assertUserCanUseForumOrThrow(userId, "create a forum post");
        const penaltySummary = await db.runTransaction(async (t) => {
            const ownerSnap = await t.get(moderationState.userRef);
            const penaltySummary = await applyAutomaticPenaltyForUser({
                transaction: t,
                userRef: moderationState.userRef,
                userData: ownerSnap.exists ? (ownerSnap.data() || {}) : {},
                userId,
                source: "vision_safesearch",
                reasonCode: "nsfw_image",
                reasonText: `Forum image upload was blocked by SafeSearch. ${imageModeration.reasonText}`,
                targetType: "post",
                targetId: postId,
                evidenceText: message,
            });

            const contentEventRef = createModerationEventRef();
            const evidenceImageUrl = imageModeration.archivedUrl || birdImageUrl;
            t.set(contentEventRef, buildModerationEventPayload({
                eventId: contentEventRef.id,
                userId,
                targetType: "post",
                targetId: postId,
                actionType: "reject_content",
                reasonCode: "nsfw_image",
                reasonText: `Forum image upload was blocked by SafeSearch. ${imageModeration.reasonText}`,
                source: "vision_safesearch",
                appealable: true,
                evidenceImageUrl,
                metadata: {
                    penaltyType: penaltySummary.penaltyType,
                    linkedModerationEventIds: Array.isArray(penaltySummary.linkedModerationEventIds)
                        ? penaltySummary.linkedModerationEventIds
                        : [],
                },
                evidenceText: message,
            }));

            return penaltySummary;
        });

        const moderationNotice = buildForumImageModerationNotice(penaltySummary);

        throw new HttpsError(
            "failed-precondition",
            moderationNotice.userMessage,
            {
                message: moderationNotice.userMessage,
                userMessage: moderationNotice.userMessage,
                moderationNoticeType: moderationNotice.moderationNoticeType,
                penaltyType: penaltySummary.penaltyType || null,
                appliedRestriction: penaltySummary.appliedRestriction || null,
                appealable: true,
                reasonCode: "nsfw_image",
            }
        );
    }

    let latitude = null;
    let longitude = null;
    if (showLocation) {
        latitude = normalizeCoordinate(data.latitude, "latitude");
        longitude = normalizeCoordinate(data.longitude, "longitude");
    }

    const { username, userProfilePictureUrl } = await getForumAuthorProfileOrThrow(userId);
    const postRef = db.collection("forumThreads").doc(postId);

    try {
        await db.runTransaction(async (t) => {
            const existing = await t.get(postRef);
            if (existing.exists) {
                const existingData = existing.data() || {};
                if (existingData.userId === userId) {
                    return;
                }
                throw new HttpsError("already-exists", "That post already exists.");
            }

            await assertAndConsumeForumSubmissionCooldown(t, userId, "post");

            const postData = {
                userId,
                username,
                userProfilePictureUrl,
                message,
                birdImageUrl,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                likeCount: 0,
                commentCount: 0,
                viewCount: 0,
                likedBy: {},
                viewedBy: {},
                edited: false,
                lastEditedAt: null,
                latitude: showLocation ? latitude : null,
                longitude: showLocation ? longitude : null,
                showLocation,
                hunted,
                spotted,
                notificationSent: false,
                likeNotificationSent: false,
                lastViewedAt: null,
                heatmapExpiresAt: showLocation
                    ? admin.firestore.Timestamp.fromMillis(Date.now() + CONFIG.FORUM_HEATMAP_TTL_MS)
                    : null,
                ...buildInitialModerationFields(),
            };

            t.create(postRef, postData);
        });

        logger.info(`createForumPost: created post ${postId} for user ${userId}`);
        return { success: true, postId };
    } catch (error) {
        logger.error("createForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to create post: ${error.message}`);
    }
});

/**
 * Creates a forum comment/reply through the backend so the app cannot spoof the author.
 */
/**
 * Export: Main callable that validates, moderates, and writes a new forum comment/reply.
 */
exports.createForumComment = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();
    const commentId = sanitizeText(data.commentId || "", 200).trim();
    const text = normalizeForumText(data.text, FORUM_COMMENT_MAX_LENGTH, "Comment cannot be empty.");
    const parentCommentId = typeof data.parentCommentId === "string"
        ? sanitizeText(data.parentCommentId, 200).trim()
        : "";

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }
    if (!commentId) {
        throw new HttpsError("invalid-argument", "commentId is required.");
    }

    await assertUserCanUseForumOrThrow(userId, parentCommentId ? "reply to comments" : "comment on posts");

    await assertNoBlockedContentOrThrow({
        userId,
        submissionType: parentCommentId ? "forum_reply_create" : "forum_comment_create",
        fieldName: parentCommentId ? "reply" : "comment",
        text,
        threadId,
        commentId,
    });

    const { username, userProfilePictureUrl } = await getForumAuthorProfileOrThrow(userId);
    const threadRef = db.collection("forumThreads").doc(threadId);
    const commentRef = threadRef.collection("comments").doc(commentId);

    try {
        await db.runTransaction(async (t) => {
    const [threadSnap, existingCommentSnap] = await Promise.all([
        t.get(threadRef),
        t.get(commentRef),
    ]);

    if (!threadSnap.exists) {
        throw new HttpsError("not-found", "Post not found.");
    }
    if (!isPublicForumStatus((threadSnap.data() || {}).moderationStatus)) {
        throw new HttpsError("failed-precondition", "That post is no longer available for comments.");
    }
    if (existingCommentSnap.exists) {
        const existingCommentData = existingCommentSnap.data() || {};
        if (existingCommentData.userId === userId) {
            return;
        }
        throw new HttpsError("already-exists", "That comment already exists.");
    }

    let normalizedParentCommentId = null;
    let parentUsername = null;

    if (parentCommentId) {
        const parentRef = threadRef.collection("comments").doc(parentCommentId);
        const parentSnap = await t.get(parentRef);

        if (!parentSnap.exists) {
            throw new HttpsError("not-found", "Parent comment not found.");
        }
        if (!isPublicForumStatus((parentSnap.data() || {}).moderationStatus)) {
            throw new HttpsError("failed-precondition", "That comment is no longer available for replies.");
        }

        normalizedParentCommentId = parentCommentId;
        parentUsername = sanitizeText(parentSnap.data().username || "", 80).trim() || null;
    }

    await assertAndConsumeForumSubmissionCooldown(
        t,
        userId,
        parentCommentId ? "reply" : "comment"
    );

    t.create(commentRef, {
        threadId,
        userId,
        username,
        userProfilePictureUrl,
        text,
        timestamp: admin.firestore.FieldValue.serverTimestamp(),
        likeCount: 0,
        likedBy: {},
        parentCommentId: normalizedParentCommentId,
        parentUsername,
        edited: false,
        lastEditedAt: null,
        likeNotificationSent: false,
        ...buildInitialModerationFields(),
    });
});

        logger.info(`createForumComment: created comment ${commentId} in thread ${threadId} for user ${userId}`);
        return { success: true, commentId };
    } catch (error) {
        logger.error("createForumComment failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to create comment: ${error.message}`);
    }
});

/**
 * Updates a post's editable content fields through the backend.
 */
/**
 * Export: Callable that edits an existing forum post while rechecking edit window/moderation rules.
 */
exports.updateForumPostContent = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const postId = sanitizeText(data.postId || "", 200).trim();
    const message = normalizeForumText(data.message, FORUM_POST_MAX_LENGTH, "Post cannot be empty.");
    const spotted = data.spotted === true;
    const hunted = data.hunted === true;
    const showLocation = data.showLocation === true;

    if (!postId) {
        throw new HttpsError("invalid-argument", "postId is required.");
    }

    await assertUserCanUseForumOrThrow(userId, "edit forum posts");

    await assertNoBlockedContentOrThrow({
        userId,
        submissionType: "forum_post_update",
        fieldName: "post",
        text: message,
        threadId: postId,
    });

    const postRef = db.collection("forumThreads").doc(postId);

    try {
        await db.runTransaction(async (t) => {
            const postSnap = await t.get(postRef);
            if (!postSnap.exists) {
                throw new HttpsError("not-found", "Post not found.");
            }

            const postData = postSnap.data() || {};
            if (postData.userId !== userId) {
                throw new HttpsError("permission-denied", "You can only edit your own posts.");
            }
            if (!isPublicForumStatus(postData.moderationStatus)) {
                throw new HttpsError("failed-precondition", "That post can no longer be edited.");
            }

            assertWithinForumEditWindow(postData.timestamp, "Post");

            t.update(postRef, {
                message,
                spotted,
                hunted,
                showLocation,
                edited: true,
                lastEditedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        });

        logger.info(`updateForumPostContent: updated post ${postId} for user ${userId}`);
        return { success: true, postId };
    } catch (error) {
        logger.error("updateForumPostContent failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to update post: ${error.message}`);
    }
});

/**
 * Updates a comment's text through the backend.
 */
/**
 * Export: Callable that edits an existing forum comment while rechecking edit window/moderation rules.
 */
exports.updateForumCommentContent = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();
    const commentId = sanitizeText(data.commentId || "", 200).trim();
    const text = normalizeForumText(data.text, FORUM_COMMENT_MAX_LENGTH, "Comment cannot be empty.");

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }
    if (!commentId) {
        throw new HttpsError("invalid-argument", "commentId is required.");
    }

    await assertUserCanUseForumOrThrow(userId, "edit comments");

    await assertNoBlockedContentOrThrow({
        userId,
        submissionType: "forum_comment_update",
        fieldName: "comment",
        text,
        threadId,
        commentId,
    });

    const commentRef = db.collection("forumThreads").doc(threadId).collection("comments").doc(commentId);

    try {
        await db.runTransaction(async (t) => {
            const commentSnap = await t.get(commentRef);
            if (!commentSnap.exists) {
                throw new HttpsError("not-found", "Comment not found.");
            }

            const commentData = commentSnap.data() || {};
            if (commentData.userId !== userId) {
                throw new HttpsError("permission-denied", "You can only edit your own comments.");
            }
            if (!isPublicForumStatus(commentData.moderationStatus)) {
                throw new HttpsError("failed-precondition", "That comment can no longer be edited.");
            }

            assertWithinForumEditWindow(commentData.timestamp, "Comment");

            t.update(commentRef, {
                text,
                edited: true,
                lastEditedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
        });

        logger.info(`updateForumCommentContent: updated comment ${commentId} in thread ${threadId} for user ${userId}`);
        return { success: true, commentId };
    } catch (error) {
        logger.error("updateForumCommentContent failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to update comment: ${error.message}`);
    }
});


/**
 * Deletes a forum post through the backend so ownership and archival cannot be bypassed.
 */
/**
 * Export: Callable that deletes/soft-deletes a forum thread and updates related counters/state.
 */
exports.deleteForumPost = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const postId = sanitizeText(data.postId || "", 200).trim();
    if (!postId) {
        throw new HttpsError("invalid-argument", "postId is required.");
    }

    const postRef = db.collection("forumThreads").doc(postId);
    const eventRef = db.collection("processedEvents").doc(`DELETE_POST_${postId}`);

    try {
        const [processedSnap, postSnap] = await Promise.all([eventRef.get(), postRef.get()]);
        if (processedSnap.exists) {
            return processedSnap.data()?.result || { success: true, postId, deletedCommentCount: 0, alreadyProcessed: true };
        }
        if (!postSnap.exists) {
            throw new HttpsError("not-found", "Post not found.");
        }

        const postData = postSnap.data() || {};
        if (postData.userId !== userId) {
            throw new HttpsError("permission-denied", "You can only delete your own posts.");
        }

        const commentsSnap = await postRef.collection("comments").get();
        const archivedImageUrl = await archiveForumPostImageIfNeeded(
            userId,
            postId,
            typeof postData.birdImageUrl === "string" ? postData.birdImageUrl : ""
        );

        const operations = [];
        for (const commentDoc of commentsSnap.docs) {
            const archiveRef = db.collection("deletedforum_backlog").doc(`POSTDEL_COMMENT_${postId}_${commentDoc.id}`);
            const commentData = commentDoc.data();
            operations.push((batch) => batch.set(archiveRef, {
                type: "comment_archived_with_post",
                originalId: commentDoc.id,
                postId,
                data: commentData,
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            }));
            operations.push((batch) => batch.delete(commentDoc.ref));
        }

        operations.push((batch) => batch.set(db.collection("deletedforum_backlog").doc(`POSTDEL_${postId}`), {
            type: "post",
            originalId: postId,
            data: {
                ...postData,
                birdImageUrl: archivedImageUrl,
            },
            deletedBy: userId,
            deletedAt: admin.firestore.FieldValue.serverTimestamp(),
        }));
        operations.push((batch) => batch.delete(postRef));

        await commitBatchOperations(operations);

        const result = { success: true, postId, deletedCommentCount: commentsSnap.size };
        await eventRef.set({ result, processedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });

        logger.info(`deleteForumPost: deleted post ${postId} for user ${userId}`);
        return result;
    } catch (error) {
        logger.error("deleteForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to delete post: ${error.message}`);
    }
});

/**
 * Deletes a forum comment or reply through the backend so ownership and archival cannot be bypassed.
 */
/**
 * Export: Callable that deletes/soft-deletes a forum comment and updates related counters/state.
 */
exports.deleteForumComment = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();
    const commentId = sanitizeText(data.commentId || "", 200).trim();

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }
    if (!commentId) {
        throw new HttpsError("invalid-argument", "commentId is required.");
    }

    const threadRef = db.collection("forumThreads").doc(threadId);
    const commentRef = threadRef.collection("comments").doc(commentId);
    const eventRef = db.collection("processedEvents").doc(`DELETE_COMMENT_${threadId}_${commentId}`);

    try {
        const [processedSnap, commentSnap] = await Promise.all([eventRef.get(), commentRef.get()]);
        if (processedSnap.exists) {
            return processedSnap.data()?.result || { success: true, commentId, deletedReplyCount: 0, alreadyProcessed: true };
        }
        if (!commentSnap.exists) {
            throw new HttpsError("not-found", "Comment not found.");
        }

        const commentData = commentSnap.data() || {};
        if (commentData.userId !== userId) {
            throw new HttpsError("permission-denied", "You can only delete your own comments.");
        }

        const isReply = !!commentData.parentCommentId;
        const operations = [];
        let deletedReplyCount = 0;

        if (!isReply) {
            const repliesSnap = await threadRef.collection("comments")
                .where("parentCommentId", "==", commentId)
                .get();

            for (const replyDoc of repliesSnap.docs) {
                operations.push((batch) => batch.set(db.collection("deletedforum_backlog").doc(`COMMENTDEL_REPLY_${threadId}_${replyDoc.id}`), {
                    type: "comment_reply",
                    originalId: replyDoc.id,
                    data: replyDoc.data(),
                    deletedBy: userId,
                    deletedAt: admin.firestore.FieldValue.serverTimestamp(),
                }));
                operations.push((batch) => batch.delete(replyDoc.ref));
                deletedReplyCount += 1;
            }

            operations.push((batch) => batch.set(db.collection("deletedforum_backlog").doc(`COMMENTDEL_${threadId}_${commentId}`), {
                type: "comment",
                originalId: commentId,
                data: commentData,
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            }));
            operations.push((batch) => batch.delete(commentRef));
        } else {
            operations.push((batch) => batch.set(db.collection("deletedforum_backlog").doc(`REPLYDEL_${threadId}_${commentId}`), {
                type: "reply",
                originalId: commentId,
                data: commentData,
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            }));
            operations.push((batch) => batch.delete(commentRef));
        }

        await commitBatchOperations(operations);

        const result = { success: true, commentId, deletedReplyCount };
        await eventRef.set({ result, processedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });

        logger.info(`deleteForumComment: deleted comment ${commentId} in thread ${threadId} for user ${userId}`);
        return result;
    } catch (error) {
        logger.error("deleteForumComment failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to delete comment: ${error.message}`);
    }
});


/**
 * Saves a forum post for the current user so it can be viewed later from the profile screen.
 * The saved post document ID matches the thread ID so duplicate entries cannot be created.
 */
/**
 * Export: Callable that saves a forum thread to the user’s saved-posts collection.
 */
exports.saveForumPost = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }

    const threadRef = db.collection("forumThreads").doc(threadId);
    const savedRef = db.collection("users").doc(userId).collection("savedPosts").doc(threadId);

    try {
        await db.runTransaction(async (transaction) => {
            const [threadSnap, savedSnap] = await Promise.all([
                transaction.get(threadRef),
                transaction.get(savedRef),
            ]);

            if (!threadSnap.exists) {
                throw new HttpsError("not-found", "Post not found.");
            }

            if (!isPublicForumStatus((threadSnap.data() || {}).moderationStatus)) {
                throw new HttpsError("failed-precondition", "That post is no longer available to save.");
            }

            if (!savedSnap.exists) {
                transaction.set(savedRef, {
                    threadId,
                    savedAt: admin.firestore.FieldValue.serverTimestamp(),
                    timestamp: admin.firestore.FieldValue.serverTimestamp(),
                });
            }
        });

        logger.info(`saveForumPost: saved thread ${threadId} for user ${userId}`);
        return { success: true, threadId };
    } catch (error) {
        logger.error("saveForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to save post: ${error.message}`);
    }
});

/**
 * Returns whether the current user has saved a given forum post.
 * This lets the app resolve Save/Unsave labels without depending on client Firestore read rules.
 */
/**
 * Export: Callable that returns whether the current user has already saved a given forum post.
 */
exports.getForumPostSaveState = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }

    const savedRef = db.collection("users").doc(userId).collection("savedPosts").doc(threadId);

    try {
        const savedSnap = await savedRef.get();
        return {
            success: true,
            threadId,
            saved: savedSnap.exists,
        };
    } catch (error) {
        logger.error("getForumPostSaveState failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to read saved post state: ${error.message}`);
    }
});

/**
 * Removes a saved forum post entry for the current user.
 */
/**
 * Export: Callable that removes a forum thread from the user’s saved-posts collection.
 */
exports.unsaveForumPost = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const threadId = sanitizeText(data.threadId || "", 200).trim();

    if (!threadId) {
        throw new HttpsError("invalid-argument", "threadId is required.");
    }

    const savedRef = db.collection("users").doc(userId).collection("savedPosts").doc(threadId);

    try {
        await savedRef.delete();
        logger.info(`unsaveForumPost: removed saved thread ${threadId} for user ${userId}`);
        return { success: true, threadId };
    } catch (error) {
        logger.error("unsaveForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to unsave post: ${error.message}`);
    }
});

