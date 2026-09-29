# Backend breakage 5

## Bug 1 — Searching by claimant name misses the claim

Before:
Create a claim whose claimant name contains a unique marker that does not appear in its title, description, reference, creator username, or creator email.

Action:
Call `GET /api/v1/claims?search=<claimant-marker>` as the claim owner or a reviewer.

Expected:
The page contains the claim because the general text search includes the claimant name.

Observed:
The request returns `200`, but the claim is absent and `totalElements` is `0` when no other visible claim contains the marker.

## Bug 2 — Literal search punctuation behaves like a wildcard

Before:
Create two visible claims with titles that differ at one character, for example `Invoice_2026` and `InvoiceX2026`. Ensure no other searchable field contains those strings.

Action:
Call `GET /api/v1/claims?search=Invoice_2026&sort=title,asc`.

Expected:
Only the claim containing the literal underscore is returned.

Observed:
Both claims are returned even though one title has `X` where the requested search text has `_`.

## Bug 3 — Registering an assigned claim removes its reviewer

Before:
As a manager, create a draft claim and assign it to an eligible reviewer. Confirm that the assignment response contains that reviewer's `assignedToId`.

Action:
Send `PATCH /api/v1/claims/<claimId>/status` with `{"status":"REGISTERED","version":<currentVersion>}`, then call `GET /api/v1/claims/<claimId>`.

Expected:
The status becomes `REGISTERED` and the existing reviewer assignment remains present.

Observed:
The status becomes `REGISTERED`, but `assignedToId`, `assignedToUsername`, and `assignedAt` become `null`.

## Bug 4 — The reviewer selector offers a user who cannot be assigned

Before:
Log in as a manager and create a non-final claim. Keep its current version.

Action:
Call `GET /api/v1/claims/reviewers`, locate `dev-user`, and send `PATCH /api/v1/claims/<claimId>/assignment` with `{"assignedToId":<dev-user-id>,"version":<currentVersion>}`.

Expected:
Every user returned by the reviewer endpoint is a valid assignment target, so `dev-user` should either be absent or the assignment should succeed.

Observed:
`dev-user` appears in the reviewer list, but assigning the claim to that returned ID fails with `400 INVALID_ASSIGNEE`.

## Bug 5 — Reassignment history loses the former reviewer

Before:
As a manager, create a non-final claim. Assign it to one eligible reviewer, then obtain the claim's new version and assign it to a different eligible reviewer.

Action:
Call `GET /api/v1/claims/<claimId>/history` and inspect the last `ASSIGNED` event.

Expected:
The event data identifies the first reviewer as `from` and the second reviewer as `to`.

Observed:
The last assignment event reports the second reviewer as both `from` and `to`, even though the claim changed reviewer.

## Bug 6 — Comments are returned in reverse chronology

Before:
Create a visible claim and post two comments in order: `First comment`, then `Second comment`.

Action:
Call `GET /api/v1/claims/<claimId>/comments`.

Expected:
The response is chronological: `First comment` followed by `Second comment`.

Observed:
The response starts with `Second comment` and ends with `First comment`.

## Bug 7 — Closing a claim makes its existing attachment unavailable

Before:
As a manager, create a claim, upload a text attachment, and confirm that its content endpoint returns `200`. Move the claim through `REGISTERED` and `UNDER_REVIEW` to `ACCEPTED` using each returned version.

Action:
Call `GET /api/v1/claims/<claimId>/attachments/<attachmentId>/content` after the claim is accepted.

Expected:
Existing attachments remain readable after finalization, so the endpoint returns `200` with the original bytes.

Observed:
The endpoint returns `409 CLAIM_NOT_EDITABLE` even though this is a read operation.
