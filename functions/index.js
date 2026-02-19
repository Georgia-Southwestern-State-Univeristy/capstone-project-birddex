// 1. Use the specific v1 auth import for the cleanup trigger
const auth = require("firebase-functions/v1/auth");

// 2. Use the standard v2 imports for your other functions
const { onCall, HttpsError } = require("firebase-functions/v2/https");
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

// Helper function to generate and save GENERAL bird facts using OpenAI with retry logic
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
    let retries = 0;
    const maxRetries = 5;
    const initialDelayMs = 1000; // 1 second

    while (retries < maxRetries) {
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

            const aiResponse = await axios.post(
                "https://api.openai.com/v1/chat/completions",
                {
                    model: "gpt-4o", // or gpt-4-turbo, gpt-3.5-turbo, etc.
                    messages: [{ role: "user", content: prompt }],
                    response_format: { type: "json_object" },
                    temperature: 0.7, // Adjust for creativity vs. factual accuracy
                    max_tokens: 1500 // Max tokens for the response
                },
                { headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` } }
            );

            const factsJson = JSON.parse(aiResponse.data.choices[0].message.content);
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
            if (error.response && error.response.status === 429) {
                retries++;
                const delayTime = initialDelayMs * Math.pow(2, retries - 1); // Exponential backoff
                console.warn(`⚠️ Rate limit hit for GENERAL facts of ${commonName} (${birdId}). Retrying in ${delayTime}ms... (Attempt ${retries}/${maxRetries})`);
                await delay(delayTime);
            } else {
                console.error(`❌ Error generating or saving GENERAL facts for ${commonName} (${birdId}):`, error);
                return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: error.message };
            }
        }
    }
    console.error(`❌ Failed to generate GENERAL facts for ${commonName} (${birdId}) after ${maxRetries} retries due to rate limits.`);
    return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: `Failed after ${maxRetries} retries due to rate limits.` };
}

// Helper function to generate and save HUNTER bird facts using OpenAI with retry logic
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
    let retries = 0;
    const maxRetries = 5;
    const initialDelayMs = 1000; // 1 second

    while (retries < maxRetries) {
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

            const aiResponse = await axios.post(
                "https://api.openai.com/v1/chat/completions",
                {
                    model: "gpt-4o",
                    messages: [{ role: "user", content: prompt }],
                    response_format: { type: "json_object" },
                    temperature: 0.7,
                    max_tokens: 1000 // Max tokens for hunter facts
                },
                { headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` } }
            );

            const hunterFactsJson = JSON.parse(aiResponse.data.choices[0].message.content);
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
            if (error.response && error.response.status === 429) {
                retries++;
                const delayTime = initialDelayMs * Math.pow(2, retries - 1); // Exponential backoff
                console.warn(`⚠️ Rate limit hit for HUNTER facts of ${commonName} (${birdId}). Retrying in ${delayTime}ms... (Attempt ${retries}/${maxRetries})`);
                await delay(delayTime);
            } else {
                console.error(`❌ Error generating or saving HUNTER facts for ${commonName} (${birdId}):`, error);
                return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: error.message };
            }
        }
    }
    console.error(`❌ Failed to generate HUNTER facts for ${commonName} (${birdId}) after ${maxRetries} retries due to rate limits.`);
    return { birdId: birdId, lastGenerated: admin.firestore.FieldValue.serverTimestamp(), error: `Failed after ${maxRetries} retries due to rate limits.` };
}


// ======================================================
// 1. Identify Bird (OpenAI)
// ======================================================
exports.identifyBird = onCall({ secrets: [OPENAI_API_KEY] }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const { image, latitude, longitude, localityName } = request.data;

    if (typeof latitude !== 'number' || typeof longitude !== 'number') {
        throw new HttpsError("invalid-argument", "Latitude and longitude are required and must be numbers.");
    }

    try {
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
            { headers: { "Authorization": `Bearer ${OPENAI_API_KEY.value()}` } }
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
        throw new HttpsError("internal", "OpenAI identification failed.");
    }
});

// ======================================================
// 2. Fetch Georgia Bird List (eBird Cache) - Now including last seen data
// ======================================================
exports.getGeorgiaBirds = onCall({ secrets: [EBIRD_API_KEY, OPENAI_API_KEY] }, async (request) => {
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
            // No else here: if cache is fresh but empty, we still fetch.
        } else {
            console.log("eBird cache is stale or empty. Fetching new data from eBird API.");
        }

        let gaDetailedBirds = [];
        let birdIdsToCache = [];

        if (shouldFetchFromEbird) {
            // --- PHASE 1: Fetch Raw eBird Data ---
            const [codesRes, taxonomyRes, observationsRes] = await Promise.all([
                axios.get("https://api.ebird.org/v2/product/spplist/US-GA", {
                    headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() }
                }),
                axios.get("https://api.ebird.org/v2/ref/taxonomy/ebird?fmt=json"),
                axios.get("https://api.ebird.org/v2/data/obs/US-GA/recent", {
                    headers: { "X-eBirdApiToken": EBIRD_API_KEY.value() }
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
                batch1.delete(db.collection("birdFacts").doc(birdId)); // Delete associated general facts
                batch1.delete(db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId)); // Delete associated hunter facts subcollection document
            }

            // 2.2 Update/add core bird data to 'birds' collection (excluding dynamic locationId for now)
            for (const bird of newBirdsFromEbird) {
                const birdRef = db.collection("birds").doc(bird.id);
                // Create a copy of the bird object, excluding dynamic fields for initial write
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

            // --- PHASE 3: Process Location IDs and Generate/Fetch Facts ---
            const factAndLocationPromises = newBirdsFromEbird.map(async (bird) => {
                let currentBirdFacts = {};
                let currentHunterFacts = {};
                let updatedLocationId = bird.lastSeenLocationIdGeorgia; // Start with current or null

                const birdFactsRef = db.collection("birdFacts").doc(bird.id);
                const birdFactsDoc = await birdFactsRef.get();
                const hunterFactsRef = db.collection("birdFacts").doc(bird.id).collection("hunterFacts").doc(bird.id); // Reference to the subcollection document
                const hunterFactsDoc = await hunterFactsRef.get();

                // Check/Generate GENERAL facts
                if (!birdFactsDoc.exists) {
                    console.log(`General facts do not exist for ${bird.commonName} (${bird.id}). Generating...`);
                    currentBirdFacts = await generateAndSaveBirdFacts(bird.id);
                } else {
                    console.log(`General facts already exist for ${bird.commonName} (${bird.id}). Fetching existing general facts.`); // Specific log
                    currentBirdFacts = birdFactsDoc.data();
                }

                // Check/Generate HUNTER facts
                // A more robust check for completeness: ensure doc exists and has a meaningful legalStatusGeorgia
                const hunterFactsExistAndComplete = hunterFactsDoc.exists && hunterFactsDoc.data().legalStatusGeorgia && !hunterFactsDoc.data().legalStatusGeorgia.includes("N/A");
                if (!hunterFactsExistAndComplete) {
                    console.log(`Hunter facts do not exist or are incomplete for ${bird.commonName} (${bird.id}). Generating...`); // Specific log
                    currentHunterFacts = await generateAndSaveHunterFacts(bird.id);
                } else {
                    console.log(`Hunter facts already exist and are complete for ${bird.commonName} (${bird.id}). Fetching existing hunter facts.`); // Specific log
                    currentHunterFacts = hunterFactsDoc.data();
                }

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

                // Return the combined object for final list construction
                return {
                    ...bird,
                    ...currentBirdFacts,
                    hunterFacts: currentHunterFacts, // Embed hunter facts as a nested object
                    georgiaDNRHuntingLink: GEORGIA_DNR_HUNTING_LINK, // Add the hardcoded link here
                    lastSeenLocationIdGeorgia: updatedLocationId
                };
            });

            gaDetailedBirds = await Promise.all(factAndLocationPromises); // Wait for all dynamic processing
            console.log("✅ All facts generated/fetched and location IDs updated on individual bird documents.");

        } else {
            // --- If cache is fresh, retrieve birds and their facts from local collections ---
            const birdsWithFactsPromises = cachedBirdIds.map(birdId =>
                db.collection("birds").doc(birdId).get().then(async birdDoc => {
                    if (birdDoc.exists) {
                        let birdData = birdDoc.data();
                        const generalFactsDoc = await db.collection("birdFacts").doc(birdId).get();
                        const hunterFactsDoc = await db.collection("birdFacts").doc(birdId).collection("hunterFacts").doc(birdId).get(); // Reference to subcollection

                        let currentBirdFacts = {};
                        if (generalFactsDoc.exists) {
                            console.log(`General facts for ${birdId} are present in Firestore. Fetching existing.`);
                            currentBirdFacts = generalFactsDoc.data();
                        } else {
                            console.warn(`General facts for ${birdId} are missing from Firestore. Generating now.`);
                            currentBirdFacts = await generateAndSaveBirdFacts(birdId);
                        }

                        let currentHunterFacts = {};
                        const hunterFactsExistAndComplete = hunterFactsDoc.exists && hunterFactsDoc.data().legalStatusGeorgia && !hunterFactsDoc.data().legalStatusGeorgia.includes("N/A");
                        if (!hunterFactsExistAndComplete) {
                            console.warn(`Hunter facts for ${birdId} are missing or incomplete from Firestore. Generating now.`);
                            currentHunterFacts = await generateAndSaveHunterFacts(birdId);
                        } else {
                            console.log(`Hunter facts for ${birdId} are present and complete in Firestore. Fetching existing.`);
                            currentHunterFacts = hunterFactsDoc.data();
                        }

                        return {
                            ...birdData,
                            ...currentBirdFacts,
                            hunterFacts: currentHunterFacts,
                            georgiaDNRHuntingLink: GEORGIA_DNR_HUNTING_LINK
                        };
                    }
                    return null;
                })
            );
            gaDetailedBirds = (await Promise.all(birdsWithFactsPromises)).filter(b => b !== null);
            console.log("✅ Returning cached birds with last seen data and all facts.");
        }

        return { birds: gaDetailedBirds };
    } catch (error) {
        console.error("❌ Error fetching or processing eBird data:", error);
        throw new HttpsError("internal", `eBird data processing failed: ${error.message}`);
    }
});

// ======================================================\
// 3. Search Bird Image (Nuthatch API)
// ======================================================\
exports.searchBirdImage = onCall({ secrets: [NUTHATCH_API_KEY] }, async (request) => {
    try {
        const response = await axios.get(
            `https://nuthatch.lastelm.software/v2/birds?name=${encodeURIComponent(request.data.searchTerm)}&hasImg=true`,
            { headers: { "api-key": NUTHATCH_API_KEY.value() } }
        );
        return { data: response.data };
    } catch (error) {
        throw new HttpsError("internal", "Nuthatch failed.");
    }
});

// ======================================================\
// 4. AUTOMATIC USER DATA CLEANUP
//=======================================================
// Using the v1 auth module directly ensures 'user' is never undefined.
exports.cleanupUserData = auth.user().onDelete(async (user) => {
    const uid = user.uid;
    console.log(`🧹 Starting cleanup for user: ${uid}`);

    const batch = db.batch();

    try {
        // 1. Delete user profile
        batch.delete(db.collection("users").doc(uid));

        // 2. Delete collectionSlot subcollection
        const slotSnap = await db.collection("users").doc(uid).collection("collectionSlot").get();
        slotSnap.forEach(doc => batch.delete(doc.ref));

        // 3. Delete from related global collections
        const collections = ["userBirds", "media", "birdCards"];
        for (const col of collections) {
            const snap = await db.collection(col).where("userId", "==", uid).get();
            snap.forEach(doc => batch.delete(doc.ref));
        }

        await batch.commit();
        console.log(`✅ Successfully deleted all data for ${uid}`);
        return null;
    } catch (err) {
        console.error("❌ Cleanup failed:", err);
        return null;
    }
});
