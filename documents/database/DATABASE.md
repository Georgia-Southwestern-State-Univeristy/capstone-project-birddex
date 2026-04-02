🐦 BirdDex Database Architecture
📑 Table of Contents
Overview
Core Collections
Identification Pipeline
Location & Sightings
Forum & Community
Moderation System
Archive & Recovery
External Data & Images
Event Processing
Storage Structure
Architecture Notes
System Flow
📖 Overview

BirdDex uses Firebase Cloud Firestore (NoSQL) to store application data.

The database supports:

AI-powered bird identification
User collections and profiles
Forum and social interaction
Location-based bird sightings
Moderation and audit systems
External API integrations (eBird, OpenAI, Nuthatch)
🧩 Core Collections
users (users/{uid})

Stores user profiles, stats, and moderation data.

Fields
email
username
displayName
bio
profilePictureUrl
createdAt
totalBirds
duplicateBirds
totalPoints
openAiRequestsRemaining
pfpChangesToday
warningCount
strikeCount
forumSuspendedUntil
permanentForumBan
lastViolationAt
Subcollections
collectionSlot   → BirdDex display grid
settings         → user preferences
following        → users followed
followers        → users following
savedPosts       → bookmarked posts
trackedBirds     → tracked species
rateLimits       → request limits
feedbackEntries  → AI feedback
usernames

Ensures unique usernames.

username (PK)
uid → users
birds

Master bird taxonomy (synced with eBird).

birdId (PK)
commonName
scientificName
family
species
isEndangered
canHunt
birdFacts

AI-generated bird info.

birdId → birds
lastGenerated
generalFacts (Map)
Subcollection: hunterFacts
legalStatusGeorgia
season
relevantRegulations
userBirds

Birds captured by users.

userBirdId (PK)
userId → users
birdId → birds
captureDate
locationId → locations
imageUrl
pointsEarned
isDuplicate
🤖 Identification Pipeline
identifications

Stores the entire AI decision pipeline.

Core Fields
identificationId (PK)
userId
timestamp
imageUrl
locationId
pipelineVersion
modelVersion
Nested Structures
localModel

Top 3 AI predictions.

decision

Confidence thresholds + logic.

openAi

OpenAI candidates + responses.

captureGuard

Anti-cheat + image validation.

pointAwardDecision

Controls point rewards.

finalResult
birdId
commonName
scientificName
verified
userFeedback

User corrections + confirmations.

training

AI training eligibility.

identificationLogs

Tracks identification pipeline steps.

images_fetched_identifications

Stores reference images used during AI processing.

🌍 Location & Sightings
locations

Central location registry.

locationId (PK)
latitude
longitude
country
state
locality
metadata
userBirdSightings

Used for Near Me / heatmap.

sightingId (PK)
userId
birdId
locationId
isSpotted
isHunted
imageUrl
timestamp
userBirdSightings_backlog

Archived sightings.

eBirdApiSightings

External sightings (not user-generated).

ebird_ga_cache

Caches bird data to reduce API calls.

💬 Forum & Community
forumThreads

Top-level posts.

postId (PK)
userId
username
title
message
imageUrl
timestamp
likeCount
commentCount
hunted
spotted
Subcollection: comments
commentId
userId
username
text
timestamp
parentCommentId
🛡 Moderation System
reports

User reports on posts/comments.

moderationEvents

Tracks:

warnings
strikes
bans
moderationAppeals

User appeals.

filteredContentLogs

Automated filtering logs.

privateAuditLogs

Admin/internal logs.

🗂 Archive & Recovery
deletedforum_backlog

Stores deleted forum content.

usersdeletedAccounts

Stores deleted user data.

eBirdApiSightings_backlog

Archived external sightings.

🖼 External Data & Images
nuthatch_images

Bird images from Nuthatch API.

inaturalist_images

Bird images from iNaturalist.

missing_hybrid_birds

Unsupported hybrid species.

still_missing_birds

Missing species tracking.

⚙️ Event Processing
processedEvents

General backend events.

processedAIEvents

AI-specific processing logs.

📦 Storage Structure
identificationImages/{uid}/{uuid}.jpg
user_images/{uid}/...
userCollectionImages/{uid}/...
profile_pictures/{uid}/...
forum_post_images/{imageId}.jpg
archive/forum_post_images/{uid}/...
🧠 Architecture Notes
Scalability

Firestore collections are separated by feature for performance.

Data Integrity
birds = single source of truth
locations = prevents duplicates
AI Pipeline

identifications stores full decision logic, not just results.

Moderation

Dedicated collections allow:

audit trails
safe community management
Archival Strategy

Backlog collections prevent permanent data loss.

🔄 System Flow
Capture Image
   ↓
AI Identification (OpenAI + Local Model)
   ↓
eBird Verification
   ↓
Store in Firestore
   ↓
Add to userBirds
   ↓
Add to userBirdSightings
   ↓
(Optional) Forum Post
✅ Summary

BirdDex’s database supports:

📸 Bird capture & identification
🧠 AI-driven validation pipeline
🗺 Location-based discovery
💬 Community interaction
🛡 Moderation & safety
📦 Scalable, real-time data storage
