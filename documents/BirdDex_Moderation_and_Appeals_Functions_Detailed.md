# BirdDex Moderation and Appeals Functions
_Current code-state reference updated from `Current_App_Version.zip`_

## Purpose
This document reflects the current moderation and appeals backend found in `functions/index.js` in the uploaded project. It focuses on what the live callables, triggers, state fields, and scheduled jobs do right now.

## 1. Current function map

| Function / trigger | Type | Current role |
|---|---|---|
| `initializeUser` | Callable | Creates or repairs the base user profile and seeds moderation fields such as `warningCount`, `strikeCount`, `permanentForumBan`, `forumSuspendedUntil`, and `lastViolationAt` when needed. |
| `submitReport` | Callable | Validates a report, normalizes reason codes, blocks self-reports and duplicate reports, applies per-user hourly report limits, updates content aggregates, and can move content to `under_review` or `hidden`. |
| `getMyModerationState` | Callable | User-facing moderation summary endpoint. Returns current warning / strike / restriction state plus recent moderation events and appeals. |
| `submitModerationAppeal` | Callable | Creates one pending appeal per user per moderation event, as long as the event is appealable and still inside the appeal window. |
| `getPendingModerationAppeals` | Callable | Reviewer queue endpoint. Requires reviewer custom claims and returns up to 100 pending appeals. |
| `reviewModerationAppeal` | Callable | Reviewer approval / denial endpoint. Denials mark the appeal reviewed; approvals reverse the source event, recompute live user restriction fields, and attempt content restoration where applicable. |
| `decayModerationState` | Scheduled job | Runs every 60 minutes. Expires moderation events whose `expiresAt` has passed and clears ended temporary suspensions. |
| `clearRemovedForumPostCoordinates` | Firestore trigger | When a post moderation status changes to `removed`, clears `showLocation`, `latitude`, and `longitude`. Hidden posts keep coordinates. |
| `createForumPost` | Callable | Enforces forum restrictions, text moderation, optional image moderation, cooldowns, and writes the post with initial moderation / report fields. |
| `createForumComment` | Callable | Enforces forum restrictions, text moderation, cooldowns, and writes comments or replies with initial moderation / report fields. |
| `updateForumPostContent` | Callable | Allows post edits only during the 5-minute edit window and only if the user is not currently restricted. |
| `updateForumCommentContent` | Callable | Allows comment and reply edits only during the 5-minute edit window and only if the user is not currently restricted. |

## 2. State constants and thresholds

| Setting | Current value | Notes |
|---|---:|---|
| `moderationStatus` values | `visible`, `under_review`, `hidden`, `removed` | `visible` and `under_review` are treated as public states by helper logic. |
| `FORUM_EDIT_WINDOW_MS` | 5 minutes | Applies to posts, comments, and replies. |
| `FORUM_SUBMISSION_COOLDOWN_MS` | 15 seconds | Server-side submission cooldown. |
| `REPORT_UNDER_REVIEW_THRESHOLD` | 3 unique reporters | Content moves to `under_review`. |
| `REPORT_HIDE_THRESHOLD` | 5 unique reporters | Content moves to `hidden`. |
| `MAX_REPORTS_PER_HOUR` | 10 | Rate limit per user per UTC hour bucket. |
| `MODERATION_APPEAL_WINDOW_MS` | 14 days | Appeals older than this are rejected. |
| `MODERATION_WARNING_EXPIRY_MS` | 90 days | Warning expiration window. |
| `MODERATION_STRIKE_EXPIRY_MS` | 180 days | Strike expiration window. |
| `MODERATION_SUSPEND_STAGE_ONE_MS` | 24 hours | Suspension after strike 1. |
| `MODERATION_SUSPEND_STAGE_TWO_MS` | 7 days | Suspension after strike 2. |

## 3. What `submitReport` currently does

- Requires auth plus `targetId`, `targetType`, and a non-empty `reason`.
- Requires `threadId` for comment and reply reports.
- Normalizes the report reason into backend reason codes.
- Resolves the moderation target from Firestore and rejects targets that cannot be safely resolved.
- Rejects self-reports.
- Rejects reports against already non-public content.
- Uses a deterministic report id so one reporter can only report one target once.
- Applies an hourly rate limit under `users/{uid}/settings/forumReportRateLimit`.
- Increments `reportCount`, `uniqueReporterCount`, and the selected `reportReasonCounts.<bucket>` field on the target document.
- Moves content to `under_review` at 3 unique reporters.
- Moves content to `hidden` at 5 unique reporters.
- When content becomes `hidden`, writes a `hide_content` moderation event and runs the automatic penalty helper for the content owner.

### Operational note
Changing a `reports/{reportId}` document later is bookkeeping only. The moderation effect happens when the callable processes the report and updates the target.

## 4. Automatic penalty ladder in the current backend

The current automatic ladder is event-based, not a simple “3 warnings = 1 strike” system.

| Condition | Event(s) written | User-state effect |
|---|---|---|
| User has no active warnings and no active strikes | `warning` event | `warningCount + 1`, `lastViolationAt` updated |
| User already has moderation history and next strike count becomes 1 | `strike` + `forum_suspension` | `strikeCount = 1`, `forumSuspendedUntil` set 24 hours ahead |
| Next strike count becomes 2 | `strike` + `forum_suspension` | `strikeCount = 2`, `forumSuspendedUntil` set 7 days ahead |
| Next strike count is 3 or more | `strike` + `forum_ban` | `strikeCount` increments, `permanentForumBan = true` |

## 5. Forum image moderation path

- `createForumPost` calls the forum-image moderation helper before the post document is written.
- The helper derives a Cloud Storage path from the image download URL and sends a SafeSearch request to the Vision API.
- Current rejection rule:
  - `adult` == `LIKELY` or `VERY_LIKELY`, or
  - `racy` == `VERY_LIKELY`
- If the image is blocked, the backend can:
  - archive the image under `archive/forum_post_images/{userId}/...`
  - write a `reject_content` moderation event
  - run automatic penalty logic
  - reject the post request
- If Vision fails, the current code logs the error and allows the post instead of hard-failing the user action.

## 6. `getMyModerationState`

This is the current user-side moderation summary endpoint.

### Returns
- `warningCount`
- `strikeCount`
- `permanentForumBan`
- `forumSuspendedUntil`
- up to 25 recent `moderationEvents`
- up to 25 recent `moderationAppeals`

The function sorts both events and appeals newest-first before returning them.

## 7. `submitModerationAppeal`

- Requires auth, `moderationEventId`, and non-empty `appealText`.
- Uses a deterministic appeal document id of `userId_moderationEventId`.
- Rejects duplicate appeals for the same event.
- Checks that the moderation event:
  - exists
  - belongs to the same user
  - is appealable
  - still has status `active`
- Rejects appeals outside the 14-day appeal window.
- Writes a pending `moderationAppeals` doc with snapshot fields copied from the source event.

## 8. Reviewer-side appeal functions

Reviewer access is gated by custom auth claims. The backend currently accepts reviewer callers whose token claims contain `admin`, `moderator`, or `staff`.

| Function | Reviewer behavior |
|---|---|
| `getPendingModerationAppeals` | Reads up to 100 pending appeals and includes linked moderation-event context when available. |
| `reviewModerationAppeal` | Validates the appeal, marks it reviewed, and either denies it or reverses the linked moderation event. |

### On approval, `reviewModerationAppeal` currently does all of this
- marks the source event `reversed`
- recomputes `warningCount`, `strikeCount`, `permanentForumBan`, and `forumSuspendedUntil` from the remaining active moderation events
- restores content to `visible` when the reversed action was a takedown action and the target still exists

## 9. Content restoration on approved appeals

| Appealed `actionType` | Approval result |
|---|---|
| `hide_content` | Target `moderationStatus` becomes `visible` |
| `remove_content` | Target `moderationStatus` becomes `visible` |
| `reject_content` | Target `moderationStatus` becomes `visible` if the target document still exists |

### Map-specific consequence
- Hidden posts keep coordinates, so restoring a hidden post can let its heat map pin reappear.
- Removed posts are different: a separate trigger clears coordinates when the post becomes `removed`.

## 10. `decayModerationState` scheduled job

- Runs every 60 minutes.
- Finds moderation events whose `expiresAt` is in the past and whose `status` is still `active`.
- Marks those events `expired`.
- Decrements `warningCount` or `strikeCount` when the expired event was a warning or strike.
- Separately clears `forumSuspendedUntil` for users whose temporary suspension has ended, unless the user is permanently banned.

## 11. Firestore records involved

| Collection / document | What moderation uses it for |
|---|---|
| `users/{uid}` | Live warning, strike, suspension, and permanent-ban state |
| `forumThreads/{postId}` | Post content, moderation status, report aggregates, and optional map coordinates |
| `forumThreads/{postId}/comments/{commentId}` | Comment and reply content plus moderation and report fields |
| `reports/{reportId}` | Stored report intake records |
| `moderationEvents/{eventId}` | Canonical moderation action history |
| `moderationAppeals/{appealId}` | Pending / approved / denied appeals |
| `users/{uid}/settings/forumReportRateLimit` | Per-user hourly report bucket used for rate limiting |
| `deletedforum_backlog/{id}` | Archive area used when forum content is removed or deleted |
| `usersdeletedAccounts/{uid}` | Archive record created during account-deletion flow before later cleanup |

## 12. Current implementation notes to remember

- Reviewer access still depends on custom claims being assigned.
- Forum image archiving is part of moderation and deletion flows.
- Hidden posts preserve coordinates; removed posts clear them.
- App Check is not yet enforced at callable level.

### End-user moderation UI (current state)

- Users can submit reports from posts/comments/replies.
- Users can view moderation state via `getMyModerationState`.
- Users can submit appeals via `submitModerationAppeal`.
- UI reflects moderation through disabled actions and feedback.

### Developer + Reviewer UI (Admin behavior)

- Reviewers access moderation through backend-connected UI (admin/mod tools).
- Reviewer UI pulls pending appeals using `getPendingModerationAppeals`.
- Each appeal includes:
  - moderation event context
  - user info
  - reason and evidence
- Reviewers can:
  - approve → reverses moderation event, restores content, recalculates user state
  - deny → marks appeal reviewed with no reversal
- Reviewer access is controlled via Firebase custom claims (`admin`, `moderator`, `staff`).
- Admin UI should display:
  - pending appeals queue
  - moderation history per user
  - content context (post/comment)