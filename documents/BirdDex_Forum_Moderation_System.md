# BirdDex Forum Moderation System
_Current backend-aligned revision from `Current_App_Version.zip`_

## What this version is for
This writeup aligns the forum moderation overview with the current backend that is actually wired into BirdDex. It reflects the current thresholds, appeal plumbing, reviewer functions, Cloud Vision SafeSearch behavior, seeded user moderation fields, and the current rule that hidden posts keep coordinates while removed posts clear them.

## 1. Moderation scope at a glance

| Area | Current behavior in BirdDex |
|---|---|
| Forum scope | Open-topic social discussion is allowed. Moderation targets unsafe behavior and unsafe content rather than non-bird conversation by itself. |
| Edit window | Posts, comments, and replies can be edited for 5 minutes. |
| Restriction scope | Suspended or banned users can still sign in and read, but cannot post, comment, reply, or edit forum content. |
| Image moderation | Forum post images are checked in the backend with Vision SafeSearch before a post is accepted. |
| Appeals | Users can fetch their moderation state and submit one appeal per moderation event. Reviewers can approve or deny appeals through backend callables. |
| Map behavior | Hidden posts keep coordinates. Removed posts clear coordinates through a trigger. |

## 2. Content states and visibility

The current backend uses four explicit moderation states on posts, comments, and replies.

| `moderationStatus` | Meaning | Current effect |
|---|---|---|
| `visible` | Normal public state | Shows in public forum surfaces |
| `under_review` | Public but flagged | Still treated as public by helper logic |
| `hidden` | No longer public | Hidden from forum surfaces, but stored post coordinates are kept |
| `removed` | Stronger takedown state | Hidden from public surfaces; post coordinates are cleared automatically |

### Heat map behavior
A hidden post is reversible without losing its map data. A removed post is treated as a stronger takedown and clears `showLocation`, `latitude`, and `longitude` when the moderation status changes to `removed`.

## 3. User moderation fields

The backend seeds moderation fields when an account is initialized so restriction state exists from the start.

| Field | Purpose |
|---|---|
| `warningCount` | Tracks active warnings |
| `strikeCount` | Tracks active strikes |
| `forumSuspendedUntil` | End of a temporary forum restriction |
| `permanentForumBan` | Permanent forum-ban switch |
| `lastViolationAt` | Most recent moderation timestamp used for history and review context |

## 4. Reporting flow and thresholds

Reports are active moderation inputs in the current backend.

| Rule | Current implementation |
|---|---|
| Authentication | Reports come from the authenticated caller in a callable |
| Duplicate prevention | One report per reporter-target pair |
| Self-report blocking | Users cannot report their own post, comment, or reply |
| Reason normalization | Reasons are normalized into backend categories such as `language`, `image`, `spam`, `harassment`, and `other` |
| Hourly rate limit | A user can submit up to 10 reports per hour |
| Thresholds | 3 unique reporters -> `under_review`; 5 unique reporters -> `hidden` |

### Important detail
Only a hidden result currently triggers the automatic penalty helper for the content owner. `under_review` is a visibility state, not an automatic punishment by itself.

## 5. Automatic user penalties in the live backend

The current code uses an automatic penalty helper when content is hidden by repeated reports or when a forum image is blocked by SafeSearch.

| Step | What the current code does |
|---|---|
| First automatic penalty | If the user has 0 warnings and 0 strikes, create one warning event and increment `warningCount` |
| Warning expiry | Warning events expire after 90 days |
| Later automatic penalties | Once the user already has moderation history, later automatic penalties create strikes |
| Strike expiry | Strike events expire after 180 days |
| Strike 1 | 24-hour forum suspension |
| Strike 2 | 7-day forum suspension |
| Strike 3+ | Permanent forum ban |

### Important correction from older planning docs
The current backend does **not** use a “3 warnings = 1 strike” ladder. The live logic is: first automatic penalty can be a warning, and later automatic penalties become strikes.

## 6. Server-side enforcement points

Forum restrictions are enforced on the backend before a user can create or edit forum content.

- `createForumPost` checks restriction state before accepting a post
- `createForumComment` checks restriction state before accepting comments or replies
- `updateForumPostContent` checks restriction state and the 5-minute edit window
- `updateForumCommentContent` checks restriction state and the 5-minute edit window

This means a modified client still has to get past backend checks.

## 7. Image moderation for forum posts

Forum post images are scanned in the backend using Vision SafeSearch.

| Outcome | Current backend response |
|---|---|
| No image | Image moderation is skipped and the post proceeds normally |
| Vision error | The helper logs the failure and allows the post instead of hard-failing the user action |
| Blocked image | The image can be archived, a moderation event is created, automatic penalty logic runs, and the post request is rejected |

### Current block rule
The current code blocks an image when:
- `adult` is `LIKELY` or `VERY_LIKELY`, or
- `racy` is `VERY_LIKELY`

### Storage note
The current app upload flow still writes new forum images under `forum_post_images/{imageId}.jpg`, while moderation / delete flows archive copies under `archive/forum_post_images/{userId}/...`.

## 8. Appeals structure

The backend includes both end-user appeal submission and reviewer-side appeal handling.

| Callable | Purpose |
|---|---|
| `getMyModerationState` | Returns warning count, strike count, suspension / ban state, recent moderation events, and recent appeals |
| `submitModerationAppeal` | Lets the affected user submit one appeal for one moderation event within the appeal window |
| `getPendingModerationAppeals` | Reviewer queue for pending appeals |
| `reviewModerationAppeal` | Reviewer action that approves or denies an appeal and reverses the linked moderation event when approved |

### Reviewer access
Reviewer access is gated by custom auth claims. The current backend accepts `admin`, `moderator`, or `staff` claims as valid reviewer roles.

## 9. What an appeal approval does right now

When a reviewer approves an appeal, the backend:

- marks the appeal reviewed with `approved`
- marks the linked moderation event `reversed`
- recalculates `warningCount` and `strikeCount` from remaining active moderation events
- recomputes whether the user still has an active suspension or permanent forum ban
- restores content to `visible` when the reversed event was a content-takedown action such as `hide_content`, `remove_content`, or `reject_content`

## 10. Scheduled moderation cleanup

A scheduled backend job runs every 60 minutes.

It:
- marks expired moderation events as `expired`
- decrements active warning / strike counters when appropriate
- clears `forumSuspendedUntil` for users whose temporary suspension has ended

## 11. Related Firestore collections in the current project

| Collection | Role |
|---|---|
| `forumThreads` | Posts and post-level moderation fields |
| `forumThreads/{postId}/comments` | Comments, replies, and comment-level moderation fields |
| `reports` | Report intake records |
| `moderationEvents` | Canonical moderation history |
| `moderationAppeals` | Appeal records |
| `deletedforum_backlog` | Archived deleted forum content |
| `users` | Live warning / strike / restriction state |

## 12. Current implementation gaps worth remembering

- Reviewer tooling depends on custom claims.
- App Check not yet enforced.
- Forum image storage ownership not fully secured.

### End-user moderation UI (current state)

- Reporting available on posts/comments/replies.
- Moderation effects visible (hidden/removed content).
- Posting disabled when user is restricted.
- Appeals submitted through UI.

### Developer + Reviewer UI (Admin behavior)

- Admin/reviewer tools connect to backend moderation functions.
- Reviewers view:
  - pending appeals
  - moderation events
  - user violation history
- Actions available:
  - approve appeal → restore content, reverse event
  - deny appeal → keep moderation state
- UI integrates:
  - content preview (post/comment)
  - user profile info
  - timestamps and reasons
- Access restricted via Firebase custom claims.