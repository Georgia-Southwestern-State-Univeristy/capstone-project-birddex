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

if (admin.apps.length === 0) {
    admin.initializeApp();
}
const db = admin.firestore();
const storage = admin.storage();
const messaging = admin.messaging();

// Secrets
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");
const EBIRD_API_KEY = defineSecret("EBIRD_API_KEY");
const NUTHATCH_API_KEY = defineSecret("NUTHATCH_API_KEY");
const BIRDDEX_MODEL_API_KEY = defineSecret("BIRDDEX_MODEL_API_KEY");

// ======================================================
// CENTRALIZED CONFIG (replaces scattered hard-coded values)
// ======================================================
const CONFIG = {
    GEORGIA_DNR_HUNTING_LINK: "https://georgiawildlife.com/hunting",
    MAX_OPENAI_REQUESTS: 100,
    MAX_PFP_CHANGES: 5,
    COOLDOWN_PERIOD_MS: 24 * 60 * 60 * 1000,       // 24 hours
    FACT_CACHE_LIFETIME_MS: 90 * 24 * 60 * 60 * 1000, // 90 days
    EBIRD_CACHE_TTL_MS: 72 * 60 * 60 * 1000,          // 72 hours
    FORUM_HEATMAP_TTL_MS: 48 * 60 * 60 * 1000,        // 48 hours
    FORUM_ARCHIVE_DAYS_MS: 7 * 24 * 60 * 60 * 1000,   // 7 days
    UNVERIFIED_USER_TTL_MS: 72 * 60 * 60 * 1000,      // 72 hours
    FIRESTORE_BATCH_SIZE: 400,  // Firestore max is 500; using 400 for safety
    LOCATION_PRECISION: 4,      // decimal places (~11 meters)
};

// ======================================================
// HYBRID IDENTIFICATION CONFIG
// ======================================================
const HYBRID_ID_CONFIG = {
    BIRDDEX_MODEL_URL: "https://birddex-api-650774648072.us-central1.run.app/predict-json",
    MODEL_DIRECT_CONFIDENCE_THRESHOLD: 0.70,
    MODEL_DIRECT_MARGIN_THRESHOLD: 0.15,
    MODEL_TIEBREAK_MARGIN_THRESHOLD: 0.10,
    MODEL_FULL_FALLBACK_CONFIDENCE_THRESHOLD: 0.55,
    MODEL_TOP_K: 3,
};
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
function sanitizeText(text, maxLength = 5000) {
    if (!text || typeof text !== "string") return "";
    const trimmed = text.trim();
    if (trimmed.length > maxLength) return trimmed.substring(0, maxLength);
    return trimmed.replace(/<[^>]*>/g, "");
}
// ======================================================
// logfilteredContentAttempt
// ======================================================
// Add this callable to your current updated index.js so client-side blocks can still be logged.
// ======================================================
// logFilteredContentAttempt — client-side blocked content logger
// ======================================================
exports.logFilteredContentAttempt = onCall(async (request) => {
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

function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\$&");
}

function checkBlockedWordMatch(input, target) {
    if (!target || target.length < 3) return false;
    const regex = new RegExp(`\b${escapeRegex(target)}\b`, "i");
    return regex.test(input);
}

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

function isBirdWhitelistMatch(rawInput, blockedWord) {
    const input = String(rawInput || "").toLowerCase();
    return SERVER_BIRD_WHITELIST.some((white) => input.includes(white) && white.includes(blockedWord));
}

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
// HELPER: Location (FIXED race condition — always uses merge)
// ======================================================
async function getOrCreateLocation(latitude, longitude, localityName, db) {
    const fixedLat = latitude.toFixed(CONFIG.LOCATION_PRECISION);
    const fixedLng = longitude.toFixed(CONFIG.LOCATION_PRECISION);
    const locationId = `LOC_${fixedLat}_${fixedLng}`;
    const locationRef = db.collection("locations").doc(locationId);

    const newLocationData = {
        latitude: latitude,
        longitude: longitude,
        country: "US",
        state: "GA",
        locality: localityName || `Lat: ${fixedLat}, Lng: ${fixedLng}`
    };

    // FIXED: Always use set({ merge: true }) to prevent race condition duplicates
    await locationRef.set(newLocationData, { merge: true });
    return locationId;
}

// ======================================================
// HELPER: Delay
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
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
exports.checkUsernameAndEmailAvailability = onCall(async (request) => {
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
exports.initializeUser = onCall(async (request) => {
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
        });

        logger.info(`Successfully initialized user ${uid} with username ${sanitizedUsername}`);
        return { success: true };
    } catch (error) {
        logger.error(`Error initializing user ${uid}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to initialize user.");
    }
});

// ======================================================
// createUserDocument — on Auth signup
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
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
exports.archiveAndDeleteUser = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "The function must be called while authenticated.");

    const uid = request.auth.uid;
    // This is where the function touches Firestore documents/collections for the requested action.
    const userRef = db.collection("users").doc(uid);
    const archiveRef = db.collection("usersdeletedAccounts").doc(uid);
    try {
        const [userDoc, archiveDoc] = await Promise.all([userRef.get(), archiveRef.get()]);

        if (!archiveDoc.exists) {
            if (!userDoc.exists) {
                // Nothing to archive and nothing to delete — already fully cleaned up.
                return { success: true };
            }
            // Atomic: write archive record AND delete source doc together.
            const batch = db.batch();
            batch.set(archiveRef, {
                ...userDoc.data(),
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                originalUid: uid,
                deletionReason: "User requested account deletion"
            });
            batch.delete(userRef);
            const deletedUsername = userDoc.data().username;
                if (deletedUsername) {
                batch.delete(db.collection("usernames").doc(deletedUsername));
                    }
                    await batch.commit();
            logger.info(`Archived and deleted user doc for UID: ${uid}`);
        } else {
            // Archive already written (crash after set, before delete — or duplicate call).
            // Just ensure the source doc is gone.
            if (userDoc.exists) {
                await userRef.delete();
                logger.info(`archiveAndDeleteUser: Archive existed; deleted stale source doc for UID: ${uid}`);
            } else {
                logger.info(`archiveAndDeleteUser: Already fully processed for UID: ${uid}`);
            }
        }
        return { success: true };
    } catch (error) {
        logger.error("Error archiving user:", error);
        throw new HttpsError("internal", `Internal error during account archiving: ${error.message}`);
    }
});

// ======================================================
// HELPER: Hybrid bird identification pipeline helpers
// ======================================================
function parseBirdIdentificationText(identificationText) {
    const safeText = identificationText || "";
    return {
        birdId: safeText.split("ID:")[1]?.split("\n")[0]?.trim() || null,
        commonName: safeText.split("Common Name:")[1]?.split("\n")[0]?.trim() || null,
        scientificName: safeText.split("Scientific Name:")[1]?.split("\n")[0]?.trim() || null,
        species: safeText.split("Species:")[1]?.split("\n")[0]?.trim() || null,
        family: safeText.split("Family:")[1]?.split("\n")[0]?.trim() || null,
    };
}

function buildBirdIdentificationText(birdId, birdData) {
    return `ID: ${birdId}
Common Name: ${birdData.commonName || "Unknown"}
Scientific Name: ${birdData.scientificName || "Unknown"}
Species: ${birdData.species || "Unknown"}
Family: ${birdData.family || "Unknown"}`;
}

async function findBirdByCommonName(commonName) {
    if (!commonName || typeof commonName !== "string") return null;
    const snapshot = await db.collection("birds")
        .where("commonName", "==", commonName.trim())
        .limit(1)
        .get();

    if (snapshot.empty) return null;
    const doc = snapshot.docs[0];
    return normalizeBirdData(doc.data(), doc.id);
}

async function callBirdModelApi(base64Image) {
    const headers = { "Content-Type": "application/json" };
    const internalKey = BIRDDEX_MODEL_API_KEY.value();
    if (internalKey) {
        headers["X-Internal-Api-Key"] = internalKey;
    }

    const response = await axios.post(
        HYBRID_ID_CONFIG.BIRDDEX_MODEL_URL,
        {
            imageBase64: base64Image,
            topK: HYBRID_ID_CONFIG.MODEL_TOP_K,
        },
        {
            headers,
            timeout: 15000,
        }
    );

    const rawPredictions = Array.isArray(response.data?.top_predictions)
        ? response.data.top_predictions
        : [];

    if (!rawPredictions.length) {
        throw new HttpsError("internal", "Bird model returned no predictions.");
    }

    return rawPredictions.map((prediction) => ({
        commonName: prediction.commonName || prediction.species || prediction.label || null,
        confidence: Number(prediction.confidence || 0),
    }));
}

async function callOpenAiBirdTieBreak(base64Image, candidateA, candidateB) {
    const aiResponse = await axios.post(
        "https://api.openai.com/v1/chat/completions",
        {
            model: "gpt-4o",
            messages: [{
                role: "user",
                content: [
                    {
                        type: "text",
                        text: `You are identifying a bird from an image.
If the image contains a dead bird, gore, or graphic violence, respond ONLY with 'GORE'.
Choose ONLY between these two BirdDex candidates and return exactly one winner in this exact format:
ID: [bird_id]
Common Name: [name]
Scientific Name: [name]
Species: [name]
Family: [name]

Candidate 1:
ID: ${candidateA.id}
Common Name: ${candidateA.commonName}
Scientific Name: ${candidateA.scientificName}
Species: ${candidateA.species}
Family: ${candidateA.family}

Candidate 2:
ID: ${candidateB.id}
Common Name: ${candidateB.commonName}
Scientific Name: ${candidateB.scientificName}
Species: ${candidateB.species}
Family: ${candidateB.family}`
                    },
                    {
                        type: "image_url",
                        image_url: {
                            url: `data:image/jpeg;base64,${base64Image}`,
                            detail: "high"
                        }
                    }
                ]
            }],
            max_tokens: 250
        },
        {
            headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
            timeout: 25000
        }
    );

    if (!aiResponse.data?.choices?.[0]) {
        throw new HttpsError("internal", "OpenAI tie-break returned invalid response format.");
    }

    const identification = aiResponse.data.choices[0].message?.content;
    if (!identification) {
        throw new HttpsError("internal", "OpenAI tie-break returned empty response.");
    }

    return identification;
}

async function callOpenAiBirdFullFallback(base64Image) {
    const aiResponse = await axios.post(
        "https://api.openai.com/v1/chat/completions",
        {
            model: "gpt-4o",
            messages: [{
                role: "user",
                content: [
                    {
                        type: "text",
                        text: `Identify the bird in this image. If the image contains a dead bird, gore, or graphic violence, respond ONLY with 'GORE'. Otherwise, respond exactly as:
ID: [ebird_species_code]
Common Name: [name]
Scientific Name: [name]
Species: [name]
Family: [name]`
                    },
                    {
                        type: "image_url",
                        image_url: {
                            url: `data:image/jpeg;base64,${base64Image}`,
                            detail: "high"
                        }
                    }
                ]
            }],
            max_tokens: 300
        },
        {
            headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
            timeout: 25000
        }
    );

    if (!aiResponse.data?.choices?.[0]) {
        throw new HttpsError("internal", "OpenAI returned invalid response format.");
    }

    const identification = aiResponse.data.choices[0].message?.content;
    if (!identification) {
        throw new HttpsError("internal", "OpenAI returned empty response.");
    }

    return identification;
}

async function reserveOpenAiQuota(userRef, eventLogRef, userId) {
    await db.runTransaction(async (transaction) => {
        const eventDoc = await transaction.get(eventLogRef);
        if (eventDoc.exists) {
            throw new HttpsError("aborted", "Identification in progress. Please retry in a moment.");
        }

        const userDoc = await transaction.get(userRef);
        if (!userDoc.exists) throw new HttpsError("not-found", "User document not found.");

        const userData = userDoc.data();
        let currentRequestsRemaining = userData.openAiRequestsRemaining || 0;
        const openAiCooldownResetTimestamp = userData.openAiCooldownResetTimestamp?.toDate() || null;
        const currentTime = new Date();

        if (openAiCooldownResetTimestamp && (currentTime.getTime() - openAiCooldownResetTimestamp.getTime()) >= CONFIG.COOLDOWN_PERIOD_MS) {
            currentRequestsRemaining = CONFIG.MAX_OPENAI_REQUESTS;
        }

        if (currentRequestsRemaining <= 0) {
            throw new HttpsError("resource-exhausted", "AI request limit reached.");
        }

        const updatedOpenAiRequestsRemaining = currentRequestsRemaining - 1;
        let newCooldownTimestamp = openAiCooldownResetTimestamp;
        if (currentRequestsRemaining === CONFIG.MAX_OPENAI_REQUESTS) {
            newCooldownTimestamp = admin.firestore.FieldValue.serverTimestamp();
        }

        transaction.update(userRef, {
            openAiRequestsRemaining: updatedOpenAiRequestsRemaining,
            openAiCooldownResetTimestamp: newCooldownTimestamp,
        });

        transaction.set(eventLogRef, {
            userId,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            pending: true,
        });
    });
}

function normalizeBirdData(birdData, explicitId = null) {
    if (!birdData || typeof birdData !== "object") return null;
    const resolvedId = explicitId || birdData.id || null;
    return {
        id: resolvedId,
        commonName: birdData.commonName || null,
        scientificName: birdData.scientificName || null,
        family: birdData.family || null,
        species: birdData.species || null,
    };
}

async function getBirdById(birdId) {
    if (!birdId || typeof birdId !== "string") return null;
    const doc = await db.collection("birds").doc(birdId.trim()).get();
    if (!doc.exists) return null;
    return normalizeBirdData(doc.data(), doc.id);
}

function buildBirdChoicePayload(birdData, source, extra = {}) {
    const normalized = normalizeBirdData(birdData, birdData?.id || birdData?.birdId || null);
    if (!normalized && !extra?.commonName && !extra?.scientificName) return null;
    return {
        birdId: normalized?.id || birdData?.birdId || null,
        commonName: normalized?.commonName || extra?.commonName || birdData?.commonName || null,
        scientificName: normalized?.scientificName || extra?.scientificName || birdData?.scientificName || null,
        family: normalized?.family || extra?.family || birdData?.family || null,
        species: normalized?.species || extra?.species || birdData?.species || null,
        source: source || null,
        isSupportedInDatabase: extra?.isSupportedInDatabase !== undefined ? !!extra.isSupportedInDatabase : !!(normalized?.id || birdData?.id),
        ...extra,
    };
}

function buildOpenAiLooseChoicePayload(choice, source, extra = {}) {
    if (!choice) return null;
    const commonName = sanitizeText(choice.commonName || choice.name || "", 200).trim() || null;
    const scientificName = sanitizeText(choice.scientificName || "", 200).trim() || null;
    const species = sanitizeText(choice.species || "", 200).trim() || null;
    const family = sanitizeText(choice.family || "", 200).trim() || null;
    const birdId = sanitizeText(choice.birdId || choice.id || "", 200).trim() || null;
    if (!birdId && !commonName && !scientificName) return null;
    return {
        birdId,
        commonName,
        scientificName,
        family,
        species,
        source: source || null,
        isSupportedInDatabase: false,
        ...extra,
    };
}

function buildModelAlternativeChoices(topBirdMatches) {
    return (topBirdMatches || [])
        .slice(1, 3)
        .map((birdData, index) => {
            const normalized = normalizeBirdData(birdData);
            if (!normalized || !normalized.id) return null;
            return buildBirdChoicePayload(normalized, "model_alternative", {
                rank: index + 2,
            });
        })
        .filter(Boolean);
}

function cleanJsonResponseText(rawText) {
    if (!rawText || typeof rawText !== "string") return "";
    return rawText
        .trim()
        .replace(/^```json\s*/i, "")
        .replace(/^```\s*/i, "")
        .replace(/```$/i, "")
        .trim();
}

function parseRankedBirdChoicesJson(rawText) {
    const cleaned = cleanJsonResponseText(rawText);
    if (!cleaned) return [];

    const parsed = JSON.parse(cleaned);
    const rawChoices = Array.isArray(parsed?.choices) ? parsed.choices : [];

    return rawChoices.map((choice) => ({
        birdId: sanitizeText(choice?.birdId || choice?.id || "", 200).trim() || null,
        commonName: sanitizeText(choice?.commonName || choice?.name || "", 200).trim() || null,
        scientificName: sanitizeText(choice?.scientificName || "", 200).trim() || null,
        species: sanitizeText(choice?.species || "", 200).trim() || null,
        family: sanitizeText(choice?.family || "", 200).trim() || null,
    })).filter((choice) => choice.birdId || choice.commonName);
}

async function resolveBirdChoiceToSupportedBird(choice) {
    if (!choice) return null;

    const byId = await getBirdById(choice.birdId);
    if (byId) return byId;

    const byCommonName = await findBirdByCommonName(choice.commonName);
    if (byCommonName) return normalizeBirdData(byCommonName, byCommonName.id || null);

    return null;
}

function normalizeBirdChoiceToken(value) {
    return sanitizeText(String(value || ""), 200).trim().toLowerCase();
}

function buildExcludedBirdChoiceTokens(birds) {
    const excluded = new Set();
    for (const bird of (birds || [])) {
        const normalized = normalizeBirdData(bird, bird?.id || bird?.birdId || null);
        const rawBirdId = bird?.birdId || bird?.id || null;
        const birdId = normalized?.id || rawBirdId;
        const commonName = normalized?.commonName || bird?.commonName || null;

        const birdIdToken = normalizeBirdChoiceToken(birdId);
        const commonNameToken = normalizeBirdChoiceToken(commonName);

        if (birdIdToken) excluded.add(`id:${birdIdToken}`);
        if (commonNameToken) excluded.add(`name:${commonNameToken}`);
    }
    return excluded;
}

async function callOpenAiBirdReviewCandidates(base64Image, candidateA, candidateB, excludedBirds = []) {
    const excludedLines = (excludedBirds || []).map((bird, index) => {
        const normalized = normalizeBirdData(bird, bird?.id || bird?.birdId || null);
        const birdId = normalized?.id || bird?.birdId || bird?.id || "Unknown";
        const commonName = normalized?.commonName || bird?.commonName || "Unknown";
        return `${index + 1}. ID: ${birdId} | Common Name: ${commonName}`;
    }).filter(Boolean);

    const excludedBirdsBlock = excludedLines.length > 0 ? excludedLines.join("\n") : "None";

    const aiResponse = await axios.post(
        "https://api.openai.com/v1/chat/completions",
        {
            model: "gpt-4o",
            messages: [{
                role: "user",
                content: [
                    {
                        type: "text",
                        text: `You are identifying a bird from an image for a BirdDex review flow.
If the image contains a dead bird, gore, or graphic violence, respond ONLY with 'GORE'.
Use the two BirdDex model hints below only as hints. They may be wrong.
Do NOT return any bird that appears in the excluded list.
Do NOT repeat the same bird twice.
Return EXACTLY valid JSON with this schema and nothing else:
{
  "choices": [
    {
      "birdId": "ebird_or_birddex_species_code_or_null",
      "commonName": "bird common name",
      "scientificName": "scientific name",
      "species": "species label",
      "family": "family name"
    }
  ]
}
Return up to 6 ranked choices from most likely to less likely so BirdDex can filter excluded birds and keep two fresh options.
It is OK if a likely bird is not in BirdDex's supported birds collection yet. In that case still return its best common name and scientific name and leave birdId null or blank.

BirdDex model hint 1:
ID: ${candidateA?.id || "Unknown"}
Common Name: ${candidateA?.commonName || "Unknown"}
Scientific Name: ${candidateA?.scientificName || "Unknown"}
Species: ${candidateA?.species || "Unknown"}
Family: ${candidateA?.family || "Unknown"}

BirdDex model hint 2:
ID: ${candidateB?.id || "Unknown"}
Common Name: ${candidateB?.commonName || "Unknown"}
Scientific Name: ${candidateB?.scientificName || "Unknown"}
Species: ${candidateB?.species || "Unknown"}
Family: ${candidateB?.family || "Unknown"}

Excluded birds (never return these):
${excludedBirdsBlock}`
                    },
                    {
                        type: "image_url",
                        image_url: {
                            url: `data:image/jpeg;base64,${base64Image}`,
                            detail: "high",
                        },
                    },
                ],
            }],
            max_tokens: 700,
        },
        {
            headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
            timeout: 25000,
        }
    );

    if (!aiResponse.data?.choices?.[0]) {
        throw new HttpsError("internal", "OpenAI review returned invalid response format.");
    }

    const identification = aiResponse.data.choices[0].message?.content;
    if (!identification) {
        throw new HttpsError("internal", "OpenAI review returned empty response.");
    }

    return identification;
}

async function persistHybridIdentification({
    userId,
    imageUrl,
    locationId,
    modelPredictions,
    topBirdMatches,
    decisionReason,
    modelVersion,
    usedOpenAi,
    openAiMode,
    openAiCandidates,
    openAiRawResponse,
    finalBirdData,
    finalBirdId,
    finalSource,
    isVerified,
}) {
    const identificationLogRef = db.collection("identificationLogs").doc();

    const safeTop1 = modelPredictions[0] || null;
    const safeTop2 = modelPredictions[1] || null;
    const safeTop3 = modelPredictions[2] || null;
    const birdMatch1 = normalizeBirdData(topBirdMatches[0] || null);
    const birdMatch2 = normalizeBirdData(topBirdMatches[1] || null);
    const birdMatch3 = normalizeBirdData(topBirdMatches[2] || null);
    const modelAlternativeChoices = buildModelAlternativeChoices(topBirdMatches);

    const identificationLogData = {
        userId,
        timestamp: admin.firestore.FieldValue.serverTimestamp(),
        imageUrl: imageUrl || "",
        locationId: locationId || null,
        pipelineVersion: "hybrid_v1",
        modelVersion,
        localModel: {
            top1Common: safeTop1?.commonName || null,
            top1BirdId: birdMatch1?.id || null,
            top1Scientific: birdMatch1?.scientificName || null,
            top1Confidence: safeTop1?.confidence ?? null,
            top2Common: safeTop2?.commonName || null,
            top2BirdId: birdMatch2?.id || null,
            top2Scientific: birdMatch2?.scientificName || null,
            top2Confidence: safeTop2?.confidence ?? null,
            top3Common: safeTop3?.commonName || null,
            top3BirdId: birdMatch3?.id || null,
            top3Scientific: birdMatch3?.scientificName || null,
            top3Confidence: safeTop3?.confidence ?? null,
        },
        decision: {
            usedOpenAi,
            decisionReason,
            confidenceThreshold: HYBRID_ID_CONFIG.MODEL_DIRECT_CONFIDENCE_THRESHOLD,
            marginThreshold: HYBRID_ID_CONFIG.MODEL_DIRECT_MARGIN_THRESHOLD,
            tieBreakMarginThreshold: HYBRID_ID_CONFIG.MODEL_TIEBREAK_MARGIN_THRESHOLD,
            lowConfidenceThreshold: HYBRID_ID_CONFIG.MODEL_FULL_FALLBACK_CONFIDENCE_THRESHOLD,
        },
        openAi: {
            used: usedOpenAi,
            mode: openAiMode || null,
            candidate1BirdId: openAiCandidates?.[0]?.id || null,
            candidate1Common: openAiCandidates?.[0]?.commonName || null,
            candidate1Scientific: openAiCandidates?.[0]?.scientificName || null,
            candidate2BirdId: openAiCandidates?.[1]?.id || null,
            candidate2Common: openAiCandidates?.[1]?.commonName || null,
            candidate2Scientific: openAiCandidates?.[1]?.scientificName || null,
            responseText: openAiRawResponse || null,
            reviewRequestedAt: null,
            reviewCandidates: [],
            reviewResponseText: null,
        },
        modelAlternatives: modelAlternativeChoices,
        finalResult: {
            birdId: finalBirdId || null,
            commonName: finalBirdData?.commonName || null,
            scientificName: finalBirdData?.scientificName || null,
            family: finalBirdData?.family || null,
            species: finalBirdData?.species || null,
            source: finalSource,
            verified: isVerified,
        },
        userFeedback: {
            status: "awaiting_user_confirmation",
            confirmedCorrect: null,
            correctedBirdId: null,
            correctedCommonName: null,
            correctedScientificName: null,
            correctedSpecies: null,
            correctedFamily: null,
            correctedSource: null,
            feedbackTimestamp: null,
            initialResultRejectedAt: null,
            modelAlternativesRejectedAt: null,
            lastSelectionAt: null,
            finalConfirmedAt: null,
        },
        training: {
            eligibleForTraining: false,
            labelQuality: usedOpenAi ? (openAiMode === "tiebreak" ? "openai_tiebreak_pending_confirmation" : "openai_full_pending_confirmation") : "model_only_pending_confirmation",
            finalConfirmedBirdId: null,
            finalConfirmedSource: null,
        },
    };

    await identificationLogRef.set(identificationLogData);

    let identificationId = null;
    if (isVerified && finalBirdData && finalBirdId) {
        const identificationRef = db.collection("identifications").doc();
        const identificationData = {
            birdId: finalBirdId,
            commonName: finalBirdData.commonName || null,
            scientificName: finalBirdData.scientificName || null,
            family: finalBirdData.family || null,
            species: finalBirdData.species || null,
            locationId: locationId || null,
            verified: true,
            imageUrl: imageUrl || "",
            timestamp: admin.firestore.FieldValue.serverTimestamp(),
            source: finalSource,
            identificationLogId: identificationLogRef.id,
            modelVersion,
            usedOpenAi,
            pipelineVersion: "hybrid_v1",
            predictedBirdId: finalBirdId,
            predictedCommonName: finalBirdData.commonName || null,
            predictedScientificName: finalBirdData.scientificName || null,
            predictedFamily: finalBirdData.family || null,
            predictedSpecies: finalBirdData.species || null,
            predictedSource: finalSource,
            userConfirmed: false,
            trainingEligible: false,
            finalSelectionSource: null,
            finalConfirmedAt: null,
        };

        await identificationRef.set(identificationData);
        identificationId = identificationRef.id;
        await identificationLogRef.set({ identificationId }, { merge: true });
    }

    return {
        identificationLogId: identificationLogRef.id,
        identificationId,
        modelAlternativeChoices,
    };
}

function buildIdentifyBirdResponse({
    finalIdentification,
    finalBirdData,
    finalSource,
    isVerified,
    isGore,
    identificationLogId,
    identificationId,
    imageUrl,
    modelAlternativeChoices,
    userMessage = null,
}) {
    return {
        result: finalIdentification || null,
        isVerified: !!isVerified,
        isGore: !!isGore,
        isInDatabase: !!isVerified,
        userMessage,
        imageUrl: imageUrl || "",
        identificationLogId: identificationLogId || null,
        identificationId: identificationId || null,
        primaryBird: isVerified && finalBirdData
            ? buildBirdChoicePayload(normalizeBirdData(finalBirdData, finalBirdData.id || null), finalSource || "initial_result")
            : null,
        modelAlternatives: Array.isArray(modelAlternativeChoices) ? modelAlternativeChoices : [],
        openAiAlternatives: [],
    };
}

// ======================================================
// identifyBird (Hybrid local model + OpenAI fallback)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * It also calls an external API, which is where third-party bird/AI/network data enters the
 * backend flow.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.identifyBird = onCall({ secrets: [OPENAI_API_KEY, BIRDDEX_MODEL_API_KEY], timeoutSeconds: 60 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);
    const { image, imageUrl, latitude, longitude, localityName, requestId } = request.data;

    const idempotencyKey = requestId || `IDEN_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedAIEvents").doc(idempotencyKey);

    if (!image || typeof image !== "string") {
        throw new HttpsError("invalid-argument", "Image Base64 data is required.");
    }
    if (typeof latitude !== "number" || typeof longitude !== "number") {
        throw new HttpsError("invalid-argument", "Latitude and longitude are required numbers.");
    }

    try {
        const existingEvent = await eventLogRef.get();
        if (existingEvent.exists) {
            const cached = existingEvent.data().result;
            if (cached !== undefined) {
                logger.info(`identifyBird: Request ${idempotencyKey} already processed. Returning cached result.`);
                return cached;
            }
            throw new HttpsError("aborted", "Identification in progress. Please retry in a moment.");
        }

        const locationId = await getOrCreateLocation(latitude, longitude, localityName, db);
        const modelPredictions = await callBirdModelApi(image);
        const topBirdMatches = await Promise.all(
            modelPredictions.slice(0, 3).map((prediction) => findBirdByCommonName(prediction.commonName))
        );

        const top1 = modelPredictions[0] || null;
        const top2 = modelPredictions[1] || null;
        const top1Bird = normalizeBirdData(topBirdMatches[0] || null);
        const top2Bird = normalizeBirdData(topBirdMatches[1] || null);
        const top1Confidence = top1?.confidence ?? 0;
        const top2Confidence = top2?.confidence ?? 0;
        const topMargin = top1Confidence - top2Confidence;
        const modelVersion = "20260319_071617_best";

        let finalBirdData = null;
        let finalBirdId = null;
        let finalIdentification = null;
        let finalSource = null;
        let isVerified = false;
        let usedOpenAi = false;
        let openAiMode = null;
        let openAiRawResponse = null;
        let openAiCandidates = [];
        let decisionReason = "model_confident";

        const needsFullFallback = !top1Bird || top1Confidence < HYBRID_ID_CONFIG.MODEL_FULL_FALLBACK_CONFIDENCE_THRESHOLD;
        const shouldTieBreak = !!top2Bird && topMargin <= HYBRID_ID_CONFIG.MODEL_TIEBREAK_MARGIN_THRESHOLD;
        const shouldPreferModelDirect = !!top1Bird
            && top1Confidence >= HYBRID_ID_CONFIG.MODEL_DIRECT_CONFIDENCE_THRESHOLD
            && topMargin >= HYBRID_ID_CONFIG.MODEL_DIRECT_MARGIN_THRESHOLD;

        if (shouldPreferModelDirect) {
            finalBirdData = top1Bird;
            finalBirdId = top1Bird.id;
            finalSource = "local_model";
            isVerified = true;
            finalIdentification = buildBirdIdentificationText(finalBirdId, finalBirdData);
        } else {
            usedOpenAi = true;

            if (shouldTieBreak) {
                decisionReason = "top2_close";
                openAiMode = "tiebreak";
                openAiCandidates = [top1Bird, top2Bird].filter(Boolean);
            } else if (needsFullFallback) {
                decisionReason = !top1Bird ? "top1_not_in_supported_birds" : "low_confidence";
                openAiMode = "full_fallback";
            } else {
                decisionReason = "margin_below_direct_threshold";
                openAiMode = top2Bird ? "tiebreak" : "full_fallback";
                openAiCandidates = top2Bird ? [top1Bird, top2Bird].filter(Boolean) : [];
            }

            await reserveOpenAiQuota(userRef, eventLogRef, userId);

            openAiRawResponse = openAiMode === "tiebreak"
                ? await callOpenAiBirdTieBreak(image, openAiCandidates[0], openAiCandidates[1])
                : await callOpenAiBirdFullFallback(image);

            if (openAiRawResponse.includes("GORE")) {
                const goreLogRef = db.collection("identificationLogs").doc();
                await goreLogRef.set({
                    userId,
                    timestamp: admin.firestore.FieldValue.serverTimestamp(),
                    imageUrl: imageUrl || "",
                    locationId: locationId || null,
                    pipelineVersion: "hybrid_v1",
                    modelVersion,
                    decision: {
                        usedOpenAi: true,
                        decisionReason,
                    },
                    openAi: {
                        used: true,
                        mode: openAiMode,
                        responseText: openAiRawResponse,
                    },
                    finalResult: {
                        birdId: null,
                        commonName: null,
                        scientificName: null,
                        family: null,
                        species: null,
                        source: "gore_blocked",
                        verified: false,
                    },
                    training: {
                        eligibleForTraining: false,
                        labelQuality: "blocked_gore",
                    },
                });

                const goreResult = {
                    result: "GORE",
                    isVerified: false,
                    isGore: true,
                    isInDatabase: false,
                    userMessage: "Please take a picture of a non-gore picture of a bird.",
                    identificationLogId: goreLogRef.id,
                    identificationId: null,
                    imageUrl: imageUrl || "",
                    primaryBird: null,
                    modelAlternatives: [],
                    openAiAlternatives: [],
                };
                await eventLogRef.set({
                    userId,
                    processedAt: admin.firestore.FieldValue.serverTimestamp(),
                    pending: false,
                    result: goreResult,
                }, { merge: true });
                return goreResult;
            }

            const parsedOpenAi = parseBirdIdentificationText(openAiRawResponse);
            const matchedBird = await resolveBirdChoiceToSupportedBird(parsedOpenAi);

            if (matchedBird) {
                finalBirdData = matchedBird;
                finalBirdId = matchedBird.id;
                finalSource = openAiMode === "tiebreak" ? "openai_tiebreak" : "openai_full";
                isVerified = true;
                finalIdentification = buildBirdIdentificationText(finalBirdId, finalBirdData);
            } else {
                finalBirdData = null;
                finalBirdId = null;
                finalSource = openAiMode === "tiebreak" ? "openai_tiebreak_unverified" : "openai_full_unverified";
                isVerified = false;
                finalIdentification = null;
            }
        }

        const persisted = await persistHybridIdentification({
            userId,
            imageUrl,
            locationId,
            modelPredictions,
            topBirdMatches,
            decisionReason,
            modelVersion,
            usedOpenAi,
            openAiMode,
            openAiCandidates,
            openAiRawResponse,
            finalBirdData,
            finalBirdId,
            finalSource,
            isVerified,
        });

        const finalResult = isVerified
            ? buildIdentifyBirdResponse({
                finalIdentification,
                finalBirdData,
                finalSource,
                isVerified,
                isGore: false,
                identificationLogId: persisted.identificationLogId,
                identificationId: persisted.identificationId,
                imageUrl,
                modelAlternativeChoices: persisted.modelAlternativeChoices,
            })
            : {
                result: null,
                isVerified: false,
                isGore: false,
                isInDatabase: false,
                userMessage: "Sorry, this bird is not in our database just yet.",
                imageUrl: imageUrl || "",
                identificationLogId: persisted.identificationLogId,
                identificationId: persisted.identificationId,
                primaryBird: null,
                modelAlternatives: persisted.modelAlternativeChoices || [],
                openAiAlternatives: [],
            };

        await eventLogRef.set({
            userId,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            pending: false,
            result: finalResult,
        }, { merge: true });

        return finalResult;
    } catch (error) {
        logger.error("Hybrid identification failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Hybrid identification failed: ${error.message}`);
    }
});

exports.reviewBirdAlternatives = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);
    const { image, imageUrl, identificationLogId, requestId } = request.data || {};

    if (!image || typeof image !== "string") {
        throw new HttpsError("invalid-argument", "Image Base64 data is required.");
    }
    if (!identificationLogId || typeof identificationLogId !== "string") {
        throw new HttpsError("invalid-argument", "identificationLogId is required.");
    }

    const idempotencyKey = requestId || `IDEN_REVIEW_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedAIEvents").doc(`REVIEW_${idempotencyKey}`);

    try {
        const existingEvent = await eventLogRef.get();
        if (existingEvent.exists) {
            const cached = existingEvent.data().result;
            if (cached !== undefined) {
                logger.info(`reviewBirdAlternatives: Request ${idempotencyKey} already processed. Returning cached result.`);
                return cached;
            }
            throw new HttpsError("aborted", "Alternative review already in progress. Please retry in a moment.");
        }

        const identificationLogRef = db.collection("identificationLogs").doc(identificationLogId);
        const identificationLogDoc = await identificationLogRef.get();
        if (!identificationLogDoc.exists) {
            throw new HttpsError("not-found", "Identification log not found.");
        }

        const identificationLogData = identificationLogDoc.data() || {};
        if (identificationLogData.userId !== userId) {
            throw new HttpsError("permission-denied", "You do not have access to this identification log.");
        }

        const top1Bird = await getBirdById(identificationLogData.localModel?.top1BirdId || null);
        const top2Bird = await getBirdById(identificationLogData.localModel?.top2BirdId || null);
        const top3Bird = await getBirdById(identificationLogData.localModel?.top3BirdId || null);

        if (!top1Bird || !top2Bird) {
            throw new HttpsError("failed-precondition", "Not enough BirdDex candidates were available for AI review.");
        }

        const excludedBirds = [top1Bird, top2Bird, top3Bird].filter(Boolean);
        const excludedTokens = buildExcludedBirdChoiceTokens(excludedBirds);

        await reserveOpenAiQuota(userRef, eventLogRef, userId);

        const openAiRawResponse = await callOpenAiBirdReviewCandidates(image, top1Bird, top2Bird, excludedBirds);
        if (openAiRawResponse.includes("GORE")) {
            await identificationLogRef.set({
                openAi: {
                    reviewRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
                    reviewResponseText: openAiRawResponse,
                    reviewCandidates: [],
                    excludedBirdIds: excludedBirds.map((bird) => bird.id || null).filter(Boolean),
                    excludedCommonNames: excludedBirds.map((bird) => bird.commonName || null).filter(Boolean),
                },
                userFeedback: {
                    status: "openai_review_blocked_gore",
                    feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
                },
            }, { merge: true });

            const goreResult = {
                isVerified: false,
                isGore: true,
                isInDatabase: false,
                userMessage: "Please take a picture of a non-gore picture of a bird.",
                openAiAlternatives: [],
                identificationLogId,
            };
            await eventLogRef.set({
                userId,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
                pending: false,
                result: goreResult,
            }, { merge: true });
            return goreResult;
        }

        const parsedChoices = parseRankedBirdChoicesJson(openAiRawResponse);
        const responseChoices = [];
        const seenChoiceTokens = new Set();

        for (const parsedChoice of parsedChoices) {
            const parsedBirdIdToken = normalizeBirdChoiceToken(parsedChoice?.birdId);
            const parsedCommonNameToken = normalizeBirdChoiceToken(parsedChoice?.commonName);
            if ((parsedBirdIdToken && excludedTokens.has(`id:${parsedBirdIdToken}`)) ||
                (parsedCommonNameToken && excludedTokens.has(`name:${parsedCommonNameToken}`))) {
                continue;
            }

            const matchedBird = await resolveBirdChoiceToSupportedBird(parsedChoice);
            let responseChoice = null;

            if (matchedBird && matchedBird.id) {
                const matchedBirdIdToken = normalizeBirdChoiceToken(matchedBird.id);
                const matchedCommonNameToken = normalizeBirdChoiceToken(matchedBird.commonName);
                if ((matchedBirdIdToken && excludedTokens.has(`id:${matchedBirdIdToken}`)) ||
                    (matchedCommonNameToken && excludedTokens.has(`name:${matchedCommonNameToken}`))) {
                    continue;
                }

                const dedupeToken = matchedBirdIdToken || `name:${matchedCommonNameToken}`;
                if (dedupeToken && seenChoiceTokens.has(dedupeToken)) {
                    continue;
                }
                if (dedupeToken) seenChoiceTokens.add(dedupeToken);

                responseChoice = buildBirdChoicePayload(matchedBird, "openai_review", {
                    isSupportedInDatabase: true,
                });
            } else {
                responseChoice = buildOpenAiLooseChoicePayload(parsedChoice, "openai_review_unsupported", {
                    isSupportedInDatabase: false,
                });
                if (!responseChoice) {
                    continue;
                }

                const looseBirdIdToken = normalizeBirdChoiceToken(responseChoice.birdId);
                const looseCommonNameToken = normalizeBirdChoiceToken(responseChoice.commonName);
                if ((looseBirdIdToken && excludedTokens.has(`id:${looseBirdIdToken}`)) ||
                    (looseCommonNameToken && excludedTokens.has(`name:${looseCommonNameToken}`))) {
                    continue;
                }

                const dedupeToken = looseBirdIdToken || `name:${looseCommonNameToken}`;
                if (dedupeToken && seenChoiceTokens.has(dedupeToken)) {
                    continue;
                }
                if (dedupeToken) seenChoiceTokens.add(dedupeToken);
            }

            if (responseChoice) {
                responseChoices.push(responseChoice);
            }
            if (responseChoices.length >= 2) break;
        }

        await identificationLogRef.set({
            openAi: {
                reviewRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewResponseText: openAiRawResponse,
                reviewCandidates: responseChoices,
                excludedBirdIds: excludedBirds.map((bird) => bird.id || null).filter(Boolean),
                excludedCommonNames: excludedBirds.map((bird) => bird.commonName || null).filter(Boolean),
            },
            userFeedback: {
                status: responseChoices.length > 0 ? "openai_review_candidates_ready" : "openai_review_no_candidates",
                feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
            },
        }, { merge: true });

        const response = {
            isVerified: responseChoices.length > 0,
            isGore: false,
            isInDatabase: responseChoices.length > 0,
            userMessage: responseChoices.length > 0 ? null : "Sorry, AI could not produce two more options.",
            openAiAlternatives: responseChoices,
            identificationLogId,
            imageUrl: imageUrl || identificationLogData.imageUrl || "",
        };

        await eventLogRef.set({
            userId,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            pending: false,
            result: response,
        }, { merge: true });

        return response;
    } catch (error) {
        logger.error("Alternative review failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Alternative review failed: ${error.message}`);
    }
});

exports.syncIdentificationFeedback = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const {
        identificationLogId,
        identificationId,
        action,
        selectedBirdId,
        selectionSource,
        note,
        selectedCommonName,
        selectedScientificName,
        selectedSpecies,
        selectedFamily,
    } = request.data || {};

    if (!identificationLogId || typeof identificationLogId !== "string") {
        throw new HttpsError("invalid-argument", "identificationLogId is required.");
    }
    if (!action || typeof action !== "string") {
        throw new HttpsError("invalid-argument", "action is required.");
    }

    const identificationLogRef = db.collection("identificationLogs").doc(identificationLogId);
    const identificationLogDoc = await identificationLogRef.get();
    if (!identificationLogDoc.exists) {
        throw new HttpsError("not-found", "Identification log not found.");
    }

    const identificationLogData = identificationLogDoc.data() || {};
    if (identificationLogData.userId !== userId) {
        throw new HttpsError("permission-denied", "You do not have access to this identification log.");
    }

    let identificationRef = identificationId ? db.collection("identifications").doc(String(identificationId)) : null;
    if (identificationRef) {
        const identificationDoc = await identificationRef.get();
        if (identificationDoc.exists && identificationDoc.data()?.identificationLogId !== identificationLogId) {
            throw new HttpsError("permission-denied", "Identification record does not belong to this log.");
        }
    }

    const updatePayload = {
        userFeedback: {
            feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
            lastAction: action,
            note: typeof note === "string" ? note : null,
        },
    };

    const selectionActions = ["select_model_alternative", "select_openai_alternative", "confirm_final_choice"];
    let selectedBird = null;
    const selectedSource = typeof selectionSource === "string" ? selectionSource : null;
    const normalizedSelectedCommonName = sanitizeText(selectedCommonName || "", 200).trim() || null;
    const normalizedSelectedScientificName = sanitizeText(selectedScientificName || "", 200).trim() || null;
    const normalizedSelectedSpecies = sanitizeText(selectedSpecies || "", 200).trim() || null;
    const normalizedSelectedFamily = sanitizeText(selectedFamily || "", 200).trim() || null;
    const isUnsupportedOpenAiSelection = !selectedBirdId && selectedSource && selectedSource.startsWith("openai_review");

    if (selectionActions.includes(action)) {
        if (selectedBirdId) {
            selectedBird = await getBirdById(selectedBirdId);
        }

        if (selectedBird) {
            updatePayload.userFeedback.correctedBirdId = selectedBird.id;
            updatePayload.userFeedback.correctedCommonName = selectedBird.commonName || null;
            updatePayload.userFeedback.correctedScientificName = selectedBird.scientificName || null;
            updatePayload.userFeedback.correctedSpecies = selectedBird.species || null;
            updatePayload.userFeedback.correctedFamily = selectedBird.family || null;
            updatePayload.userFeedback.correctedIsSupportedInDatabase = true;
        } else if (isUnsupportedOpenAiSelection || action === "reject_initial_result" || action === "discarded") {
            updatePayload.userFeedback.correctedBirdId = selectedBirdId || null;
            updatePayload.userFeedback.correctedCommonName = normalizedSelectedCommonName;
            updatePayload.userFeedback.correctedScientificName = normalizedSelectedScientificName;
            updatePayload.userFeedback.correctedSpecies = normalizedSelectedSpecies;
            updatePayload.userFeedback.correctedFamily = normalizedSelectedFamily;
            updatePayload.userFeedback.correctedIsSupportedInDatabase = false;
        } else if (!selectedBird && (normalizedSelectedCommonName || normalizedSelectedScientificName)) {
            updatePayload.userFeedback.correctedBirdId = selectedBirdId || null;
            updatePayload.userFeedback.correctedCommonName = normalizedSelectedCommonName;
            updatePayload.userFeedback.correctedScientificName = normalizedSelectedScientificName;
            updatePayload.userFeedback.correctedSpecies = normalizedSelectedSpecies;
            updatePayload.userFeedback.correctedFamily = normalizedSelectedFamily;
            updatePayload.userFeedback.correctedIsSupportedInDatabase = false;
        } else if (action !== "reject_initial_result" && action !== "discarded") {
            throw new HttpsError("failed-precondition", "Selected bird data was missing.");
        }

        updatePayload.userFeedback.correctedSource = selectedSource || null;
        updatePayload.userFeedback.lastSelectionAt = admin.firestore.FieldValue.serverTimestamp();
    }

    switch (action) {
    case "reject_initial_result":
        updatePayload.userFeedback.status = "initial_result_rejected";
        updatePayload.userFeedback.confirmedCorrect = false;
        updatePayload.userFeedback.initialResultRejectedAt = admin.firestore.FieldValue.serverTimestamp();
        break;
    case "request_openai_review":
        updatePayload.userFeedback.status = "openai_review_requested";
        updatePayload.userFeedback.modelAlternativesRejectedAt = admin.firestore.FieldValue.serverTimestamp();
        break;
    case "select_model_alternative":
        updatePayload.userFeedback.status = "model_alternative_selected";
        updatePayload.userFeedback.confirmedCorrect = false;
        break;
    case "select_openai_alternative":
        updatePayload.userFeedback.status = selectedBird ? "openai_alternative_selected" : "openai_alternative_selected_unsupported";
        updatePayload.userFeedback.confirmedCorrect = false;
        break;
    case "confirm_final_choice":
        updatePayload.userFeedback.status = "final_choice_confirmed";
        updatePayload.userFeedback.confirmedCorrect = (selectedSource || "initial_result") === "initial_result";
        updatePayload.userFeedback.finalConfirmedAt = admin.firestore.FieldValue.serverTimestamp();
        updatePayload.training = {
            eligibleForTraining: !!selectedBird,
            finalConfirmedBirdId: selectedBird?.id || null,
            finalConfirmedCommonName: selectedBird?.commonName || normalizedSelectedCommonName || null,
            finalConfirmedScientificName: selectedBird?.scientificName || normalizedSelectedScientificName || null,
            finalConfirmedSpecies: selectedBird?.species || normalizedSelectedSpecies || null,
            finalConfirmedFamily: selectedBird?.family || normalizedSelectedFamily || null,
            finalConfirmedSource: selectedSource || null,
            finalConfirmedIsSupportedInDatabase: !!selectedBird,
            labelQuality: selectedBird ? "user_confirmed_supported" : "user_confirmed_unsupported_openai_review",
        };
        break;
    case "retake_after_openai_review":
        updatePayload.userFeedback.status = "retake_after_openai_review";
        break;
    case "discarded":
        updatePayload.userFeedback.status = "discarded";
        break;
    default:
        throw new HttpsError("invalid-argument", "Unsupported feedback action.");
    }

    await identificationLogRef.set(updatePayload, { merge: true });

    if (!identificationRef && action === "confirm_final_choice") {
        identificationRef = db.collection("identifications").doc();
        await identificationRef.set({
            identificationLogId,
            imageUrl: identificationLogData.imageUrl || "",
            locationId: identificationLogData.locationId || null,
            timestamp: admin.firestore.FieldValue.serverTimestamp(),
            source: identificationLogData.finalResult?.source || null,
            modelVersion: identificationLogData.modelVersion || null,
            usedOpenAi: !!identificationLogData.decision?.usedOpenAi,
            pipelineVersion: identificationLogData.pipelineVersion || "hybrid_v1",
            predictedBirdId: identificationLogData.finalResult?.birdId || null,
            predictedCommonName: identificationLogData.finalResult?.commonName || null,
            predictedScientificName: identificationLogData.finalResult?.scientificName || null,
            predictedFamily: identificationLogData.finalResult?.family || null,
            predictedSpecies: identificationLogData.finalResult?.species || null,
            predictedSource: identificationLogData.finalResult?.source || null,
            userConfirmed: false,
            trainingEligible: false,
        }, { merge: true });
        await identificationLogRef.set({ identificationId: identificationRef.id }, { merge: true });
    }

    if (identificationRef && action === "confirm_final_choice") {
        await identificationRef.set({
            birdId: selectedBird?.id || null,
            commonName: selectedBird?.commonName || normalizedSelectedCommonName || null,
            scientificName: selectedBird?.scientificName || normalizedSelectedScientificName || null,
            family: selectedBird?.family || normalizedSelectedFamily || null,
            species: selectedBird?.species || normalizedSelectedSpecies || null,
            verified: !!selectedBird,
            userConfirmed: true,
            trainingEligible: !!selectedBird,
            finalSelectionSource: selectedSource || null,
            finalSelectionSupportedInDatabase: !!selectedBird,
            finalConfirmedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });
    }

    return { success: true };
});

// ======================================================
// recordPfpChange - FIXED: Idempotent version
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.recordPfpChange = onCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
    // This is where the function touches Firestore documents/collections for the requested action.
    const userRef = db.collection("users").doc(userId);
    const { changeId } = request.data;

    // Use changeId for idempotency
    const idempotencyKey = changeId || `PFP_${userId}_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedEvents").doc(idempotencyKey);

    try {
        let finalRemaining = 0;
        await db.runTransaction(async (transaction) => {
            const eventDoc = await transaction.get(eventLogRef);
            const userDoc = await transaction.get(userRef);

            if (!userDoc.exists) throw new HttpsError("not-found", "User document not found.");
            const userData = userDoc.data();

            if (eventDoc.exists) {
                logger.info(`recordPfpChange: Change ${idempotencyKey} already processed.`);
                finalRemaining = userData.pfpChangesToday;
                return;
            }

            let pfpChangesToday = userData.pfpChangesToday || 0;
            const pfpCooldownResetTimestamp = userData.pfpCooldownResetTimestamp?.toDate() || null;
            const currentTime = new Date();

            if (pfpCooldownResetTimestamp && (currentTime.getTime() - pfpCooldownResetTimestamp.getTime()) >= CONFIG.COOLDOWN_PERIOD_MS) {
                pfpChangesToday = CONFIG.MAX_PFP_CHANGES;
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

            transaction.set(eventLogRef, { userId, processedAt: admin.firestore.FieldValue.serverTimestamp() });
        });

        return { success: true, pfpChangesToday: finalRemaining };
    } catch (error) {
        logger.error(`Error recording PFP change for user ${userId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to record profile picture change.");
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
// getGeorgiaBirds (Callable version)
// ======================================================
/**
 * Retrieves data from Firestore or an external service and normalizes it for the caller.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.getGeorgiaBirds = onCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
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
// getBirdDetailsAndFacts (lazy on-demand)
// ======================================================
/**
 * Retrieves data from Firestore or an external service and normalizes it for the caller.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.getBirdDetailsAndFacts = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
    const { birdId } = request.data;
    if (!birdId || typeof birdId !== "string" || birdId.trim().length === 0) {
        throw new HttpsError("invalid-argument", "The birdId field is required and must be a non-empty string.");
    }

    try {
        // This is where the function touches Firestore documents/collections for the requested action.
        const birdDoc = await db.collection("birds").doc(birdId).get();
        if (!birdDoc.exists) throw new HttpsError("not-found", `Bird with ID ${birdId} not found.`);

        const coreBirdData = birdDoc.data();
        const { generalFacts, hunterFacts } = await getOrCreateAndSaveBirdFacts(birdId, coreBirdData.commonName);

        return {
            ...coreBirdData,
            generalFacts,
            hunterFacts: { ...hunterFacts, georgiaDNRHuntingLink: CONFIG.GEORGIA_DNR_HUNTING_LINK }
        };
    } catch (error) {
        logger.error(`Error fetching details for bird ${birdId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to get bird details: ${error.message}`);
    }
});


// ======================================================
// cleanupUserData — on Auth delete (v1)
// FIXED: Parallel processing of forum threads to prevent timeout
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
exports.cleanupUserData = functions.runWith({
    timeoutSeconds: 300,
    memory: "512MB",
}).auth.user().onDelete(async (user) => {
    const uid = user.uid;
    logger.info(`Starting cleanup for user: ${uid}`);

    try {
        // This is where the function touches Firestore documents/collections for the requested action.
        const userRef = admin.firestore().collection("users").doc(uid);
        const userDoc = await userRef.get();

        const threadsSnap = await admin.firestore().collection("forumThreads").where("userId", "==", uid).get();
        logger.info(`Found ${threadsSnap.size} forum threads to archive for user ${uid}`);

        // FIXED: Process threads in parallel with Promise.all() instead of sequentially
        await Promise.all(threadsSnap.docs.map(async (threadDoc) => {
            const threadData = threadDoc.data();
            const threadId = threadDoc.id;

            const commentsSnap = await threadDoc.ref.collection("comments").get();
            const comments = commentsSnap.docs.map(c => ({ id: c.id, ...c.data() }));

            await admin.firestore().collection("deletedforum_backlog").doc(threadId).set({
                ...threadData,
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                originalThreadId: threadId,
                archivedComments: comments
            });

            const postImageUrl = threadData.birdImageUrl || threadData.imageUrl;
            if (postImageUrl && postImageUrl.includes("forum_post_images")) {
                try {
                    const decodedUrl = decodeURIComponent(postImageUrl);
                    const pathStart = decodedUrl.indexOf("/o/") + 3;
                    const pathEnd = decodedUrl.indexOf("?");
                    const oldPath = decodedUrl.substring(pathStart, pathEnd !== -1 ? pathEnd : decodedUrl.length);
                    const newPath = `archive/forum_post_images/${oldPath.split("/").pop()}`;

                    const bucket = admin.storage().bucket();
                    const file = bucket.file(oldPath);
                    const [exists] = await file.exists();
                    if (exists) {
                        await file.copy(bucket.file(newPath));
                        await file.delete();
                    }
                } catch (e) {
                    logger.error(`Image archive failed for post ${threadId}:`, e);
                }
            }

            const threadBatch = admin.firestore().batch();
            commentsSnap.forEach(c => threadBatch.delete(c.ref));
            threadBatch.delete(threadDoc.ref);
            await threadBatch.commit();
        }));

        if (userDoc.exists) {
            const userData = userDoc.data();
            const pfpUrl = userData.profilePictureUrl;

            if (pfpUrl && pfpUrl.includes("firebasestorage.googleapis.com")) {
                try {
                    const decodedUrl = decodeURIComponent(pfpUrl);
                    const filePath = decodedUrl.substring(decodedUrl.indexOf("/o/") + 3, decodedUrl.indexOf("?"));
                    await admin.storage().bucket().file(filePath).delete();
                } catch (e) { logger.error("PFP storage deletion failed", e); }
            }

            await userRef.update({
                username: "Deleted User",
                email: "deleted@user.com",
                profilePictureUrl: "",
                isDeleted: true,
                followerCount: 0,
                followingCount: 0,
                deletedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        }

        // Parallel following/follower cleanup
        const [followingSnap, followersSnap] = await Promise.all([
            userRef.collection("following").get(),
            userRef.collection("followers").get()
        ]);

        await Promise.all([
            ...followingSnap.docs.map(async (doc) => {
                const targetId = doc.id;
                const batch = admin.firestore().batch();
                batch.delete(admin.firestore().collection("users").doc(targetId).collection("followers").doc(uid));
                batch.update(admin.firestore().collection("users").doc(targetId), { followerCount: admin.firestore.FieldValue.increment(-1) });
                batch.delete(doc.ref);
                return batch.commit();
            }),
            ...followersSnap.docs.map(async (doc) => {
                const followerId = doc.id;
                const batch = admin.firestore().batch();
                batch.delete(admin.firestore().collection("users").doc(followerId).collection("following").doc(uid));
                batch.update(admin.firestore().collection("users").doc(followerId), { followingCount: admin.firestore.FieldValue.increment(-1) });
                batch.delete(doc.ref);
                return batch.commit();
            })
        ]);

        const personalCols = ["userBirds", "media", "birdCards"];
        await Promise.all(personalCols.map(async (col) => {
            const snap = await admin.firestore().collection(col).where("userId", "==", uid).get();
            await Promise.all(snap.docs.map(doc => doc.ref.delete()));
        }));

        try {
            await admin.storage().bucket().deleteFiles({ prefix: `userCollectionImages/${uid}/` });
        } catch (e) { logger.error(`Storage wipe failed for ${uid}`, e); }

        const sightingsSnap = await admin.firestore().collection("userBirdSightings").where("userId", "==", uid).get();
        await Promise.all(sightingsSnap.docs.map(doc => doc.ref.update({
            username: "Deleted User",
            imageUrl: "",
            isAnonymized: true
        })));

        logger.info(`Successfully archived and cleaned up deleted user ${uid}`);
    } catch (err) {
        logger.error(`Cleanup failed for user ${uid}:`, err);
    }
});

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
exports.onUserBirdCreated = onDocumentCreated("userBirds/{uploadId}", async (event) => {
    // Read the new userBird document that was just created.
    const userBirdData = event.data.data();

    // userId = who saved/identified the bird
    // birdSpeciesId = which species this bird is
    // awardPoints = whether this save is even allowed to earn points
    // (for example, your gallery-upload flow can set this to false)
    const { userId, birdSpeciesId, awardPoints = true } = userBirdData;

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

    // Default values before we inspect this user's history for this species.
    let isDuplicate = false;          // true if this user already had this species before
    let pointsEarned = 0;             // how many points THIS identification gives
    let pointAwardedAt = null;        // when this identification actually gave a point
    let pointCooldownBlocked = false; // true if it was blocked by the 5-minute cooldown

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
        if (awardPoints) {
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
            } else {
                // Cooldown has passed (or no previous point-award exists),
                // so give exactly 1 point for this identification.
                pointsEarned = 1;
                pointCooldownBlocked = false;
                pointAwardedAt = nowTs;
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
        pointCooldownBlocked
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
exports.triggerEbirdDataFetch = onCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
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
// onDeleteUserBirdImage
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
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
// moderatePfpImage (OpenAI Vision)
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * It also calls an external API, which is where third-party bird/AI/network data enters the
 * backend flow.
 * Input/permission checks happen here first so invalid requests fail before any expensive
 * backend work starts.
 */
exports.moderatePfpImage = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 30 }, async (request) => {
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
// archiveOldForumPosts (disabled)
// ======================================================
/**
 * Forum posts now stay visible indefinitely in the social feed.
 * This scheduled function is intentionally a no-op so older posts are not removed from forumThreads.
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
// IDEMPOTENT Forum Aggregates (Recalculation triggers)
// ======================================================

/**
 * Main backend logic block for this Firebase Functions file.
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
// FIX #8: Both cleanupUserData (v1) and onUserAuthDeleted (v2) fire on auth.user().onDelete
// concurrently.  They both race to archive + delete the same users/{uid} doc.
// Fix: (a) Use a WriteBatch so archive-set and user-delete are atomic.
//      (b) Check usersdeletedAccounts first — if already archived, skip silently.
// ======================================================
/**
 * Main backend logic block for this Firebase Functions file.
 * This code reads/writes Firestore documents, so it is part of the persistent backend state
 * for the app.
 */
exports.onUserAuthDeleted = auth.user().onDelete(async (user) => {
    const uid = user.uid;
    // This is where the function touches Firestore documents/collections for the requested action.
    const userRef = db.collection("users").doc(uid);
    const archiveRef = db.collection("usersdeletedAccounts").doc(uid);
    try {
        // Idempotency: if already archived (by cleanupUserData or a prior retry), skip.
        const [userDoc, archiveDoc] = await Promise.all([userRef.get(), archiveRef.get()]);
        if (archiveDoc.exists) {
            logger.info(`onUserAuthDeleted: UID ${uid} already archived. Skipping.`);
            return;
        }
        if (userDoc.exists) {
            // Atomic: write archive record AND delete source doc in one batch.
            const batch = db.batch();
            batch.set(archiveRef, {
                ...userDoc.data(),
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                deletionType: "Automatic Auth Trigger (Manual or App Deletion)"
            });
            batch.delete(userRef);
            const deletedUsername = userDoc.data().username;
                if (deletedUsername) {
                batch.delete(db.collection("usernames").doc(deletedUsername));
                }
            await batch.commit();
            logger.info(`Automatically archived and cleaned up Firestore for UID: ${uid}`);
        }
    } catch (error) {
        logger.error("Error in onUserAuthDeleted trigger:", error);
    }
});

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
exports.toggleFollow = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const followerId = request.auth.uid;
    const { targetUserId, action } = request.data;

    if (!targetUserId || typeof targetUserId !== "string") {
        throw new HttpsError("invalid-argument", "targetUserId is required.");
    }
    if (action !== "follow" && action !== "unfollow") {
        throw new HttpsError("invalid-argument", "action must be 'follow' or 'unfollow'.");
    }
    if (followerId === targetUserId) {
        throw new HttpsError("invalid-argument", "Cannot follow yourself.");
    }

    // This is where the function touches Firestore documents/collections for the requested action.
    const followerRef = db.collection("users").doc(followerId);
    const targetRef = db.collection("users").doc(targetUserId);
    const followingDocRef = followerRef.collection("following").doc(targetUserId);
    const followerDocRef = targetRef.collection("followers").doc(followerId);

    await db.runTransaction(async (t) => {
        const followingDoc = await t.get(followingDocRef);

        if (action === "follow" && !followingDoc.exists) {
            t.set(followingDocRef, { timestamp: admin.firestore.FieldValue.serverTimestamp() });
            t.set(followerDocRef, { timestamp: admin.firestore.FieldValue.serverTimestamp() });
            t.update(followerRef, { followingCount: admin.firestore.FieldValue.increment(1) });
            t.update(targetRef, { followerCount: admin.firestore.FieldValue.increment(1) });
        } else if (action === "unfollow" && followingDoc.exists) {
            t.delete(followingDocRef);
            t.delete(followerDocRef);
            t.update(followerRef, { followingCount: admin.firestore.FieldValue.increment(-1) });
            t.update(targetRef, { followerCount: admin.firestore.FieldValue.increment(-1) });
        }
    });

    return { success: true };
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
exports.submitReport = onCall(async (request) => {
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
                throw new HttpsError("already-exists", "You already reported this.");
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
exports.getMyModerationState = onCall(async (request) => {
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
exports.submitModerationAppeal = onCall(async (request) => {
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
exports.getPendingModerationAppeals = onCall(async (request) => {
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
exports.getPendingModerationReports = onCall(async (request) => {
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
exports.reviewModerationAppeal = onCall(async (request) => {
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
exports.reviewPendingReport = onCall(async (request) => {
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
exports.getLeaderboard = onCall(async (request) => {
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
exports.triggerArchiveStaleEBirdSightings = onCall(async (request) => {
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
exports.triggerArchiveStaleUserBirdSightings = onCall(async (request) => {
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
exports.recordBirdSighting = onCall(async (request) => {
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
    } = request.data;

    if (!birdId || typeof birdId !== "string") {
        throw new HttpsError("invalid-argument", "birdId is required.");
    }

    const COOLDOWN_MS = 24 * 60 * 60 * 1000; // 24 hours
    const now = Date.now();
    const sightingTimestamp = (typeof clientTimestamp === "number") ? clientTimestamp : now;

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
                return { recorded: false, reason: "cooldown", nextAllowedMs: lastUploadMs + COOLDOWN_MS };
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
                commonName: commonName || "",
                userBirdId: userBirdId || "",
                timestamp: new Date(sightingTimestamp),
                latitude: typeof latitude === "number" ? latitude : 0.0,
                longitude: typeof longitude === "number" ? longitude : 0.0,
                state: state || "",
                locality: locality || "",
                country: country || "US",
                quantity: quantity || "1",
            });

            return { recorded: true };
        });

        logger.info(`recordBirdSighting: userId=${userId} birdId=${birdId} recorded=${result.recorded}`);
        return result;

    } catch (error) {
        logger.error("recordBirdSighting failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to record sighting: ${error.message}`);
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
exports.recordForumPost = onCall(async (request) => {
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

// ======================================================
// Forum write callables — backend-authoritative create/edit paths
// ======================================================
const FORUM_POST_MAX_LENGTH = 500;
const FORUM_COMMENT_MAX_LENGTH = 300;
const FORUM_EDIT_WINDOW_MS = 5 * 60 * 1000;
const FORUM_SUBMISSION_COOLDOWN_MS = 15 * 1000;


const MODERATION_STATUS_VISIBLE = "visible";
const MODERATION_STATUS_UNDER_REVIEW = "under_review";
const MODERATION_STATUS_HIDDEN = "hidden";
const MODERATION_STATUS_REMOVED = "removed";

const REPORT_UNDER_REVIEW_THRESHOLD = 3;
const REPORT_HIDE_THRESHOLD = 5;
const MAX_REPORTS_PER_HOUR = 10;
const MODERATION_APPEAL_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;
const MODERATION_WARNING_EXPIRY_MS = 90 * 24 * 60 * 60 * 1000;
const MODERATION_STRIKE_EXPIRY_MS = 180 * 24 * 60 * 60 * 1000;
const MODERATION_SUSPEND_STAGE_ONE_MS = 24 * 60 * 60 * 1000;
const MODERATION_SUSPEND_STAGE_TWO_MS = 7 * 24 * 60 * 60 * 1000;
const REPORT_RATE_LIMIT_KEY = "forumReportRateLimit";

function buildInitialReportReasonCounts() {
    return {
        language: 0,
        image: 0,
        spam: 0,
        harassment: 0,
        other: 0,
    };
}

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

function buildInitialUserModerationFields() {
    return {
        warningCount: 0,
        strikeCount: 0,
        permanentForumBan: false,
        forumSuspendedUntil: null,
        lastViolationAt: null,
    };
}

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

function isPublicForumStatus(status) {
    const normalized = normalizeModerationStatus(status);
    return normalized === MODERATION_STATUS_VISIBLE || normalized === MODERATION_STATUS_UNDER_REVIEW;
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

function timestampToDate(value) {
    const millis = timestampToMillis(value);
    return millis == null ? null : new Date(millis);
}

function buildModerationRateLimitRef(userId, key = REPORT_RATE_LIMIT_KEY) {
    return db.collection("users").doc(userId).collection("settings").doc(key);
}

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

function createModerationEventRef() {
    return db.collection("moderationEvents").doc();
}

function createModerationAppealRef(userId, moderationEventId) {
    return db.collection("moderationAppeals").doc(`${userId}_${moderationEventId}`);
}

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

function buildUserModerationDefaults(userData = {}) {
    return {
        warningCount: typeof userData.warningCount === "number" ? userData.warningCount : 0,
        strikeCount: typeof userData.strikeCount === "number" ? userData.strikeCount : 0,
        permanentForumBan: userData.permanentForumBan === true,
    };
}

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

function buildForumRestrictionCopy(actionLabel) {
    return `You cannot ${actionLabel} because your forum access is currently restricted.`;
}

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

function isModerationEventCurrentlyActive(eventData, nowMs = Date.now()) {
    if (!eventData || eventData.status !== "active") return false;

    const expiresAtMs = timestampToMillis(eventData.expiresAt);
    if (expiresAtMs != null && expiresAtMs <= nowMs) {
        return false;
    }

    return true;
}

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

function normalizeReportSourceContext(value) {
    const normalized = sanitizeText(value || "", 80).trim().toLowerCase();
    if (!normalized) return null;

    if (normalized === "heatmap" || normalized === "nearby_heatmap") return "heatmap";
    if (normalized === "post_detail" || normalized === "postdetail" || normalized === "post-detail") return "post_detail";
    if (normalized === "forum_feed" || normalized === "forum" || normalized === "forum_fragment") return "forum_feed";
    if (normalized === "profile" || normalized === "user_profile" || normalized === "profile_posts") return "profile";

    return normalized;
}

function buildVisionAccessTokenProjectId() {
    return process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || null;
}

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

function shouldRejectForumImageFromSafeSearch(annotation) {
    if (!annotation || typeof annotation !== "object") return false;

    const adult = String(annotation.adult || "UNKNOWN").toUpperCase();
    const racy = String(annotation.racy || "UNKNOWN").toUpperCase();

    return adult === "VERY_LIKELY" || adult === "LIKELY" || racy === "VERY_LIKELY";
}

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
function getForumSubmissionCooldownRef(userId) {
    return db.collection("users").doc(userId).collection("settings").doc("forumSubmissionCooldown");
}


/**
 * Throws a user-facing cooldown error when the previous forum submission is still too recent.
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
exports.createForumPost = onCall(async (request) => {
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
        await db.runTransaction(async (t) => {
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
        });

        throw new HttpsError(
            "failed-precondition",
            "This image could not be posted because it was flagged as explicit. You can appeal that moderation decision in-app once the moderation screen is wired up."
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
exports.createForumComment = onCall(async (request) => {
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
exports.updateForumPostContent = onCall(async (request) => {
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
exports.updateForumCommentContent = onCall(async (request) => {
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
exports.deleteForumPost = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const data = request.data || {};
    const postId = sanitizeText(data.postId || "", 200).trim();
    if (!postId) {
        throw new HttpsError("invalid-argument", "postId is required.");
    }

    const postRef = db.collection("forumThreads").doc(postId);

    try {
        const postSnap = await postRef.get();
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

        const batch = db.batch();
        for (const commentDoc of commentsSnap.docs) {
            batch.set(db.collection("deletedforum_backlog").doc(), {
                type: "comment_archived_with_post",
                originalId: commentDoc.id,
                postId,
                data: commentDoc.data(),
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            batch.delete(commentDoc.ref);
        }

        batch.set(db.collection("deletedforum_backlog").doc(), {
            type: "post",
            originalId: postId,
            data: {
                ...postData,
                birdImageUrl: archivedImageUrl,
            },
            deletedBy: userId,
            deletedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        batch.delete(postRef);

        await batch.commit();

        logger.info(`deleteForumPost: deleted post ${postId} for user ${userId}`);
        return { success: true, postId, deletedCommentCount: commentsSnap.size };
    } catch (error) {
        logger.error("deleteForumPost failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to delete post: ${error.message}`);
    }
});

/**
 * Deletes a forum comment or reply through the backend so ownership and archival cannot be bypassed.
 */
exports.deleteForumComment = onCall(async (request) => {
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

    try {
        const commentSnap = await commentRef.get();
        if (!commentSnap.exists) {
            throw new HttpsError("not-found", "Comment not found.");
        }

        const commentData = commentSnap.data() || {};
        if (commentData.userId !== userId) {
            throw new HttpsError("permission-denied", "You can only delete your own comments.");
        }

        const isReply = !!commentData.parentCommentId;
        const batch = db.batch();
        let deletedReplyCount = 0;

        if (!isReply) {
            const repliesSnap = await threadRef.collection("comments")
                .where("parentCommentId", "==", commentId)
                .get();

            for (const replyDoc of repliesSnap.docs) {
                batch.set(db.collection("deletedforum_backlog").doc(), {
                    type: "comment_reply",
                    originalId: replyDoc.id,
                    data: replyDoc.data(),
                    deletedBy: userId,
                    deletedAt: admin.firestore.FieldValue.serverTimestamp(),
                });
                batch.delete(replyDoc.ref);
                deletedReplyCount += 1;
            }

            batch.set(db.collection("deletedforum_backlog").doc(), {
                type: "comment",
                originalId: commentId,
                data: commentData,
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            batch.delete(commentRef);
        } else {
            batch.set(db.collection("deletedforum_backlog").doc(), {
                type: "reply",
                originalId: commentId,
                data: commentData,
                deletedBy: userId,
                deletedAt: admin.firestore.FieldValue.serverTimestamp(),
            });
            batch.delete(commentRef);
        }

        await batch.commit();

        logger.info(`deleteForumComment: deleted comment ${commentId} in thread ${threadId} for user ${userId}`);
        return { success: true, commentId, deletedReplyCount };
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
exports.saveForumPost = onCall(async (request) => {
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
exports.getForumPostSaveState = onCall(async (request) => {
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
exports.unsaveForumPost = onCall(async (request) => {
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

exports.upgradeCollectionSlotRarity = onCall(async (request) => {
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
exports.revertCollectionSlotRarity = onCall(async (request) => {
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
