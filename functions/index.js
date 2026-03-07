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

// ======================================================
// CENTRALIZED CONFIG (replaces scattered hard-coded values)
// ======================================================
const CONFIG = {
    GEORGIA_DNR_HUNTING_LINK: "https://georgiawildlife.com/hunting",
    MAX_OPENAI_REQUESTS: 100,
    MAX_PFP_CHANGES: 5,
    COOLDOWN_PERIOD_MS: 24 * 60 * 60 * 1000,       // 24 hours
    FACT_CACHE_LIFETIME_MS: 30 * 24 * 60 * 60 * 1000, // 30 days
    EBIRD_CACHE_TTL_MS: 72 * 60 * 60 * 1000,          // 72 hours
    FORUM_ARCHIVE_DAYS_MS: 7 * 24 * 60 * 60 * 1000,   // 7 days
    UNVERIFIED_USER_TTL_MS: 72 * 60 * 60 * 1000,      // 72 hours
    FIRESTORE_BATCH_SIZE: 400,  // Firestore max is 500; using 400 for safety
    LOCATION_PRECISION: 4,      // decimal places (~11 meters)
};

// ======================================================
// HELPER: Input Sanitization
// ======================================================
function sanitizeUsername(username) {
    if (!username || typeof username !== "string") {
        throw new HttpsError("invalid-argument", "Username must be a non-empty string.");
    }
    const trimmed = username.trim();
    if (trimmed.length < 3 || trimmed.length > 30) {
        throw new HttpsError("invalid-argument", "Username must be between 3 and 30 characters.");
    }
    if (!/^[a-zA-Z0-9_]+$/.test(trimmed)) {
        throw new HttpsError("invalid-argument", "Username can only contain letters, numbers, and underscores.");
    }
    return trimmed;
}

function sanitizeText(text, maxLength = 5000) {
    if (!text || typeof text !== "string") return "";
    const trimmed = text.trim();
    if (trimmed.length > maxLength) return trimmed.substring(0, maxLength);
    return trimmed.replace(/<[^>]*>/g, "");
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
exports.checkUsernameAndEmailAvailability = onCall(async (request) => {
    const { username, email } = request.data;
    const sanitizedUsername = sanitizeUsername(username);

    if (!email || typeof email !== "string" || email.trim().length === 0) {
        throw new HttpsError("invalid-argument", "The email field is required and must be a non-empty string.");
    }

    try {
        const [usernameSnapshot, emailSnapshot] = await Promise.all([
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
exports.initializeUser = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const { username, email } = request.data;
    const uid = request.auth.uid;
    const sanitizedUsername = sanitizeUsername(username);

    try {
        const userRef = db.collection("users").doc(uid);
        const usernameSnapshot = await db.collection("users").where("username", "==", sanitizedUsername).limit(1).get();

        if (!usernameSnapshot.empty && usernameSnapshot.docs[0].id !== uid) {
            throw new HttpsError("already-exists", "Username is already taken.");
        }

        await userRef.set({
            username: sanitizedUsername,
            email: email || request.auth.token.email,
            id: uid,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });

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
exports.createUserDocument = auth.user().onCreate(async (user) => {
    const { uid, email } = user;
    if (!email) {
        logger.error(`createUserDocument: No email found for user ${uid}.`);
        return null;
    }

    try {
        await db.collection("users").doc(uid).set({
            email,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            openAiRequestsRemaining: CONFIG.MAX_OPENAI_REQUESTS,
            pfpChangesToday: CONFIG.MAX_PFP_CHANGES,
            totalBirds: 0,
            duplicateBirds: 0,
            totalPoints: 0,
            notificationsEnabled: true,
            repliesEnabled: true,
            notificationCooldownHours: 2
        }, { merge: true });
        logger.info(`Created user document for ${uid}`);
    } catch (error) {
        logger.error(`Error creating user document for ${uid}:`, error);
    }
    return null;
});

// ======================================================
// archiveAndDeleteUser
// ======================================================
exports.archiveAndDeleteUser = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "The function must be called while authenticated.");

    const uid = request.auth.uid;
    try {
        const userDoc = await db.collection("users").doc(uid).get();
        if (userDoc.exists) {
            await db.collection("usersdeletedAccounts").doc(uid).set({
                ...userDoc.data(),
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                originalUid: uid,
                deletionReason: "User requested account deletion"
            });
            await db.collection("users").doc(uid).delete();
            logger.info(`Archived and deleted user doc for UID: ${uid}`);
        }
        return { success: true };
    } catch (error) {
        logger.error("Error archiving user:", error);
        throw new HttpsError("internal", `Internal error during account archiving: ${error.message}`);
    }
});

// ======================================================
// identifyBird (OpenAI) - FIXED: Idempotent version
// ======================================================
exports.identifyBird = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 30 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);
    const { image, imageUrl, latitude, longitude, localityName, requestId } = request.data;

    // Use requestId if provided for idempotency, otherwise default to a hash of the image URL or Base64 (fallback)
    const idempotencyKey = requestId || `IDEN_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedAIEvents").doc(idempotencyKey);

    if (typeof latitude !== "number" || typeof longitude !== "number") {
        throw new HttpsError("invalid-argument", "Latitude and longitude are required numbers.");
    }

    try {
        // Idempotency check FIRST
        const existingEvent = await eventLogRef.get();
        if (existingEvent.exists) {
            const cached = existingEvent.data().result;
            if (cached !== undefined) {
                logger.info(`identifyBird: Request ${idempotencyKey} already processed. Returning cached result.`);
                return cached;
            }
            // A concurrent request has claimed this key but hasn't finished verification.
            // Throw a retryable error — client should retry in ~2 seconds.
            throw new HttpsError(
                "aborted",
                "Identification in progress. Please retry in a moment."
            );
        }

        // Make OpenAI call FIRST (before deducting quota)
        const aiResponse = await axios.post(
            "https://api.openai.com/v1/chat/completions",
            {
                model: "gpt-4o",
                messages: [{
                    role: "user",
                    content: [
                        { type: "text", text: "Identify the bird in this image. If the image contains a dead bird, gore, or graphic violence, respond ONLY with 'GORE'. Otherwise, respond exactly as:\nID: [ebird_species_code]\nCommon Name: [name]\nScientific Name: [name]\nSpecies: [name]\nFamily: [name]" },
                        { type: "image_url", image_url: { url: `data:image/jpeg;base64,${image}` } }
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

        let identification = aiResponse.data.choices[0].message?.content;
        if (!identification) throw new HttpsError("internal", "OpenAI returned empty response.");

        if (identification.includes("GORE")) {
            logger.warn(`Gore detected in image from user ${userId}. Identification aborted.`);
            return { result: "GORE", isVerified: false, isGore: true };
        }

        // Deduct quota after successful OpenAI call in an idempotent transaction
        let isVerified = false;
        let finalIdentification = identification;

        await db.runTransaction(async (transaction) => {
            // Check again inside transaction
            const eventDoc = await transaction.get(eventLogRef);
            if (eventDoc.exists) return;

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

            // Deduct request
            const updatedOpenAiRequestsRemaining = currentRequestsRemaining - 1;
            let newCooldownTimestamp = openAiCooldownResetTimestamp;
            if (currentRequestsRemaining === CONFIG.MAX_OPENAI_REQUESTS) {
                newCooldownTimestamp = admin.firestore.FieldValue.serverTimestamp();
            }

            transaction.update(userRef, {
                openAiRequestsRemaining: updatedOpenAiRequestsRemaining,
                openAiCooldownResetTimestamp: newCooldownTimestamp,
            });

            // We can't do the external verification lookups easily inside transaction,
            // so we handle the quota and event log here.
            transaction.set(eventLogRef, {
                userId,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
                pending: true,
                // result is written after verification completes (see eventLogRef.update below)
            });
        });

        // --- Post-Quota Logic (Verification & Logging) ---
        const aiBirdId = identification.split("ID:")[1]?.split("\n")[0]?.trim() || null;
        const aiCommonName = identification.split("Common Name:")[1]?.split("\n")[0]?.trim() || null;
        const aiScientificName = identification.split("Scientific Name:")[1]?.split("\n")[0]?.trim() || null;
        const aiSpecies = identification.split("Species:")[1]?.split("\n")[0]?.trim() || null;
        const aiFamily = identification.split("Family:")[1]?.split("\n")[0]?.trim() || null;

        let verifiedBirdData = null;
        let finalBirdId = aiBirdId;

        const [byIdDoc, byNameSnapshot] = await Promise.all([
            db.collection("birds").doc(aiBirdId).get(),
            (aiCommonName && aiScientificName)
                ? db.collection("birds").where("commonName", "==", aiCommonName).where("scientificName", "==", aiScientificName).limit(1).get()
                : Promise.resolve({ empty: true, docs: [] })
        ]);

        if (byIdDoc.exists) {
            isVerified = true;
            verifiedBirdData = byIdDoc.data();
            finalBirdId = verifiedBirdData.id;
        } else if (!byNameSnapshot.empty) {
            isVerified = true;
            verifiedBirdData = byNameSnapshot.docs[0].data();
            finalBirdId = verifiedBirdData.id;
        }

        const locationId = await getOrCreateLocation(latitude, longitude, localityName, db);

        if (isVerified && verifiedBirdData) {
            const identificationData = {
                birdId: finalBirdId,
                commonName: verifiedBirdData.commonName || aiCommonName,
                scientificName: verifiedBirdData.scientificName || aiScientificName,
                family: verifiedBirdData.family || aiFamily,
                species: verifiedBirdData.species || aiSpecies,
                locationId,
                verified: true,
                imageUrl: imageUrl || "",
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            };
            await db.collection("identifications").add(identificationData);
            finalIdentification = `ID: ${finalBirdId}\nCommon Name: ${identificationData.commonName}\nScientific Name: ${identificationData.scientificName}\nSpecies: ${identificationData.species}\nFamily: ${identificationData.family}`;
        } else {
            finalIdentification = `ID: Unknown\n` + identification;
        }

        const finalResult = { result: finalIdentification, isVerified };
        // Update the log with the final result for future retries
        await eventLogRef.update({ result: finalResult });

        return finalResult;
    } catch (error) {
        logger.error("OpenAI identification failed:", error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `OpenAI identification failed: ${error.message}`);
    }
});

// ======================================================
// recordPfpChange - FIXED: Idempotent version
// ======================================================
exports.recordPfpChange = onCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    const userId = request.auth.uid;
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
// ======================================================
async function _syncGeorgiaBirdsCore() {
    logger.info("Executing _syncGeorgiaBirdsCore...");
    const ebirdCacheDocRef = db.collection("ebird_ga_cache").doc("data");

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
    }
}

// ======================================================
// getGeorgiaBirds (Callable version)
// ======================================================
exports.getGeorgiaBirds = onCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Authentication required.");

    try {
        await _syncGeorgiaBirdsCore();

        // Fetch and return the list (to maintain backward compatibility with client expectations)
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
exports.getBirdDetailsAndFacts = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
    const { birdId } = request.data;
    if (!birdId || typeof birdId !== "string" || birdId.trim().length === 0) {
        throw new HttpsError("invalid-argument", "The birdId field is required and must be a non-empty string.");
    }

    try {
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
// searchBirdImage (Nuthatch API)
// ======================================================
exports.searchBirdImage = onCall({ secrets: [NUTHATCH_API_KEY], timeoutSeconds: 15 }, async (request) => {
    try {
        const response = await axios.get(
            `https://nuthatch.lastelm.software/v2/birds?name=${encodeURIComponent(request.data.searchTerm)}&hasImg=true`,
            { headers: { "api-key": NUTHATCH_API_KEY.value() }, timeout: 10000 }
        );
        return { data: response.data };
    } catch (error) {
        logger.error("Nuthatch API failed:", error);
        throw new HttpsError("internal", `Nuthatch API request failed: ${error.message}`);
    }
});

// ======================================================
// cleanupUserData — on Auth delete (v1)
// FIXED: Parallel processing of forum threads to prevent timeout
// ======================================================
exports.cleanupUserData = functions.runWith({
    timeoutSeconds: 300,
    memory: "512MB",
}).auth.user().onDelete(async (user) => {
    const uid = user.uid;
    logger.info(`Starting cleanup for user: ${uid}`);

    try {
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
// ======================================================
exports.onCollectionSlotUpdatedForImageDeletion = onDocumentUpdated("users/{userId}/collectionSlot/{slotId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();
    const { userBirdId } = beforeData;
    const imageUrlBefore = beforeData.imageUrl;
    const imageUrlAfter = afterData.imageUrl;

    if (userBirdId && imageUrlBefore && !imageUrlAfter) {
        logger.info(`Starting cleanup for userBirdId: ${userBirdId}`);
        try {
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
// ======================================================
exports.onUserBirdCreated = onDocumentCreated("userBirds/{uploadId}", async (event) => {
    const userBirdData = event.data.data();
    const { userId, pointsEarned = 0, isDuplicate = false } = userBirdData;
    if (!userId) {
        logger.error("onUserBirdCreated: No userId in document.");
        return null;
    }
    // Use the document ID (uploadId) as the unique event key for idempotency
    await _updateUserTotals(userId, `CREATED_${event.params.uploadId}`, 1, isDuplicate ? 1 : 0, pointsEarned);
    return null;
});

// ======================================================
// onUserBirdDeleted
// ======================================================
exports.onUserBirdDeleted = onDocumentDeleted("userBirds/{uploadId}", async (event) => {
    const userBirdData = event.data.data();
    const { userId, pointsEarned = 0, isDuplicate = false } = userBirdData;
    if (!userId) {
        logger.error("onUserBirdDeleted: No userId in document.");
        return null;
    }
    // Use the document ID (uploadId) as the unique event key for idempotency
    await _updateUserTotals(userId, `DELETED_${event.params.uploadId}`, -1, isDuplicate ? -1 : 0, -pointsEarned);
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

        // Now safe to open a second transaction — outer one has already committed
        if (shouldDeleteAndDecrement) {
            await _updateUserTotals(
                userId,
                `DELETED_USERBIRD_${userBirdRefId}`,
                -1,
                capturedIsDuplicate ? -1 : 0,
                -capturedPointsEarned
            );
        }

// ======================================================
// moderatePfpImage (OpenAI Vision)
// ======================================================
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
                        { type: "image_url", image_url: { url: `data:image/jpeg;base64,${imageBase64}` } }
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
// archiveOldForumPosts (scheduled, every 24h)
// ======================================================
exports.archiveOldForumPosts = onSchedule({
    schedule: "every 24 hours",
    timeZone: "America/New_York",
    timeoutSeconds: 540
}, async (event) => {
    logger.info("Starting scheduled forum post archiving...");
    const archiveThreshold = new Date(Date.now() - CONFIG.FORUM_ARCHIVE_DAYS_MS);
    const storageBucket = admin.storage().bucket();

    try {
        const postsToArchive = await db.collection("forumThreads")
            .where("timestamp", "<", archiveThreshold)
            .limit(500)
            .get();

        if (postsToArchive.empty) {
            logger.info("No old posts found to archive.");
            return null;
        }

        logger.info(`Archiving ${postsToArchive.size} posts...`);

        await Promise.all(postsToArchive.docs.map(async (postDoc) => {
            const postData = postDoc.data();
            const postId = postDoc.id;
            const commentsSnap = await postDoc.ref.collection("comments").get();
            const comments = commentsSnap.docs.map(doc => ({ id: doc.id, ...doc.data() }));

            await storageBucket.file(`archived_posts/${postId}.json`).save(
                JSON.stringify({ ...postData, id: postId, archivedAt: new Date().toISOString(), comments }),
                { contentType: "application/json" }
            );

            const batch = db.batch();
            commentsSnap.forEach(doc => batch.delete(doc.ref));
            batch.delete(postDoc.ref);
            await batch.commit();
        }));

        logger.info("Forum post archiving cycle complete.");
    } catch (error) {
        logger.error("Error during forum post archiving:", error);
    }
    return null;
});

// ======================================================
// IDEMPOTENT Forum Aggregates (Recalculation triggers)
// ======================================================

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

// ======================================================
// onCommentCreated — notify on reply or post activity + IDEMPOTENT COUNT
// ======================================================
exports.onCommentCreated = onDocumentCreated("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const commentData = event.data.data();
    const threadId = event.params.threadId;
    const commentId = event.params.commentId;
    const parentCommentId = commentData.parentCommentId;

    // --- IDEMPOTENT commentCount INCREMENT ---
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
exports.onCommentDeleted = onDocumentDeleted("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const threadId = event.params.threadId;
    const commentId = event.params.commentId;
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
exports.onPostLiked = onDocumentUpdated("forumThreads/{threadId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    if ((afterData.likeCount || 0) <= (beforeData.likeCount || 0) || afterData.likeNotificationSent === true) return null;

    try {
        const recipientDoc = await db.collection("users").doc(afterData.userId).get();
        if (!recipientDoc.exists) return null;

        const { fcmToken, notificationsEnabled = true, notificationCooldownHours = 2 } = recipientDoc.data();
        if (!fcmToken || !notificationsEnabled) return null;

        if (notificationCooldownHours > 0 && afterData.lastViewedAt &&
            (Date.now() - afterData.lastViewedAt.toDate().getTime()) < notificationCooldownHours * 60 * 60 * 1000) return null;

        try {
            await messaging.send({
                token: fcmToken,
                notification: { title: "Post Liked!", body: "Someone liked your post." },
                data: { postId: event.params.threadId, type: "like" }
            });
            await event.data.after.ref.update({ likeNotificationSent: true });
        } catch (error) {
            if (error.code === "messaging/registration-token-not-registered") {
                await db.collection("users").doc(afterData.userId).update({ fcmToken: admin.firestore.FieldValue.delete() });
            }
        }
    } catch (error) { logger.error("Error in onPostLiked:", error); }
    return null;
});

// ======================================================
// onCommentLiked
// ======================================================
exports.onCommentLiked = onDocumentUpdated("forumThreads/{threadId}/comments/{commentId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    if ((afterData.likeCount || 0) <= (beforeData.likeCount || 0) || afterData.likeNotificationSent === true) return null;

    try {
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
            await event.data.after.ref.update({ likeNotificationSent: true });
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
exports.onUserProfileUpdated = onDocumentUpdated({
    document: "users/{userId}",
    timeoutSeconds: 540
}, async (event) => {
    const newData = event.data.after.data();
    const oldData = event.data.before.data();
    const userId = event.params.userId;

    const newPfp = newData.profilePictureUrl || "";
    const oldPfp = oldData.profilePictureUrl || "";
    const newUsername = newData.username || "";
    const oldUsername = oldData.username || "";

    // Delete old PFP from storage if changed
    if (oldPfp && oldPfp !== newPfp && oldPfp.includes("firebasestorage.googleapis.com")) {
        try {
            const decodedUrl = decodeURIComponent(oldPfp);
            const filePath = decodedUrl.substring(decodedUrl.indexOf("/o/") + 3, decodedUrl.indexOf("?"));
            const bucket = admin.storage().bucket();
            const file = bucket.file(filePath);
            const [exists] = await file.exists();
            if (exists) await file.delete();
            logger.info(`Deleted old storage PFP for user ${userId}`);
        } catch (error) {
            logger.error(`Error deleting old PFP for user ${userId}:`, error);
        }
    }

    const updates = {};
    if (newPfp !== oldPfp) updates.userProfilePictureUrl = newPfp;
    if (newUsername !== oldUsername) updates.username = newUsername;

    if (Object.keys(updates).length === 0) {
        logger.info(`No visual profile changes detected for user ${userId}.`);
        return null;
    }

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

    // 1. Update forumThreads (always works)
    try {
        const postsSnap = await db.collection("forumThreads").where("userId", "==", userId).get();
        const postsCount = await processBatch(postsSnap, updates);
        logger.info(`Updated ${postsCount} forum posts for user ${userId}`);
    } catch (e) { logger.error("Failed to update forum posts:", e); }

    // 2. Update userBirdSightings (always works)
    if (newUsername !== oldUsername) {
        try {
            const sightingsSnap = await db.collection("userBirdSightings").where("userId", "==", userId).get();
            const sightingsCount = await processBatch(sightingsSnap, { username: newUsername });
            logger.info(`Updated ${sightingsCount} sightings for user ${userId}`);
        } catch (e) { logger.error("Failed to update sightings:", e); }
    }

    // 3. Update comments (requires collectionGroup index)
    try {
        const commentsSnap = await db.collectionGroup("comments").where("userId", "==", userId).get();
        const commentsCount = await processBatch(commentsSnap, updates);
        logger.info(`Updated ${commentsCount} comments for user ${userId}`);
    } catch (e) {
        logger.warn("Could not update comments - likely missing collectionGroup index:", e.message);
    }

    // 4. Update parentUsername (requires collectionGroup index)
    if (newUsername !== oldUsername) {
        try {
            const repliesSnap = await db.collectionGroup("comments").where("parentUsername", "==", oldUsername).get();
            const repliesCount = await processBatch(repliesSnap, { parentUsername: newUsername });
            logger.info(`Updated ${repliesCount} reply references for user ${userId}`);
        } catch (e) {
            logger.warn("Could not update reply references - likely missing collectionGroup index:", e.message);
        }
    }

    return null;
});

// ======================================================
// onUserAuthDeleted — automatic auth trigger
// ======================================================
exports.onUserAuthDeleted = auth.user().onDelete(async (user) => {
    const uid = user.uid;
    const userRef = db.collection("users").doc(uid);
    try {
        const userDoc = await userRef.get();
        if (userDoc.exists) {
            await db.collection("usersdeletedAccounts").doc(uid).set({
                ...userDoc.data(),
                archivedAt: admin.firestore.FieldValue.serverTimestamp(),
                deletionType: "Automatic Auth Trigger (Manual or App Deletion)"
            });
            await userRef.delete();
            logger.info(`Automatically archived and cleaned up Firestore for UID: ${uid}`);
        }
    } catch (error) {
        logger.error("Error in onUserAuthDeleted trigger:", error);
    }
});

// ======================================================
// NEW: toggleFollow — atomic follow/unfollow
// ======================================================
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
// NEW: submitReport — server-side duplicate check
// ======================================================
exports.submitReport = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required.");

    const { targetId, targetType, reason } = request.data;
    const reporterId = request.auth.uid;

    if (!targetId || !targetType || !reason) {
        throw new HttpsError("invalid-argument", "targetId, targetType, and reason are required.");
    }

    // Deterministic ID: one document per (reporter, target) pair
    const reportId = `${reporterId}_${targetId}`;
    const reportRef = db.collection("reports").doc(reportId);

    try {
        await db.runTransaction(async (t) => {
            const existing = await t.get(reportRef);
            if (existing.exists) {
                throw new HttpsError("already-exists", "You already reported this.");
            }
            t.set(reportRef, {
                reporterId,
                targetId,
                targetType,
                reason: sanitizeText(reason, 500),
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
                status: "pending"
            });
        });
    } catch (error) {
        if (error instanceof HttpsError) throw error;
        logger.error("submitReport transaction failed:", error);
        throw new HttpsError("internal", "Failed to submit report.");
    }

    return { success: true };
});

// ======================================================
// NEW: getLeaderboard — server-side top 20 by points
// ======================================================
exports.getLeaderboard = onCall(async (request) => {
    try {
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
