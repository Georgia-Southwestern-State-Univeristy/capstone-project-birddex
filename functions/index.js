// 1. Use the specific v1 auth import for the cleanup trigger
const auth = require("firebase-functions/v1/auth");

// 2. Use the standard v2 imports for your other functions
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentCreated, onDocumentDeleted, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const axios = require("axios");

admin.initializeApp();
const db = admin.firestore();

// Secrets
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");
const EBIRD_API_KEY = defineSecret("EBIRD_API_KEY");
const NUTHATCH_API_KEY = defineSecret("NUTHATCH_API_KEY");

// Hardcoded Georgia DNR Hunting Regulations link
const GEORGIA_DNR_HUNTING_LINK = "https://georgiawildlife.com/hunting";

// Constants for daily limits
const MAX_OPENAI_REQUESTS = 100;
const MAX_PFP_CHANGES = 5;
const COOLDOWN_PERIOD_MS = 24 * 60 * 60 * 1000; // 24 hours in milliseconds
const FACT_CACHE_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000; // 30 days in milliseconds

// Helper to create or get location document based on lat/lng and an optional locality name
async function getOrCreateLocation(latitude, longitude, localityName, db) {
    // Round to a reasonable precision for location grouping, e.g., 4 decimal places (~11 meters)
    const fixedLat = latitude.toFixed(4);
    const fixedLng = longitude.toFixed(4);
    const locationId = `LOC_${fixedLat}_${fixedLng}`;
    const locationRef = db.collection("locations").doc(locationId);

    const doc = await locationRef.get();
    if (!doc.exists) {
        const newLocationData = {
            latitude: latitude,
            longitude: longitude,
            country: "US", // Placeholder - eBird observation might give this, or use geo-reverse lookup
            state: "GA",   // Placeholder - eBird observation might give this
            locality: localityName || `Lat: ${fixedLat}, Lng: ${fixedLng}` // Use provided localityName or default
        };
        await locationRef.set(newLocationData, { merge: true }); // Merge to preserve any other fields
    } else if (localityName && doc.data().locality !== localityName) {
        // If document exists but locality name is different and provided, update it
        await locationRef.update({ locality: localityName });
    }
    return locationId;
}

// Helper function to add a delay
function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Helper to make an OpenAI API call with retry logic for rate limits.
 * @param {object} params - Parameters for the OpenAI API call.
 * @param {string} params.prompt - The text prompt for the AI.
 * @param {string} params.model - The OpenAI model to use.
 * @param {object} params.response_format - The desired response format.
 * @param {number} params.temperature - Controls creativity vs. factual accuracy.
 * @param {number} params.max_tokens - Maximum tokens for the AI response.
 * @param {string} params.logPrefix - Prefix for logging messages.
 * @param {number} [params.timeout=10000] - Timeout for the axios request in milliseconds (default 10 seconds).
 * @returns {Promise<object>} The parsed JSON response from OpenAI.
 * @throws {Error} If the OpenAI call fails after retries or with a non-rate-limit error.
 */
async function callOpenAIWithRetry({ prompt, model, response_format, temperature, max_tokens, logPrefix, timeout = 10000 }) {
    let retries = 0;
    const maxRetries = 5;
    const initialDelayMs = 1000; // 1 second

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
                    timeout: timeout // Apply timeout here
                }
            );
            return JSON.parse(aiResponse.data.choices[0].message.content);
        } catch (error) {
            if (error.code === 'ECONNABORTED') {
                console.warn(`⚠️ OpenAI call timed out for ${logPrefix}. Retrying... (Attempt ${retries + 1}/${maxRetries})`);
                retries++;
                const delayTime = initialDelayMs * Math.pow(2, retries - 1);
                await delay(delayTime);
                continue;
            }
            if (error.response && error.response.status === 429) {
                retries++;
                const delayTime = initialDelayMs * Math.pow(2, retries - 1); // Exponential backoff
                console.warn(`⚠️ Rate limit hit for ${logPrefix}. Retrying in ${delayTime}ms... (Attempt ${retries}/${maxRetries})`);
                await delay(delayTime);
            } else {
                console.error(`❌ Error calling OpenAI for ${logPrefix}:`, error);
                throw error; // Re-throw other errors immediately
            }
        }
    }
    throw new Error(`Failed to call OpenAI for ${logPrefix} after ${maxRetries} retries due to rate limits or timeouts.`);
}

// Helper function to generate and save GENERAL bird facts using OpenAI
async function generateAndSaveBirdFacts(birdId) {
    const birdDoc = await db.collection("birds").doc(birdId).get();
    if (!birdDoc.exists) {
        console.error(`❌ Bird document with ID ${birdId} not found for general fact generation.`);
        return { birdId: birdId, error: `Bird ${birdId} not found for general facts.` };
    }
    const birdData = birdDoc.data();
    const commonName = birdData.commonName;
    const scientificName = birdData.scientificName;

    console.log(`Generating general facts for ${commonName} (${birdId})...`);

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
  "uniqueBehaviorsSpecific": "Highlight unique behaviors (e.g., woodpeckers’ shock-absorbing skulls, hummingbirds’ hovering).",
  "recordSettingFacts": "Mention any record-setting facts (fastest flyer, longest migration, loudest call).",
  "culturalHistoricalNotes": "Include cultural or historical notes (state bird, local folklore).",
  "conservationStatus": "Describe their status (common, declining, threatened).",
  "howToHelp": "Provide tips on how to help (feeder tips, habitat protection, avoiding window strikes).",
  "threatsInGeorgia": "List specific threats in Georgia (habitat loss, invasive species, climate impacts).",
  "bestAnglesBehaviors": "Tips for capturing photos: best angles or behaviors to photograph.",
  "timesBestLighting": "Tips for capturing photos: times of day with best lighting.",
  "avoidDisturbing": "Tips for capturing photos: how to avoid disturbing the bird."
}
`;

        const factsJson = await callOpenAIWithRetry({
            prompt: prompt,
            model: "gpt-4o",
            response_format: { type: "json_object" },
            temperature: 0.7,
            max_tokens: 1500,
            logPrefix: `GENERAL facts for ${commonName} (${birdId})`,
            timeout: 25000 // OpenAI API call timeout (25 seconds)
        });

        console.log(`Raw GENERAL factsJson from OpenAI for ${commonName} (${birdId}):`, JSON.stringify(factsJson)); // Debug log

        const birdFactsRef = db.collection("birdFacts").doc(birdId);
        await birdFactsRef.set({
            birdId: birdId,
            lastGenerated: admin.firestore.FieldValue.serverTimestamp(),
            ...factsJson
        }, { merge: true }); // Merge to update existing facts or add new ones

        console.log(`✅ Successfully generated and saved general facts for ${commonName} (${birdId})`);
        return factsJson; // Return the generated facts
    } catch (error) {
        console.error(`❌ Error generating or saving GENERAL facts for ${commonName} (${birdId}):`, error);
        return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: error.message };
    }
}

// Helper function to generate and save HUNTER bird facts using OpenAI
async function generateAndSaveHunterFacts(birdId) {
    const birdDoc = await db.collection("birds").doc(birdId).get();
    if (!birdDoc.exists) {
        console.error(`❌ Bird document with ID ${birdId} not found for hunter fact generation.`);
        return { birdId: birdId, error: `Bird ${birdId} not found for hunter facts.` };
    }
    const birdData = birdDoc.data();
    const commonName = birdData.commonName;
    const scientificName = birdData.scientificName;

    console.log(`Generating hunter facts for ${commonName} (${birdId})...`);

    try {
        const prompt = `Generate specific "Hunter Facts" for the ${commonName} (${scientificName}) bird, based on general knowledge of Georgia hunting regulations, referencing official sources like Georgia Department of Natural Resources (DNR) and U.S. Fish and Wildlife Service (USFWS) where applicable. Format the response as a JSON object with the following keys. If a category is not applicable or information is scarce, use "N/A" or "Not readily available." If no specific hunter facts are found, default the legalStatusGeorgia to "N/A - Information not readily available." and the notHuntableStatement to "N/A".

The JSON object should have these keys and corresponding facts:
{
  "legalStatusGeorgia": "Protected species — hunting not permitted", // or "Game species — regulated hunting allowed" or "N/A - Information not readily available."
  "season": "N/A", // e.g., "Fall/Winter (Sept 1 - Jan 31)"
  "licenseRequirements": "N/A", // e.g., "Georgia hunting license, Federal Duck Stamp"
  "federalProtections": "N/A", // e.g., "Federally protected under Migratory Bird Treaty Act"
  "notHuntableStatement": "Protected songbird — illegal to hunt under federal law.", // or N/A
  "isEndangered": "No", // or "Yes"
  "relevantRegulations": "Consult Georgia Department of Natural Resources (DNR) and U.S. Fish and Wildlife Service (USFWS) for specific legal details."
}
`;

        const hunterFactsJson = await callOpenAIWithRetry({
            prompt: prompt,
            model: "gpt-4o",
            response_format: { type: "json_object" },
            temperature: 0.7,
            max_tokens: 1000,
            logPrefix: `HUNTER facts for ${commonName} (${birdId})`,
            timeout: 25000 // OpenAI API call timeout (25 seconds)
        });

        console.log(`Raw HUNTER factsJson from OpenAI for ${commonName} (${birdId}):`, JSON.stringify(hunterFactsJson)); // Debug log

        const hunterFactsRef = db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId); // Subcollection
        await hunterFactsRef.set({
            birdId: birdId,
            lastGenerated: admin.firestore.FieldValue.serverTimestamp(),
            georgiaDNRHuntingLink: GEORGIA_DNR_HUNTING_LINK, // Add the hardcoded link here
            ...hunterFactsJson
        }, { merge: true });

        console.log(`✅ Successfully generated and saved hunter facts for ${commonName} (${birdId})`);
        return { ...hunterFactsJson, georgiaDNRHuntingLink: GEORGIA_DNR_HUNTING_LINK };
    } catch (error) {
        console.error(`❌ Error generating or saving HUNTER facts for ${commonName} (${birdId}):`, error);
        return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: error.message };
    }
}

/**
 * Helper to get or generate general and hunter facts for a bird, with staleness check.
 * @param {string} birdId - The ID of the bird.
 * @param {string} commonName - The common name of the bird.
 * @returns {Promise<{generalFacts: object, hunterFacts: object}>} The general and hunter facts.
 */
async function getOrCreateAndSaveBirdFacts(birdId, commonName) {
    const birdFactsRef = db.collection("birdFacts").doc(birdId);
    const hunterFactsRef = db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId);

    const [birdFactsDoc, hunterFactsDoc] = await Promise.all([
        birdFactsRef.get(),
        hunterFactsRef.get()
    ]);

    let generalFacts = {};
    let hunterFacts = {};
    const currentTime = Date.now();

    // --- General Facts ---
    let shouldRegenerateGeneral = true;
    if (birdFactsDoc.exists) {
        const data = birdFactsDoc.data();
        if (data.lastGenerated && (currentTime - data.lastGenerated.toDate().getTime()) < FACT_CACHE_LIFETIME_MS) {
            generalFacts = data;
            shouldRegenerateGeneral = false;
            console.log(`General facts for ${commonName} (${birdId}) are fresh in Firestore.`);
        } else {
            console.log(`General facts for ${commonName} (${birdId}) are stale. Regenerating...`);
        }
    }

    if (shouldRegenerateGeneral) {
        generalFacts = await generateAndSaveBirdFacts(birdId);
    }

    // --- Hunter Facts ---
    let shouldRegenerateHunter = true;
    if (hunterFactsDoc.exists) {
        const data = hunterFactsDoc.data();
        if (data.lastGenerated && (currentTime - data.lastGenerated.toDate().getTime()) < FACT_CACHE_LIFETIME_MS) {
            hunterFacts = data;
            shouldRegenerateHunter = false;
            console.log(`Hunter facts for ${commonName} (${birdId}) are fresh in Firestore.`);
        } else {
            console.log(`Hunter facts for ${commonName} (${birdId}) are stale. Regenerating...`);
        }
    } else {
        console.log(`Hunter facts do not exist for ${commonName} (${birdId}). Generating...`);
    }

    if (shouldRegenerateHunter) {
        hunterFacts = await generateAndSaveHunterFacts(birdId);
    }

    return { generalFacts, hunterFacts };
}

// ======================================================
// New: checkUsernameAndEmailAvailability Cloud Function
// This function allows checking username and email availability *before* a user signs up.
// It does NOT require the user to be logged in.
// ======================================================
exports.checkUsernameAndEmailAvailability = onCall(async (request) => {
    const { username, email } = request.data;

    // No authentication check needed as per requirement.

    if (!username || typeof username !== 'string' || username.trim().length === 0) {
        throw new HttpsError('invalid-argument', 'The username field is required and must be a non-empty string.');
    }
    if (!email || typeof email !== 'string' || email.trim().length === 0) {
        throw new HttpsError('invalid-argument', 'The email field is required and must be a non-empty string.');
    }

    try {
        // Check username availability
        const usernameSnapshot = await db.collection('users')
                                       .where('username', '==', username)
                                       .limit(1)
                                       .get();
        const isUsernameAvailable = usernameSnapshot.empty;

        // Check email availability in Firestore 'users' collection
        // Note: Firebase Authentication itself ensures email uniqueness during user creation.
        // This check is for an 'email' field potentially stored in a custom 'users' Firestore collection.
        const emailSnapshot = await db.collection('users')
                                       .where('email', '==', email)
                                       .limit(1)
                                       .get();
        const isEmailAvailable = emailSnapshot.empty;

        console.log(`Availability check for username "${username}": ${isUsernameAvailable}, email "${email}": ${isEmailAvailable}`);
        return { isUsernameAvailable: isUsernameAvailable, isEmailAvailable: isEmailAvailable };

    } catch (error) {
        console.error("Error checking username and email availability in Cloud Function:", error);
        throw new HttpsError('internal', 'Unable to check username and email availability.');
    }
});

// ======================================================
// NEW: Create User Document on Firebase Auth Signup
// Triggered when a new user signs up in Firebase Authentication.
// ======================================================
exports.createUserDocument = auth.user().onCreate(async (user) => {
    const uid = user.uid;
    const email = user.email;

    if (!email) {
        console.error(`❌ createUserDocument: No email found for user ${uid}. Cannot create user document.`);
        return null;
    }

    const userRef = db.collection("users").doc(uid);

    try {
        // Note: A username should ideally be collected during app's signup flow
        // and then associated with this UID. For now, we'll use a placeholder or assume it's set later.
        // You might consider passing the username via a custom claim or directly setting it
        // from the client after this function creates the initial document.

        // Check if a document already exists (e.g., if a previous attempt failed but Auth user was created)
        const userDoc = await userRef.get();
        if (userDoc.exists) {
            console.log(`⚠️ User document for ${uid} already exists. Skipping creation.`);
            return null;
        }

        const newUserData = {
            email: email,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            openAiRequestsRemaining: MAX_OPENAI_REQUESTS,
            pfpChangesToday: MAX_PFP_CHANGES,
            totalBirds: 0,
            duplicateBirds: 0,
            totalPoints: 0,
            // 'username' field will need to be populated from the client or another function
            // For now, it's not set here as it's typically provided by the client during registration.
        };
        await userRef.set(newUserData);
        console.log(`✅ Created new user document for user ${uid} with email ${email}.`);
        return null;
    } catch (error) {
        console.error(`❌ Error creating user document for ${uid}:`, error);
        return null;
    }
});


// ======================================================
// 1. Identify Bird (OpenAI)
// Modifying to include OpenAI request limit logic and rolling 24-hour cooldown.
// ======================================================
exports.identifyBird = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 30 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);

    const { image, latitude, longitude, localityName } = request.data;

    if (typeof latitude !== 'number' || typeof longitude !== 'number') {
        throw new HttpsError("invalid-argument", "Latitude and longitude are required and must be numbers.");
    }

    try {
        let updatedOpenAiRequestsRemaining = 0;
        await db.runTransaction(async (transaction) => {
            const userDoc = await transaction.get(userRef);
            if (!userDoc.exists) {
                throw new HttpsError("not-found", "User document not found.");
            }

            const userData = userDoc.data();
            let currentRequestsRemaining = userData.openAiRequestsRemaining || 0;
            const openAiCooldownResetTimestamp = userData.openAiCooldownResetTimestamp ? userData.openAiCooldownResetTimestamp.toDate() : null;
            const currentTime = new Date();

            // Check if cooldown has expired
            if (openAiCooldownResetTimestamp && (currentTime.getTime() - openAiCooldownResetTimestamp.getTime()) >= COOLDOWN_PERIOD_MS) {
                currentRequestsRemaining = MAX_OPENAI_REQUESTS; // Reset to max allowance
                // openAiCooldownResetTimestamp will be set to null for now, and updated on first use
                console.log(`OpenAI request cooldown expired for user ${userId}. Resetting to ${MAX_OPENAI_REQUESTS}.`);
            }

            if (currentRequestsRemaining <= 0) {
                // If requests are 0 and cooldown is still active, throw error
                const remainingCooldownMs = openAiCooldownResetTimestamp ? (openAiCooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS - currentTime.getTime()) : 0;
                throw new HttpsError("resource-exhausted", `You have reached your limit for AI bird identification requests. Try again in ${Math.ceil(remainingCooldownMs / (60 * 1000))} minutes.`);
            }

            updatedOpenAiRequestsRemaining = currentRequestsRemaining - 1;
            let newOpenAiCooldownResetTimestamp = openAiCooldownResetTimestamp;

            // If this is the first request after a reset or when at max, start the cooldown
            if (currentRequestsRemaining === MAX_OPENAI_REQUESTS && updatedOpenAiRequestsRemaining < MAX_OPENAI_REQUESTS) {
                 newOpenAiCooldownResetTimestamp = admin.firestore.FieldValue.serverTimestamp();
                 console.log(`User ${userId} made first OpenAI request for new cooldown cycle. Starting 24-hour cooldown.`);
            }

            transaction.update(userRef, {
                openAiRequestsRemaining: updatedOpenAiRequestsRemaining,
                openAiCooldownResetTimestamp: newOpenAiCooldownResetTimestamp,
            });
            console.log(`User ${userId} used 1 OpenAI request. Remaining: ${updatedOpenAiRequestsRemaining}`);
        });

        // Proceed with OpenAI API call only if transaction was successful
        const aiResponse = await axios.post(
            "https://api.openai.com/v1/chat/completions",
            {
                model: "gpt-4o",
                messages: [{
                    role: "user",
                    content: [
                        { type: "text", text: "Identify the bird in this image. Respond exactly as:\nID: [ebird_species_code]\nCommon Name: [name]\nScientific Name: [name]\nSpecies: [name]\nFamily: [name]" },
                        { type: "image_url", image_url: { url: `data:image/jpeg;base64,${image}` } } // Use the image from request.data
                    ]
                }],
                max_tokens: 300
            },
            {
                headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
                timeout: 25000 // Timeout for the OpenAI API call itself (25 seconds)
            }
        );

        let identification = aiResponse.data.choices[0].message.content;

        const aiBirdId = identification.split("ID:")[1]?.split("\n")[0]?.trim(); // Extract ID from OpenAI response
        const aiCommonName = identification.split("Common Name:")[1]?.split("\n")[0]?.trim();
        const aiScientificName = identification.split("Scientific Name:")[1]?.split("\n")[0]?.trim();
        const aiSpecies = identification.split("Species:")[1]?.split("\n")[0]?.trim();
        const aiFamily = identification.split("Family:")[1]?.split("\n")[0]?.trim();

        let isVerified = false;
        let verifiedBirdData = null;
        let finalBirdId = aiBirdId; // Start with AI's provided ID

        // Try to verify against our static bird data
        // Prioritize verification by the provided ID first, then by names
        if (aiBirdId) {
            const birdsSnapshot = await db.collection("birds")
                                          .doc(aiBirdId)
                                          .get();
            if (birdsSnapshot.exists) {
                isVerified = true;
                verifiedBirdData = birdsSnapshot.data();
                finalBirdId = verifiedBirdData.id; // Use the ID from our database for consistency
            }
        }

        // Fallback verification by names if ID verification failed
        if (!isVerified && aiCommonName && aiScientificName) {
            const birdsSnapshot = await db.collection("birds")
                                          .where("commonName", "==", aiCommonName)
                                          .where("scientificName", "==", aiScientificName)
                                          .limit(1)
                                          .get();
            if (!birdsSnapshot.empty) {
                isVerified = true;
                verifiedBirdData = birdsSnapshot.docs[0].data();
                finalBirdId = verifiedBirdData.id; // Use the ID from our database for consistency
            }
        }

        // Get the location ID for where the image was taken
        const locationId = await getOrCreateLocation(latitude, longitude, localityName, db);

        // Log the identification if it was verified
        if (isVerified && verifiedBirdData) {
            const identificationData = {
                birdId: finalBirdId,
                commonName: verifiedBirdData.commonName || aiCommonName,
                scientificName: verifiedBirdData.scientificName || aiScientificName,
                family: verifiedBirdData.family || aiFamily,
                species: verifiedBirdData.species || aiSpecies,
                locationId: locationId,
                verified: true,
                imageUrl: `data:image/jpeg;base64,${image.substring(0, 100)}...` || "",
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            };
            await db.collection("identifications").add(identificationData);
            console.log(`✅ Logged verified identification for ${identificationData.commonName} (ID: ${finalBirdId})`);

            identification = `ID: ${finalBirdId}\n` +
                             `Common Name: ${identificationData.commonName}\n` +
                             `Scientific Name: ${identificationData.scientificName}\n` +
                             `Species: ${identificationData.species}\n` +
                             `Family: ${identificationData.family}`;
        } else {
             identification = `ID: Unknown\n` + identification;
        }


        return { result: identification, isVerified: isVerified };
    } catch (error) {
        console.error("OpenAI identification failed:", error);
        // If the transaction failed, it might be due to resource exhaustion or other issues.
        // Re-throw the original HttpsError if it came from the transaction.
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "OpenAI identification failed.");
    }
});

// ======================================================
// New: Callable function to record profile picture change
// ======================================================
exports.recordPfpChange = onCall({ timeoutSeconds: 15 }, async (request) => {
    if (!request.auth) {
        throw new HttpsError("unauthenticated", "Authentication required.");
    }

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);

    try {
        let updatedPfpChangesToday = 0;
        await db.runTransaction(async (transaction) => {
            const userDoc = await transaction.get(userRef);
            if (!userDoc.exists) {
                throw new HttpsError("not-found", "User document not found.");
            }

            const userData = userDoc.data();
            let pfpChangesToday = userData.pfpChangesToday || 0;
            const pfpCooldownResetTimestamp = userData.pfpCooldownResetTimestamp ? userData.pfpCooldownResetTimestamp.toDate() : null;
            const currentTime = new Date();

            // Check if cooldown has expired
            if (pfpCooldownResetTimestamp && (currentTime.getTime() - pfpCooldownResetTimestamp.getTime()) >= COOLDOWN_PERIOD_MS) {
                pfpChangesToday = MAX_PFP_CHANGES; // Reset to max allowance
                // pfpCooldownResetTimestamp will be set to null for now, and updated on first use
                console.log(`PFP change cooldown expired for user ${userId}. Resetting to ${MAX_PFP_CHANGES}.`);
            }

            if (pfpChangesToday <= 0) {
                // If requests are 0 and cooldown is still active, throw error
                const remainingCooldownMs = pfpCooldownResetTimestamp ? (pfpCooldownResetTimestamp.getTime() + COOLDOWN_PERIOD_MS - currentTime.getTime()) : 0;
                throw new HttpsError("resource-exhausted", `You have reached your limit for profile picture changes. Try again in ${Math.ceil(remainingCooldownMs / (60 * 1000))} minutes.`);
            }

            updatedPfpChangesToday = pfpChangesToday - 1;
            let newPfpCooldownResetTimestamp = pfpCooldownResetTimestamp;

            // If this is the first request after a reset or when at max, start the cooldown
            if (pfpChangesToday === MAX_PFP_CHANGES && updatedPfpChangesToday < MAX_PFP_CHANGES) {
                newPfpCooldownResetTimestamp = admin.firestore.FieldValue.serverTimestamp();
                console.log(`User ${userId} made first PFP change for new cooldown cycle. Starting 24-hour cooldown.`);
            }

            transaction.update(userRef, {
                pfpChangesToday: updatedPfpChangesToday,
                pfpCooldownResetTimestamp: newPfpCooldownResetTimestamp,
            });
            console.log(`User ${userId} changed PFP. Remaining changes today: ${updatedPfpChangesToday}`);
        });

        return { success: true, pfpChangesToday: updatedPfpChangesToday };

    } catch (error) {
        console.error(`Error recording PFP change for user ${userId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", "Failed to record profile picture change.");
    }
});


// ======================================================
// 2. Fetch Georgia Bird List (eBird Cache) - Now ONLY fetching core bird data
// ======================================================
exports.getGeorgiaBirds = onCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
    const ebirdCacheDocRef = db.collection("ebird_ga_cache").doc("data");
    const seventyTwoHours = 72 * 60 * 60 * 1000;

    try {
        let cachedBirdIds = [];
        const currentCacheDoc = await ebirdCacheDocRef.get();
        if (currentCacheDoc.exists && currentCacheDoc.data().birdIds) {
            cachedBirdIds = currentCacheDoc.data().birdIds;
        }

        let shouldFetchFromEbird = true;
        if (currentCacheDoc.exists && (Date.now() - currentCacheDoc.data().lastUpdated < seventyTwoHours)) {
            console.log("eBird cache is fresh.");
            if (cachedBirdIds.length > 0) {
                shouldFetchFromEbird = false;
            }
        }

        let gaCoreBirds = []; // Renamed from gaDetailedBirds

        if (shouldFetchFromEbird) {
            // --- PHASE 1: Fetch Raw eBird Data ---
            const [codesRes, taxonomyRes, observationsRes] = await Promise.all([
                axios.get("https://api.ebird.org/v2/product/spplist/US-GA", {
                    headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() },
                    timeout: 15000 // eBird API call timeout (15 seconds)
                }),
                axios.get("https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json", {
                    timeout: 15000 // eBird API call timeout (15 seconds)
                }),
                axios.get("https://api.ebird.org/v2/data/obs/US-GA/recent", {
                    headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() },
                    timeout: 15000 // eBird API call timeout (15 seconds)
                })
            ]);

            const gaCodes = codesRes.data;
            const recentObservations = observationsRes.data;

            const lastSeenMap = new Map();
            for (const obs of recentObservations) {
                const observationTimestamp = new Date(obs.obsDt).getTime();
                const speciesCode = obs.speciesCode;

                if (typeof obs.lat === 'number' && typeof obs.lng === 'number' &&
                    (!lastSeenMap.has(speciesCode) || observationTimestamp > lastSeenMap.get(speciesCode).lastSeenTimestampGeorgia)) {
                    lastSeenMap.set(speciesCode, {
                        lastSeenTimestampGeorgia: observationTimestamp,
                        lastSeenLatitudeGeorgia: obs.lat,
                        lastSeenLongitudeGeorgia: obs.lng,
                        obsLocName: obs.locName
                    });
                }
            }

            // Prepare new birds list, including basic info and last seen data placeholders
            const newBirdsFromEbird = taxonomyRes.data
                .filter(bird => gaCodes.includes(bird.speciesCode))
                .map(bird => {
                    const lastSeenData = lastSeenMap.get(bird.speciesCode);
                    return {
                        id: bird.speciesCode,
                        commonName: bird.comName,
                        scientificName: bird.sciName,
                        family: bird.familyComName,
                        species: bird.sciName,
                        isEndangered: false,
                        canHunt: false,
                        lastSeenTimestampGeorgia: lastSeenData ? lastSeenData.lastSeenTimestampGeorgia : null,
                        lastSeenLatitudeGeorgia: lastSeenData ? lastSeenData.lastSeenLatitudeGeorgia : null,
                        lastSeenLongitudeGeorgia: lastSeenData ? lastSeenData.lastSeenLongitudeGeorgia : null,
                        lastSeenLocationIdGeorgia: null // Will be resolved and updated later
                    };
                });

            newBirdsFromEbird.sort((a, b) => a.commonName.localeCompare(b.commonName));
            birdIdsToCache = newBirdsFromEbird.map(bird => bird.id);

            // --- PHASE 2: Update Core 'birds' Collection & Cache Metadata ---
            const batch1 = db.batch();

            // 2.1 Identify removed birds and delete their basic data and facts
            const removedBirdIds = cachedBirdIds.filter(id => !birdIdsToCache.includes(id));
            for (const birdId of removedBirdIds) {
                console.log(`Removing bird: ${birdId}`);
                batch1.delete(db.collection("birds").doc(birdId));
                // Only delete fact documents if they exist. We don't delete them here anymore,
                // as getBirdDetailsAndFacts will handle their lifecycle.
                // We'll trust that if a bird is removed, its facts won't be requested.
            }

            // 2.2 Update/add core bird data to 'birds' collection (excluding dynamic locationId for now)
            for (const bird of newBirdsFromEbird) {
                const birdRef = db.collection("birds").doc(bird.id);
                const birdCoreData = { ...bird };
                delete birdCoreData.lastSeenLocationIdGeorgia; // Don't write this yet, resolve separately
                batch1.set(birdRef, birdCoreData, { merge: true });
            }
            await batch1.commit(); // Commit all bird data updates/deletions
            console.log("✅ Core 'birds' collection updated and removed birds processed.");

            // 2.3 Update the ebird_ga_cache document with metadata (after core birds are updated)
            await ebirdCacheDocRef.set({
                lastUpdated: Date.now(),
                lastUpdatedReadable: new Date().toLocaleString("en-US", { timeZone: "America/New_York" }),
                birdIds: birdIdsToCache
            }, { merge: true });
            console.log("✅ eBird cache metadata updated.");

            // --- PHASE 3: Resolve Location IDs for core birds ---
            const locationPromises = newBirdsFromEbird.map(async (bird) => {
                let updatedLocationId = bird.lastSeenLocationIdGeorgia; // Start with current or null

                // Resolve and update lastSeenLocationIdGeorgia
                if (bird.lastSeenLatitudeGeorgia !== null && bird.lastSeenLongitudeGeorgia !== null) {
                    const lastSeenObservationForBird = lastSeenMap.get(bird.id);
                    const localityName = lastSeenObservationForBird ? lastSeenObservationForBird.obsLocName : null;
                    updatedLocationId = await getOrCreateLocation(
                        bird.lastSeenLatitudeGeorgia,
                        bird.lastSeenLongitudeGeorgia,
                        localityName,
                        db
                    );
                    // Update the 'birds' document with the resolved location ID
                    await db.collection("birds").doc(bird.id).update({
                        lastSeenLocationIdGeorgia: updatedLocationId
                    });
                }
                return { ...bird, lastSeenLocationIdGeorgia: updatedLocationId };
            });

            gaCoreBirds = await Promise.all(locationPromises); // Wait for all dynamic processing
            console.log("✅ All core bird data and location IDs updated.");

        } else {
            // --- If cache is fresh, retrieve birds from local 'birds' collection ---
            const birdsPromises = cachedBirdIds.map(birdId =>
                db.collection("birds").doc(birdId).get().then(birdDoc => {
                    if (birdDoc.exists) {
                        return birdDoc.data();
                    }
                    return null;
                })
            );
            gaCoreBirds = (await Promise.all(birdsPromises)).filter(b => b !== null);
            console.log("✅ Returning cached core bird data.");
        }

        return { birds: gaCoreBirds }; // Only return core bird data
    } catch (error) {
        console.error("❌ Error fetching or processing eBird data:", error);
        throw new HttpsError("internal", `eBird data processing failed: ${error.message}`);
    }
});

// ======================================================
// NEW: Callable function to get detailed bird facts (on demand)
// ======================================================
exports.getBirdDetailsAndFacts = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
    const { birdId } = request.data;

    if (!birdId || typeof birdId !== 'string' || birdId.trim().length === 0) {
        throw new HttpsError('invalid-argument', 'The birdId field is required and must be a non-empty string.');
    }

    try {
        // 1. Fetch core bird data
        const birdDoc = await db.collection("birds").doc(birdId).get();
        if (!birdDoc.exists) {
            throw new HttpsError('not-found', `Bird with ID ${birdId} not found.`);
        }
        const coreBirdData = birdDoc.data();

        // 2. Get or generate facts using the helper
        const { generalFacts, hunterFacts } = await getOrCreateAndSaveBirdFacts(birdId, coreBirdData.commonName);

        // 3. Combine and return all data
        return {
            ...coreBirdData,
            generalFacts: generalFacts,
            hunterFacts: {
                ...hunterFacts,
                georgiaDNRHuntingLink: GEORGIA_DNR_HUNTING_LINK // Ensure link is always included with hunter facts
            }
        };

    } catch (error) {
        console.error(`❌ Error fetching or generating details for bird ${birdId}:`, error);
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", `Failed to get bird details: ${error.message}`);
    }
});


// ======================================================
// 3. Search Bird Image (Nuthatch API)
// ======================================================
exports.searchBirdImage = onCall({ secrets: [NUTHATCH_API_KEY], timeoutSeconds: 15 }, async (request) => {
    try {
        const response = await axios.get(
            `https://nuthatch.lastelm.software/v2/birds?name=${encodeURIComponent(request.data.searchTerm)}&hasImg=true`,
            {
                headers: { "api-key": NUTHATCH_API_KEY.value() },
                timeout: 10000 // Nuthatch API call timeout (10 seconds)
            }
        );
        return { data: response.data };
    } catch (error) {
        console.error("Nuthatch failed:", error);
        throw new HttpsError("internal", "Nuthatch failed.");
    }
});

// ======================================================
// 4. AUTOMATIC USER DATA CLEANUP (ENTIRE ACCOUNT)
//=======================================================
// Using the v1 auth module directly ensures 'user' is never undefined.
exports.cleanupUserData = auth.user().onDelete(async (user) => {
    const uid = user.uid;
    console.log(`🧹 Starting cleanup for user account: ${uid}`);

    const batch = db.batch();

    try {
        // 1. Delete user profile
        batch.delete(db.collection("users").doc(uid));

        // 2. Delete collectionSlot subcollection
        const slotSnap = await db.collection("users").doc(uid).collection("collectionSlot").get();
        slotSnap.forEach(doc => batch.delete(doc.ref));

        // 3. Delete from related global collections
        const collections = ["userBirds", "media", "birdCards", "userBirdSightings"]; // Added userBirdSightings
        for (const col of collections) {
            const snap = await db.collection(col).where("userId", "==", uid).get();
            snap.forEach(doc => batch.delete(doc.ref));
        }

        // Additional cleanup for specific relationships (if not handled by generic collections array)
        // Delete Identification documents related to the user
        const identificationsSnap = await db.collection("identifications").where("userId", "==", uid).get();
        identificationsSnap.forEach(doc => batch.delete(doc.ref));

        // Delete Threads and their Posts (assuming userId in Thread and Post)
        const threadsSnap = await db.collection("threads").where("userId", "==", uid).get();
        const threadIdsToDelete = [];
        threadsSnap.forEach(doc => {
            threadIdsToDelete.push(doc.id);
            batch.delete(doc.ref);
        });

        for (const threadId of threadIdsToDelete) {
            const postsSnap = await db.collection("threads").doc(threadId).collection("posts").get();
            postsSnap.forEach(doc => batch.delete(doc.ref));
        }

        // Delete Reports (if they have a userId field and are owned by the user) -
        const reportsSnap = await db.collection("reports").where("userId", "==", uid).get();
        reportsSnap.forEach(doc => batch.delete(doc.ref));

        // Locations are shared, so we don't delete them here unless completely unreferenced (more complex logic).

        await batch.commit();
        console.log(`✅ Successfully deleted all data for user account ${uid}`);
        return null;
    } catch (err) {
        console.error(`❌ Cleanup failed for user account ${uid}:`, err);
        return null;
    }
});


// ======================================================
// 5. AUTOMATIC SIGHTING CLEANUP (SINGLE ITEM) - v2 Syntax
//    Triggered when imageUrl is deleted from a collectionSlot document
// ======================================================
/**
 * Triggers when the 'imageUrl' field is deleted (removed) from a user's collectionSlot document.
 * It then cleans up all related data (userBirds).
 */
exports.onCollectionSlotUpdatedForImageDeletion = onDocumentUpdated("users/{userId}/collectionSlot/{slotId}", async (event) => {
    const beforeData = event.data.before.data();
    const afterData = event.data.after.data();

    const userBirdId = beforeData.userBirdId; // Get userBirdId from the state BEFORE deletion
    const imageUrlBefore = beforeData.imageUrl;
    const imageUrlAfter = afterData.imageUrl;

    // Check if imageUrl existed before and is now deleted (or null/undefined/empty string)
    if (userBirdId && imageUrlBefore && !imageUrlAfter) {
        console.log(`🧹 Starting cleanup for userBirdId: ${userBirdId} due to imageUrl deletion.`);
        const batch = db.batch();

        try {
            // 1. Delete the UserBird document
            const userBirdRef = db.collection("userBirds").doc(userBirdId);
            batch.delete(userBirdRef);

            // Removed: Deletion of userBirdSightings as per user's request.
            // userBirdSightings will remain to consolidate location data.

            // Commit all deletions
            await batch.commit();
            console.log(`✅ Successfully cleaned up data for userBirdId: ${userBirdId} after imageUrl deletion.`);
            return null;

        } catch (err) {
            console.error(`❌ Failed to cleanup data for userBirdId: ${userBirdId} after imageUrl deletion.`, err);
            return null;
        }
    } else {
        console.log(`No imageUrl deletion detected for collectionSlot ${event.params.slotId} or no userBirdId present.`);
        return null;
    }
});

/**
 * Helper function to update user totals in a Firestore transaction.
 * @param {string} userId - The ID of the user.
 * @param {number} totalBirdsChange - Change in totalBirds (e.g., 1 for creation, -1 for deletion)._
 * @param {number} duplicateBirdsChange - Change in duplicateBirds (e.g., 1 for duplicate creation, -1 for duplicate deletion)._
 * @param {number} totalPointsChange - Change in totalPoints.
 */
async function _updateUserTotals(userId, totalBirdsChange, duplicateBirdsChange, totalPointsChange) {
    const userRef = db.collection("users").doc(userId);

    try {
        await db.runTransaction(async (transaction) => {
            const userDoc = await transaction.get(userRef);
            if (!userDoc.exists) {
                console.error(`❌ _updateUserTotals: User document ${userId} not found.`);
                return;
            }

            const userData = userDoc.data();
            const currentTotalBirds = userData.totalBirds || 0;
            const currentDuplicateBirds = userData.duplicateBirds || 0;
            const currentTotalPoints = userData.totalPoints || 0;

            transaction.update(userRef, {
                totalBirds: Math.max(0, currentTotalBirds + totalBirdsChange),
                duplicateBirds: Math.max(0, currentDuplicateBirds + duplicateBirdsChange),
                totalPoints: Math.max(0, currentTotalPoints + totalPointsChange),
            });
        });
        console.log(`✅ Successfully updated totals for user ${userId}.`);
    } catch (error) {
        console.error(`❌ Failed to update totals for user ${userId}:`, error);
    }
}

// ======================================================
// ON CREATE UserBird -> Update User Totals
// ======================================================
exports.onUserBirdCreated = onDocumentCreated("userBirds/{uploadId}", async (event) => {
    const userBirdData = event.data.data();
    const userId = userBirdData.userId;

    if (!userId) {
        console.error("❌ onUserBirdCreated: No userId found in created userBird document.");
        return null;
    }

    const pointsEarned = userBirdData.pointsEarned || 0;
    const isDuplicate = userBirdData.isDuplicate || false;

    console.log(`⬆️ onUserBirdCreated: Processing new userBird for user ${userId}. Points: ${pointsEarned}, Duplicate: ${isDuplicate}`);

    await _updateUserTotals(userId, 1, isDuplicate ? 1 : 0, pointsEarned);
    return null;
});

// ======================================================
// NEW: ON DELETE UserBird -> Reverse User Totals
// ======================================================
exports.onUserBirdDeleted = onDocumentDeleted("userBirds/{uploadId}", async (event) => {
    const userBirdData = event.data.data(); // This is the data *before* deletion
    const userId = userBirdData.userId;

    if (!userId) {
        console.error("❌ onUserBirdDeleted: No userId found in deleted userBird document.");
        return null;
    }

    const pointsEarned = userBirdData.pointsEarned || 0;
    const isDuplicate = userBirdData.isDuplicate || false;

    console.log(`⬇️ onUserBirdDeleted: Processing deleted userBird for user ${userId}. Points: ${pointsEarned}, Duplicate: ${isDuplicate}`);

    await _updateUserTotals(userId, -1, isDuplicate ? -1 : 0, -pointsEarned);
    return null;
});


// --- CORE EBIRD FETCH/STORE LOGIC (for both scheduled & callable functions) ---
async function _fetchAndStoreEBirdDataCore() {
    console.log("Executing _fetchAndStoreEBirdDataCore...");

    // 1. Specify the region code for the area you want to query.
    const REGION_CODE = "US-GA";
    // 2. Construct the eBird API endpoint URL for recent notable observations.
    const ebirdApiUrl = `https://api.ebird.org/v2/data/obs/${REGION_CODE}/recent/notable`;

    try {
        // --- Make the API Call ---
        const response = await axios.get(ebirdApiUrl, {
            headers: {
                "X-eBirdApiToken": EBIRD_API_KEY.value(),
            },
            timeout: 15000 // eBird API call timeout (15 seconds)
        });

        const ebirdSightings = response.data;
        if (!ebirdSightings || ebirdSightings.length === 0) {
            console.log("No new eBird sightings found.");
            return { status: "success", message: "No new eBird sightings found." };
        }

        console.log(`Found ${ebirdSightings.length} sightings from eBird API.`);
        const batch = db.batch();
        let sightingsAdded = 0;

        // --- Process and Store the Data ---
        for (const sighting of ebirdSightings) {
            // Use the eBird sighting ID as our document ID to prevent duplicates
            const docId = sighting.subId;
            if (!docId) continue; // Skip if there's no unique ID

            const docRef = db.collection("eBirdApiSightings").doc(docId);

            // Create a new document with the data you need for the map
            const newSighting = {
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
            };

            batch.set(docRef, newSighting);
            sightingsAdded++;
        }

        // Commit all the new sightings to Firestore in one batch
        if (sightingsAdded > 0) {
            await batch.commit();
            console.log(`✅ Successfully added or updated ${sightingsAdded} eBird sightings.`);
        } else {
            console.log("No valid sightings to add to the batch.");
        }
        return { status: "success", message: `Successfully added or updated ${sightingsAdded} eBird sightings.` };
    } catch (error) {
        console.error("❌ Error fetching or storing eBird data:", error);
        throw new HttpsError("internal", `eBird data processing failed: ${error.message}`);
    }
}

// ======================================================
// SCHEDULED EBIRD SIGHTING FETCH (Calls the core logic)
// ======================================================
/**
 * A scheduled Cloud Function to fetch recent eBird sightings for a specific region
 * and store them in the 'eBirdApiSightings' collection.
 * This function now calls the _fetchAndStoreEBirdDataCore helper.
 */
exports.fetchAndStoreEBirdData = onSchedule({
    schedule: "every 72 hours",
    secrets: [EBIRD_API_KEY],
    timeoutSeconds: 300 // 5 minutes for scheduled eBird fetch
}, async (event) => {
    console.log("Scheduled function: Starting eBird data fetch.");
    try {
        await _fetchAndStoreEBirdDataCore();
        console.log("Scheduled function: eBird data fetch completed successfully.");
        return null;
    } catch (error) {
        console.error("Scheduled function: Error during eBird data fetch:", error);
        return null;
    }
});


// ======================================================
// CALLABLE EBIRD SIGHTING FETCH (for app warmup)
// ======================================================
/**
 * A callable Cloud Function that the Android app can invoke on warmup
 * to trigger an immediate fetch and store of eBird sightings.
 * This function also calls the _fetchAndStoreEBirdDataCore helper.
 */
exports.triggerEbirdDataFetch = onCall({ secrets: [EBIRD_API_KEY], timeoutSeconds: 60 }, async (request) => {
    console.log("Callable function: Triggered eBird data fetch from app.");
    try {
        // You can add an optional authentication check here if only logged-in users should trigger this.
        // if (!request.auth) {
        //     throw new HttpsError("unauthenticated", "Authentication required to trigger eBird data fetch.");
        // }

        const result = await _fetchAndStoreEBirdDataCore();
        console.log("Callable function: eBird data fetch completed successfully.");
        return result; // Return the result/message from the core function
    } catch (error) {
        console.error("Callable function: Error during eBird data fetch:", error);
        throw error; // Re-throw HttpsError or create a new one for app client
    }
});

// ======================================================
// NEW: SCHEDULED UNVERIFIED USER CLEANUP (AFTER 72 HOURS)
// ======================================================
/**
 * A scheduled Cloud Function to delete Firebase Auth users who have not
 * verified their email address within 72 hours of account creation.
 * Runs once every 24 hours.
 */
exports.cleanupUnverifiedUsers = onSchedule({
    schedule: "every 24 hours", // Run daily
    timeZone: "America/New_York", // IMPORTANT: Set to your project's primary timezone or user's expected timezone
    timeoutSeconds: 60 // 1 minute for user cleanup
}, async (event) => {
    console.log("Starting scheduled cleanup of unverified users...");

    // Calculate the timestamp 72 hours ago
    const seventyTwoHoursAgo = Date.now() - (72 * 60 * 60 * 1000); // 72 hours in milliseconds

    try {
        let nextPageToken;
        let unverifiedUsersDeletedCount = 0;

        do {
            // List users in batches (Firebase Authentication does not have direct query for emailVerified status)
            const listUsersResult = await admin.auth().listUsers(1000, nextPageToken); // Fetch up to 1000 users at a time
            nextPageToken = listUsersResult.pageToken;

            for (const userRecord of listUsersResult.users) {
                // Check if email is not verified AND account was created more than 72 hours ago
                if (!userRecord.emailVerified && userRecord.metadata.creationTime < seventyTwoHoursAgo) {
                    console.log(`Deleting unverified user: ${userRecord.uid}, email: ${userRecord.email}, created: ${new Date(userRecord.metadata.creationTime).toISOString()}`);
                    await admin.auth().deleteUser(userRecord.uid);
                    unverifiedUsersDeletedCount++;
                }
            }
        } while (nextPageToken);

        console.log(`✅ Completed scheduled cleanup. Total unverified users deleted: ${unverifiedUsersDeletedCount}`);
        return null;
    } catch (error) {
        console.error(`❌ Error during unverified user cleanup:`, error);
        // Returning null allows the function to complete successfully even if some errors occur,
        // preventing infinite retries if the error is non-fatal for future runs.
        return null;
    }
});

// ======================================================
// NEW: ON DELETE UserBirdImage -> Deduct Points and Update Counts
// ======================================================
exports.onDeleteUserBirdImage = onDocumentDeleted("users/{userId}/userBirdImage/{userBirdImageId}", async (event) => {
    const deletedImage = event.data.data(); // This is the data *before* deletion
    const userId = event.params.userId;
    const userBirdRefId = deletedImage.userBirdRefId; // The ID of the associated UserBird

    if (!userBirdRefId) {
        console.log('onDeleteUserBirdImage: No userBirdRefId found on deleted UserBirdImage, skipping update.');
        return null;
    }

    const userBirdRef = db.collection('userBirds').doc(userBirdRefId);

    // Check if this was the last image associated with this UserBird
    const remainingImagesSnapshot = await db.collection('users').doc(userId)
        .collection('userBirdImage')
        .where('userBirdRefId', '==', userBirdRefId)
        .get();

    if (remainingImagesSnapshot.empty) {
        // This means the deleted image was the LAST image for this userBirdRefId.
        // Therefore, we need to revert the counts associated with the UserBird and delete the UserBird.

        // Get the UserBird data to know its points and duplicate status
        const userBirdDoc = await userBirdRef.get();

        if (userBirdDoc.exists) {
            const userBirdData = userBirdDoc.data();
            const pointsEarnedByBird = userBirdData.pointsEarned || 0;
            const isDuplicate = userBirdData.isDuplicate || false;

            console.log(`⬇️ onDeleteUserBirdImage: Last image deleted for UserBird ${userBirdRefId}. Reverting user totals.`);

            // Deduct points and counts from the user's aggregate totals
            await _updateUserTotals(userId, -1, isDuplicate ? -1 : 0, -pointsEarnedByBird);

            // Delete the UserBird document itself, as it no longer has associated images.
            await userBirdRef.delete();
            console.log(`🗑️ Deleted UserBird document ${userBirdRefId} for user ${userId} as no images remain.`);
        } else {
            console.log(`onDeleteUserBirdImage: UserBird document ${userBirdRefId} not found, but last image deleted. User totals already consistent?`);
        }
    } else {
        console.log(`onDeleteUserBirdImage: UserBird document ${userBirdRefId} still has ${remainingImagesSnapshot.size} images. No changes to user totals.`);
    }

    return null;
});
// ======================================================
// NEW: Callable function to moderate profile picture images using OpenAI Vision
// ======================================================
exports.moderatePfpImage = onCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 30 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const { imageBase64 } = request.data;

    if (!imageBase64 || typeof imageBase64 !== 'string') {
        throw new HttpsError('invalid-argument', 'Image data (Base64) is required.');
    }

    try {
        console.log(`Starting PFP image moderation for user ${request.auth.uid}...`);

        const moderationPrompt = `Analyze the provided image for inappropriate content. Inappropriate content includes, but is not limited to, nudity, sexually suggestive material, hate symbols, graphic violence, illegal activities, or promotion of self-harm.

Respond STRICTLY in JSON format with two keys:
1. "isAppropriate": true if the image is appropriate, false otherwise.
2. "moderationReason": a brief, clear reason if "isAppropriate" is false (e.g., "Contains nudity", "Hate symbol detected", "Graphic violence"), or "N/A" if appropriate.

Example for inappropriate image:
{
  "isAppropriate": false,
  "moderationReason": "Contains nudity"
}

Example for appropriate image:
{
  "isAppropriate": true,
  "moderationReason": "N/A"
}`;

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
                response_format: { type: "json_object" }, // Ensure JSON output
                max_tokens: 200, // Sufficient for a brief reason
                temperature: 0.0 // Keep it factual
            },
            {
                headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` },
                timeout: 25000
            }
        );

        const moderationResult = JSON.parse(aiResponse.data.choices[0].message.content);

        console.log(`Moderation result for user ${request.auth.uid}: ${JSON.stringify(moderationResult)}`);

        // Validate the structure of the AI response
        if (typeof moderationResult.isAppropriate === 'boolean' && typeof moderationResult.moderationReason === 'string') {
            return moderationResult;
        } else {
            console.error("OpenAI moderation returned an unexpected format:", moderationResult);
            throw new HttpsError('internal', 'OpenAI moderation response malformed.');
        }

    } catch (error) {
        console.error("Error during PFP image moderation:", error);
        if (error.response && error.response.data) {
            console.error("OpenAI API error details:", error.response.data);
        }
        throw new HttpsError("internal", "Failed to moderate image content.");
    }
});