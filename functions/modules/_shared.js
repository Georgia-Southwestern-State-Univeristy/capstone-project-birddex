/**
 * Firebase Functions backend for BirdDex.
 *
 * These added notes explain what the specific backend blocks are doing so you can match
 * Android app actions to the Cloud Function / Firestore logic that supports them.
 */

// 1. Use the specific v1 auth import for the cleanup trigger
const functions = require("firebase-functions/v1");
const { logger } = require("firebase-functions");
const auth = functions.auth;

// 2. Standard v2 imports
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentCreated, onDocumentDeleted, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");

// 3. Admin and external libraries (DECLARED ONLY ONCE)
const admin = require("firebase-admin");
const axios = require("axios");
const crypto = require("crypto");
const { GoogleAuth } = require("google-auth-library");

if (admin.apps.length === 0) {
    admin.initializeApp();
}
const db = admin.firestore();
const storage = admin.storage();
const messaging = admin.messaging();
const FieldValue = admin.firestore.FieldValue;
const Timestamp = admin.firestore.Timestamp;

// Secrets
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");
const EBIRD_API_KEY = defineSecret("EBIRD_API_KEY");
const NUTHATCH_API_KEY = defineSecret("NUTHATCH_API_KEY");
const BIRDDEX_MODEL_API_KEY = defineSecret("BIRDDEX_MODEL_API_KEY");

function secureOnCall(optionsOrHandler, maybeHandler) {
    if (typeof optionsOrHandler === "function") {
        return onCall({ enforceAppCheck: true }, optionsOrHandler);
    }
    return onCall({ ...(optionsOrHandler || {}), enforceAppCheck: true }, maybeHandler);
}

// ======================================================
// CENTRALIZED CONFIG (replaces scattered hard-coded values)
// ======================================================
const CONFIG = {
    GEORGIA_DNR_HUNTING_LINK: "https://georgiawildlife.com/hunting",
    BIRDDEX_MODEL_BASE_URL: "https://birddex-api-650774648072.us-central1.run.app",
    MAX_OPENAI_REQUESTS: 100,
    MAX_PFP_CHANGES: 5,
    COOLDOWN_PERIOD_MS: 24 * 60 * 60 * 1000,       // 24 hours
    FACT_CACHE_LIFETIME_MS: 90 * 24 * 60 * 60 * 1000, // 90 days
    EBIRD_CACHE_TTL_MS: 72 * 60 * 60 * 1000,          // 72 hours
    FORUM_HEATMAP_TTL_MS: 48 * 60 * 60 * 1000,        // 48 hours
    FORUM_ARCHIVE_DAYS_MS: 7 * 24 * 60 * 60 * 1000,   // 7 days
    UNVERIFIED_USER_TTL_MS: 72 * 60 * 60 * 1000,      // 72 hours
    IDENTIFICATION_IMAGE_TTL_MS: 24 * 60 * 60 * 1000, // 24 hours
    FIRESTORE_BATCH_SIZE: 400,  // Firestore max is 500; using 400 for safety
    LOCATION_PRECISION: 3,      // decimal places (~110 meters)
};

const MODERATION_STATUS_VISIBLE = "visible";
const MODERATION_STATUS_UNDER_REVIEW = "under_review";
const MODERATION_STATUS_HIDDEN = "hidden";
const MODERATION_STATUS_REMOVED = "removed";

const PRIVATE_AUDIT_LOG_COLLECTION = "privateAuditLogs";
const USER_RATE_LIMITS = {
    forumPostLike: {
        windowMs: 60 * 1000,
        maxEvents: 60,
        userMessage: "You're liking posts too quickly. Please slow down.",
    },
    forumCommentLike: {
        windowMs: 60 * 1000,
        maxEvents: 90,
        userMessage: "You're liking comments too quickly. Please slow down.",
    },
    forumPostView: {
        windowMs: 60 * 1000,
        maxEvents: 240,
        userMessage: "You're opening posts too quickly. Please slow down.",
    },
    followToggle: {
        windowMs: 60 * 1000,
        maxEvents: 40,
        userMessage: "You're following and unfollowing too quickly. Please slow down.",
    },
    identifyBird: {
        windowMs: 60 * 1000,
        maxEvents: 25,
        userMessage: "You're identifying birds too quickly. Please wait a moment and try again.",
    },
    birdDexWarmup: {
        windowMs: 10 * 60 * 1000,
        maxEvents: 2,
        userMessage: "BirdDex is already warming up. Please wait a moment and try again.",
    },
    bugReport: {
        windowMs: 60 * 60 * 1000,
        maxEvents: 5,
        userMessage: "You've submitted too many bug reports recently. Please try again later.",
    },
    profileUpdate: {
        windowMs: 10 * 60 * 1000,
        maxEvents: 10,
        userMessage: "Profile updates are happening too quickly. Please wait a moment and try again.",
    },
    usernameChange: {
        windowMs: 24 * 60 * 60 * 1000,
        maxEvents: 5,
        userMessage: "You've changed your username too many times today. Please try again later.",
    },
};

// ======================================================
// HYBRID IDENTIFICATION CONFIG
// ======================================================
const HYBRID_ID_CONFIG = {
    BIRDDEX_MODEL_URL: `${CONFIG.BIRDDEX_MODEL_BASE_URL}/predict-json`,
    MODEL_DIRECT_CONFIDENCE_THRESHOLD: 0.70,
    MODEL_DIRECT_MARGIN_THRESHOLD: 0.15,
    MODEL_TIEBREAK_MARGIN_THRESHOLD: 0.10,
    MODEL_FULL_FALLBACK_CONFIDENCE_THRESHOLD: 0.55,
    MODEL_TOP_K: 3,
    NOT_MY_BIRD_LOCK_CONFIDENCE_THRESHOLD: 0.80,
    NOT_MY_BIRD_LOCK_MARGIN_THRESHOLD: 0.12,
    REVIEW_LOCATION_PLAUSIBILITY_THRESHOLD: 0.60,
};

const IDENTIFICATION_FEEDBACK_CONFIG = {
    SUBMIT_FEEDBACK_COOLDOWN_MS: 15 * 1000,
    SUBMIT_FEEDBACK_MAX_PER_LOG: 8,
    COULDNT_FIND_BIRD_COOLDOWN_MS: 30 * 1000,
    COULDNT_FIND_BIRD_MAX_PER_LOG: 1,
};

const CAPTURE_GUARD_CONFIG = {
    REQUIRED_CAPTURE_SOURCE: "camera_burst",
    MIN_BURST_FRAME_COUNT: 3,
    SUSPICION_BLOCK_THRESHOLD: 0.58,
    HIGH_SIMILARITY_BLOCK_THRESHOLD: 0.95,
    ALIASING_BLOCK_THRESHOLD: 0.06,
    SCREEN_ARTIFACT_BLOCK_THRESHOLD: 0.11,
    HIGH_SCREEN_ARTIFACT_THRESHOLD: 0.15,
    BORDER_BLOCK_THRESHOLD: 0.22,
    METADATA_SUPPORT_SCORE_THRESHOLD: 0.45,
    MODERATE_ALIASING_THRESHOLD: 0.05,
    MODERATE_SCREEN_ARTIFACT_THRESHOLD: 0.10,
    MODERATE_BORDER_THRESHOLD: 0.18,
    MODERATE_SIMILARITY_THRESHOLD: 0.93,
};

/**
 * Helper: Utility that clamps a numeric score into the safe 0..1 range used by moderation/capture
 * scoring.
 */
function clamp01(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return 0;
    if (num < 0) return 0;
    if (num > 1) return 1;
    return num;
}

/**
 * Helper: Normalizes the client-provided capture source so point-award logic compares a trusted value.
 */
function sanitizeCaptureSource(source) {
    if (typeof source !== "string") return "unknown";
    const trimmed = source.trim().toLowerCase();
    return trimmed || "unknown";
}

/**
 * Helper: Normalizes the client capture-guard payload and strips anything the backend should not trust
 * as-is.
 */
function sanitizeCaptureGuardPayload(raw) {
    const data = raw && typeof raw === "object" ? raw : {};
    const reasons = Array.isArray(data.reasons)
        ? data.reasons
            .filter(reason => typeof reason === "string")
            .map(reason => sanitizeText(reason, 80))
            .filter(Boolean)
            .slice(0, 8)
        : [];

    return {
        analyzerVersion: sanitizeText(String(data.analyzerVersion || "capture_guard_unknown"), 80),
        suspicionScore: clamp01(data.suspicionScore),
        suspicious: data.suspicious === true,
        burstFrameCount: Math.max(0, Math.min(10, Number(data.burstFrameCount || 0))),
        burstSpanMs: Math.max(0, Math.min(10_000, Number(data.burstSpanMs || 0))),
        selectedFrameIndex: Math.max(0, Math.min(10, Number(data.selectedFrameIndex || 0))),
        frameSimilarity: clamp01(data.frameSimilarity),
        aliasingScore: clamp01(data.aliasingScore),
        screenArtifactScore: clamp01(data.screenArtifactScore),
        borderScore: clamp01(data.borderScore),
        glareScore: clamp01(data.glareScore),
        selectedFrameSharpness: Math.max(0, Number(data.selectedFrameSharpness || 0)),
        metadataScore: clamp01(data.metadataScore),
        metadataSuspicious: data.metadataSuspicious === true,
        editedSoftwareTagPresent: data.editedSoftwareTagPresent === true,
        cameraMakeModelMissing: data.cameraMakeModelMissing === true,
        dateTimeOriginalMissing: data.dateTimeOriginalMissing === true,
        reasons,
    };
}

/**
 * Helper: Turns capture source + burst/suspicion scores into the final backend point-award decision.
 */
function buildPointAwardDecision({ captureSource, captureGuard }) {
    const normalizedSource = sanitizeCaptureSource(captureSource);
    const safeGuard = sanitizeCaptureGuardPayload(captureGuard);

    let allowPointAward = false;
    let reason = "points_require_camera_burst";
    let userMessage = "Identification completed, but points were disabled because BirdDex could not verify this as a live in-app camera capture.";

    const strongSecondarySignal =
        (safeGuard.frameSimilarity >= CAPTURE_GUARD_CONFIG.HIGH_SIMILARITY_BLOCK_THRESHOLD &&
            (safeGuard.aliasingScore >= CAPTURE_GUARD_CONFIG.ALIASING_BLOCK_THRESHOLD
                || safeGuard.borderScore >= CAPTURE_GUARD_CONFIG.BORDER_BLOCK_THRESHOLD
                || safeGuard.screenArtifactScore >= CAPTURE_GUARD_CONFIG.SCREEN_ARTIFACT_BLOCK_THRESHOLD))
        || (safeGuard.screenArtifactScore >= CAPTURE_GUARD_CONFIG.HIGH_SCREEN_ARTIFACT_THRESHOLD &&
            (safeGuard.aliasingScore >= CAPTURE_GUARD_CONFIG.MODERATE_ALIASING_THRESHOLD
                || safeGuard.borderScore >= CAPTURE_GUARD_CONFIG.MODERATE_BORDER_THRESHOLD
                || safeGuard.frameSimilarity >= CAPTURE_GUARD_CONFIG.MODERATE_SIMILARITY_THRESHOLD));

    if (normalizedSource !== CAPTURE_GUARD_CONFIG.REQUIRED_CAPTURE_SOURCE) {
        allowPointAward = false;
        reason = "points_require_camera_burst";
    } else if (safeGuard.burstFrameCount < CAPTURE_GUARD_CONFIG.MIN_BURST_FRAME_COUNT) {
        allowPointAward = false;
        reason = "camera_burst_incomplete";
        userMessage = "Identification completed, but points were disabled because BirdDex did not receive a full live burst from the in-app camera.";
    } else if (safeGuard.suspicionScore >= CAPTURE_GUARD_CONFIG.SUSPICION_BLOCK_THRESHOLD || strongSecondarySignal) {
        allowPointAward = false;
        reason = "screen_photo_suspected";
        userMessage = "Identification completed, but points were disabled because this capture looked like a photo of a screen.";
    } else {
        allowPointAward = true;
        reason = "eligible_live_camera_capture";
        userMessage = null;
    }

    return {
        allowPointAward,
        reason,
        userMessage,
        captureSource: normalizedSource,
        suspicionScore: safeGuard.suspicionScore,
        burstFrameCount: safeGuard.burstFrameCount,
        frameSimilarity: safeGuard.frameSimilarity,
        aliasingScore: safeGuard.aliasingScore,
        screenArtifactScore: safeGuard.screenArtifactScore,
        borderScore: safeGuard.borderScore,
        glareScore: safeGuard.glareScore,
        analyzerVersion: safeGuard.analyzerVersion,
        strongSecondarySignal,
        suspicionThreshold: CAPTURE_GUARD_CONFIG.SUSPICION_BLOCK_THRESHOLD,
    };
}

/**
 * Helper: Loads the stored identification decision/log and returns the authoritative point-award
 * eligibility for a saved bird.
 */
async function resolvePointAwardEligibility({ identificationId, identificationLogId }) {
    let snapshot = null;

    if (identificationId) {
        snapshot = await db.collection("identifications").doc(String(identificationId)).get();
        if (snapshot.exists) {
            const data = snapshot.data() || {};
            const decision = data.pointAwardDecision && typeof data.pointAwardDecision === "object"
                ? data.pointAwardDecision
                : null;
            const captureGuard = data.captureGuard && typeof data.captureGuard === "object"
                ? data.captureGuard
                : null;
            if (decision && typeof decision.allowPointAward === "boolean") {
                return {
                    allowPointAward: decision.allowPointAward,
                    reason: sanitizeText(String(decision.reason || "missing_point_award_decision"), 80),
                    captureSource: sanitizeCaptureSource(captureGuard?.captureSource || decision.captureSource),
                    suspicionScore: clamp01(captureGuard?.suspicionScore),
                };
            }
        }
    }

    if (identificationLogId) {
        snapshot = await db.collection("identificationLogs").doc(String(identificationLogId)).get();
        if (snapshot.exists) {
            const data = snapshot.data() || {};
            const decision = data.pointAwardDecision && typeof data.pointAwardDecision === "object"
                ? data.pointAwardDecision
                : null;
            const captureGuard = data.captureGuard && typeof data.captureGuard === "object"
                ? data.captureGuard
                : null;
            if (decision && typeof decision.allowPointAward === "boolean") {
                return {
                    allowPointAward: decision.allowPointAward,
                    reason: sanitizeText(String(decision.reason || "missing_point_award_decision"), 80),
                    captureSource: sanitizeCaptureSource(captureGuard?.captureSource || decision.captureSource),
                    suspicionScore: clamp01(captureGuard?.suspicionScore),
                };
            }
        }
    }

    return {
        allowPointAward: false,
        reason: "missing_identification_capture_guard",
        captureSource: "unknown",
        suspicionScore: 0,
    };
}

/**
 * Helper: Builds a structured count object for moderation report reasons.
 */
function buildInitialReportReasonCounts() {
    return {
        language: 0,
        image: 0,
        spam: 0,
        harassment: 0,
        other: 0,
    };
}

/**
 * Helper: Builds the default moderation fields for a new forum post or comment.
 */
function buildInitialModerationFields() {
    return {
        moderationStatus: MODERATION_STATUS_VISIBLE,
        moderationReason: null,
        moderationSource: null,
        moderatedAt: null,
        hiddenAt: null,
        reportCount: 0,
        uniqueReporterCount: 0,
        reportReasonCounts: buildInitialReportReasonCounts(),
        lastReportedAt: null,
    };
}

/**
 * Helper: Builds the default moderation fields for a new user document.
 */
function buildInitialUserModerationFields() {
    return {
        warningCount: 0,
        strikeCount: 0,
        permanentForumBan: false,
        forumSuspendedUntil: null,
        lastViolationAt: null,
    };
}

// ======================================================
// HELPER: Input Sanitization
// ======================================================

function assertForumTextAllowed(text, fieldName = "Text") {
    if (text === undefined || text === null || text === "") return;

    if (typeof text !== "string") {
        throw new HttpsError("invalid-argument", `${fieldName} must be a string.`);
    }

    const trimmed = text.trim();

    // Reject obvious control characters except normal whitespace.
    if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(trimmed)) {
        throw new HttpsError("invalid-argument", `${fieldName} contains invalid characters.`);
    }
}
// ======================================================
// HELPER: Input Sanitization
// ======================================================
/**
 * Validates/cleans incoming text before the backend trusts it or writes it to Firestore.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
/**
 * Helper: Validates and normalizes usernames before they are stored.
 */
function sanitizeUsername(username) {
    if (!username || typeof username !== "string") {
        throw new HttpsError("invalid-argument", "Username must be a non-empty string.");
    }
    // Trim edges and collapse multiple internal spaces into one
    const trimmed = username.trim().replace(/ {2,}/g, " ");
    if (trimmed.length < 3 || trimmed.length > 30) {
        throw new HttpsError("invalid-argument", "Username must be between 3 and 30 characters.");
    }
    if (!/^[a-zA-Z0-9_ ]+$/.test(trimmed)) {
        throw new HttpsError("invalid-argument", "Username can only contain letters, numbers, underscores, and spaces.");
    }
    assertForumTextAllowed(trimmed, "Username");
    return trimmed;
}

/**
 * Validates/cleans incoming text before the backend trusts it or writes it to Firestore.
 */
/**
 * Helper: General text sanitizer used all over the backend before writes/comparisons.
 */
function sanitizeText(text, maxLength = 5000) {
    if (!text || typeof text !== "string") return "";
    const trimmed = text.trim();
    if (trimmed.length > maxLength) return trimmed.substring(0, maxLength);
    return trimmed.replace(/<[^>]*>/g, "");
}

function getUserRateLimitRef(userId, bucket) {
    return db.collection("users").doc(userId).collection("rateLimits").doc(bucket);
}

function createPrivateAuditLogRef() {
    return db.collection(PRIVATE_AUDIT_LOG_COLLECTION).doc();
}

function sanitizeAuditMetadata(value, depth = 0) {
    if (depth > 3 || value === undefined) return null;
    if (value === null) return null;
    if (typeof value === "string") return sanitizeText(value, 500);
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (typeof value === "boolean") return value;
    if (Array.isArray(value)) {
        return value.slice(0, 25)
            .map((item) => sanitizeAuditMetadata(item, depth + 1))
            .filter((item) => item !== undefined);
    }
    if (value instanceof admin.firestore.Timestamp) return value;
    if (typeof value === "object") {
        const out = {};
        Object.entries(value).slice(0, 40).forEach(([key, val]) => {
            const sanitized = sanitizeAuditMetadata(val, depth + 1);
            if (sanitized !== undefined) out[sanitizeText(key, 80)] = sanitized;
        });
        return out;
    }
    return sanitizeText(String(value), 500);
}

function buildPrivateAuditLogPayload({
    action,
    status = "success",
    userId = null,
    actorUserId = null,
    targetType = null,
    targetId = null,
    reasonCode = null,
    message = null,
    metadata = null,
}) {
    return {
        action: sanitizeText(action || "unknown_action", 120),
        status: sanitizeText(status || "success", 40),
        userId: userId ? sanitizeText(userId, 200) : null,
        actorUserId: actorUserId ? sanitizeText(actorUserId, 200) : (userId ? sanitizeText(userId, 200) : null),
        targetType: targetType ? sanitizeText(targetType, 80) : null,
        targetId: targetId ? sanitizeText(targetId, 200) : null,
        reasonCode: reasonCode ? sanitizeText(reasonCode, 120) : null,
        message: message ? sanitizeText(message, 1000) : null,
        metadata: sanitizeAuditMetadata(metadata),
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
    };
}

function queuePrivateAuditLog(transaction, entry) {
    const auditRef = createPrivateAuditLogRef();
    transaction.set(auditRef, buildPrivateAuditLogPayload(entry));
}

async function writePrivateAuditLog(entry) {
    const auditRef = createPrivateAuditLogRef();
    await auditRef.set(buildPrivateAuditLogPayload(entry));
}

async function assertAndConsumeUserRateLimit(transaction, userId, bucket, config, metadata = null) {
    if (!config || !userId || !bucket) return;

    const rateLimitRef = getUserRateLimitRef(userId, bucket);
    const rateLimitSnap = await transaction.get(rateLimitRef);
    const nowMs = Date.now();
    const minAllowedMs = nowMs - config.windowMs;

    let existingEvents = [];
    if (rateLimitSnap.exists) {
        const rawEvents = rateLimitSnap.get("events");
        if (Array.isArray(rawEvents)) {
            existingEvents = rawEvents
                .map((value) => Number(value))
                .filter((value) => Number.isFinite(value));
        }
    }

    const recentEvents = existingEvents
        .filter((value) => value >= minAllowedMs)
        .slice(-(Math.max(1, config.maxEvents) - 1));

    if (recentEvents.length >= config.maxEvents) {
        throw new HttpsError("resource-exhausted", config.userMessage || "Too many requests.");
    }

    recentEvents.push(nowMs);
    transaction.set(rateLimitRef, {
        events: recentEvents,
        lastActionAt: admin.firestore.Timestamp.fromMillis(nowMs),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        metadata: sanitizeAuditMetadata(metadata),
    }, { merge: true });
}

async function readUserRateLimitState(transaction, userId, bucket, config, metadata = null) {
    if (!config || !userId || !bucket) return null;

    const rateLimitRef = getUserRateLimitRef(userId, bucket);
    const rateLimitSnap = await transaction.get(rateLimitRef);
    const nowMs = Date.now();
    const minAllowedMs = nowMs - config.windowMs;

    let existingEvents = [];
    if (rateLimitSnap.exists) {
        const rawEvents = rateLimitSnap.get("events");
        if (Array.isArray(rawEvents)) {
            existingEvents = rawEvents
                .map((value) => Number(value))
                .filter((value) => Number.isFinite(value));
        }
    }

    const recentEvents = existingEvents
        .filter((value) => value >= minAllowedMs)
        .slice(-(Math.max(1, config.maxEvents) - 1));

    if (recentEvents.length >= config.maxEvents) {
        throw new HttpsError("resource-exhausted", config.userMessage || "Too many requests.");
    }

    return {
        rateLimitRef,
        events: recentEvents,
        nowMs,
        metadata: sanitizeAuditMetadata(metadata),
    };
}

function commitUserRateLimitState(transaction, state) {
    if (!state || !state.rateLimitRef) return;

    const nextEvents = Array.isArray(state.events) ? [...state.events, state.nowMs] : [state.nowMs];
    transaction.set(state.rateLimitRef, {
        events: nextEvents,
        lastActionAt: admin.firestore.Timestamp.fromMillis(state.nowMs),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        metadata: state.metadata || null,
    }, { merge: true });
}
// ======================================================
// logfilteredContentAttempt
// ======================================================
// Add this callable to your current updated index.js so client-side blocks can still be logged.
// ======================================================
// logFilteredContentAttempt — client-side blocked content logger
// ======================================================
const logFilteredContentAttempt = exports.logFilteredContentAttempt = secureOnCall(async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Login required.");
    }

    const userId = request.auth.uid;
    const data = request.data || {};

    const submissionType = typeof data.submissionType === "string"
        ? data.submissionType.trim()
        : "";
    const fieldName = typeof data.fieldName === "string"
        ? data.fieldName.trim()
        : "";
    const text = typeof data.text === "string"
        ? data.text
        : "";
    const threadId = typeof data.threadId === "string"
        ? data.threadId.trim()
        : null;
    const commentId = typeof data.commentId === "string"
        ? data.commentId.trim()
        : null;
    const extra = data.extra && typeof data.extra === "object"
        ? data.extra
        : {};

    if (!submissionType) {
        throw new HttpsError("invalid-argument", "submissionType is required.");
    }

    if (!fieldName) {
        throw new HttpsError("invalid-argument", "fieldName is required.");
    }

    await logFilteredContent({
        userId,
        submissionType,
        fieldName,
        text,
        threadId,
        commentId,
        extra: {
            source: "client_block",
            ...extra,
        },
    });

    return { success: true };
});

// ======================================================
// HELPER: Server-side content filter + logging
// Mirrors the app-side ContentFilter so blocked content is not stored even if
// the client is bypassed or modified.
// ======================================================
const FILTERED_CONTENT_LOG_COLLECTION = "filteredContentLogs";
const SERVER_NSFW_WORDS = [
    "fuck", "shit", "asshole", "bitch", "cunt", "dick", "pussy", "bastard",
    "slut", "whore", "sex", "porn", "pornography", "xxx", "nsfw", "erotic",
    "hardcore", "softcore", "adult content", "motherfucker", "cocksucker",
    "cockfucker", "jackass", "dipshit", "dumbass", "dumbshit", "goddamn", "piss",
    "ahole", "biotch", "penis", "vagina", "clitoris", "testicles", "scrotum",
    "boobs", "tits", "ass", "butt", "breasts", "genitals", "cock", "balls",
    "clit", "labia", "erection", "masturbate", "masturbation", "orgasm", "cum",
    "cumming", "ejaculate", "penetrate", "penetration", "intercourse", "coitus",
    "blowjob", "handjob", "deepthroat", "rimjob", "rimming", "anal", "cummin",
    "coom", "blowie", "his member", "wet cunt", "onlyfans", "camgirl", "camsite",
    "stripper", "escort", "brothel", "fetish", "bdsm", "kink", "kinky",
    "dominatrix", "submissive", "bondage", "dildo", "vibrator", "pegging",
    "fingering", "scissoring", "grinding", "foot fetish", "roleplay sex", "nude",
    "nudes", "naked", "topless", "lewd", "explicit", "send nudes", "milf",
    "dilf", "sugar daddy", "sugar baby", "creampie", "facial", "spitroast",
    "threesome", "foursome", "gangbang", "orgy", "hentai", "doujinshi",
    "adult video", "sex tape", "gilf", "hookup", "one night stand", "booty call",
    "smash", "get laid", "bang", "doggy style", "69", "quickie", "hooking up",
    "sleep together", "nigger", "kike", "faggot", "dyke", "retard", "tranny",
    "spic", "chink", "wetback", "coon", "nazi", "hitler", "negro", "beaner",
    "gook", "gypo", "fag", "cracker", "zipperhead", "sand nigger", "turban head",
    "darkie", "chud", "transvestite", "troon", "nigga", "dark skin", "cholo",
    "gringo", "kill yourself", "suicide", "murder", "rape", "molest", "pedophile",
    "underage", "terrorist", "massacre", "genocide", "kys", "rapist", "al qaeda",
    "isis", "kkk", "klu klux klan", "kool kids klub", "cia", "fbi", "cocaine",
    "heroin", "meth", "fentanyl", "oxycodone", "xanax", "percocet", "crack cocaine",
    "mdma", "ecstasy", "9-11", "white power", "black lives matter", "magam", "maga",
    "magat", "libtard", "glowie", "ice agent", "israel", "palestine",
    "jet fuel can't melt steel beams", "jet fuel cant melt steel beams", "black excellence",
    "white superiority", "idf", "ukraine", "from the river to the sea",
    "from the river, to the sea", "russia", "free palestine", "trump", "biden",
    "obama", "bill clinton", "hillary clinton", "nick fuentes", "osama", "bin laden",
    "jd vance", "andrew tate", "tristan tate", "sneako", "epstein", "ghislaine maxwell",
    "jeffery epstein", "benjamin netanyahu", "netanyahu", "adolf hitler", "himmler",
    "g string", "lingerie", "thong"
];
const SERVER_BIRD_WHITELIST = [
    "tit", "tits", "booby", "boobies", "shag", "woodcock", "dickcissel",
    "bushtit", "cock", "ass", "blue tit", "great tit", "tufted titmouse"
];
const EMAIL_PATTERN = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,6}/;
const PHONE_PATTERN = /\b(\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b/;
const URL_PATTERN = /https?:\/\/\S+\s?/i;
const SPAM_REPETITION_PATTERN = /(.)\1{4,}/;
const CREDIT_CARD_PATTERN = /\b(?:\d[ -]*?){13,16}\b/;
const ZALGO_PATTERN = /[\u0300-\u036F\u1DC0-\u1DFF\u20D0-\u20FF\uFE20-\uFE2F]{3,}/;

/**
 * Helper: Normalizes text into a form that is easier to scan for blocked words/bypass attempts.
 */
function normalizeContentFilterText(text) {
    if (text == null) return "";
    return String(text).toLowerCase()
        .replace(/0/g, "o")
        .replace(/1/g, "i")
        .replace(/3/g, "e")
        .replace(/4/g, "a")
        .replace(/5/g, "s")
        .replace(/7/g, "t")
        .replace(/8/g, "b")
        .replace(/@/g, "a")
        .replace(/\$/g, "s")
        .replace(/!/g, "i")
        .replace(/(.)\1+/g, "$1");
}

/**
 * Helper: Escapes dynamic text before it is used to build a regular expression safely.
 */
function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\$&");
}

/**
 * Helper: Checks whether a blocked term appears in normalized text.
 */
function checkBlockedWordMatch(input, target) {
    if (!target || target.length < 3) return false;
    const regex = new RegExp(`\b${escapeRegex(target)}\b`, "i");
    return regex.test(input);
}

/**
 * Helper: Checks blocked terms while also looking for simple bypass attempts/spaced variants.
 */
function checkBlockedWordWithBypass(input, word) {
    let regexString = "\b";
    for (let i = 0; i < word.length; i += 1) {
        regexString += escapeRegex(word[i]);
        if (i < word.length - 1) {
            regexString += "[\\W_]*";
        }
    }
    regexString += "\b";
    return new RegExp(regexString, "i").test(input);
}

/**
 * Helper: Adds reviewer identity/source metadata so moderation actions show who performed them.
 */
function buildModeratorTaggedSource(baseSource, moderatorMeta = {}) {
    const base = sanitizeText(baseSource || "", 120).trim() || "moderator_action";

    const email = sanitizeText(moderatorMeta.email || "", 200).trim();
    const username = sanitizeText(
        moderatorMeta.username || moderatorMeta.displayName || "",
        120
    ).trim();
    const uid = sanitizeText(moderatorMeta.uid || "", 200).trim();

    const who = email || username || uid;
    return who ? `${base} (${who})` : base;
}

/**
 * Helper: Checks whether a bird/species should bypass a broader text/content block because it is
 * explicitly allowed.
 */
function isBirdWhitelistMatch(rawInput, blockedWord) {
    const input = String(rawInput || "").toLowerCase();
    return SERVER_BIRD_WHITELIST.some((white) => input.includes(white) && white.includes(blockedWord));
}

/**
 * Helper: Builds the user/admin reason payload for blocked-content detections.
 */
function getBlockedContentReason(text) {
    if (text == null || String(text).trim() === "") return null;

    if (ZALGO_PATTERN.test(String(text))) return "glitch text";

    const unicodeNormalized = String(text)
        .normalize("NFD")
        .replace(/[^\p{ASCII}]/gu, "");
    const lower = unicodeNormalized.toLowerCase();

    if (CREDIT_CARD_PATTERN.test(String(text))) return "sensitive financial data";
    if (EMAIL_PATTERN.test(String(text))) return "an email address";
    if (PHONE_PATTERN.test(String(text))) return "a phone number";
    if (URL_PATTERN.test(String(text))) return "external links";
    if (SPAM_REPETITION_PATTERN.test(String(text))) return "excessive character repetition";

    const normalized = normalizeContentFilterText(lower);
    for (const word of SERVER_NSFW_WORDS) {
        const normalizedWord = normalizeContentFilterText(word);
        if (isBirdWhitelistMatch(lower, normalizedWord)) continue;
        if (checkBlockedWordMatch(normalized, normalizedWord) || checkBlockedWordWithBypass(lower, word.toLowerCase())) {
            return "inappropriate language";
        }
    }

    return null;
}

/**
 * Helper: Writes blocked-content attempts to Firestore so moderators/admins can review them later.
 */
async function logFilteredContent({
    userId = null,
    submissionType,
    fieldName,
    text,
    threadId = null,
    commentId = null,
    extra = {}
}) {
    try {
        await db.collection(FILTERED_CONTENT_LOG_COLLECTION).add({
            userId,
            submissionType,
            fieldName,
            threadId,
            commentId,
            textPreview: typeof text === "string" ? text.substring(0, 500) : "",
            textLength: typeof text === "string" ? text.length : 0,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            ...extra,
        });
    } catch (error) {
        logger.error("Failed to log filtered content:", error);
    }
}

/**
 * Helper: Runs content filtering and throws before the request can proceed if blocked content is found.
 */
async function assertNoBlockedContentOrThrow({
    userId = null,
    submissionType,
    fieldName,
    text,
    threadId = null,
    commentId = null,
    extra = {}
}) {
    const reason = getBlockedContentReason(text);
    if (!reason) return;

    await logFilteredContent({
        userId,
        submissionType,
        fieldName,
        text,
        threadId,
        commentId,
        extra: {
            blockedReason: reason,
            ...extra,
        },
    });

    throw new HttpsError(
        "invalid-argument",
        `Your ${fieldName} contains ${reason} and could not be submitted.`
    );
}

// ======================================================
// HELPER: Location (backend-owned + privacy-rounded)
// ======================================================
function roundCoordinateForStorage(value, precision = CONFIG.LOCATION_PRECISION) {
    const num = Number(value);
    if (!Number.isFinite(num)) return null;
    return Number(num.toFixed(precision));
}

function buildRoundedLocationId(latitude, longitude) {
    const fixedLat = Number(latitude).toFixed(CONFIG.LOCATION_PRECISION);
    const fixedLng = Number(longitude).toFixed(CONFIG.LOCATION_PRECISION);
    return `LOC_${fixedLat}_${fixedLng}`;
}

// ======================================================
// HELPER: Hotspot Bucket ID (Matches Android App)
// ======================================================
function calculateHotspotBucketId(lat, lng) {
    const BUCKET_SIZE = 0.02;
    const blat = (Math.round(lat / BUCKET_SIZE) * BUCKET_SIZE).toFixed(4);
    const blng = (Math.round(lng / BUCKET_SIZE) * BUCKET_SIZE).toFixed(4);
    return `${blat},${blng}`;
}

async function getOrCreateLocation(latitude, longitude, localityName, db, extra = {}) {
    const roundedLat = roundCoordinateForStorage(latitude);
    const roundedLng = roundCoordinateForStorage(longitude);
    const safeCountry = typeof extra.country === "string" && extra.country.trim() ? sanitizeText(extra.country, 80) : "US";
    const safeState = typeof extra.state === "string" && extra.state.trim() ? sanitizeText(extra.state, 80) : "GA";
    const safeLocality = typeof localityName === "string" && localityName.trim()
        ? sanitizeText(localityName, 120)
        : null;

    let locationId;
    let locationData;

    if (roundedLat !== null && roundedLng !== null) {
        locationId = buildRoundedLocationId(roundedLat, roundedLng);
        const fixedLat = roundedLat.toFixed(CONFIG.LOCATION_PRECISION);
        const fixedLng = roundedLng.toFixed(CONFIG.LOCATION_PRECISION);
        locationData = {
            id: locationId,
            latitude: roundedLat,
            longitude: roundedLng,
            country: safeCountry,
            state: safeState,
            locality: safeLocality || `Lat: ${fixedLat}, Lng: ${fixedLng}`,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            createdBy: extra.userId ? sanitizeText(extra.userId, 200) : null,
        };
    } else {
        const textKey = crypto.createHash("sha1")
            .update(`${safeCountry}|${safeState}|${safeLocality || "unknown"}`)
            .digest("hex")
            .slice(0, 24);
        locationId = `LOC_TEXT_${textKey}`;
        locationData = {
            id: locationId,
            latitude: null,
            longitude: null,
            country: safeCountry,
            state: safeState,
            locality: safeLocality || "Unknown locality",
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            createdBy: extra.userId ? sanitizeText(extra.userId, 200) : null,
        };
    }

    await db.collection("locations").doc(locationId).set(locationData, { merge: true });
    return locationId;
}

async function commitBatchOperations(operations, chunkSize = CONFIG.FIRESTORE_BATCH_SIZE) {
    if (!Array.isArray(operations) || operations.length === 0) return;

    for (let i = 0; i < operations.length; i += chunkSize) {
        const batch = db.batch();
        for (const op of operations.slice(i, i + chunkSize)) {
            op(batch);
        }
        await batch.commit();
    }
}

function normalizeHotspotBirdKeySegment(value, maxLength = 120) {
    const safe = sanitizeText(value || "", maxLength).toLowerCase();
    return safe
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "")
        .slice(0, maxLength);
}

function buildHotspotBirdKey({ birdId = null, commonName = null, userBirdId = null } = {}) {
    const normalizedBirdId = normalizeHotspotBirdKeySegment(birdId, 120);
    if (normalizedBirdId) return `bird_${normalizedBirdId}`;

    const normalizedUserBirdId = normalizeHotspotBirdKeySegment(userBirdId, 120);
    if (normalizedUserBirdId) return `userbird_${normalizedUserBirdId}`;

    const normalizedCommonName = sanitizeText(commonName || "", 200).toLowerCase();
    if (normalizedCommonName) {
        const hash = crypto.createHash("sha1").update(normalizedCommonName).digest("hex").slice(0, 16);
        return `name_${hash}`;
    }

    return "bird_unknown";
}

function normalizeHotspotVoteValue(value) {
    return value === "down" ? "down" : "up";
}

function getHotspotVotesCollectionRef(hotspotId, birdKey) {
    return db.collection("hotspotVotes").doc(hotspotId).collection("birds").doc(birdKey).collection("votes");
}

function getHotspotBirdSummaryRef(hotspotId, birdKey) {
    return db.collection("hotspotVoteSummaries").doc(hotspotId).collection("birds").doc(birdKey);
}

function getHotspotSummaryRef(hotspotId) {
    return db.collection("hotspotVoteSummaries").doc(hotspotId);
}

async function getUserBirdSightingRowsForHotspot(hotspotId) {
    if (!hotspotId || typeof hotspotId !== "string") return [];
    const snap = await db.collection("userBirdSightings").where("hotspotId", "==", hotspotId).get();
    return snap.docs.map((doc) => ({ id: doc.id, ...(doc.data() || {}) }));
}

function buildHotspotBirdAggregatesFromSightings(sightings) {
    const birdMap = new Map();

    for (const sighting of sightings) {
        const birdKey = buildHotspotBirdKey({
            birdId: sighting.birdId || null,
            commonName: sighting.commonName || null,
            userBirdId: sighting.userBirdId || null,
        });

        if (!birdMap.has(birdKey)) {
            birdMap.set(birdKey, {
                birdKey,
                birdId: sighting.birdId || null,
                commonName: sighting.commonName || "",
                userBirdCount: 0,
                lastSightingAt: sighting.timestamp || null,
            });
        }

        const entry = birdMap.get(birdKey);
        entry.userBirdCount += 1;
        if (!entry.birdId && sighting.birdId) entry.birdId = sighting.birdId;
        if ((!entry.commonName || !entry.commonName.trim()) && sighting.commonName) entry.commonName = sighting.commonName;
        if (sighting.timestamp) entry.lastSightingAt = sighting.timestamp;
    }

    return birdMap;
}

async function recomputeHotspotBirdSummary(hotspotId, birdKey) {
    if (!hotspotId || typeof hotspotId !== "string" || !birdKey || typeof birdKey !== "string") return null;

    const sightings = await getUserBirdSightingRowsForHotspot(hotspotId);
    const birdAggregates = buildHotspotBirdAggregatesFromSightings(sightings);
    const birdAggregate = birdAggregates.get(birdKey) || null;

    const votesSnap = await getHotspotVotesCollectionRef(hotspotId, birdKey).get();
    let upVoteCount = 0;
    let downVoteCount = 0;

    votesSnap.forEach((doc) => {
        const vote = normalizeHotspotVoteValue(doc.get("vote"));
        if (vote === "down") downVoteCount += 1;
        else upVoteCount += 1;
    });

    const summaryRef = getHotspotBirdSummaryRef(hotspotId, birdKey);
    const userBirdCount = birdAggregate?.userBirdCount || 0;

    if (userBirdCount <= 0) {
        await summaryRef.delete().catch(() => null);
        return null;
    }

    const isVerified = (upVoteCount > 0 && upVoteCount > downVoteCount);
    const status = isVerified ? "verified" : (downVoteCount > upVoteCount ? "flagged" : "unverified");

    const summaryData = {
        hotspotId,
        birdKey,
        birdId: birdAggregate?.birdId || null,
        commonName: birdAggregate?.commonName || "",
        userBirdCount,
        upVoteCount,
        downVoteCount,
        voteCount: upVoteCount + downVoteCount,
        netVotes: upVoteCount - downVoteCount,
        isVerified,
        status,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        lastSightingAt: birdAggregate?.lastSightingAt || null,
    };

    await summaryRef.set(summaryData, { merge: true });
    return summaryData;
}

async function recomputeHotspotSummary(hotspotId) {
    if (!hotspotId || typeof hotspotId !== "string") return null;

    const birdsSnap = await getHotspotSummaryRef(hotspotId).collection("birds").get();
    let userBirdCount = 0;
    let verifiedBirdCount = 0;
    let unverifiedBirdCount = 0;
    const birds = {};

    birdsSnap.forEach((doc) => {
        const data = doc.data() || {};
        const ubc = data.userBirdCount || 0;
        if (ubc <= 0) return; // Ignore birds with no user sightings

        userBirdCount += 1;
        const isV = (data.isVerified === true);
        if (isV) {
            verifiedBirdCount += 1;
        } else {
            unverifiedBirdCount += 1;
        }

        birds[doc.id] = {
            birdId: data.birdId || null,
            commonName: data.commonName || "",
            userBirdCount: ubc,
            upVoteCount: data.upVoteCount || 0,
            downVoteCount: data.downVoteCount || 0,
            isVerified: isV,
            status: data.status || "unverified",
        };
    });

    let state = "unverified";
    if (userBirdCount > 0) {
        if (verifiedBirdCount > 0 && verifiedBirdCount === userBirdCount) {
            state = "verified";
        } else if (verifiedBirdCount > 0) {
            state = "mixed";
        } else {
            const hasFlags = Object.values(birds).some(b => b.status === "flagged");
            state = hasFlags ? "flagged" : "unverified";
        }
    }

    const hotspotSummaryRef = getHotspotSummaryRef(hotspotId);
    const summaryPayload = {
        hotspotId,
        sourceType: "user",
        state,
        status: state,
        displayState: state,
        verifiedBirdCount,
        unverifiedBirdCount,
        userBirdCount,
        hasUserSightings: userBirdCount > 0,
        isVerified: state === "verified",
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        birds,
    };

    await hotspotSummaryRef.set(summaryPayload, { merge: true });
    return summaryPayload;
}

async function recomputeHotspotVoteSummaryForHotspot(hotspotId) {
    if (!hotspotId || typeof hotspotId !== "string") return null;

    const sightings = await getUserBirdSightingRowsForHotspot(hotspotId);
    const birdAggregates = buildHotspotBirdAggregatesFromSightings(sightings);

    const existingBirdDocs = await getHotspotSummaryRef(hotspotId).collection("birds").listDocuments();
    const knownBirdKeys = new Set([...birdAggregates.keys(), ...existingBirdDocs.map((doc) => doc.id)]);

    for (const birdKey of knownBirdKeys) {
        await recomputeHotspotBirdSummary(hotspotId, birdKey);
    }

    return recomputeHotspotSummary(hotspotId);
}

// ======================================================
// HELPER: Delay
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 */
/**
 * Helper: Small async sleep helper used between retries/backoff attempts.
 */
function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// ======================================================
// HELPER: OpenAI call with retry logic
// ======================================================
async function callOpenAIWithRetry({ prompt, model, response_format, temperature, max_tokens, logPrefix, timeout = 10000 }) {
    let retries = 0;
    const maxRetries = 5;
    const initialDelayMs = 1000;

    while (retries < maxRetries) {
        try {
            const aiResponse = await axios.post(
                "https://api.openai.com/v1/chat/completions",
                {
                    model: model,
                    messages: [{ role: "user", content: prompt }],
                    response_format: response_format,
                    temperature: temperature,
                    max_tokens: max_tokens
                },
                {
                    headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
                    timeout: timeout
                }
            );

            if (!aiResponse.data || !aiResponse.data.choices || !aiResponse.data.choices[0]) {
                throw new Error("OpenAI returned invalid response format");
            }
            const content = aiResponse.data.choices[0].message?.content;
            if (!content) throw new Error("OpenAI returned empty content");

            return JSON.parse(content);
        } catch (error) {
            if (error.code === "ECONNABORTED") {
                logger.warn(`OpenAI call timed out for ${logPrefix}. Retrying... (${retries + 1}/${maxRetries})`);
                retries++;
                await delay(initialDelayMs * Math.pow(2, retries - 1));
                continue;
            }
            if (error.response && error.response.status === 429) {
                retries++;
                const delayTime = initialDelayMs * Math.pow(2, retries - 1);
                logger.warn(`Rate limit hit for ${logPrefix}. Retrying in ${delayTime}ms... (${retries}/${maxRetries})`);
                await delay(delayTime);
            } else {
                logger.error(`Error calling OpenAI for ${logPrefix}:`, error);
                throw error;
            }
        }
    }
    throw new Error(`Failed to call OpenAI for ${logPrefix} after ${maxRetries} retries.`);
}

// ======================================================
// HELPER: Generate and save GENERAL bird facts
// ======================================================
async function generateAndSaveBirdFacts(birdId) {
    const birdDoc = await db.collection("birds").doc(birdId).get();
    if (!birdDoc.exists) {
        logger.error(`Bird document ${birdId} not found for general fact generation.`);
        throw new HttpsError("not-found", `Bird ${birdId} not found for general facts.`);
    }
    const { commonName, scientificName } = birdDoc.data();

    logger.info(`Generating general facts for ${commonName} (${birdId})`);

    try {
        const prompt = `Generate comprehensive, engaging, and paraphrased general bird facts for the ${commonName} (${scientificName}) bird, drawing information from general knowledge sources like "All About Birds" (do NOT copy directly). Format the response as a JSON object with the following keys. If a category is not applicable or information is scarce, use "N/A" or "Not readily available."

The JSON object should have these keys and corresponding facts:
{
  "sizeAppearance": "Describe wingspan, color patterns, male vs. female, juvenile vs. adult differences.",
  "distinctiveBehaviors": "Describe how they fly, perch, unique calls, and feeding habits.",
  "similarSpecies": "List similar species and quick tips to tell them apart (e.g., 'Often confused with...').",
  "whereInGeorgiaFound": "Specify regions in Georgia where they're found (Coastal plain, Piedmont, mountains, wetlands, backyards, etc.).",
  "seasonalPresence": "Indicate if they're a year-round resident, summer breeder, winter visitor, or migratory stopover.",
  "peakViewingTimes": "Suggest best times for viewing (dawn vs. dusk, migration, nesting season).",
  "diet": "Describe their diet (seeds, insects, fish, nectar, small mammals).",
  "nestingHabits": "Detail nest type, typical clutch size, preferred nesting locations.",
  "roleInEcosystem": "Explain their role (pollinator, pest control, seed disperser).",
  "uniqueBehaviorsSpecific": "Highlight unique behaviors (e.g., woodpeckers' shock-absorbing skulls, hummingbirds' hovering).",
  "recordSettingFacts": "Mention any record-setting facts (fastest flyer, longest migration, loudest call).",
  "culturalHistoricalNotes": "Include cultural or historical notes (state bird, local folklore).",
  "conservationStatus": "Describe their status (common, declining, threatened).",
  "howToHelp": "Provide tips on how to help (feeder tips, habitat protection, avoiding window strikes).",
  "threatsInGeorgia": "List specific threats in Georgia (habitat loss, invasive species, climate impacts).",
  "bestAnglesBehaviors": "Tips for capturing photos: best angles or behaviors to photograph.",
  "timesBestLighting": "Tips for capturing photos: times of day with best lighting.",
  "avoidDisturbing": "Tips for capturing photos: how to avoid disturbing the bird."
}`;

        const factsJson = await callOpenAIWithRetry({
            prompt,
            model: "gpt-4o",
            response_format: { type: "json_object" },
            temperature: 0.7,
            max_tokens: 1500,
            logPrefix: `GENERAL facts for ${commonName} (${birdId})`,
            timeout: 25000
        });

        const birdFactsRef = db.collection("birdFacts").doc(birdId);
        await birdFactsRef.set({
            birdId,
            lastGenerated: admin.firestore.FieldValue.serverTimestamp(),
            ...factsJson
        }, { merge: true });

        logger.info(`Successfully generated and saved general facts for ${commonName} (${birdId})`);
        return factsJson;
    } catch (error) {
        logger.error(`Error generating GENERAL facts for ${commonName} (${birdId}):`, error);
        throw new HttpsError("internal", `Failed to generate bird facts: ${error.message}`);
    }
}

// ======================================================
// HELPER: Generate and save HUNTER bird facts
// ======================================================
async function generateAndSaveHunterFacts(birdId) {
    const birdDoc = await db.collection("birds").doc(birdId).get();
    if (!birdDoc.exists) {
        logger.error(`Bird document ${birdId} not found for hunter fact generation.`);
        throw new HttpsError("not-found", `Bird ${birdId} not found for hunter facts.`);
    }
    const { commonName, scientificName } = birdDoc.data();

    logger.info(`Generating hunter facts for ${commonName} (${birdId})`);

    try {
        const prompt = `Generate specific "Hunter Facts" for the ${commonName} (${scientificName}) bird, based on general knowledge of Georgia hunting regulations, referencing official sources like Georgia Department of Natural Resources (DNR) and U.S. Fish and Wildlife Service (USFWS) where applicable. Format the response as a JSON object with the following keys. If a category is not applicable or information is scarce, use "N/A" or "Not readily available." If no specific hunter facts are found, default the legalStatusGeorgia to "N/A - Information not readily available." and the notHuntableStatement to "N/A".

The JSON object should have these keys and corresponding facts:
{
  "legalStatusGeorgia": "Protected species — hunting not permitted",
  "season": "N/A",
  "licenseRequirements": "N/A",
  "federalProtections": "N/A",
  "notHuntableStatement": "Protected songbird — illegal to hunt under federal law.",
  "isEndangered": "No",
  "relevantRegulations": "Consult Georgia Department of Natural Resources (DNR) and U.S. Fish and Wildlife Service (USFWS) for specific legal details."
}`;

        const hunterFactsJson = await callOpenAIWithRetry({
            prompt,
            model: "gpt-4o",
            response_format: { type: "json_object" },
            temperature: 0.7,
            max_tokens: 1000,
            logPrefix: `HUNTER facts for ${commonName} (${birdId})`,
            timeout: 25000
        });

        const hunterFactsRef = db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId);
        await hunterFactsRef.set({
            birdId,
            lastGenerated: admin.firestore.FieldValue.serverTimestamp(),
            georgiaDNRHuntingLink: CONFIG.GEORGIA_DNR_HUNTING_LINK,
            ...hunterFactsJson
        }, { merge: true });

        logger.info(`Successfully generated and saved hunter facts for ${commonName} (${birdId})`);
        return { ...hunterFactsJson, georgiaDNRHuntingLink: CONFIG.GEORGIA_DNR_HUNTING_LINK };
    } catch (error) {
        logger.error(`Error generating HUNTER facts for ${commonName} (${birdId}):`, error);
        throw new HttpsError("internal", `Failed to generate hunter facts: ${error.message}`);
    }
}

// ======================================================
// HELPER: Get or generate bird facts (lazy, with staleness check)
// ======================================================
async function getOrCreateAndSaveBirdFacts(birdId, commonName) {
    const birdFactsRef = db.collection("birdFacts").doc(birdId);
    const hunterFactsRef = db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId);
    const generationLockRef = db.collection("birdFactsGenerating").doc(birdId);

    const [birdFactsDoc, hunterFactsDoc] = await Promise.all([
        birdFactsRef.get(),
        hunterFactsRef.get()
    ]);

    const currentTime = Date.now();
    const STALE_LOCK_MS = 5 * 60 * 1000; // 5 minutes

    let generalFacts = {};
    let hunterFacts = {};
    let shouldRegenerateGeneral = true;
    let shouldRegenerateHunter = true;

    // --- Staleness checks (unchanged) ---
    if (birdFactsDoc.exists) {
        const data = birdFactsDoc.data();
        if (data.lastGenerated && (currentTime - data.lastGenerated.toDate().getTime()) < CONFIG.FACT_CACHE_LIFETIME_MS) {
            generalFacts = data;
            shouldRegenerateGeneral = false;
        }
    }
    if (hunterFactsDoc.exists) {
        const data = hunterFactsDoc.data();
        if (data.lastGenerated && (currentTime - data.lastGenerated.toDate().getTime()) < CONFIG.FACT_CACHE_LIFETIME_MS) {
            hunterFacts = data;
            shouldRegenerateHunter = false;
        }
    }

    if (!shouldRegenerateGeneral && !shouldRegenerateHunter) {
        return { generalFacts, hunterFacts };
    }

    // --- Acquire generation lock ---
    const lockAcquired = await db.runTransaction(async (t) => {
        const lockDoc = await t.get(generationLockRef);
        if (lockDoc.exists) {
            const startedAt = lockDoc.data().startedAt?.toDate();
            // Allow override if lock is stale (previous invocation crashed)
            if (startedAt && (currentTime - startedAt.getTime()) < STALE_LOCK_MS) {
                return false; // lock held by another active invocation
            }
        }
        t.set(generationLockRef, { startedAt: admin.firestore.FieldValue.serverTimestamp() });
        return true;
    });

    if (!lockAcquired) {
        // Return whatever is cached (even if stale) rather than double-generating
        logger.info(`Facts for ${birdId} are being generated by another instance. Returning cached data.`);
        const staleFacts = birdFactsDoc.exists ? birdFactsDoc.data() : {};
        const staleHunterFacts = hunterFactsDoc.exists ? hunterFactsDoc.data() : {};
        return { generalFacts: staleFacts, hunterFacts: staleHunterFacts };
    }

    try {
        if (shouldRegenerateGeneral) {
            generalFacts = await generateAndSaveBirdFacts(birdId);
        }
        if (shouldRegenerateHunter) {
            hunterFacts = await generateAndSaveHunterFacts(birdId);
        }
    } finally {
        // Always release lock, even on failure
        await generationLockRef.delete().catch(e =>
            logger.warn(`Failed to release generation lock for ${birdId}:`, e)
        );
    }

    return { generalFacts, hunterFacts };
}

function haversineMiles(lat1, lon1, lat2, lon2) {
    const toRad = (deg) => deg * (Math.PI / 180);
    const R = 3958.7613; // Earth radius in miles

    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);

    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(toRad(lat1)) *
        Math.cos(toRad(lat2)) *
        Math.sin(dLon / 2) *
        Math.sin(dLon / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

function timestampToMillis(value) {
    if (!value) return null;
    try {
        if (typeof value.toMillis === "function") return value.toMillis();
        if (value instanceof Date) return value.getTime();
        if (typeof value === "number") return value;
        if (typeof value === "string") {
            const parsed = Date.parse(value);
            return Number.isNaN(parsed) ? null : parsed;
        }
    } catch (error) {
        logger.warn("timestampToMillis failed:", error);
    }
    return null;
}

function createModerationAppealRef(userId, moderationEventId) {
    return db.collection("moderationAppeals").doc(`${userId}_${moderationEventId}`);
}

module.exports = {
  Timestamp,
  FieldValue,
  GoogleAuth,
  defineSecret,
  onDocumentUpdated,
  onDocumentDeleted,
  onDocumentCreated,
  onSchedule,
  onCall,
  HttpsError,
  logger,
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
  buildInitialReportReasonCounts,
  buildInitialModerationFields,
  buildInitialUserModerationFields,
  MODERATION_STATUS_VISIBLE,
  MODERATION_STATUS_UNDER_REVIEW,
  MODERATION_STATUS_HIDDEN,
  MODERATION_STATUS_REMOVED,
  timestampToMillis,
  haversineMiles,
  createModerationAppealRef,
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
};
