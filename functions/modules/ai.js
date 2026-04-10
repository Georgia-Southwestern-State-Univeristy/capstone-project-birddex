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
  haversineMiles,
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
        qualityAssessment: safeText.split("Quality:")[1]?.split("\n")[0]?.trim() || null,
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildBirdIdentificationText(birdId, birdData) {
    return `ID: ${birdId}
Common Name: ${birdData.commonName || "Unknown"}
Scientific Name: ${birdData.scientificName || "Unknown"}
Species: ${birdData.species || "Unknown"}
Family: ${birdData.family || "Unknown"}`;
}

/**
 * Helper: Looks up a supported bird document by common name.
 */
async function findBirdByCommonName(commonName) {
    const raw = await findBirdRecordByCommonName(commonName);
    return raw ? normalizeBirdData(raw, raw.id) : null;
}

/**
 * Helper: Calls the BirdDex model service running on Cloud Run.
 */
async function callBirdModelApi({ imageBase64, latitude = null, longitude = null, observedAt = null, topK = HYBRID_ID_CONFIG.MODEL_TOP_K }) {
    const serviceUrl = CONFIG.BIRDDEX_MODEL_BASE_URL;
    const targetUrl = HYBRID_ID_CONFIG.BIRDDEX_MODEL_URL;

    const authClient = await new GoogleAuth().getIdTokenClient(serviceUrl);

    const headers = {
        "Content-Type": "application/json",
    };

    const internalKey = BIRDDEX_MODEL_API_KEY.value();
    if (internalKey) {
        headers["X-Internal-Api-Key"] = internalKey;
    }

    const requestData = {
        imageBase64,
        topK,
    };
    if (typeof latitude === "number" && Number.isFinite(latitude)) requestData.latitude = latitude;
    if (typeof longitude === "number" && Number.isFinite(longitude)) requestData.longitude = longitude;
    if (typeof observedAt === "string" && observedAt.trim()) requestData.observedAt = observedAt.trim();

    const response = await authClient.request({
        url: targetUrl,
        method: "POST",
        data: requestData,
        headers,
        timeout: 50000,
    });

    const responseData = response.data || {};
    const rawTopPredictions = Array.isArray(responseData?.top_predictions)
        ? responseData.top_predictions
        : [];
    const rawMainPredictions = Array.isArray(responseData?.main_model_top_predictions)
        ? responseData.main_model_top_predictions
        : rawTopPredictions;

    const topPredictions = rawTopPredictions.map(normalizeModelPrediction).filter(Boolean);
    const mainModelTopPredictions = rawMainPredictions.map(normalizeModelPrediction).filter(Boolean);

    if (!topPredictions.length) {
        throw new HttpsError("internal", "Bird model returned no predictions.");
    }

    const rawGeo = responseData?.geo && typeof responseData.geo === "object" ? responseData.geo : null;

    return {
        topPredictions,
        mainModelTopPredictions: mainModelTopPredictions.length ? mainModelTopPredictions : topPredictions,
        reranker: buildRerankerResultPayload(responseData?.reranker, rawMainPredictions, rawTopPredictions),
        filename: typeof responseData?.filename === "string" ? responseData.filename : null,
        geo: rawGeo ? {
            used: rawGeo.used === true,
            available: rawGeo.available !== false,
            fusionLam: typeof rawGeo.fusion_lam === "number" ? rawGeo.fusion_lam : null,
            imageTop1Confidence: typeof rawGeo.image_top1_confidence === "number" ? rawGeo.image_top1_confidence : null,
            observedAt: typeof rawGeo.observedAt === "string" ? rawGeo.observedAt : (requestData.observedAt || null),
            latitude: typeof rawGeo.latitude === "number" ? rawGeo.latitude : (requestData.latitude ?? null),
            longitude: typeof rawGeo.longitude === "number" ? rawGeo.longitude : (requestData.longitude ?? null),
            reason: typeof rawGeo.reason === "string" ? rawGeo.reason : null,
            imageTopPredictions: Array.isArray(rawGeo.image_top_predictions)
                ? rawGeo.image_top_predictions.map(normalizeModelPrediction).filter(Boolean)
                : [],
            geoTopPredictions: Array.isArray(rawGeo.geo_top_predictions)
                ? rawGeo.geo_top_predictions.map(normalizeModelPrediction).filter(Boolean)
                : [],
            fullGeoTopPredictions: Array.isArray(rawGeo.full_geo_top_predictions)
                ? rawGeo.full_geo_top_predictions.map(normalizeModelPrediction).filter(Boolean)
                : [],
            plausibilityByCommonName: buildGeoPlausibilityMap(rawGeo),
        } : null,
    };
}

/**
 * Helper: Normalizes a single Cloud Run bird prediction object.
 */
function normalizeModelPrediction(prediction) {
    if (!prediction || typeof prediction !== "object") return null;
    const commonName = prediction.commonName || prediction.species || prediction.label || null;
    if (!commonName) return null;
    return {
        commonName,
        confidence: Number(prediction.confidence || 0),
    };
}


/**
 * Helper: Normalizes a single Cloud Run plausibility entry so Firebase can consume it safely.
 */
function normalizeGeoPlausibilityEntry(entry) {
    if (!entry || typeof entry !== "object") return null;

    const commonName = sanitizeText(
        entry.commonName || entry.species || entry.label || entry.name || "",
        200
    ).trim() || null;

    if (!commonName) return null;

    const scoreRaw = typeof entry.score === "number"
        ? entry.score
        : (typeof entry.plausibilityScore === "number" ? entry.plausibilityScore : null);

    const distanceMiles = safeFiniteNumber(
        typeof entry.distanceMilesFromUser === "number"
            ? entry.distanceMilesFromUser
            : entry.nearestSightingDistanceMiles
    );

    return {
        commonName,
        locationPlausible: entry.locationPlausible === true
            || entry.plausible === true
            || entry.isPlausible === true,
        locationPlausibilityScore: scoreRaw === null ? null : Number(Number(scoreRaw).toFixed(3)),
        locationPlausibilityReason: sanitizeText(
            entry.reason || entry.locationPlausibilityReason || "cloud_run_geo_plausibility",
            200
        ).trim() || "cloud_run_geo_plausibility",
        distanceMilesFromUser: distanceMiles,
        daysSinceLastSeenGeorgia: Number.isFinite(Number(entry.daysSinceLastSeenGeorgia))
            ? Number(entry.daysSinceLastSeenGeorgia)
            : null,
        nearestSightingDistanceMiles: distanceMiles,
        daysSinceNearestNearbySighting: Number.isFinite(Number(entry.daysSinceNearestNearbySighting))
            ? Number(entry.daysSinceNearestNearbySighting)
            : null,
        sightingsWithin25Miles: Number.isFinite(Number(entry.sightingsWithin25Miles))
            ? Number(entry.sightingsWithin25Miles)
            : null,
        sightingsWithin50Miles: Number.isFinite(Number(entry.sightingsWithin50Miles))
            ? Number(entry.sightingsWithin50Miles)
            : null,
        sightingsWithin100Miles: Number.isFinite(Number(entry.sightingsWithin100Miles))
            ? Number(entry.sightingsWithin100Miles)
            : null,
        sightingsWithin200Miles: Number.isFinite(Number(entry.sightingsWithin200Miles))
            ? Number(entry.sightingsWithin200Miles)
            : null,
        monthlyPresenceScore: safeFiniteNumber(entry.monthlyPresenceScore),
        countyPresenceScore: safeFiniteNumber(entry.countyPresenceScore),
        statewideCommonnessScore: safeFiniteNumber(entry.statewideCommonnessScore),
        statewideRecentSightingsScore: safeFiniteNumber(entry.statewideRecentSightingsScore),
        plausibilitySource: "cloud_run_geo",
    };
}

/**
 * Helper: Builds a lookup map from bird common name -> Cloud Run geo plausibility entry.
 */
function buildGeoPlausibilityMap(rawGeo) {
    if (!rawGeo || typeof rawGeo !== "object") return {};

    const candidates = []
        .concat(Array.isArray(rawGeo?.plausibility) ? rawGeo.plausibility : [])
        .concat(Array.isArray(rawGeo?.plausibility_by_common_name) ? rawGeo.plausibility_by_common_name : [])
        .concat(Array.isArray(rawGeo?.plausibilityByCommonName) ? rawGeo.plausibilityByCommonName : [])
        .concat(Array.isArray(rawGeo?.candidate_plausibility) ? rawGeo.candidate_plausibility : []);

    const map = {};
    for (const candidate of candidates) {
        const normalized = normalizeGeoPlausibilityEntry(candidate);
        if (!normalized?.commonName) continue;
        map[normalized.commonName] = normalized;
    }
    return map;
}

/**
 * Helper: Creates a safe normalized reranker payload from the Cloud Run response.
 */
function buildRerankerResultPayload(reranker, mainModelTopPredictions = [], finalTopPredictions = []) {
    const normalizedMain = Array.isArray(mainModelTopPredictions)
        ? mainModelTopPredictions.map(normalizeModelPrediction).filter(Boolean)
        : [];
    const normalizedFinal = Array.isArray(finalTopPredictions)
        ? finalTopPredictions.map(normalizeModelPrediction).filter(Boolean)
        : [];

    const safe = reranker && typeof reranker === "object" ? reranker : {};
    const candidatePair = Array.isArray(safe.candidate_pair)
        ? safe.candidate_pair.filter(value => typeof value === "string" && value.trim())
        : [];

    const mainTop1 = normalizedMain[0] || null;
    const mainTop2 = normalizedMain[1] || null;
    const rerankerTop1 = normalizedFinal[0] || null;
    const rerankerTop2 = normalizedFinal[1] || null;

    const mainWinner = mainTop1?.commonName || null;
    const rerankerWinner = rerankerTop1?.commonName || null;

    return {
        used: safe.used === true,
        pairKey: typeof safe.pair_key === "string" ? safe.pair_key : null,
        backbone: typeof safe.backbone === "string" ? safe.backbone : null,
        candidatePair,
        mainTop1Common: mainWinner,
        mainTop2Common: mainTop2?.commonName || null,
        mainTop1Confidence: mainTop1?.confidence ?? null,
        mainTop2Confidence: mainTop2?.confidence ?? null,
        rerankerTop1Common: rerankerWinner,
        rerankerTop2Common: rerankerTop2?.commonName || null,
        rerankerTop1Confidence: rerankerTop1?.confidence ?? null,
        rerankerTop2Confidence: rerankerTop2?.confidence ?? null,
        flippedWinner: !!(mainWinner && rerankerWinner && mainWinner !== rerankerWinner),
    };
}

/**
 * Helper: Writes aggregate reranker accuracy metrics once the user confirms/corrects an identification.
 */
async function writeRerankerAccuracyMetrics({
    identificationLogRef,
    identificationLogData,
    resolvedChosenBirdId,
    resolvedChosenCommonName,
    action,
    selectedSource,
}) {
    const rerankerLog = identificationLogData?.reranker || {};
    if (rerankerLog.used !== true) {
        return;
    }

    const finalResult = identificationLogData?.finalResult || {};
    const localModel = identificationLogData?.localModel || {};

    const chosenBirdId = resolvedChosenBirdId || null;
    const chosenCommonName = resolvedChosenCommonName || null;

    const mainWinnerBirdId = localModel.top1BirdId || null;
    const mainWinnerCommon = localModel.top1Common || null;
    const rerankerWinnerBirdId = rerankerLog.rerankerTop1BirdId || finalResult.birdId || null;
    const rerankerWinnerCommon = rerankerLog.rerankerTop1Common || finalResult.commonName || null;

    const chosenMatchesMain = !!(
        chosenBirdId
            ? (mainWinnerBirdId && chosenBirdId === mainWinnerBirdId)
            : (chosenCommonName && mainWinnerCommon && chosenCommonName === mainWinnerCommon)
    );
    const chosenMatchesReranker = !!(
        chosenBirdId
            ? (rerankerWinnerBirdId && chosenBirdId === rerankerWinnerBirdId)
            : (chosenCommonName && rerankerWinnerCommon && chosenCommonName === rerankerWinnerCommon)
    );

    const pairKey = rerankerLog.pairKey || "unknown_pair";
    const safePairKey = pairKey.replace(/[\/\\.#$\[\]]/g, "_");

    const aggregateRef = db.collection("identificationMetrics").doc("reranker_overall");
    const pairAggregateRef = db.collection("identificationMetrics").doc(`reranker_pair_${safePairKey}`);

    const aggregateUpdate = {
        lastMeasuredAt: admin.firestore.FieldValue.serverTimestamp(),
        totalMeasured: admin.firestore.FieldValue.increment(1),
        mainCorrectCount: admin.firestore.FieldValue.increment(chosenMatchesMain ? 1 : 0),
        rerankerCorrectCount: admin.firestore.FieldValue.increment(chosenMatchesReranker ? 1 : 0),
        rerankerWinDeltaCount: admin.firestore.FieldValue.increment(
            chosenMatchesReranker && !chosenMatchesMain ? 1 : 0
        ),
        rerankerLossDeltaCount: admin.firestore.FieldValue.increment(
            chosenMatchesMain && !chosenMatchesReranker ? 1 : 0
        ),
    };

    const pairUpdate = {
        pairKey,
        backbone: rerankerLog.backbone || null,
        candidatePair: Array.isArray(rerankerLog.candidatePair) ? rerankerLog.candidatePair : [],
        lastMeasuredAt: admin.firestore.FieldValue.serverTimestamp(),
        totalMeasured: admin.firestore.FieldValue.increment(1),
        mainCorrectCount: admin.firestore.FieldValue.increment(chosenMatchesMain ? 1 : 0),
        rerankerCorrectCount: admin.firestore.FieldValue.increment(chosenMatchesReranker ? 1 : 0),
        rerankerWinDeltaCount: admin.firestore.FieldValue.increment(
            chosenMatchesReranker && !chosenMatchesMain ? 1 : 0
        ),
        rerankerLossDeltaCount: admin.firestore.FieldValue.increment(
            chosenMatchesMain && !chosenMatchesReranker ? 1 : 0
        ),
    };

    const measurementData = {
        measuredAt: admin.firestore.FieldValue.serverTimestamp(),
        action: action || null,
        selectedSource: selectedSource || null,
        pairKey,
        backbone: rerankerLog.backbone || null,
        chosenBirdId,
        chosenCommonName,
        mainWinnerBirdId,
        mainWinnerCommon,
        rerankerWinnerBirdId,
        rerankerWinnerCommon,
        chosenMatchesMain,
        chosenMatchesReranker,
    };

    await identificationLogRef.set({
        rerankerMeasurement: {
            ...measurementData,
        },
    }, { merge: true });

    const batch = db.batch();
    batch.set(aggregateRef, aggregateUpdate, { merge: true });
    batch.set(pairAggregateRef, pairUpdate, { merge: true });
    await batch.commit();
}

/**
 * Helper: Uses OpenAI as the broader fallback when the model is not confident enough.
 */
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
Family: [name]
Quality: [A one-sentence feedback on image quality if it's hard to identify, e.g., 'too blurry', 'too far away', 'obscured by branches', 'low lighting'. If good, leave as 'clear'.]`
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

/**
 * Helper: Enforces the OpenAI request cap before making another model-assisted request.
 */
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

/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
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

/**
 * Helper: Loads the raw bird document by id.
 */
async function getBirdRecordById(birdId) {
    if (!birdId || typeof birdId !== "string") return null;
    const doc = await db.collection("birds").doc(birdId.trim()).get();
    if (!doc.exists) return null;
    return { id: doc.id, ...(doc.data() || {}) };
}

/**
 * Helper: Looks up the raw bird document by common name.
 */
async function findBirdRecordByCommonName(commonName) {
    if (!commonName || typeof commonName !== "string") return null;
    const snapshot = await db.collection("birds")
        .where("commonName", "==", commonName.trim())
        .limit(1)
        .get();

    if (snapshot.empty) return null;
    const doc = snapshot.docs[0];
    return { id: doc.id, ...(doc.data() || {}) };
}

/**
 * Helper: Looks up the raw bird document by scientific name.
 */
async function findBirdRecordByScientificName(scientificName) {
    if (!scientificName || typeof scientificName !== "string") return null;
    const snapshot = await db.collection("birds")
        .where("scientificName", "==", scientificName.trim())
        .limit(1)
        .get();

    if (snapshot.empty) return null;
    const doc = snapshot.docs[0];
    return { id: doc.id, ...(doc.data() || {}) };
}

/**
 * Helper: Loads a bird document by id with normalization/safety checks.
 */
async function getBirdById(birdId) {
    const raw = await getBirdRecordById(birdId);
    return raw ? normalizeBirdData(raw, raw.id) : null;
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
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

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
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

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
async function buildModelAlternativeChoices(
    topBirdMatches,
    userLocation,
    captureSource = null,
    observedAt = null,
    geoPlausibilityMap = null
) {
    const results = [];

    for (const [index, birdData] of (topBirdMatches || []).slice(1, 3).entries()) {
        const normalized = normalizeBirdData(birdData);
        if (!normalized || !normalized.id) continue;

        const plausibility = await buildBirdLocationPlausibility(
            birdData,
            userLocation,
            observedAt,
            geoPlausibilityMap
        );

        const choice = buildBirdChoicePayload(normalized, "model_alternative", {
            rank: index + 2,
            ...plausibility,
            alternativeReasonText: buildAlternativeReasonText(plausibility, "model_alternative", captureSource),
        });

        if (choice) results.push(choice);
    }

    return results;
}

/**
 * Helper: Safety wrapper that returns a normalized primitive instead of a risky raw value.
 */
function safeFiniteNumber(value) {
    return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * Helper: Simple conversion helper.
 */
function toMillis(value) {
    if (value === null || value === undefined) return null;
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (value instanceof Date) return value.getTime();
    if (typeof value?.toMillis === "function") return value.toMillis();
    if (typeof value?.toDate === "function") return value.toDate().getTime();
    return null;
}

/**
 * Helper: Builds lightweight location context used during identification/review prompts.
 */
async function getLocationContext(locationId) {
    if (!locationId || typeof locationId !== "string") {
        return {
            locationId: null,
            latitude: null,
            longitude: null,
            locality: null,
            state: "GA",
            country: "US",
        };
    }

    const doc = await db.collection("locations").doc(locationId).get();
    const data = doc.exists ? (doc.data() || {}) : {};
    return {
        locationId,
        latitude: safeFiniteNumber(data.latitude),
        longitude: safeFiniteNumber(data.longitude),
        locality: typeof data.locality === "string" ? data.locality : null,
        state: typeof data.state === "string" ? data.state : "GA",
        country: typeof data.country === "string" ? data.country : "US",
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildNotMyBirdGate(top1Confidence, top2Confidence) {
    const safeTop1 = safeFiniteNumber(top1Confidence) ?? 0;
    const safeTop2 = safeFiniteNumber(top2Confidence) ?? 0;
    const margin = safeTop1 - safeTop2;
    const isLocked = safeTop1 >= HYBRID_ID_CONFIG.NOT_MY_BIRD_LOCK_CONFIDENCE_THRESHOLD
        && margin >= HYBRID_ID_CONFIG.NOT_MY_BIRD_LOCK_MARGIN_THRESHOLD;

    return {
        allowed: !isLocked,
        top1Confidence: safeTop1,
        top2Confidence: safeTop2,
        confidenceMargin: margin,
        blockMessage: isLocked ? "were more than confident on the current result" : null,
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildBirdLocationPlausibility(birdData, userLocation, observedAt = null, geoPlausibilityMap = null) {
    const normalizedBird = normalizeBirdData(birdData, birdData?.id || birdData?.birdId || null);
    const commonName = normalizedBird?.commonName || birdData?.commonName || null;

    if (geoPlausibilityMap && typeof geoPlausibilityMap === "object" && commonName && geoPlausibilityMap[commonName]) {
        return geoPlausibilityMap[commonName];
    }

    const userLat = safeFiniteNumber(userLocation?.latitude);
    const userLng = safeFiniteNumber(userLocation?.longitude);
    const lastLat = safeFiniteNumber(birdData?.lastSeenLatitudeGeorgia);
    const lastLng = safeFiniteNumber(birdData?.lastSeenLongitudeGeorgia);
    const lastSeenMs = toMillis(birdData?.lastSeenTimestampGeorgia);

    if (userLat === null || userLng === null) {
        return {
            locationPlausible: true,
            locationPlausibilityScore: null,
            locationPlausibilityReason: "missing_user_location",
            distanceMilesFromUser: null,
            daysSinceLastSeenGeorgia: null,
            nearestSightingDistanceMiles: null,
            daysSinceNearestNearbySighting: null,
            sightingsWithin25Miles: null,
            sightingsWithin50Miles: null,
            sightingsWithin100Miles: null,
            sightingsWithin200Miles: null,
            monthlyPresenceScore: null,
            countyPresenceScore: null,
            statewideCommonnessScore: null,
            statewideRecentSightingsScore: null,
            plausibilitySource: "missing_user_location",
        };
    }

    if (lastLat === null || lastLng === null || lastSeenMs === null) {
        return {
            locationPlausible: true,
            locationPlausibilityScore: null,
            locationPlausibilityReason: "missing_bird_location_data",
            distanceMilesFromUser: null,
            daysSinceLastSeenGeorgia: null,
            nearestSightingDistanceMiles: null,
            daysSinceNearestNearbySighting: null,
            sightingsWithin25Miles: null,
            sightingsWithin50Miles: null,
            sightingsWithin100Miles: null,
            sightingsWithin200Miles: null,
            monthlyPresenceScore: null,
            countyPresenceScore: null,
            statewideCommonnessScore: null,
            statewideRecentSightingsScore: null,
            plausibilitySource: "legacy_missing_data",
        };
    }

    const distanceMiles = haversineMiles(userLat, userLng, lastLat, lastLng);
    const daysSinceLastSeen = Math.max(0, (Date.now() - lastSeenMs) / (24 * 60 * 60 * 1000));

    const distanceScore = distanceMiles <= 25 ? 1.0
        : distanceMiles <= 75 ? 0.92
        : distanceMiles <= 150 ? 0.82
        : distanceMiles <= 250 ? 0.68
        : distanceMiles <= 400 ? 0.48
        : 0.18;

    const recencyScore = daysSinceLastSeen <= 7 ? 1.0
        : daysSinceLastSeen <= 30 ? 0.92
        : daysSinceLastSeen <= 90 ? 0.82
        : daysSinceLastSeen <= 180 ? 0.68
        : daysSinceLastSeen <= 365 ? 0.48
        : 0.18;

    const score = (distanceScore * 0.60) + (recencyScore * 0.40);
    const roundedScore = Number(score.toFixed(3));

    return {
        locationPlausible: roundedScore >= HYBRID_ID_CONFIG.REVIEW_LOCATION_PLAUSIBILITY_THRESHOLD,
        locationPlausibilityScore: roundedScore,
        locationPlausibilityReason: roundedScore >= HYBRID_ID_CONFIG.REVIEW_LOCATION_PLAUSIBILITY_THRESHOLD
            ? "plausible_for_user_location_date"
            : "too_far_or_not_recent_for_user_location_date",
        distanceMilesFromUser: Number(distanceMiles.toFixed(1)),
        daysSinceLastSeenGeorgia: Math.round(daysSinceLastSeen),
        nearestSightingDistanceMiles: Number(distanceMiles.toFixed(1)),
        daysSinceNearestNearbySighting: Math.round(daysSinceLastSeen),
        sightingsWithin25Miles: null,
        sightingsWithin50Miles: null,
        sightingsWithin100Miles: null,
        sightingsWithin200Miles: null,
        monthlyPresenceScore: null,
        countyPresenceScore: null,
        statewideCommonnessScore: null,
        statewideRecentSightingsScore: null,
        plausibilitySource: "legacy_last_seen_point",
    };
}

/**
 * Helper: Builds a short user-facing explanation for why a candidate is shown.
 */
function buildAlternativeReasonText(choice, source = null, captureSource = null) {
    const isGallery = captureSource === "gallery_import";
    const looksLikePrefix = source === "openai_review"
        ? (isGallery ? "This bird looks kind of similar to the one in your uploaded photo" : "This bird looks kind of similar to your photo")
        : (isGallery ? "This bird is extremely close to the one in your uploaded photo" : "This bird is extremely close to your picture");

    if (choice?.locationPlausible === false) {
        return isGallery
            ? `${looksLikePrefix}, but isn't usually around your area in your uploaded photo this time of year.`
            : `${looksLikePrefix}, but isn't usually around that area this time of year.`;
    }

    if (choice?.locationPlausibilityReason === "missing_user_location") {
        return `${looksLikePrefix} and is another strong BirdDex match to consider.`;
    }

    return isGallery
        ? `${looksLikePrefix} and is usually around your area in your uploaded photo this time of year.`
        : `${looksLikePrefix} and is usually in that area this time of year.`;
}

/**
 * Helper: Builds the list of review candidates that make sense for the user to pick from.
 */
async function buildPlausibleReviewChoices(
    rawChoices,
    userLocation,
    excludedTokens = new Set(),
    limit = 2,
    captureSource = null,
    observedAt = null,
    geoPlausibilityMap = null
) {
    const responseChoices = [];
    const seenChoiceTokens = new Set();

    for (const rawChoice of (rawChoices || [])) {
        const rawBirdIdToken = normalizeBirdChoiceToken(rawChoice?.birdId || rawChoice?.id || null);
        const rawCommonNameToken = normalizeBirdChoiceToken(rawChoice?.commonName || null);
        if ((rawBirdIdToken && excludedTokens.has(`id:${rawBirdIdToken}`)) ||
            (rawCommonNameToken && excludedTokens.has(`name:${rawCommonNameToken}`))) {
            continue;
        }

        const matchedBird = await resolveBirdChoiceToSupportedBird(rawChoice);
        if (!matchedBird || !matchedBird.id) {
            continue;
        }

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
        if (dedupeToken) {
            seenChoiceTokens.add(dedupeToken);
        }

        const plausibility = await buildBirdLocationPlausibility(
            matchedBird,
            userLocation,
            observedAt,
            geoPlausibilityMap
        );
        if (!plausibility.locationPlausible) {
            continue;
        }

        const responseChoice = buildBirdChoicePayload(matchedBird, rawChoice?.source || "openai_review", {
            isSupportedInDatabase: true,
            ...plausibility,
            alternativeReasonText: buildAlternativeReasonText(plausibility, rawChoice?.source || "openai_review", captureSource),
        });
        if (responseChoice) {
            responseChoices.push(responseChoice);
        }
        if (responseChoices.length >= limit) {
            break;
        }
    }

    return responseChoices;
}

/**
 * Helper: Cleans raw text into a safer format for parsing/storage.
 */
function cleanJsonResponseText(rawText) {
    if (!rawText || typeof rawText !== "string") return "";
    return rawText
        .trim()
        .replace(/^```json\s*/i, "")
        .replace(/^```\s*/i, "")
        .replace(/```$/i, "")
        .trim();
}

/**
 * Helper: Parses raw text/JSON into a structured value the backend can use.
 */
function parseRankedBirdChoicesJson(rawText) {
    const cleaned = cleanJsonResponseText(rawText);
    if (!cleaned) return [];

    let parsed;
    try {
        parsed = JSON.parse(cleaned);
    } catch (primaryError) {
        const firstBrace = cleaned.indexOf("{");
        const lastBrace = cleaned.lastIndexOf("}");
        if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
            const sliced = cleaned.slice(firstBrace, lastBrace + 1);
            parsed = JSON.parse(sliced);
        } else {
            throw primaryError;
        }
    }

    const rawChoices = Array.isArray(parsed?.choices) ? parsed.choices : [];

    return rawChoices.map((choice) => ({
        birdId: sanitizeText(choice?.birdId || choice?.id || "", 200).trim() || null,
        commonName: sanitizeText(choice?.commonName || choice?.name || "", 200).trim() || null,
        scientificName: sanitizeText(choice?.scientificName || "", 200).trim() || null,
        species: sanitizeText(choice?.species || "", 200).trim() || null,
        family: sanitizeText(choice?.family || "", 200).trim() || null,
    })).filter((choice) => choice.birdId || choice.commonName);
}

/**
 * Helper: Best-effort parser that returns a safe result instead of throwing when parsing fails.
 */
function tryParseRankedBirdChoicesJson(rawText) {
    try {
        return {
            choices: parseRankedBirdChoicesJson(rawText),
            parseSucceeded: true,
            parseErrorMessage: null,
        };
    } catch (error) {
        return {
            choices: [],
            parseSucceeded: false,
            parseErrorMessage: error?.message || String(error),
        };
    }
}

let supportedBirdsCache = null;
let lastCacheUpdate = 0;
const CACHE_TTL = 30 * 60 * 1000; // 30 minutes

/**
 * Helper: Normalizes text for comparison by lowercasing and removing non-alphanumeric chars.
 */
function normalizeForMatch(text) {
    return (text || "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

/**
 * Helper: Fetches and caches the list of supported birds for fuzzy matching.
 */
async function getSupportedBirds() {
    const now = Date.now();
    if (supportedBirdsCache && (now - lastCacheUpdate < CACHE_TTL)) {
        return supportedBirdsCache;
    }
    try {
        const snapshot = await db.collection("birds").get();
        supportedBirdsCache = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
        lastCacheUpdate = now;
        return supportedBirdsCache;
    } catch (error) {
        logger.error("Failed to fetch birds for cache:", error);
        return supportedBirdsCache || [];
    }
}

/**
 * Helper: Performs a fuzzy match against the cached bird list.
 */
async function findBirdRecordFuzzy(choice) {
    if (!choice) return null;
    const birds = await getSupportedBirds();

    const normCommon = normalizeForMatch(choice.commonName);
    const normSci = normalizeForMatch(choice.scientificName);
    const normId = normalizeForMatch(choice.birdId);

    if (!normCommon && !normSci && !normId) return null;

    const match = birds.find(b =>
        (normId && normalizeForMatch(b.id) === normId) ||
        (normCommon && normalizeForMatch(b.commonName) === normCommon) ||
        (normSci && normalizeForMatch(b.scientificName) === normSci)
    );

    return match ? { id: match.id, ...match } : null;
}

/**
 * Helper: Maps a user/review bird choice back to a supported BirdDex bird record.
 */
async function resolveBirdChoiceToSupportedBird(choice) {
    if (!choice) return null;

    // 1. Try exact ID match
    const byId = await getBirdRecordById(choice.birdId);
    if (byId) return byId;

    // 2. Try exact Common Name match
    const byCommonName = await findBirdRecordByCommonName(choice.commonName);
    if (byCommonName) return byCommonName;

    // 3. Try exact Scientific Name match
    const byScientificName = await findBirdRecordByScientificName(choice.scientificName);
    if (byScientificName) return byScientificName;

    // 4. Fallback to fuzzy matching (case-insensitive, ignores punctuation)
    const fuzzy = await findBirdRecordFuzzy(choice);
    if (fuzzy) return fuzzy;

    return null;
}

/**
 * Helper: Converts values into one consistent shape so downstream logic can compare/store them safely.
 */
function normalizeBirdChoiceToken(value) {
    return sanitizeText(String(value || ""), 200).trim().toLowerCase();
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
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

/**
 * Helper: Uses OpenAI to help rank reasonable alternative bird choices for review.
 */
async function callOpenAiBirdReviewCandidates(base64Image, candidateA, candidateB, excludedBirds = [], options = {}) {
    const strictJsonOnly = options?.strictJsonOnly === true;
    const geoHintChoices = Array.isArray(options?.geoHintChoices) ? options.geoHintChoices : [];
    const geoHintLines = geoHintChoices
        .slice(0, 5)
        .map((choice, index) => `${index + 1}. ${choice.commonName || choice.species || "Unknown"}`)
        .filter(Boolean);
    const geoHintBlock = geoHintLines.length > 0
        ? `BirdDex location/date plausibility hints:
${geoHintLines.join("\n")}
`
        : "";
    const locationContextBlock = options?.locationContextBlock
        ? `Prefer birds that are plausible for this BirdDex user context:
${options.locationContextBlock}
`
        : "";
    const excludedLines = (excludedBirds || []).map((bird, index) => {
        const normalized = normalizeBirdData(bird, bird?.id || bird?.birdId || null);
        const birdId = normalized?.id || bird?.birdId || bird?.id || "Unknown";
        const commonName = normalized?.commonName || bird?.commonName || "Unknown";
        return `${index + 1}. ID: ${birdId} | Common Name: ${commonName}`;
    }).filter(Boolean);

    const excludedBirdsBlock = excludedLines.length > 0 ? excludedLines.join("\n") : "None";
    const strictInstructionBlock = strictJsonOnly
        ? `IMPORTANT: Your previous response was not valid JSON.
Return ONLY minified valid JSON matching the schema.
Do not include markdown, apologies, commentary, or code fences.
If unsure, still return valid JSON with an empty choices array.`
        : `Return EXACTLY valid JSON with this schema and nothing else:`;

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
${strictInstructionBlock}
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
Prefer returning birds that BirdDex is likely to support in its birds collection.
${locationContextBlock}${geoHintBlock}
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

/**
 * Helper: Retry wrapper for the review-candidate OpenAI call.
 */
async function callOpenAiBirdReviewCandidatesWithRetry(base64Image, candidateA, candidateB, excludedBirds = [], options = {}) {
    const firstResponse = await callOpenAiBirdReviewCandidates(base64Image, candidateA, candidateB, excludedBirds, {
        strictJsonOnly: false,
        locationContextBlock: options?.locationContextBlock || null,
    });

    if (typeof firstResponse === "string" && firstResponse.includes("GORE")) {
        return {
            responseText: firstResponse,
            retryCount: 0,
            hadJsonRetry: false,
            parseSucceeded: false,
            parsedChoices: [],
            parseErrorMessage: null,
        };
    }

    const firstParse = tryParseRankedBirdChoicesJson(firstResponse);
    if (firstParse.parseSucceeded) {
        return {
            responseText: firstResponse,
            retryCount: 0,
            hadJsonRetry: false,
            parseSucceeded: true,
            parsedChoices: firstParse.choices,
            parseErrorMessage: null,
        };
    }

    logger.warn("reviewBirdAlternatives: OpenAI review returned non-JSON. Retrying once with stricter JSON-only prompt.", {
        error: firstParse.parseErrorMessage,
    });

    const retryResponse = await callOpenAiBirdReviewCandidates(base64Image, candidateA, candidateB, excludedBirds, {
        strictJsonOnly: true,
        locationContextBlock: options?.locationContextBlock || null,
    });

    if (typeof retryResponse === "string" && retryResponse.includes("GORE")) {
        return {
            responseText: retryResponse,
            retryCount: 1,
            hadJsonRetry: true,
            parseSucceeded: false,
            parsedChoices: [],
            parseErrorMessage: null,
        };
    }

    const retryParse = tryParseRankedBirdChoicesJson(retryResponse);
    if (retryParse.parseSucceeded) {
        return {
            responseText: retryResponse,
            retryCount: 1,
            hadJsonRetry: true,
            parseSucceeded: true,
            parsedChoices: retryParse.choices,
            parseErrorMessage: null,
        };
    }

    logger.warn("reviewBirdAlternatives: OpenAI review retry still returned non-JSON. Continuing with empty alternatives.", {
        error: retryParse.parseErrorMessage,
    });

    return {
        responseText: retryResponse,
        retryCount: 1,
        hadJsonRetry: true,
        parseSucceeded: false,
        parsedChoices: [],
        parseErrorMessage: retryParse.parseErrorMessage || null,
    };
}

/**
 * Helper: Loads prior identification candidates from the stored identification log.
 */
async function loadSupportedReviewCandidatesFromLog(identificationLogData) {
    const orderedIds = [];
    const seenIds = new Set();

    const pushId = (value) => {
        const sanitized = sanitizeText(String(value || ""), 200).trim();
        if (!sanitized || seenIds.has(sanitized)) return;
        seenIds.add(sanitized);
        orderedIds.push(sanitized);
    };

    pushId(identificationLogData?.localModel?.top1BirdId || null);
    pushId(identificationLogData?.localModel?.top2BirdId || null);
    pushId(identificationLogData?.localModel?.top3BirdId || null);

    const modelAlternatives = Array.isArray(identificationLogData?.modelAlternatives)
        ? identificationLogData.modelAlternatives
        : [];
    for (const alternative of modelAlternatives) {
        pushId(alternative?.birdId || null);
    }

    const results = [];
    const resultIds = new Set();
    for (const birdId of orderedIds) {
        const bird = await getBirdById(birdId);
        const normalized = normalizeBirdData(bird, bird?.id || birdId || null);
        if (!normalized || !normalized.id || resultIds.has(normalized.id)) continue;
        resultIds.add(normalized.id);
        results.push(normalized);
        if (results.length >= 3) break;
    }

    return results;
}


/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
function buildReviewExcludedBirds(identificationLogData, supportedReviewCandidates = []) {
    const excludedBirds = [];
    const seenTokens = new Set();

    const pushBird = (birdLike) => {
        const normalized = normalizeBirdData(birdLike, birdLike?.id || birdLike?.birdId || null);
        if (!normalized || (!normalized.id && !normalized.commonName)) {
            return;
        }

        const token = normalized.id
            ? `id:${normalizeBirdChoiceToken(normalized.id)}`
            : `name:${normalizeBirdChoiceToken(normalized.commonName)}`;
        if (!token || seenTokens.has(token)) {
            return;
        }

        seenTokens.add(token);
        excludedBirds.push(normalized);
    };

    pushBird({
        birdId: identificationLogData?.finalResult?.birdId || null,
        commonName: identificationLogData?.finalResult?.commonName || null,
        scientificName: identificationLogData?.finalResult?.scientificName || null,
        species: identificationLogData?.finalResult?.species || null,
        family: identificationLogData?.finalResult?.family || null,
    });

    for (const candidate of (supportedReviewCandidates || [])) {
        pushBird(candidate);
    }

    return excludedBirds;
}

/**
 * Helper: Writes the full hybrid identification result/log to Firestore for later save/review/audit
 * flows.
 */
async function persistHybridIdentification({
    userId,
    imageUrl,
    locationId,
    latitude,
    longitude,
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
    captureSource,
    captureGuard,
    pointAwardDecision,
    rerankerResult,
    modelApiDiagnostics,
    observedAt = null,
}) {
    const identificationLogRef = db.collection("identificationLogs").doc();

    const safeTop1 = modelPredictions[0] || null;
    const safeTop2 = modelPredictions[1] || null;
    const safeTop3 = modelPredictions[2] || null;
    const birdMatch1 = normalizeBirdData(topBirdMatches[0] || null);
    const birdMatch2 = normalizeBirdData(topBirdMatches[1] || null);
    const birdMatch3 = normalizeBirdData(topBirdMatches[2] || null);
    const sightingLocationContext = await getLocationContext(locationId || null);
    const modelAlternativeChoices = await buildModelAlternativeChoices(
        topBirdMatches,
        sightingLocationContext,
        captureSource,
        observedAt,
        modelApiDiagnostics?.geo?.plausibilityByCommonName || null
    );

    const identificationLogData = {
        userId,
        timestamp: admin.firestore.FieldValue.serverTimestamp(),
        imageUrl: imageUrl || "",
        locationId: locationId || null,
        pipelineVersion: "hybrid_v2_reranker",
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
        reranker: {
            used: rerankerResult?.used === true,
            pairKey: rerankerResult?.pairKey || null,
            backbone: rerankerResult?.backbone || null,
            candidatePair: Array.isArray(rerankerResult?.candidatePair) ? rerankerResult.candidatePair : [],
            mainTop1Common: rerankerResult?.mainTop1Common || null,
            mainTop2Common: rerankerResult?.mainTop2Common || null,
            mainTop1BirdId: birdMatch1?.id || null,
            mainTop2BirdId: birdMatch2?.id || null,
            mainTop1Confidence: rerankerResult?.mainTop1Confidence ?? null,
            mainTop2Confidence: rerankerResult?.mainTop2Confidence ?? null,
            rerankerTop1Common: rerankerResult?.rerankerTop1Common || null,
            rerankerTop2Common: rerankerResult?.rerankerTop2Common || null,
            rerankerTop1BirdId: finalBirdId || null,
            rerankerTop2BirdId: null,
            rerankerTop1Confidence: rerankerResult?.rerankerTop1Confidence ?? null,
            rerankerTop2Confidence: rerankerResult?.rerankerTop2Confidence ?? null,
            flippedWinner: rerankerResult?.flippedWinner === true,
        },
        modelApiDiagnostics: {
            filename: modelApiDiagnostics?.filename || null,
            topPredictionCount: Array.isArray(modelPredictions) ? modelPredictions.length : 0,
            mainTopPredictionCount: Array.isArray(modelApiDiagnostics?.mainModelTopPredictions)
                ? modelApiDiagnostics.mainModelTopPredictions.length
                : 0,
            geo: modelApiDiagnostics?.geo ? {
                used: modelApiDiagnostics.geo.used === true,
                available: modelApiDiagnostics.geo.available !== false,
                fusionLam: modelApiDiagnostics.geo.fusionLam ?? null,
                imageTop1Confidence: modelApiDiagnostics.geo.imageTop1Confidence ?? null,
                observedAt: modelApiDiagnostics.geo.observedAt || null,
                latitude: modelApiDiagnostics.geo.latitude ?? null,
                longitude: modelApiDiagnostics.geo.longitude ?? null,
                reason: modelApiDiagnostics.geo.reason || null,
                imageTopPredictions: Array.isArray(modelApiDiagnostics.geo.imageTopPredictions) ? modelApiDiagnostics.geo.imageTopPredictions : [],
                geoTopPredictions: Array.isArray(modelApiDiagnostics.geo.geoTopPredictions) ? modelApiDiagnostics.geo.geoTopPredictions : [],
                fullGeoTopPredictions: Array.isArray(modelApiDiagnostics.geo.fullGeoTopPredictions) ? modelApiDiagnostics.geo.fullGeoTopPredictions : [],
                plausibilityByCommonName: modelApiDiagnostics.geo.plausibilityByCommonName && typeof modelApiDiagnostics.geo.plausibilityByCommonName === "object"
                    ? modelApiDiagnostics.geo.plausibilityByCommonName
                    : {},
            } : null,
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
        captureGuard: {
            captureSource: sanitizeCaptureSource(captureSource),
            analyzerVersion: captureGuard?.analyzerVersion || null,
            suspicionScore: captureGuard?.suspicionScore ?? null,
            suspicious: captureGuard?.suspicious === true,
            burstFrameCount: captureGuard?.burstFrameCount ?? 0,
            burstSpanMs: captureGuard?.burstSpanMs ?? 0,
            selectedFrameIndex: captureGuard?.selectedFrameIndex ?? 0,
            frameSimilarity: captureGuard?.frameSimilarity ?? null,
            aliasingScore: captureGuard?.aliasingScore ?? null,
            screenArtifactScore: captureGuard?.screenArtifactScore ?? null,
            borderScore: captureGuard?.borderScore ?? null,
            glareScore: captureGuard?.glareScore ?? null,
            selectedFrameSharpness: captureGuard?.selectedFrameSharpness ?? null,
            reasons: Array.isArray(captureGuard?.reasons) ? captureGuard.reasons : [],
        },
        pointAwardDecision: {
            allowPointAward: pointAwardDecision?.allowPointAward === true,
            reason: pointAwardDecision?.reason || null,
            userMessage: pointAwardDecision?.userMessage || null,
            captureSource: pointAwardDecision?.captureSource || sanitizeCaptureSource(captureSource),
            suspicionScore: pointAwardDecision?.suspicionScore ?? null,
            screenArtifactScore: pointAwardDecision?.screenArtifactScore ?? null,
            suspicionThreshold: pointAwardDecision?.suspicionThreshold ?? null,
            strongSecondarySignal: pointAwardDecision?.strongSecondarySignal === true,
        },
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
            pipelineVersion: "hybrid_v2_reranker",
            captureGuard: {
                captureSource: sanitizeCaptureSource(captureSource),
                analyzerVersion: captureGuard?.analyzerVersion || null,
                suspicionScore: captureGuard?.suspicionScore ?? null,
                suspicious: captureGuard?.suspicious === true,
                burstFrameCount: captureGuard?.burstFrameCount ?? 0,
                burstSpanMs: captureGuard?.burstSpanMs ?? 0,
                selectedFrameIndex: captureGuard?.selectedFrameIndex ?? 0,
                frameSimilarity: captureGuard?.frameSimilarity ?? null,
                aliasingScore: captureGuard?.aliasingScore ?? null,
                screenArtifactScore: captureGuard?.screenArtifactScore ?? null,
                borderScore: captureGuard?.borderScore ?? null,
                glareScore: captureGuard?.glareScore ?? null,
                selectedFrameSharpness: captureGuard?.selectedFrameSharpness ?? null,
                reasons: Array.isArray(captureGuard?.reasons) ? captureGuard.reasons : [],
            },
            pointAwardDecision: {
                allowPointAward: pointAwardDecision?.allowPointAward === true,
                reason: pointAwardDecision?.reason || null,
                userMessage: pointAwardDecision?.userMessage || null,
                captureSource: pointAwardDecision?.captureSource || sanitizeCaptureSource(captureSource),
                suspicionScore: pointAwardDecision?.suspicionScore ?? null,
                screenArtifactScore: pointAwardDecision?.screenArtifactScore ?? null,
                suspicionThreshold: pointAwardDecision?.suspicionThreshold ?? null,
                strongSecondarySignal: pointAwardDecision?.strongSecondarySignal === true,
            },
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

        if (typeof latitude === "number" && typeof longitude === "number") {
            await upsertUserBirdSightingWithDailyCooldown({
                userId,
                birdId: finalBirdId,
                commonName: finalBirdData.commonName || null,
                scientificName: finalBirdData.scientificName || null,
                locationId: locationId || null,
                latitude,
                longitude,
                locality: sightingLocationContext.locality || null,
                state: sightingLocationContext.state === "GA" ? "Georgia" : (sightingLocationContext.state || null),
                country: sightingLocationContext.country === "US" ? "United States" : (sightingLocationContext.country || null),
                quantity: "1-3",
                identificationId,
                suspicious: captureGuard?.suspicious === true,
            });
        }

        await identificationLogRef.set({ identificationId }, { merge: true });
    }

    return {
        identificationLogId: identificationLogRef.id,
        identificationId,
        modelAlternativeChoices,
    };
}

/**
 * Helper: Builds a structured payload/default/response object used by later logic.
 */
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
    modelTop1Confidence,
    modelTop2Confidence,
    pointAwardDecision,
    userMessage = null,
    qualityAssessment = null,
}) {
    const notMyBirdGate = buildNotMyBirdGate(modelTop1Confidence, modelTop2Confidence);
    return {
        result: finalIdentification || null,
        isVerified: !!isVerified,
        isGore: !!isGore,
        isInDatabase: !!isVerified,
        userMessage,
        qualityAssessment,
        imageUrl: imageUrl || "",
        identificationLogId: identificationLogId || null,
        identificationId: identificationId || null,
        modelTop1Confidence: notMyBirdGate.top1Confidence,
        modelTop2Confidence: notMyBirdGate.top2Confidence,
        modelConfidenceMargin: notMyBirdGate.confidenceMargin,
        notMyBirdAllowed: notMyBirdGate.allowed,
        notMyBirdBlockMessage: notMyBirdGate.blockMessage,
        allowPointAward: pointAwardDecision?.allowPointAward === true,
        pointAwardBlockReason: pointAwardDecision?.reason || null,
        pointAwardUserMessage: pointAwardDecision?.userMessage || null,
        primaryBird: isVerified && finalBirdData
            ? buildBirdChoicePayload(normalizeBirdData(finalBirdData, finalBirdData.id || null), finalSource || "initial_result")
            : null,
        modelAlternatives: Array.isArray(modelAlternativeChoices) ? modelAlternativeChoices : [],
        openAiAlternatives: [],
    };
}


/**
 * Helper: Writes or updates the user's map/nearby sighting while enforcing the same
 * 1 sighting per user per species per 24 hours cooldown used by recordBirdSighting.
 * If a sighting for the same bird already exists inside the 24-hour window, it is
 * updated to the newest identification/location instead of creating a second pin.
 */
async function upsertUserBirdSightingWithDailyCooldown({
    userId,
    birdId,
    commonName,
    scientificName,
    locationId,
    latitude,
    longitude,
    locality,
    state,
    country,
    quantity = "1",
    timestampMs = Date.now(),
    userBirdId = "",
    identificationId = null,
    suspicious = false,
}) {
    const roundedLatitude = roundCoordinateForStorage(latitude);
    const roundedLongitude = roundCoordinateForStorage(longitude);
    if (roundedLatitude === null || roundedLongitude === null) {
        return { recorded: false, reason: "missing_coordinates" };
    }

    const hotspotId = calculateHotspotBucketId(roundedLatitude, roundedLongitude);
    const birdKey = buildHotspotBirdKey({ birdId, commonName, userBirdId });
    const COOLDOWN_MS = 24 * 60 * 60 * 1000;
    const now = Date.now();
    const sightingTimestampMs = Number.isFinite(Number(timestampMs)) ? Number(timestampMs) : now;
    const cutoffDate = new Date(now - COOLDOWN_MS);

    const cooldownRef = db.collection("users").doc(userId)
        .collection("settings").doc("heatmapCooldowns");
    const sightingsQuery = db.collection("userBirdSightings")
        .where("userId", "==", userId)
        .where("birdId", "==", birdId)
        .where("timestamp", ">=", cutoffDate)
        .limit(5);

    const result = await db.runTransaction(async (transaction) => {
        const [cooldownSnap, recentSightingsSnap] = await Promise.all([
            transaction.get(cooldownRef),
            transaction.get(sightingsQuery),
        ]);

        const speciesCooldowns = {};
        if (cooldownSnap.exists) {
            const raw = cooldownSnap.data()?.speciesCooldowns;
            if (raw && typeof raw === "object") {
                Object.assign(speciesCooldowns, raw);
            }
        }

        const lastUploadMs = typeof speciesCooldowns[birdId] === "number"
            ? speciesCooldowns[birdId]
            : 0;

        let existingDoc = null;
        if (!recentSightingsSnap.empty) {
            existingDoc = recentSightingsSnap.docs.sort((a, b) => {
                const av = a.get("timestamp");
                const bv = b.get("timestamp");
                const ams = av && typeof av.toMillis === "function" ? av.toMillis() : (av instanceof Date ? av.getTime() : 0);
                const bms = bv && typeof bv.toMillis === "function" ? bv.toMillis() : (bv instanceof Date ? bv.getTime() : 0);
                return bms - ams;
            })[0];
        }

        const withinCooldown = (now - lastUploadMs) < COOLDOWN_MS;
        const sightingRef = existingDoc ? existingDoc.ref : db.collection("userBirdSightings").doc();
        const sightingId = existingDoc ? existingDoc.id : sightingRef.id;
        const payload = {
            id: sightingId,
            userId,
            birdId,
            birdKey,
            hotspotId,
            locationId: locationId || null,
            identificationId: identificationId || null,
            commonName: commonName || "",
            scientificName: scientificName || "",
            userBirdId: userBirdId || "",
            timestamp: new Date(sightingTimestampMs),
            latitude: roundedLatitude,
            longitude: roundedLongitude,
            state: state || "",
            locality: locality || "",
            country: country || "US",
            quantity: quantity || "1",
            suspicious: suspicious === true,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        };

        if (!existingDoc) {
            payload.createdAt = admin.firestore.FieldValue.serverTimestamp();
        }

        transaction.set(sightingRef, payload, { merge: true });

        // Keep the original 24-hour cooldown behavior, but let a new identification
        // update the existing same-day sighting instead of creating a hidden duplicate.
        speciesCooldowns[birdId] = now;
        transaction.set(cooldownRef, {
            speciesCooldowns,
            updatedAt: now,
        }, { merge: true });

        return {
            recorded: !withinCooldown || !existingDoc,
            updatedExisting: withinCooldown && !!existingDoc,
            sightingId,
            hotspotId,
            birdKey,
        };
    });

    await recomputeHotspotVoteSummaryForHotspot(result.hotspotId);
    return result;
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
/**
 * Export: Main bird-identification callable. Runs the hybrid model/OpenAI flow, stores identification
 * logs, and returns point-award eligibility details.
 */
exports.identifyBird = secureOnCall({ secrets: [OPENAI_API_KEY, BIRDDEX_MODEL_API_KEY], timeoutSeconds: 60 }, async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Login required.");

    const userId = request.auth.uid;
    const userRef = db.collection("users").doc(userId);
    const {
        image,
        imageUrl,
        latitude,
        longitude,
        localityName,
        observedAt,
        requestId,
        captureSource,
        captureGuard,
    } = request.data || {};

    const idempotencyKey = requestId || `IDEN_${admin.firestore.Timestamp.now().toMillis()}`;
    const eventLogRef = db.collection("processedAIEvents").doc(idempotencyKey);

    if (!image || typeof image !== "string") {
        throw new HttpsError("invalid-argument", "Image Base64 data is required.");
    }
    const safeLat = typeof latitude === "number" && Number.isFinite(latitude) ? latitude : null;
    const safeLng = typeof longitude === "number" && Number.isFinite(longitude) ? longitude : null;


    const normalizedCaptureSource = sanitizeCaptureSource(captureSource);
    const normalizedCaptureGuard = sanitizeCaptureGuardPayload(captureGuard);
    const pointAwardDecision = buildPointAwardDecision({
        captureSource: normalizedCaptureSource,
        captureGuard: normalizedCaptureGuard,
    });

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

        await db.runTransaction(async (t) => {
            await assertAndConsumeUserRateLimit(t, userId, "identifyBird", USER_RATE_LIMITS.identifyBird, {
                idempotencyKey,
            });
        });

        const locationId = await getOrCreateLocation(safeLat, safeLng, localityName, db, { userId });
        const userLocationContext = await getLocationContext(locationId);
        const modelApiResult = await callBirdModelApi({
                    imageBase64: image,
                    latitude: safeLat,
                    longitude: safeLng,
                    observedAt: typeof observedAt === "string" ? observedAt : null,
                    topK: HYBRID_ID_CONFIG.MODEL_TOP_K,
        });
        const mainModelPredictions = Array.isArray(modelApiResult?.mainModelTopPredictions)
            ? modelApiResult.mainModelTopPredictions
            : [];
        const modelPredictions = Array.isArray(modelApiResult?.topPredictions)
            ? modelApiResult.topPredictions
            : mainModelPredictions;
        const rerankerResult = modelApiResult?.reranker || { used: false };

        const topBirdMatches = await Promise.all(
            mainModelPredictions.slice(0, 3).map((prediction) => findBirdRecordByCommonName(prediction.commonName))
        );

        const top1 = mainModelPredictions[0] || null;
        const top2 = mainModelPredictions[1] || null;
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
        let parsedOpenAi = null;

        const needsFullFallback = !top1Bird || top1Confidence < HYBRID_ID_CONFIG.MODEL_FULL_FALLBACK_CONFIDENCE_THRESHOLD;
        const shouldPreferModelDirect = !!top1Bird
            && top1Confidence >= HYBRID_ID_CONFIG.MODEL_DIRECT_CONFIDENCE_THRESHOLD
            && topMargin >= HYBRID_ID_CONFIG.MODEL_DIRECT_MARGIN_THRESHOLD;
        const shouldUseReranker = rerankerResult?.used === true
            && !!top2Bird
            && !shouldPreferModelDirect
            && !needsFullFallback;

        if (shouldPreferModelDirect) {
            finalBirdData = top1Bird;
            finalBirdId = top1Bird.id;
            finalSource = "local_model";
            isVerified = true;
            finalIdentification = buildBirdIdentificationText(finalBirdId, finalBirdData);
        } else if (shouldUseReranker) {
            const rerankerTopPrediction = modelPredictions[0] || null;
            const rerankerTopBird = await findBirdByCommonName(rerankerTopPrediction?.commonName || null);
            const normalizedRerankerTopBird = normalizeBirdData(rerankerTopBird || null);

            if (normalizedRerankerTopBird && normalizedRerankerTopBird.id) {
                finalBirdData = normalizedRerankerTopBird;
                finalBirdId = normalizedRerankerTopBird.id;
                finalSource = "reranker_tiebreak";
                isVerified = true;
                finalIdentification = buildBirdIdentificationText(finalBirdId, finalBirdData);
                decisionReason = rerankerResult?.flippedWinner === true
                    ? "reranker_flipped_top1"
                    : (topMargin <= HYBRID_ID_CONFIG.MODEL_TIEBREAK_MARGIN_THRESHOLD
                        ? "reranker_top2_close"
                        : "reranker_margin_below_direct_threshold");
            } else {
                decisionReason = "reranker_unverified";
            }
        }

        if (!isVerified) {
            usedOpenAi = true;
            decisionReason = decisionReason === "reranker_unverified"
                ? "reranker_unverified_full_fallback"
                : (!top1Bird ? "top1_not_in_supported_birds" : "low_confidence");
            openAiMode = "full_fallback";

            await reserveOpenAiQuota(userRef, eventLogRef, userId);

            openAiRawResponse = await callOpenAiBirdFullFallback(image);

            if (openAiRawResponse.includes("GORE")) {
                const goreLogRef = db.collection("identificationLogs").doc();
                await goreLogRef.set({
                    userId,
                    timestamp: admin.firestore.FieldValue.serverTimestamp(),
                    imageUrl: imageUrl || "",
                    locationId: locationId || null,
                    pipelineVersion: "hybrid_v2_reranker",
                    modelVersion,
                    decision: {
                        usedOpenAi: true,
                        decisionReason,
                    },
                    reranker: {
                        used: rerankerResult?.used === true,
                        pairKey: rerankerResult?.pairKey || null,
                        backbone: rerankerResult?.backbone || null,
                        candidatePair: Array.isArray(rerankerResult?.candidatePair) ? rerankerResult.candidatePair : [],
                    },
                    openAi: {
                        used: true,
                        mode: openAiMode,
                        responseText: openAiRawResponse,
                    },
                    captureGuard: {
                        captureSource: normalizedCaptureSource,
                        ...normalizedCaptureGuard,
                    },
                    pointAwardDecision,
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
                    allowPointAward: false,
                    pointAwardBlockReason: pointAwardDecision.reason,
                    pointAwardUserMessage: pointAwardDecision.userMessage,
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

            parsedOpenAi = parseBirdIdentificationText(openAiRawResponse);
            const matchedBird = await resolveBirdChoiceToSupportedBird(parsedOpenAi);

            if (matchedBird) {
                finalBirdData = matchedBird;
                finalBirdId = matchedBird.id;
                finalSource = "openai_full";
                isVerified = true;
                finalIdentification = buildBirdIdentificationText(finalBirdId, finalBirdData);

                const openAiWinnerPlausibility = await buildBirdLocationPlausibility(
                    matchedBird,
                    userLocationContext,
                    typeof observedAt === "string" ? observedAt : null,
                    modelApiResult?.geo?.plausibilityByCommonName || null
                );
                if (!openAiWinnerPlausibility.locationPlausible) {
                    decisionReason = "openai_low_plausibility";
                }
            } else {
                finalBirdData = null;
                finalBirdId = null;
                finalSource = "openai_full_unverified";
                isVerified = false;
                finalIdentification = null;

                // Check if it's because the bird isn't in our database or because of image quality
                if (parsedOpenAi.commonName && parsedOpenAi.commonName !== "Unknown") {
                    // OpenAI identified a bird, but we don't support it yet
                    decisionReason = "openai_bird_not_in_database";
                } else if (parsedOpenAi.qualityAssessment && parsedOpenAi.qualityAssessment.toLowerCase() !== "clear") {
                    decisionReason = `openai_quality_${parsedOpenAi.qualityAssessment.replace(/\s+/g, "_").toLowerCase()}`;
                }
            }
        }
        const persisted = await persistHybridIdentification({
            userId,
            imageUrl,
            locationId,
            latitude: safeLat,
            longitude: safeLng,
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
            captureSource: normalizedCaptureSource,
            captureGuard: normalizedCaptureGuard,
            pointAwardDecision,
            rerankerResult,
            modelApiDiagnostics: modelApiResult,
            observedAt: typeof observedAt === "string" ? observedAt : null,
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
                modelTop1Confidence: top1Confidence,
                modelTop2Confidence: top2Confidence,
                pointAwardDecision,
                qualityAssessment: (usedOpenAi && parsedOpenAi?.qualityAssessment) ? parsedOpenAi.qualityAssessment : null,
            })
            : {
                result: null,
                isVerified: false,
                isGore: false,
                isInDatabase: decisionReason !== "openai_bird_not_in_database",
                userMessage: decisionReason === "openai_location_gate_blocked"
                    ? (captureSource === "gallery_import"
                        ? "BirdDex could not verify an AI result that looks plausible for the location and date in your uploaded photo."
                        : "BirdDex could not verify an AI result that looks plausible for your location and date.")
                    : (decisionReason === "openai_bird_not_in_database"
                        ? `Sorry, ${parsedOpenAi?.commonName || "this bird"} is not in our database just yet.`
                        : (decisionReason.startsWith("openai_quality_")
                            ? (parsedOpenAi?.qualityAssessment || `We couldn't identify the bird because the photo is ${decisionReason.replace("openai_quality_", "").replace(/_/g, " ")}. Try to get a clearer shot!`)
                            : "We couldn't identify the bird in this photo. For the best results, try to get a clear, close-up shot with the bird centered and plenty of light!")),
                qualityAssessment: (usedOpenAi && parsedOpenAi?.qualityAssessment) ? parsedOpenAi.qualityAssessment : null,
                reasonCode: decisionReason === "openai_location_gate_blocked"
                    ? "OPENAI_LOCATION_NOT_PLAUSIBLE"
                    : (decisionReason === "openai_bird_not_in_database" ? "NOT_IN_DATABASE" : "IMAGE_QUALITY_ISSUE"),
                imageUrl: imageUrl || "",
                identificationLogId: persisted.identificationLogId,
                identificationId: persisted.identificationId,
                modelTop1Confidence: top1Confidence,
                modelTop2Confidence: top2Confidence,
                modelConfidenceMargin: top1Confidence - top2Confidence,
                notMyBirdAllowed: true,
                notMyBirdBlockMessage: null,
                allowPointAward: false,
                pointAwardBlockReason: pointAwardDecision.reason,
                pointAwardUserMessage: pointAwardDecision.userMessage,
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
        const errorData = error.response?.data;
        logger.error("Hybrid identification failed:", {
            message: error.message,
            data: errorData,
            stack: error.stack,
        });

        if (error instanceof HttpsError) throw error;

        const detail = errorData ? ` (Details: ${JSON.stringify(errorData)})` : "";
        throw new HttpsError("internal", `Hybrid identification failed: ${error.message}${detail}`);
    }
});

/**
 * Export: Callable used by the “not my bird” / review flow to build safer alternative bird choices from
 * the prior identification log.
 */
exports.reviewBirdAlternatives = secureOnCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
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

        const reviewLocation = await getLocationContext(identificationLogData.locationId || null);
        const reviewDateText = new Date().toLocaleDateString("en-US", {
            timeZone: "America/New_York",
            year: "numeric",
            month: "long",
            day: "numeric",
        });
        const locationContextBlock = [
            reviewLocation.locality ? `Locality: ${reviewLocation.locality}` : null,
            reviewLocation.state ? `State: ${reviewLocation.state}` : null,
            reviewLocation.latitude !== null && reviewLocation.longitude !== null
                ? `Coordinates: ${reviewLocation.latitude}, ${reviewLocation.longitude}`
                : null,
            `Date: ${reviewDateText}`,
        ].filter(Boolean).join("\n");
        const supportedReviewCandidates = await loadSupportedReviewCandidatesFromLog(identificationLogData);
        const reviewHintA = supportedReviewCandidates[0] || null;
        const reviewHintB = supportedReviewCandidates[1] || null;
        const excludedBirds = buildReviewExcludedBirds(identificationLogData, supportedReviewCandidates.slice(0, 3).filter(Boolean));
        const excludedTokens = buildExcludedBirdChoiceTokens(excludedBirds);

        const cachedReviewResponse = identificationLogData.openAi?.reviewCachedResponse;
        const logCaptureSource = identificationLogData.captureGuard?.captureSource || null;
        const reviewGeoPlausibilityMap = identificationLogData?.modelApiDiagnostics?.geo?.plausibilityByCommonName || null;
        if (cachedReviewResponse && typeof cachedReviewResponse === "object") {
            const cachedAlternatives = Array.isArray(cachedReviewResponse.openAiAlternatives)
                ? cachedReviewResponse.openAiAlternatives
                : [];
            const filteredCachedAlternatives = await buildPlausibleReviewChoices(
                cachedAlternatives,
                reviewLocation,
                excludedTokens,
                2,
                logCaptureSource,
                identificationLogData?.modelApiDiagnostics?.geo?.observedAt || null,
                reviewGeoPlausibilityMap
            );
            const cachedResult = {
                isVerified: filteredCachedAlternatives.length > 0,
                isGore: !!cachedReviewResponse.isGore,
                isInDatabase: filteredCachedAlternatives.length > 0,
                userMessage: filteredCachedAlternatives.length > 0
                    ? (cachedReviewResponse.userMessage || null)
                    : (logCaptureSource === "gallery_import"
                        ? "Sorry, AI could not find more BirdDex birds that look plausible for the location and date in your uploaded photo."
                        : "Sorry, AI could not find more BirdDex birds that look plausible for your location and date."),
                openAiAlternatives: filteredCachedAlternatives,
                identificationLogId,
                imageUrl: cachedReviewResponse.imageUrl || imageUrl || identificationLogData.imageUrl || "",
            };

            await eventLogRef.set({
                userId,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
                pending: false,
                result: cachedResult,
            }, { merge: true });

            return cachedResult;
        }

        if (!reviewHintA || !reviewHintB) {
            const unavailableResult = {
                isVerified: false,
                isGore: false,
                isInDatabase: false,
                userMessage: "Sorry, BirdDex could not save enough alternatives to run AI review for this photo.",
                openAiAlternatives: [],
                identificationLogId,
                imageUrl: imageUrl || identificationLogData.imageUrl || "",
            };

            await identificationLogRef.set({
                openAi: {
                    reviewRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
                    reviewResponseText: null,
                    reviewCandidates: [],
                    excludedBirdIds: excludedBirds.map((bird) => bird.id || null).filter(Boolean),
                    excludedCommonNames: excludedBirds.map((bird) => bird.commonName || null).filter(Boolean),
                    reviewCachedResponse: unavailableResult,
                    reviewRetryCount: 0,
                    reviewHadJsonRetry: false,
                },
                userFeedback: {
                    status: "openai_review_unavailable_not_enough_model_candidates",
                    feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
                },
            }, { merge: true });
            await eventLogRef.set({
                userId,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
                pending: false,
                result: unavailableResult,
            }, { merge: true });
            return unavailableResult;
        }

        await reserveOpenAiQuota(userRef, eventLogRef, userId);

        const geoHintChoices = Array.isArray(identificationLogData?.modelApiDiagnostics?.geo?.fullGeoTopPredictions)
            ? identificationLogData.modelApiDiagnostics.geo.fullGeoTopPredictions
            : (Array.isArray(identificationLogData?.modelApiDiagnostics?.geo?.geoTopPredictions)
                ? identificationLogData.modelApiDiagnostics.geo.geoTopPredictions
                : []);

        const reviewAttempt = await callOpenAiBirdReviewCandidatesWithRetry(image, reviewHintA, reviewHintB, excludedBirds, {
            locationContextBlock,
            geoHintChoices,
        });
        const openAiRawResponse = reviewAttempt.responseText;
        if (openAiRawResponse.includes("GORE")) {
            const goreResult = {
                isVerified: false,
                isGore: true,
                isInDatabase: false,
                userMessage: "Please take a picture of a non-gore picture of a bird.",
                openAiAlternatives: [],
                identificationLogId,
                imageUrl: imageUrl || identificationLogData.imageUrl || "",
            };

            await identificationLogRef.set({
                openAi: {
                    reviewRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
                    reviewResponseText: openAiRawResponse,
                    reviewCandidates: [],
                    excludedBirdIds: excludedBirds.map((bird) => bird.id || null).filter(Boolean),
                    excludedCommonNames: excludedBirds.map((bird) => bird.commonName || null).filter(Boolean),
                    reviewCachedResponse: goreResult,
                    reviewRetryCount: reviewAttempt.retryCount,
                    reviewHadJsonRetry: reviewAttempt.hadJsonRetry,
                },
                userFeedback: {
                    status: "openai_review_blocked_gore",
                    feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
                },
            }, { merge: true });
            await eventLogRef.set({
                userId,
                processedAt: admin.firestore.FieldValue.serverTimestamp(),
                pending: false,
                result: goreResult,
            }, { merge: true });
            return goreResult;
        }

        const parsedChoices = reviewAttempt.parseSucceeded
            ? (Array.isArray(reviewAttempt.parsedChoices) ? reviewAttempt.parsedChoices : [])
            : [];
        const parsedChoicesWithSource = parsedChoices.map((choice) => ({
            ...choice,
            source: "openai_review",
        }));
        const responseChoices = await buildPlausibleReviewChoices(
            parsedChoicesWithSource,
            reviewLocation,
            excludedTokens,
            2,
            logCaptureSource,
            identificationLogData?.modelApiDiagnostics?.geo?.observedAt || null,
            reviewGeoPlausibilityMap
        );

        const response = {
            isVerified: responseChoices.length > 0,
            isGore: false,
            isInDatabase: responseChoices.length > 0,
            userMessage: responseChoices.length > 0
                ? null
                : (reviewAttempt.parseSucceeded
                    ? (logCaptureSource === "gallery_import"
                        ? "Sorry, AI could not find more BirdDex birds that look plausible for the location and date in your uploaded photo."
                        : "Sorry, AI could not find more BirdDex birds that look plausible for your location and date.")
                    : "Sorry, AI could not return usable alternatives right now. Please try again."),
            openAiAlternatives: responseChoices,
            identificationLogId,
            imageUrl: imageUrl || identificationLogData.imageUrl || "",
        };

        await identificationLogRef.set({
            openAi: {
                reviewRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
                reviewResponseText: openAiRawResponse,
                reviewCandidates: responseChoices,
                excludedBirdIds: excludedBirds.map((bird) => bird.id || null).filter(Boolean),
                excludedCommonNames: excludedBirds.map((bird) => bird.commonName || null).filter(Boolean),
                reviewCachedResponse: response,
                reviewRetryCount: reviewAttempt.retryCount,
                reviewHadJsonRetry: reviewAttempt.hadJsonRetry,
                reviewParseSucceeded: reviewAttempt.parseSucceeded,
                reviewParseErrorMessage: reviewAttempt.parseErrorMessage || null,
            },
            userFeedback: {
                status: responseChoices.length > 0 ? "openai_review_candidates_ready" : "openai_review_no_candidates",
                feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
            },
        }, { merge: true });

        await eventLogRef.set({
            userId,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            pending: false,
            result: response,
        }, { merge: true });

        return response;
    } catch (error) {
        const errorData = error.response?.data;
        logger.error("Alternative review failed:", {
            message: error.message,
            data: errorData,
            stack: error.stack,
        });

        if (error instanceof HttpsError) throw error;

        const detail = errorData ? ` (Details: ${JSON.stringify(errorData)})` : "";
        throw new HttpsError("internal", `Alternative review failed: ${error.message}${detail}`);
    }
});

/**
 * Helper: Timestamp/date conversion helper used to keep time comparisons consistent.
 */
function timestampLikeToMillis(value) {
    if (!value) return 0;
    if (typeof value.toMillis === "function") {
        return value.toMillis();
    }
    if (value instanceof admin.firestore.Timestamp) {
        return value.toMillis();
    }
    if (typeof value._seconds === "number") {
        const nanos = typeof value._nanoseconds === "number" ? value._nanoseconds : 0;
        return (value._seconds * 1000) + Math.floor(nanos / 1000000);
    }
    if (typeof value.seconds === "number") {
        const nanos = typeof value.nanoseconds === "number" ? value.nanoseconds : 0;
        return (value.seconds * 1000) + Math.floor(nanos / 1000000);
    }
    if (typeof value === "number") {
        return value;
    }
    return 0;
}

/**
 * Helper: Stores feedback events while enforcing per-log cooldowns and limits.
 */
async function writeIdentificationFeedbackEventWithGuards({
    identificationLogRef,
    userId,
    identificationId,
    action,
    selectedSource,
    normalizedNote,
    updatePayload,
}) {
    const isSubmitFeedback = action === "submit_feedback";
    const isCouldntFindBird = action === "couldnt_find_your_bird";
    if (!isSubmitFeedback && !isCouldntFindBird) {
        throw new HttpsError("invalid-argument", "Unsupported guarded feedback action.");
    }

    const cooldownMs = isSubmitFeedback
        ? IDENTIFICATION_FEEDBACK_CONFIG.SUBMIT_FEEDBACK_COOLDOWN_MS
        : IDENTIFICATION_FEEDBACK_CONFIG.COULDNT_FIND_BIRD_COOLDOWN_MS;
    const maxPerLog = isSubmitFeedback
        ? IDENTIFICATION_FEEDBACK_CONFIG.SUBMIT_FEEDBACK_MAX_PER_LOG
        : IDENTIFICATION_FEEDBACK_CONFIG.COULDNT_FIND_BIRD_MAX_PER_LOG;

    return db.runTransaction(async (transaction) => {
        const freshLogDoc = await transaction.get(identificationLogRef);
        if (!freshLogDoc.exists) {
            throw new HttpsError("not-found", "Identification log not found.");
        }

        const freshLogData = freshLogDoc.data() || {};
        if (freshLogData.userId !== userId) {
            throw new HttpsError("permission-denied", "You do not have access to this identification log.");
        }

        const userFeedback = freshLogData.userFeedback || {};
        const countField = isSubmitFeedback ? "feedbackSubmissionCount" : "couldntFindBirdCount";
        const timestampField = isSubmitFeedback ? "lastSubmittedFeedbackAt" : "couldntFindBirdLastPressedAt";
        const currentCount = Number(userFeedback[countField]) || 0;
        if (currentCount >= maxPerLog) {
            throw new HttpsError(
                "resource-exhausted",
                isSubmitFeedback
                    ? "Feedback limit reached for this identification."
                    : "You already reported that we could not find your bird for this identification."
            );
        }

        const lastEventAtMs = timestampLikeToMillis(userFeedback[timestampField]);
        const nowMs = Date.now();
        if (lastEventAtMs > 0 && (nowMs - lastEventAtMs) < cooldownMs) {
            const secondsRemaining = Math.max(1, Math.ceil((cooldownMs - (nowMs - lastEventAtMs)) / 1000));
            throw new HttpsError(
                "resource-exhausted",
                isSubmitFeedback
                    ? `Please wait ${secondsRemaining} more second${secondsRemaining === 1 ? "" : "s"} before submitting more feedback.`
                    : `Please wait ${secondsRemaining} more second${secondsRemaining === 1 ? "" : "s"} before sending that report again.`
            );
        }

        const feedbackEntryRef = identificationLogRef.collection("feedbackEntries").doc();
        transaction.set(identificationLogRef, updatePayload, { merge: true });
        transaction.set(feedbackEntryRef, {
            entryType: action,
            stage: selectedSource || null,
            message: normalizedNote,
            userId,
            identificationId: identificationId || null,
            submittedAt: admin.firestore.FieldValue.serverTimestamp(),
            submittedAtMs: nowMs,
            storageMode: "subcollection_v1",
        });

        return {
            success: true,
            userMessage: isSubmitFeedback
                ? "Thanks, feedback submitted."
                : "Thanks. We logged that BirdDex could not find your bird.",
            feedbackEntryId: feedbackEntryRef.id,
        };
    });
}

/**
 * Export: Callable that records user feedback about an identification result so later tuning/auditing can
 * use it.
 */
exports.syncIdentificationFeedback = secureOnCall(async (request) => {
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

    const normalizedNote = typeof note === "string"
        ? sanitizeText(note, 2000).trim() || null
        : null;

    const updatePayload = {
        userFeedback: {
            feedbackTimestamp: admin.firestore.FieldValue.serverTimestamp(),
            lastAction: action,
            note: normalizedNote,
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

    if (action === "submit_feedback" && !normalizedNote) {
        throw new HttpsError("invalid-argument", "Feedback note is required.");
    }

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
    case "blocked_not_my_bird_press":
        updatePayload.userFeedback.notMyBirdBlockedPressCount = admin.firestore.FieldValue.increment(1);
        updatePayload.userFeedback.notMyBirdBlockedLastPressedAt = admin.firestore.FieldValue.serverTimestamp();
        updatePayload.userFeedback.notMyBirdBlockedCurrentBirdId = selectedBirdId || identificationLogData.finalResult?.birdId || null;
        updatePayload.userFeedback.notMyBirdBlockedCurrentSource = selectedSource || identificationLogData.finalResult?.source || null;
        updatePayload.userFeedback.notMyBirdBlockedCurrentCommonName = normalizedSelectedCommonName || identificationLogData.finalResult?.commonName || null;
        updatePayload.userFeedback.notMyBirdBlockedCurrentScientificName = normalizedSelectedScientificName || identificationLogData.finalResult?.scientificName || null;
        updatePayload.userFeedback.notMyBirdBlockedCurrentSpecies = normalizedSelectedSpecies || identificationLogData.finalResult?.species || null;
        updatePayload.userFeedback.notMyBirdBlockedCurrentFamily = normalizedSelectedFamily || identificationLogData.finalResult?.family || null;
        break;
    case "request_openai_review":
        updatePayload.userFeedback.status = "openai_review_requested";
        updatePayload.userFeedback.modelAlternativesRejectedAt = admin.firestore.FieldValue.serverTimestamp();
        break;
    case "submit_feedback":
        updatePayload.userFeedback.feedbackSubmissionCount = admin.firestore.FieldValue.increment(1);
        updatePayload.userFeedback.lastSubmittedFeedback = normalizedNote;
        updatePayload.userFeedback.lastSubmittedFeedbackStage = selectedSource || null;
        updatePayload.userFeedback.lastSubmittedFeedbackAt = admin.firestore.FieldValue.serverTimestamp();
        updatePayload.userFeedback.feedbackStorageMode = "subcollection_v1";
        break;
    case "couldnt_find_your_bird":
        updatePayload.userFeedback.couldntFindBirdCount = admin.firestore.FieldValue.increment(1);
        updatePayload.userFeedback.couldntFindBirdLastPressedAt = admin.firestore.FieldValue.serverTimestamp();
        updatePayload.userFeedback.couldntFindBirdLastStage = selectedSource || "not_my_bird";
        updatePayload.userFeedback.feedbackStorageMode = "subcollection_v1";
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

    let syncResponse = { success: true, userMessage: null };
    if (action === "submit_feedback" || action === "couldnt_find_your_bird") {
        syncResponse = await writeIdentificationFeedbackEventWithGuards({
            identificationLogRef,
            userId,
            identificationId,
            action,
            selectedSource,
            normalizedNote,
            updatePayload,
        });
    } else {
        await identificationLogRef.set(updatePayload, { merge: true });
    }

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

        const locationContext = await getLocationContext(identificationLogData.locationId);
        if (locationContext.latitude !== null && locationContext.longitude !== null) {
            const lat = locationContext.latitude;
            const lng = locationContext.longitude;
            const birdId = selectedBird?.id || identificationLogData.finalResult?.birdId || null;
            const commonName = selectedBird?.commonName || normalizedSelectedCommonName || identificationLogData.finalResult?.commonName || null;
            const scientificName = selectedBird?.scientificName || normalizedSelectedScientificName || identificationLogData.finalResult?.scientificName || null;

            if (birdId) {
                await upsertUserBirdSightingWithDailyCooldown({
                    userId,
                    birdId,
                    commonName,
                    scientificName,
                    locationId: identificationLogData.locationId || null,
                    latitude: lat,
                    longitude: lng,
                    locality: locationContext.locality || null,
                    state: locationContext.state === "GA" ? "Georgia" : (locationContext.state || null),
                    country: locationContext.country === "US" ? "United States" : (locationContext.country || null),
                    quantity: "1-3",
                    identificationId: identificationRef.id,
                    suspicious: identificationLogData.captureGuard?.suspicious === true,
                });
            }
        }
    }

    if (["select_model_alternative", "select_openai_alternative", "confirm_final_choice"].includes(action)) {
        const resolvedChosenBirdId = selectedBird?.id
            || updatePayload?.userFeedback?.correctedBirdId
            || identificationLogData?.finalResult?.birdId
            || null;
        const resolvedChosenCommonName = selectedBird?.commonName
            || updatePayload?.userFeedback?.correctedCommonName
            || identificationLogData?.finalResult?.commonName
            || null;

        await writeRerankerAccuracyMetrics({
            identificationLogRef,
            identificationLogData,
            resolvedChosenBirdId,
            resolvedChosenCommonName,
            action,
            selectedSource,
        });
    }

    return syncResponse;
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
/**
 * Export: Callable that loads bird metadata/facts and generates fresh facts only when cache is missing or
 * stale.
 */
exports.getBirdDetailsAndFacts = secureOnCall({ secrets: [OPENAI_API_KEY], timeoutSeconds: 60 }, async (request) => {
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

