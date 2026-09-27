## Bug 1 — Clearing a deadline keeps the old value

Context:
Use the local dev dataset. Log in with `POST /api/auth/login`:
`{"email":"user@local.dev","password":"DevUser123!"}`.
Send the returned `accessToken` as `Authorization: Bearer <accessToken>` and JSON as `Content-Type: application/json`.

Action:
1. `POST /api/v1/claims` with `{"title":"Round4 deadline","description":"Round4 reproduction","priority":"HIGH","dueAt":"2030-01-15T12:00:00Z"}`. Save `id` and `version`.
2. `PUT /api/v1/claims/<id>` with `{"title":"Round4 deadline edited","description":"Round4 reproduction","priority":"HIGH","dueAt":null,"version":<version>}`.
3. `GET /api/v1/claims/<id>`.

Expected:
The update returns 200. Both responses contain `"dueAt":null`.

Observed:
The update returns 200. Both responses retain `"dueAt":"2030-01-15T12:00:00Z"`.

## Bug 2 — Combined filters return partial matches

Context:
Use the local dev dataset. Log in with `POST /api/auth/login`:
`{"email":"user@local.dev","password":"DevUser123!"}`.
Send the returned `accessToken` as `Authorization: Bearer <accessToken>` and JSON as `Content-Type: application/json`.
Choose an unused title prefix, such as `r4-92751`. Replace `<prefix>` below with it.

Action:
1. Create four claims using `POST /api/v1/claims`. Save each response's `id` and `version`:
   - A: `{"title":"<prefix> A","description":"Round4 reproduction","priority":"HIGH"}`.
   - B: `{"title":"<prefix> B","description":"Round4 reproduction","priority":"LOW"}`.
   - C: `{"title":"<prefix> C","description":"Round4 reproduction","priority":"HIGH"}`.
   - D: `{"title":"<prefix> D","description":"Round4 reproduction","priority":"LOW"}`.
2. For C and D, send `PATCH /api/v1/claims/<id>/status` with `{"status":"REGISTERED","version":<version>}`. Both return 200. Leave A and B in DRAFT.
3. `GET /api/v1/claims?search=<prefix>&status=DRAFT&priority=HIGH&sort=title,asc`.

Expected:
Only A is returned. `totalElements` is 1.

Observed:
A, B, and C are returned. `totalElements` is 3.

## Bug 3 — A whitespace comment is saved

Context:
Use the local dev dataset. Log in with `POST /api/auth/login`:
`{"email":"user@local.dev","password":"DevUser123!"}`.
Send the returned `accessToken` as `Authorization: Bearer <accessToken>` and JSON as `Content-Type: application/json`.

Action:
1. `POST /api/v1/claims` with `{"title":"Round4 blank comment","description":"Round4 reproduction"}`. Save `id`.
2. `POST /api/v1/claims/<id>/comments` with `{"body":"Valid note"}`.
3. `POST /api/v1/claims/<id>/comments` with `{"body":"   "}` (three spaces).
4. `GET /api/v1/claims/<id>/comments`.

Expected:
The whitespace comment returns 400. Only `Valid note` is listed.

Observed:
The whitespace comment returns 201 with `"body":""`. Two comments are listed; the second has an empty body.

## Bug 4 — Another user's claim history is visible

Context:
Use the local dev dataset. Log in twice with `POST /api/auth/login`:
- Manager: `{"email":"manager@local.dev","password":"DevManager123!"}`.
- User: `{"email":"user@local.dev","password":"DevUser123!"}`.

Send the corresponding `accessToken` as `Authorization: Bearer <accessToken>` and JSON as `Content-Type: application/json`.

Action:
1. As Manager, `POST /api/v1/claims` with `{"title":"Round4 private history","description":"Round4 reproduction"}`. Save `id` and `version`.
2. As Manager, `PATCH /api/v1/claims/<id>/status` with `{"status":"REGISTERED","version":<version>}`.
3. As User, `GET /api/v1/claims/<id>`. It returns 404.
4. As User, `GET /api/v1/claims/<id>/history`.

Expected:
The history request returns 404 with `CLAIM_NOT_FOUND`.

Observed:
The history request returns 200 with the `CREATED` and `STATUS_CHANGED` entries, including `DRAFT -> REGISTERED`.

## Bug 5 — A reviewer's comment names the claim owner

Context:
Use the local dev dataset. Log in twice with `POST /api/auth/login`:
- User: `{"email":"user@local.dev","password":"DevUser123!"}`.
- Manager: `{"email":"manager@local.dev","password":"DevManager123!"}`.

Save each login response's `user.id`. Send the corresponding `accessToken` as `Authorization: Bearer <accessToken>` and JSON as `Content-Type: application/json`.

Action:
1. As User, `POST /api/v1/claims` with `{"title":"Round4 comment author","description":"Round4 reproduction"}`. Save `id`.
2. As Manager, `POST /api/v1/claims/<id>/comments` with `{"body":"Reviewed by manager"}`.
3. As User, `GET /api/v1/claims/<id>/comments`.

Expected:
The posted and listed comment has Manager's `authorId` and `"authorUsername":"dev-manager"`.

Observed:
The posted and listed comment has User's `authorId` and `"authorUsername":"dev-user"`.
