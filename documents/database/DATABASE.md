1.users (UserID\_{uid})

  Core user profiles, points, and settings.

  Sub-collection: collectionSlot (slotId) – The 40-slot display grid.



2\. birds (BirdID\_{ebirdCode})

  Master taxonomy list of every bird in Georgia (synced via ebird\_ga\_cache).



3\. userBirds (UserBirdID\_{uuid})

  Primary records of birds a user has actually "captured" (linked to their specific image and date).



4\. birdFacts (BirdFactID\_{birdId})

  AI-generated general knowledge (size, diet, behavior).

  Sub-collection: hunterFacts (birdId) – Legal status, seasons, and GA DNR info.



5\. identifications (IdentID\_{uuid})

  Raw "training data" collection. Stores every attempt the AI makes to identify a bird, used for verification history.



6\. forumThreads (ThreadID\_{uuid})

  Top-level forum posts (captions, user info, likes, view counts).

  Sub-collection: comments (commentId) – UserID, text, and nested parentCommentId for replies.



7\. userBirdSightings (SightingID\_{uuid})

  Data source for the Heatmap. Stores the "when" and "where" for every bird spotted in the wild.



8\. locations (LocationID\_LOC\_{lat}\_{lng})

  Global coordinate registry. Prevents duplicate location strings and groups sightings by locality.



9\. reports (ReportID\_{uuid})

  Moderation queue for flagged forum posts or comments.



10\. ebird\_ga\_cache (data)

  Internal utility. Stores the last time the eBird API was called and the list of IDs to prevent hitting eBird's rate limits too often.



11\. eBirdApiSightings ({ebird\_subId})

  Stores "Notable Sightings" pulled directly from the eBird API (not user-generated) to show "Nearby" birds that haven't been caught by users yet.



12\. deletedforum\_backlog (BacklogID\_{uuid})

  Safety Archive: When a user deletes a post, the Cloud Function moves the data here before wiping it from forumThreads. This allows for recovery or legal moderation if needed.



13\. usersdeletedAccounts (DeletedUID\_{uid})

  Stores basic profile data of users who deleted their accounts (archived before cleanupUserData runs).



14\. upgradeCardData (UpgradeID\_{birdId})

  Contains the metadata for the Bird Cards game logic (rarity tiers, point values, and leveling requirements for specific species).











































1. users (UserID\_{firebase\_uid})

  email: string

  displayName: string (synchronized with username)

  createdAt: timestamp

  profilePictureUrl: string

  bio: string

  locationId: string (FK → locations)

  totalBirds: number (Total unique birds)

  duplicateBirds: number (Total sightings that were already in collection)

  totalPoints: number (Game points)

  openAiRequestsRemaining: number

  pfpChangesToday: number

  Sub-collection: collectionSlot (SlotID\_{index\_or\_uuid})

    userBirdId: string (FK → userBirds)

    birdId: string (FK → birds)

    imageUrl: string



2\. birds (BirdID\_{ebird\_species\_code} – e.g., BirdID\_bkcchi)

  commonName: string

  scientificName: string

  family: string

  species: string

  isEndangered: boolean

  canHunt: boolean

  lastSeenLocationIdGeorgia: string (FK → locations)

  lastSeenTimestampGeorgia: timestamp



3\. userBirds (UserBirdID\_{unique\_upload\_id})

  userId: string (FK → users)

  birdId: string (FK → birds)

  captureDate: timestamp

  locationId: string (FK → locations)

  imageUrl: string

  pointsEarned: number

  isDuplicate: boolean



4\. birdFacts (BirdFactID\_{birdId})

  birdId: string (FK → birds)

  lastGenerated: timestamp

  generalFacts: Map (sizeAppearance, diet, etc.)

  Sub-collection: hunterFacts (HunterFactID\_{birdId})

    legalStatusGeorgia: string

    season: string

    relevantRegulations: string



5\. identifications (IdentID\_{unique\_id})

  userId: string

  birdId: string (or "Unknown")

  commonName: string

  scientificName: string

  family: string

  species: string

  locationId: string (FK → locations)

  verified: boolean

  imageUrl: string

  timestamp: timestamp



6\. forumThreads (ThreadID\_{unique\_id})

  userId: string (FK → users)

  username: string

  userProfilePictureUrl: string

  title: string

  message: string

  imageUrl: string (The photo shared)

  timestamp: timestamp

  likeCount: number

  commentCount: number

  hunted: boolean

  spotted: boolean

  Sub-collection: comments (CommentID\_{unique\_id})

    userId: string

    username: string

    text: string

    timestamp: timestamp

    parentCommentId: string (for nested replies)



7\. userBirdSightings (SightingID\_{unique\_id})

  userId: string

  birdId: string (FK → birds)

  locationId: string (FK → locations)

  isSpotted: boolean

  isHunted: boolean

  imageUrl: string

  timestamp: timestamp





8\. locations (LocationID\_LOC\_{lat}\_{lng} – e.g., LocationID\_LOC\_33.7490\_-84.3880)

  latitude: number

  longitude: number

  country: string

  state: string

  locality: string (City/Area name)

  metadata: Map (device sensors, accuracy)









9\. reports (ReportID\_{unique\_id})

  targetId: string (ThreadID or CommentID)

  targetType: string ("post" or "comment")

  reporterId: string (UserID)

  reason: string

  timestamp: timestamp

  status: string ("pending", "reviewed", "dismissed")



10\. eBirdApiSightings ({ebird\_subId} – e.g., S12345678)

  Source: Automated sync from the eBird API.

  speciesCode: string (e.g., "bkcchi")

  commonName: string

  scientificName: string

  observationDate: timestamp

  howMany: number (Default: 1)

  isReviewed: boolean (Whether eBird experts verified the sighting)

  location: Map

    latitude: number

    longitude: number

    localityName: string



11\. deletedforum\_backlog (BacklogID\_{uuid})

  Source: Triggered when a user or admin deletes a post or comment.

  type: string ("post" or "comment\_archived\_with\_post")

  originalId: string (The ID the document had in the forum)

  deletedBy: string (UserID of the person who deleted it)

  deletedAt: timestamp

  archivedAt: timestamp (Used by Cloud Functions)

  data: Map/Object (Full snapshot of the original post/comment content)

  archivedComments: Array<Map> (Only present for "post" types if comments were also archived)

  postId: string (Only present for "comment" types to link back to the parent)



12\. usersdeletedAccounts (DeletedUID\_{uid})

  Source: Triggered via the archiveAndDeleteUser function or onUserAuthDeleted trigger.

  originalUid: string

  email: string

  username: string

  profilePictureUrl: string

  totalPoints: number

  totalBirds: number

  archivedAt: timestamp

  deletionReason: string (e.g., "User requested account deletion")

  deletionType: string (e.g., "Automatic Auth Trigger")

  (Plus all other fields from the original user document at the time of deletion)


13\. ebird_ga_cache
 
birdIds: Array<String>
 
lastUpdated: Number (Timestamp) The time when the cache was last refreshed (ms).
