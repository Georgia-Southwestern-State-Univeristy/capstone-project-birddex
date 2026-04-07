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
// moderatePfpImage (OpenAI Vision)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * It also calls an external API, which is where third-party bird/AI/network data enters the
 * backend flow.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that moderates a profile picture and stores the moderation outcome.
 */
exports.moderatePfpImage = secureOnCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 30 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const { imageBase64 } = request.data;
    if (!imageBase64 || typeof imageBase64 !== "string") {
        throw new HttpsError("invalid-argument", "Image data (Base64) is required.");
    }

    try {
        const moderationPrompt = `Analyze the provided image for inappropriate content. Inappropriate content includes, but is not limited to, nudity, sexually suggestive material, hate symbols, graphic violence, illegal activities, or promotion of self-harm.

Respond STRICTLY in JSON format with two keys:
1. "isAppropriate": true if the image is appropriate, false otherwise.
2. "moderationReason": a brief, clear reason if "isAppropriate" is false (e.g., "Contains nudity", "Hate symbol detected", "Graphic violence"), or "N/A" if appropriate.`;

        const aiResponse = await axios.post(
            "https://api.openai.com/v1/chat/completions",
            {
                model: "gpt-4o",
                messages: [{
                    role: "user",
                    content: [
                        { type: "text", text: moderationPrompt },
                        {
                          type: "image_url",
                          image_url: {
                            url: `data:image/jpeg;base64,${imageBase64}`,
                            detail: "high"
                          }
                        }
                    ]
                }],
                response_format: { type: "json_object" },
                max_tokens: 200,
                temperature: 0.0
            },
            {
                headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
                timeout: 25000
            }
        );

        const content = aiResponse.data.choices[0].message?.content;
        if (!content) {
            logger.warn("moderatePfpImage: OpenAI returned empty content, defaulting to appropriate.");
            return { isAppropriate: true, moderationReason: "N/A" };
        }

        const moderationResult = JSON.parse(content);
        if (!moderationResult) {
            logger.warn("moderatePfpImage: OpenAI returned null result, defaulting to appropriate.");
            return { isAppropriate: true, moderationReason: "N/A" };
        }

        if (typeof moderationResult.isAppropriate !== "boolean" || typeof moderationResult.moderationReason !== "string") {
            logger.warn("moderatePfpImage: OpenAI moderation response malformed, defaulting to appropriate.");
            return { isAppropriate: true, moderationReason: "N/A" };
        }
        return moderationResult;
    } catch (error) {
        logger.error("Error during PFP image moderation:", error);
        throw new HttpsError("internal", `Failed to moderate image content: ${error.message}`);
    }
});

// ======================================================
// submitReport — server-side moderation intake + aggregation
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Export: Callable that lets users report posts/comments/profiles and creates a pending moderator report
 * record.
 */
exports.submitReport = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const reporterId = request.auth.uid;
    const data = request.data || {};
    const targetId = sanitizeText(data.targetId || "", 200).trim();
    const targetType = sanitizeText(data.targetType || "", 50).trim();
    const rawReason = typeof data.reason === "string" ? data.reason : "";
    const sourceContext = normalizeReportSourceContext(data.sourceContext || "");
    const threadId = sanitizeText(data.threadId || "", 200).trim();

    if (!targetId || !targetType || !rawReason.trim()) {
        throw new HttpsError("invalid-argument", "targetId, targetType, and reason are required.");
    }

    if ((targetType === "comment" || targetType === "reply") && !threadId) {
        throw new HttpsError("invalid-argument", "threadId is required for comment and reply reports.");
    }

    const reasonCode = normalizeReportReason(rawReason);
    const reasonText = sanitizeText(rawReason, 500);
    const target = await resolveModerationTargetOrThrow(targetType, targetId, threadId);

    if (!target.ownerUserId) {
        throw new HttpsError("failed-precondition", "The target owner could not be determined.");
    }
    if (target.ownerUserId === reporterId) {
        throw new HttpsError("permission-denied", "You cannot report your own content.");
    }

    if (!isPublicForumStatus(target.data.moderationStatus)) {
        throw new HttpsError("failed-precondition", "That content is no longer available for reporting.");
    }

    const reportId = `${reporterId}_${target.canonicalType}_${target.targetId}`;
    const reportRef = db.collection("reports").doc(reportId);
    const ownerRef = db.collection("users").doc(target.ownerUserId);

    try {
        const result = await db.runTransaction(async (t) => {
            const [existingReportSnap, freshTargetSnap, ownerSnap] = await Promise.all([
                t.get(reportRef),
                t.get(target.ref),
                t.get(ownerRef),
            ]);

            if (existingReportSnap.exists) {
                return {
                    contentDecision: null,
                    uniqueReporterCount: existingReportSnap.get("cachedUniqueReporterCount") || null,
                    penaltySummary: null,
                    alreadyReported: true,
                };
            }

            if (!freshTargetSnap.exists) {
                throw new HttpsError("not-found", "That content no longer exists.");
            }

            await assertHourlyRateLimitOrThrow(t, reporterId, REPORT_RATE_LIMIT_KEY, MAX_REPORTS_PER_HOUR, "reports");

            const freshTargetData = freshTargetSnap.data() || {};
            if (!isPublicForumStatus(freshTargetData.moderationStatus)) {
                throw new HttpsError("failed-precondition", "That content is no longer available for reporting.");
            }

            if (!ownerSnap.exists) {
                throw new HttpsError("failed-precondition", "The target owner profile could not be found.");
            }

            const currentReportCount = typeof freshTargetData.reportCount === "number" ? freshTargetData.reportCount : 0;
            const currentUniqueReporterCount = typeof freshTargetData.uniqueReporterCount === "number"
                ? freshTargetData.uniqueReporterCount
                : 0;
            const nextUniqueReporterCount = currentUniqueReporterCount + 1;
            const currentStatus = normalizeModerationStatus(freshTargetData.moderationStatus);
            const nowTimestamp = admin.firestore.FieldValue.serverTimestamp();
            const targetTextSnapshot = extractModerationTargetText(freshTargetData) || extractModerationTargetText(target.data) || null;

            const targetUpdates = {
                reportCount: currentReportCount + 1,
                uniqueReporterCount: nextUniqueReporterCount,
                lastReportedAt: nowTimestamp,
                [`reportReasonCounts.${reasonCode}`]: admin.firestore.FieldValue.increment(1),
            };

            let contentDecision = null;
            if (nextUniqueReporterCount >= REPORT_HIDE_THRESHOLD && currentStatus !== MODERATION_STATUS_HIDDEN && currentStatus !== MODERATION_STATUS_REMOVED) {
                contentDecision = MODERATION_STATUS_HIDDEN;
            } else if (nextUniqueReporterCount >= REPORT_UNDER_REVIEW_THRESHOLD && currentStatus === MODERATION_STATUS_VISIBLE) {
                contentDecision = MODERATION_STATUS_UNDER_REVIEW;
            }

            if (contentDecision) {
                Object.assign(
                    targetUpdates,
                    buildContentModerationUpdate(
                        contentDecision,
                        "report_threshold",
                        contentDecision === MODERATION_STATUS_HIDDEN
                            ? "Automatically hidden after repeated unique reports."
                            : "Placed under review after repeated unique reports.",
                        nowTimestamp
                    )
                );

                if (contentDecision === MODERATION_STATUS_HIDDEN && target.canonicalType === "post") {
                    // Keep the stored coordinates so the pin can reappear if moderationStatus returns to visible.
                    // NearbyHeatmapActivity should skip hidden/removed content based on moderationStatus.
                }
            }

            t.set(reportRef, {
                reportId,
                reporterId,
                targetId: target.targetId,
                targetType: target.rawType || target.canonicalType,
                threadId: target.threadId || threadId || null,
                targetOwnerId: target.ownerUserId,
                sourceContext: sourceContext || null,
                reasonCode,
                reasonText,
                timestamp: nowTimestamp,
                status: "pending",
                targetTextSnapshot,
            });

            t.set(target.ref, targetUpdates, { merge: true });

            let penaltySummary = null;

            if (contentDecision === MODERATION_STATUS_HIDDEN) {
                const contentEventRef = createModerationEventRef();
                t.set(contentEventRef, buildModerationEventPayload({
                    eventId: contentEventRef.id,
                    userId: target.ownerUserId,
                    targetType: target.rawType || target.canonicalType,
                    targetId: target.targetId,
                    actionType: "hide_content",
                    reasonCode: "reported_content",
                    reasonText: "Content was automatically hidden after repeated unique reports.",
                    source: "report_threshold",
                    appealable: true,
                    metadata: {
                        uniqueReporterCount: nextUniqueReporterCount,
                        sourceContext: sourceContext || null,
                        threadId: target.threadId || threadId || null,
                    },
                    evidenceText: targetTextSnapshot,
                }));

                penaltySummary = await applyAutomaticPenaltyForUser({
                    transaction: t,
                    userRef: ownerRef,
                    userData: ownerSnap.data() || {},
                    userId: target.ownerUserId,
                    source: "report_threshold",
                    reasonCode: "reported_content",
                    reasonText: "Repeated unique reports caused content to be hidden automatically.",
                    targetType: target.rawType || target.canonicalType,
                    targetId: target.targetId,
                    evidenceText: targetTextSnapshot,
                });
            }

            return {
                contentDecision,
                uniqueReporterCount: nextUniqueReporterCount,
                penaltySummary,
            };
        });

        return {
            success: true,
            alreadyReported: result.alreadyReported === true,
            moderationStatus: result.contentDecision || null,
            uniqueReporterCount: result.uniqueReporterCount || null,
            penaltyType: result.penaltySummary?.penaltyType || null,
        };
    } catch (error) {
    logger.error("submitReport transaction failed:", {
        message: error?.message || null,
        code: error?.code || null,
        stack: error?.stack || null,
        targetId,
        targetType,
        reporterId,
    });

    if (error instanceof HttpsError) throw error;

    throw new HttpsError(
        "internal",
        error?.message || "Failed to submit report.");
}
});

// ======================================================
// getMyModerationState — user-facing moderation overview for appeals/restrictions
// ======================================================
exports.getMyModerationState = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const userId = request.auth.uid;
    const userSnap = await db.collection("users").doc(userId).get();
    const userData = userSnap.exists ? (userSnap.data() || {}) : {};

    const [eventsSnap, appealsSnap] = await Promise.all([
        db.collection("moderationEvents").where("userId", "==", userId).limit(25).get(),
        db.collection("moderationAppeals").where("userId", "==", userId).limit(25).get(),
    ]);

    const events = eventsSnap.docs.map((doc) => ({ id: doc.id, ...doc.data() })); // evidenceImageUrl is forwarded automatically via the document spread (moderation-image-evidence)
    events.sort((a, b) => (timestampToMillis(b.createdAt) || 0) - (timestampToMillis(a.createdAt) || 0));

    const appeals = appealsSnap.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
    appeals.sort((a, b) => (timestampToMillis(b.createdAt) || 0) - (timestampToMillis(a.createdAt) || 0));

    return {
        success: true,
        warningCount: typeof userData.warningCount === "number" ? userData.warningCount : 0,
        strikeCount: typeof userData.strikeCount === "number" ? userData.strikeCount : 0,
        permanentForumBan: userData.permanentForumBan === true,
        forumSuspendedUntil: userData.forumSuspendedUntil || null,
        events,
        appeals,
    };
});

// ======================================================
// submitModerationAppeal — one appeal per moderation event
// ======================================================
exports.submitModerationAppeal = secureOnCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const moderationEventId = sanitizeText(data.moderationEventId || "", 200).trim();
    const appealText = sanitizeText(data.appealText || "", 1000).trim();

    if (!moderationEventId) {
        throw new HttpsError("invalid-argument", "moderationEventId is required.");
    }
    if (!appealText) {
        throw new HttpsError("invalid-argument", "Please explain why you are appealing this decision.");
    }

    const eventRef = db.collection("moderationEvents").doc(moderationEventId);
    const appealRef = createModerationAppealRef(userId, moderationEventId);

    try {
        await db.runTransaction(async (t) => {
            const [eventSnap, existingAppealSnap] = await Promise.all([
                t.get(eventRef),
                t.get(appealRef),
            ]);

            if (!eventSnap.exists) {
                throw new HttpsError("not-found", "Moderation event not found.");
            }
            if (existingAppealSnap.exists) {
                throw new HttpsError("already-exists", "You already submitted an appeal for this decision.");
            }

            const eventData = eventSnap.data() || {};
            if (eventData.userId !== userId) {
                throw new HttpsError("permission-denied", "You can only appeal decisions made on your own account or content.");
            }
            if (eventData.appealable !== true) {
                throw new HttpsError("failed-precondition", "That moderation decision cannot be appealed.");
            }
            if (eventData.status !== "active") {
                throw new HttpsError("failed-precondition", "That moderation decision is no longer active.");
            }

            const createdAtMs = timestampToMillis(eventData.createdAt);
            if (createdAtMs != null && (Date.now() - createdAtMs) > MODERATION_APPEAL_WINDOW_MS) {
                throw new HttpsError("deadline-exceeded", "The appeal window for that decision has expired.");
            }

            t.set(appealRef, {
                appealId: appealRef.id,
                userId,
                moderationEventId,
                targetType: eventData.targetType || "user",
                targetId: eventData.targetId || null,
                appealText,
                status: "pending",
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewedAt: null,
                reviewedBy: null,
                decisionNote: null,
                snapshotActionType: eventData.actionType || null,
                snapshotReasonCode: eventData.reasonCode || null,
                snapshotReasonText: eventData.reasonText || null,
                snapshotEvidenceImageUrl: eventData.evidenceImageUrl || null,
                snapshotEvidenceText: eventData.evidenceText || null,
                snapshotSourceContext: normalizeReportSourceContext((eventData.metadata && eventData.metadata.sourceContext) || "") || null,
                snapshotThreadId: sanitizeText((eventData.metadata && eventData.metadata.threadId) || "", 200).trim() || null,
            });
        });

        return {
            success: true,
            moderationEventId,
        };
    } catch (error) {
        if (error instanceof HttpsError) throw error;
        logger.error("submitModerationAppeal failed:", error);
        throw new HttpsError("internal", "Failed to submit appeal.");
    }
});

// ======================================================
// getPendingModerationAppeals — moderator queue for pending appeals
// ======================================================
exports.getPendingModerationAppeals = secureOnCall(async (request) => {
    getModerationReviewerIdentityOrThrow(request);

    const appealsSnap = await db.collection("moderationAppeals")
        .where("status", "==", "pending")
        .limit(100)
        .get();

    const appeals = await Promise.all(appealsSnap.docs.map(async (doc) => {
        const data = doc.data() || {};
        const moderationEventId = sanitizeText(data.moderationEventId || "", 200).trim();

        let linkedEventData = {};
        if (moderationEventId) {
            try {
                const linkedEventSnap = await db.collection("moderationEvents").doc(moderationEventId).get();
                if (linkedEventSnap.exists) {
                    linkedEventData = linkedEventSnap.data() || {};
                }
            } catch (error) {
                logger.warn("getPendingModerationAppeals: failed to fetch linked moderation event", error);
            }
        }

        return {
            id: doc.id,
            ...data,
            snapshotReasonText: sanitizeText(
                data.snapshotReasonText || linkedEventData.reasonText || "",
                500
            ) || null,
            snapshotEvidenceImageUrl: typeof data.snapshotEvidenceImageUrl === "string" && data.snapshotEvidenceImageUrl.trim()
                ? data.snapshotEvidenceImageUrl.trim()
                : (typeof linkedEventData.evidenceImageUrl === "string" && linkedEventData.evidenceImageUrl.trim()
                    ? linkedEventData.evidenceImageUrl.trim()
                    : null),
            snapshotEvidenceText: sanitizeText(
                data.snapshotEvidenceText || linkedEventData.evidenceText || "",
                1000
            ) || null,
            snapshotSourceContext: normalizeReportSourceContext(
                data.snapshotSourceContext
                    || (linkedEventData.metadata && linkedEventData.metadata.sourceContext)
                    || ""
            ) || null,
        };
    }));
    appeals.sort((a, b) => (timestampToMillis(b.createdAt) || 0) - (timestampToMillis(a.createdAt) || 0));

    return {
        success: true,
        appeals,
    };
});

// ======================================================
// getPendingModerationReports — moderator queue for pending user reports
// ======================================================
exports.getPendingModerationReports = secureOnCall(async (request) => {
    getModerationReviewerIdentityOrThrow(request);

    const reportsSnap = await db.collection("reports")
        .where("status", "==", "pending")
        .limit(100)
        .get();

    const reports = await Promise.all(reportsSnap.docs.map(async (doc) => {
        const data = doc.data() || {};

        let targetExists = false;
        let targetPreview = sanitizeText(data.targetTextSnapshot || "", 1000).trim() || null;
        let targetModerationStatus = null;
        let currentUniqueReporterCount = null;
        let currentReportCount = null;
        let resolvedThreadId = sanitizeText(data.threadId || "", 200).trim() || null;
        let resolvedTargetOwnerId = sanitizeText(data.targetOwnerId || "", 200).trim() || null;
        let resolvedTargetType = sanitizeText(data.targetType || "", 50).trim().toLowerCase() || null;
        let resolvedSourceContext = normalizeReportSourceContext(data.sourceContext || "") || null;

        try {
            const target = await resolveModerationTargetOrThrow(
                data.targetType || "",
                data.targetId || "",
                data.threadId || ""
            );
            const targetData = target.data || {};

            targetExists = true;
            resolvedThreadId = resolvedThreadId || target.threadId || null;
            resolvedTargetOwnerId = resolvedTargetOwnerId || target.ownerUserId || null;
            resolvedTargetType = target.rawType || target.canonicalType || resolvedTargetType;
            targetPreview = sanitizeText(targetPreview || extractModerationTargetText(targetData) || "", 1000) || null;
            targetModerationStatus = normalizeModerationStatus(targetData.moderationStatus);
            currentUniqueReporterCount = typeof targetData.uniqueReporterCount === "number"
                ? targetData.uniqueReporterCount
                : null;
            currentReportCount = typeof targetData.reportCount === "number"
                ? targetData.reportCount
                : null;
        } catch (error) {
            if (!(error instanceof HttpsError && error.code === "not-found")) {
                logger.warn("getPendingModerationReports resolve target failed:", error);
            }
        }

        let targetOwnerUsername = null;
        if (resolvedTargetOwnerId) {
            try {
                const ownerSnap = await db.collection("users").doc(resolvedTargetOwnerId).get();
                targetOwnerUsername = sanitizeText((ownerSnap.data() || {}).username || "", 80) || null;
            } catch (error) {
                logger.warn("getPendingModerationReports resolve owner failed:", error);
            }
        }

        let evidenceImageUrl = null;
        if (data.targetId) {
            try {
                const evSnap = await db.collection("moderationEvents")
                    .where("targetId", "==", data.targetId)
                    .where("actionType", "==", "reject_content")
                    .limit(1)
                    .get();
                if (!evSnap.empty) {
                    evidenceImageUrl = evSnap.docs[0].data().evidenceImageUrl || null;
                }
            } catch (e) {
                logger.warn("getPendingModerationReports: failed to fetch evidenceImageUrl", e);
            }
        }

        return {
            id: doc.id,
            ...data,
            targetType: resolvedTargetType || data.targetType || null,
            sourceContext: resolvedSourceContext,
            threadId: resolvedThreadId,
            targetOwnerId: resolvedTargetOwnerId,
            targetOwnerUsername,
            targetExists,
            targetPreview,
            targetModerationStatus,
            currentUniqueReporterCount,
            currentReportCount,
            evidenceImageUrl,
            targetTextSnapshot: targetPreview,
        };
    }));

    reports.sort((a, b) => (timestampToMillis(b.timestamp) || 0) - (timestampToMillis(a.timestamp) || 0));

    return {
        success: true,
        reports,
    };
});

// ======================================================
// reviewModerationAppeal — moderator approval/denial with reversal handling
// ======================================================
exports.reviewModerationAppeal = secureOnCall(async (request) => {
    const { reviewedBy, moderatorSourceMeta } = getModerationReviewerIdentityOrThrow(request);

    const data = request.data || {};
    const appealId = sanitizeText(data.appealId || "", 300).trim();
    const decision = sanitizeText(data.decision || "", 50).trim().toLowerCase();
    const decisionNote = sanitizeText(data.decisionNote || "", 1000).trim();

    if (!appealId) {
        throw new HttpsError("invalid-argument", "appealId is required.");
    }
    if (!["approved", "denied"].includes(decision)) {
        throw new HttpsError("invalid-argument", "decision must be approved or denied.");
    }

    const appealRef = db.collection("moderationAppeals").doc(appealId);

    try {
        await db.runTransaction(async (t) => {
            const appealSnap = await t.get(appealRef);
            if (!appealSnap.exists) {
                throw new HttpsError("not-found", "Appeal not found.");
            }

            const appealData = appealSnap.data() || {};
            if (appealData.status !== "pending") {
                throw new HttpsError("failed-precondition", "That appeal has already been reviewed.");
            }

            const moderationEventId = sanitizeText(appealData.moderationEventId || "", 200).trim();
            if (!moderationEventId) {
                throw new HttpsError("failed-precondition", "Appeal is missing a moderation event reference.");
            }

            const eventRef = db.collection("moderationEvents").doc(moderationEventId);
            const eventSnap = await t.get(eventRef);
            if (!eventSnap.exists) {
                throw new HttpsError("not-found", "Moderation event not found.");
            }

            const eventData = eventSnap.data() || {};
            const userId = sanitizeText(eventData.userId || appealData.userId || "", 200).trim();
            if (!userId) {
                throw new HttpsError("failed-precondition", "Moderation event is missing a user reference.");
            }

            const userRef = db.collection("users").doc(userId);
            const [userSnap, userEventsSnap, target] = await Promise.all([
                t.get(userRef),
                t.get(db.collection("moderationEvents").where("userId", "==", userId).limit(200)),
                resolveModerationTargetForTransaction(
                    t,
                    appealData.targetType || eventData.targetType || "user",
                    appealData.targetId || eventData.targetId || null,
                    appealData.snapshotThreadId
                        || (eventData.metadata && eventData.metadata.threadId)
                        || null
                ),
            ]);

            t.set(appealRef, {
                status: decision,
                reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewedBy,
                decisionNote: decisionNote || null,
            }, { merge: true });

            if (decision !== "approved") {
                return;
            }

            t.set(eventRef, {
                status: "reversed",
                reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewedBy,
                reversalReason: decisionNote || "Appeal approved.",
            }, { merge: true });

            const linkedModerationEventIds = eventData.actionType === "reject_content"
                ? Array.from(new Set(
                    (Array.isArray(eventData.metadata?.linkedModerationEventIds)
                        ? eventData.metadata.linkedModerationEventIds
                        : [])
                        .map((value) => sanitizeText(value || "", 200).trim())
                        .filter((value) => value && value !== moderationEventId)
                ))
                : [];

            for (const linkedEventSnap of userEventsSnap.docs) {
                if (!linkedModerationEventIds.includes(linkedEventSnap.id)) continue;
                const linkedEventData = linkedEventSnap.data() || {};
                if (!isModerationEventCurrentlyActive(linkedEventData)) continue;

                t.set(linkedEventSnap.ref, {
                    status: "reversed",
                    reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
                    reviewedBy,
                    reversalReason: decisionNote || "Appeal approved for linked rejected-content penalty.",
                }, { merge: true });
            }

            const reversedEventIds = new Set([moderationEventId, ...linkedModerationEventIds]);
            const nowMs = Date.now();
            const remainingActiveEvents = userEventsSnap.docs
                .filter((doc) => !reversedEventIds.has(doc.id))
                .map((doc) => ({ id: doc.id, ...doc.data() }))
                .filter((doc) => isModerationEventCurrentlyActive(doc, nowMs));

            const nextWarningCount = remainingActiveEvents.filter((doc) => doc.actionType === "warning").length;
            const nextStrikeCount = remainingActiveEvents.filter((doc) => doc.actionType === "strike").length;
            const hasActiveForumBan = remainingActiveEvents.some((doc) => doc.actionType === "forum_ban");

            let nextForumSuspendedUntil = null;
            for (const event of remainingActiveEvents) {
                if (event.actionType !== "forum_suspension") continue;
                const expiresAtMs = timestampToMillis(event.expiresAt);
                if (expiresAtMs == null || expiresAtMs <= nowMs) continue;
                if (!nextForumSuspendedUntil || expiresAtMs > nextForumSuspendedUntil.toMillis()) {
                    nextForumSuspendedUntil = admin.firestore.Timestamp.fromMillis(expiresAtMs);
                }
            }

            const userUpdate = {
                warningCount: nextWarningCount,
                strikeCount: nextStrikeCount,
                permanentForumBan: hasActiveForumBan,
                forumSuspendedUntil: hasActiveForumBan ? null : nextForumSuspendedUntil,
            };

            if (userSnap.exists) {
                t.set(userRef, userUpdate, { merge: true });
            }

            if (["hide_content", "remove_content", "reject_content"].includes(eventData.actionType) && target) {
                t.set(target.ref, {
                    ...buildContentModerationUpdate(
                        MODERATION_STATUS_VISIBLE,
                        buildModeratorTaggedSource("appeal_approved", moderatorSourceMeta),
                        decisionNote || "Appeal approved.",
                        admin.firestore.FieldValue.serverTimestamp()
                    ),
                    hiddenAt: null,
                }, { merge: true });
            }
        });

        return {
            success: true,
            appealId,
            decision,
        };
    } catch (error) {
        if (error instanceof HttpsError) throw error;
        logger.error("reviewModerationAppeal failed:", error);
        throw new HttpsError("internal", "Failed to review appeal.");
    }
});

// ======================================================
// reviewPendingReport — moderator review path for pending user reports
// ======================================================
exports.reviewPendingReport = secureOnCall(async (request) => {
    const { reviewedBy, moderatorSourceMeta } = getModerationReviewerIdentityOrThrow(request);

    const data = request.data || {};
    const reportId = sanitizeText(data.reportId || "", 300).trim();
    const userAction = sanitizeText(data.userAction || "none", 50).trim().toLowerCase();
    const contentAction = sanitizeText(data.contentAction || "keep", 50).trim().toLowerCase();
    const decisionNote = sanitizeText(data.decisionNote || "", 1000).trim();

    const allowedUserActions = ["none", "warning", "strike", "suspend_24h", "suspend_7d", "forum_ban"];
    const allowedContentActions = ["keep", "hide_content", "remove_content"];

    if (!reportId) {
        throw new HttpsError("invalid-argument", "reportId is required.");
    }
    if (!allowedUserActions.includes(userAction)) {
        throw new HttpsError("invalid-argument", "Unsupported userAction.");
    }
    if (!allowedContentActions.includes(contentAction)) {
        throw new HttpsError("invalid-argument", "Unsupported contentAction.");
    }

    const reportRef = db.collection("reports").doc(reportId);

    try {
        await db.runTransaction(async (t) => {
            const reportSnap = await t.get(reportRef);
            if (!reportSnap.exists) {
                throw new HttpsError("not-found", "Report not found.");
            }

            const reportData = reportSnap.data() || {};
            if (sanitizeText(reportData.status || "pending", 50).trim().toLowerCase() !== "pending") {
                throw new HttpsError("failed-precondition", "That report has already been reviewed.");
            }

            const targetType = sanitizeText(reportData.targetType || "", 50).trim().toLowerCase();
            const targetId = sanitizeText(reportData.targetId || "", 200).trim();
            let target = null;

            if (contentAction !== "keep" || !sanitizeText(reportData.targetOwnerId || "", 200).trim()) {
                target = await resolveModerationTargetForTransaction(
                    t,
                    targetType,
                    targetId,
                    sanitizeText(reportData.threadId || "", 200).trim() || null
                );
            }

            if (contentAction !== "keep" && !target) {
                throw new HttpsError("failed-precondition", "The reported content no longer exists, so a content action cannot be applied.");
            }

            const affectedUserId = sanitizeText(
                reportData.targetOwnerId || (target && target.snap && (target.snap.data() || {}).userId) || "",
                200
            ).trim();

            if (userAction !== "none" && !affectedUserId) {
                throw new HttpsError("failed-precondition", "The reported content is missing an owner reference.");
            }

            let userRef = null;
            let userSnap = null;
            let userData = {};
            if (userAction !== "none") {
                userRef = db.collection("users").doc(affectedUserId);
                userSnap = await t.get(userRef);
                if (!userSnap.exists) {
                    throw new HttpsError("failed-precondition", "The reported user profile could not be found.");
                }
                userData = userSnap.data() || {};
            }

            const createdModerationEventIds = [];
            const nowMs = Date.now();
            const nowTimestamp = admin.firestore.FieldValue.serverTimestamp();
            const reportReasonCode = sanitizeText(reportData.reasonCode || "reported_content", 100).trim() || "reported_content";
            const reportReasonText = sanitizeText(reportData.reasonText || "Content was reported.", 250).trim() || "Content was reported.";
            const reportTargetText = sanitizeText(
                reportData.targetTextSnapshot
                    || extractModerationTargetText(target && target.snap ? (target.snap.data() || {}) : null)
                    || "",
                1000
            ).trim() || null;
            const moderatorReasonText = decisionNote || `Moderator reviewed a pending report: ${reportReasonText}`;
            const normalizedTargetType = target && (target.rawType || target.canonicalType)
                ? (target.rawType || target.canonicalType)
                : (targetType || "user");
            const reportSourceContext = normalizeReportSourceContext(reportData.sourceContext || "") || null;

            if (userAction !== "none") {
                const moderationDefaults = buildUserModerationDefaults(userData);
                const userEventRef = createModerationEventRef();
                let actionType = userAction;
                let expiresAt = null;
                const userUpdate = {
                    lastViolationAt: nowTimestamp,
                };

                if (userAction === "warning") {
                    expiresAt = admin.firestore.Timestamp.fromMillis(nowMs + MODERATION_WARNING_EXPIRY_MS);
                    userUpdate.warningCount = moderationDefaults.warningCount + 1;
                } else if (userAction === "strike") {
                    expiresAt = admin.firestore.Timestamp.fromMillis(nowMs + MODERATION_STRIKE_EXPIRY_MS);
                    userUpdate.strikeCount = moderationDefaults.strikeCount + 1;
                } else if (userAction === "suspend_24h" || userAction === "suspend_7d") {
                    actionType = "forum_suspension";
                    const durationMs = userAction === "suspend_24h"
                        ? MODERATION_SUSPEND_STAGE_ONE_MS
                        : MODERATION_SUSPEND_STAGE_TWO_MS;
                    expiresAt = admin.firestore.Timestamp.fromMillis(nowMs + durationMs);

                    const existingSuspendedUntilMs = timestampToMillis(userData.forumSuspendedUntil);
                    const nextSuspendedUntilMs = existingSuspendedUntilMs != null && existingSuspendedUntilMs > nowMs
                        ? Math.max(existingSuspendedUntilMs, expiresAt.toMillis())
                        : expiresAt.toMillis();
                    userUpdate.forumSuspendedUntil = admin.firestore.Timestamp.fromMillis(nextSuspendedUntilMs);
                } else if (userAction === "forum_ban") {
                    actionType = "forum_ban";
                    userUpdate.permanentForumBan = true;
                    userUpdate.forumSuspendedUntil = null;
                }

                t.set(userEventRef, buildModerationEventPayload({
                    eventId: userEventRef.id,
                    userId: affectedUserId,
                    targetType: normalizedTargetType,
                    targetId: targetId || null,
                    actionType,
                    reasonCode: reportReasonCode,
                    reasonText: moderatorReasonText,
                    source: buildModeratorTaggedSource("moderator_report_review", moderatorSourceMeta),
                    createdBy: reviewedBy,
                    appealable: true,
                    expiresAt,
                    metadata: {
                        reportId,
                        reviewUserAction: userAction,
                        reviewContentAction: contentAction,
                        sourceContext: reportSourceContext,
                        threadId: sanitizeText(reportData.threadId || "", 200).trim() || null,
                    },
                    evidenceText: reportTargetText,
                }));
                t.set(userRef, userUpdate, { merge: true });
                createdModerationEventIds.push(userEventRef.id);
            }

            if (contentAction !== "keep") {
                const moderationStatus = contentAction === "remove_content"
                    ? MODERATION_STATUS_REMOVED
                    : MODERATION_STATUS_HIDDEN;
                const contentEventRef = createModerationEventRef();

                t.set(contentEventRef, buildModerationEventPayload({
                    eventId: contentEventRef.id,
                    userId: affectedUserId || sanitizeText((target.snap.data() || {}).userId || "", 200).trim() || null,
                    targetType: target.rawType || target.canonicalType,
                    targetId: targetId || null,
                    actionType: contentAction,
                    reasonCode: reportReasonCode,
                    reasonText: moderatorReasonText,
                    source: buildModeratorTaggedSource("moderator_report_review", moderatorSourceMeta),
                    createdBy: reviewedBy,
                    appealable: true,
                    metadata: {
                        reportId,
                        reviewUserAction: userAction,
                        reviewContentAction: contentAction,
                        sourceContext: reportSourceContext,
                        threadId: sanitizeText(reportData.threadId || "", 200).trim() || null,
                    },
                    evidenceText: reportTargetText,
                }));

                t.set(target.ref, {
                    ...buildContentModerationUpdate(
                        moderationStatus,
                        buildModeratorTaggedSource("moderator_report_review", moderatorSourceMeta),
                        moderatorReasonText,
                        nowTimestamp
                    ),
                }, { merge: true });
                createdModerationEventIds.push(contentEventRef.id);
            }

            const finalStatus = userAction === "none" && contentAction === "keep"
                ? "dismissed"
                : "resolved";

            t.set(reportRef, {
                status: finalStatus,
                reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewedBy,
                decisionNote: decisionNote || null,
                userAction,
                contentAction,
                createdModerationEventIds,
            }, { merge: true });
        });

        return {
            success: true,
            reportId,
            reviewed: true,
        };
    } catch (error) {
        if (error instanceof HttpsError) throw error;
        logger.error("reviewPendingReport failed:", error);
        throw new HttpsError("internal", "Failed to review report.");
    }
});

// ======================================================
// decayModerationState — expire warnings/strikes and lift ended suspensions
// ======================================================
exports.decayModerationState = onSchedule("every 60 minutes", async () => {
    const now = admin.firestore.Timestamp.now();

    try {
        const expiredSnap = await db.collection("moderationEvents")
            .where("expiresAt", "<=", now)
            .limit(200)
            .get();

        if (!expiredSnap.empty) {
            const batch = db.batch();

            for (const doc of expiredSnap.docs) {
                const data = doc.data() || {};
                if (data.status !== "active") continue;

                const userId = sanitizeText(data.userId || "", 200).trim();
                if (!userId) continue;

                batch.set(doc.ref, {
                    status: "expired",
                    reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
                    reversalReason: "Automatic expiration",
                }, { merge: true });

                const userRef = db.collection("users").doc(userId);

                if (data.actionType === "warning") {
                    batch.set(userRef, { warningCount: admin.firestore.FieldValue.increment(-1) }, { merge: true });
                } else if (data.actionType === "strike") {
                    batch.set(userRef, { strikeCount: admin.firestore.FieldValue.increment(-1) }, { merge: true });
                }
            }

            await batch.commit();
        }

        const suspendedUsersSnap = await db.collection("users")
            .where("forumSuspendedUntil", "<=", now)
            .limit(200)
            .get();

        if (!suspendedUsersSnap.empty) {
            const batch = db.batch();
            for (const doc of suspendedUsersSnap.docs) {
                const data = doc.data() || {};
                if (data.permanentForumBan === true) continue;

                batch.set(doc.ref, {
                    forumSuspendedUntil: null,
                }, { merge: true });
            }
            await batch.commit();
        }
    } catch (error) {
        logger.error("decayModerationState failed:", error);
    }

    return null;
});

