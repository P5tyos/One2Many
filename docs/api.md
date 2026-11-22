# API reference

All endpoints are under the same address as the app. Requests and responses are JSON (the API always answers JSON, whatever `Accept` header is sent).

**Contents:** [Authentication](#authentication) · [Errors](#errors) · [Public](#public) · [Attendee](#attendee-phone) · [Staff: station](#staff-station) · [Staff: dashboard](#staff-dashboard) · [Staff: re-scans](#staff-re-scans) · [Status codes](#status-codes)

---

## Authentication

| Who | How |
|---|---|
| **Staff** (station, dashboard) | User `admin`. In a browser: the login form at `/login` (session cookie `JSESSIONID`, SameSite=Strict); afterwards you always land on `/dashboard`. From scripts: HTTP Basic, e.g. `curl -u admin:PASSWORD`. |
| **Attendee** (phone) | The token in the URL, plus the `o2m_session` cookie ("wristband") that the first `GET /api/attendees/{token}` hands out |
| **Public** | Nothing |

When not logged in, pages (`/station`, `/dashboard`) redirect to `/login`, and API calls get **401 with an empty body**. Send `X-Requested-With: XMLHttpRequest` (the frontend's `api.js` does) so a 401 has no `WWW-Authenticate` header and the browser doesn't open its own password popup.

The password comes from the `SPRING_SECURITY_USER_PASSWORD` environment variable (`start-event.bat` asks for it). Without it, Spring prints a random password at startup.

## Errors

Every error is JSON, translated from the `Accept-Language` header (English by default, Spanish `es`, Galician `gl`):

```json
{ "error": "This link is not valid." }
```

Validation errors (400) also list each field:

```json
{ "error": "Name is required", "fields": { "name": "Name is required" } }
```

Exceptions: **401** (not logged in) and **413** (body too large) have an empty body, and so do Spring's own client mistakes (wrong HTTP method 405, wrong content type 415).

Request bodies on `/api/**` are limited to **8 KB**: larger ones get 413 without being read; a body of unknown length that goes over 8 KB gets 400.

---

## Public

### `GET /api/event`

The event details shown on the station and the phones.

```json
{
  "name": "Encuentro DAM / DAW",
  "date": "Mié 25 nov, 08:10",
  "location": "IES de Teis, Vigo",
  "organizers": "",
  "description": "",
  "linkedinUrl": null
}
```

`linkedinUrl` is only passed on if it is an `https://…linkedin.com/…` link; otherwise `null`.

---

## Attendee (phone)

### `GET /api/attendees/{token}`

Opens the personal page. The **first** call redeems the token and sets the wristband cookie: `o2m_session`, HttpOnly, SameSite=Lax, Path `/api/attendees`, 12 hours, Secure at the event. Later calls must send that cookie (a page refresh does).

```json
{ "profileComplete": false, "name": null, "message": null, "status": "VISIBLE" }
```

`status`: `VISIBLE`, `FLAGGED` (hidden, asked to change the text) or `PENDING_REVIEW` (waiting for approval).

Errors: 404 unknown token · 409 already opened on another phone · 410 replaced by a re-scan.

### `POST /api/attendees/{token}/profile`

Saves the name and sentence. Needs the wristband.

```json
{ "name": "Ana García", "message": "Estudiante de DAW. Me encanta el frontend.", "nameWarned": false }
```

| Field | Rules |
|---|---|
| `name` | Required, max 60 characters. **Only used on the first save**; afterwards the stored name is kept (only staff can rename). |
| `message` | Optional, max 100 characters |
| `nameWarned` | Optional. `true` if the phone's filter warned about the name and the person saved anyway: the row turns yellow on the dashboard |

Both texts are cleaned first: control characters become spaces; invisible characters (e.g. right-to-left overrides) are removed, except the zero-width joiner that emoji need; stacked accent marks are cut to 2.

Returns the same shape as `GET /api/attendees/{token}`, with the stored values.

Errors: 400 validation · 403 missing or wrong wristband · 422 inappropriate language, links, a name that is empty after cleaning, or unchanged text while flagged.

### `GET /api/attendees/{token}/list`

Everyone else who is visible, oldest first. The host card (if configured) comes first. Needs the wristband.

```json
[
  { "name": "Greg Horvath", "message": "Organiser of this networking station.", "linkedinUrl": "https://www.linkedin.com/in/…", "host": true },
  { "name": "Ana García", "message": "Estudiante de DAW.", "linkedinUrl": "https://www.linkedin.com/in/ana-garcia", "host": false }
]
```

The list never contains tokens, statuses or the viewer themselves.

---

## Staff: station

### `POST /api/attendees/scan`

A LinkedIn QR was scanned.

```json
{ "linkedinUrl": "https://www.linkedin.com/in/ana-garcia?fromQR=1" }
```

Accepted: `https://(www.)linkedin.com/in/<name>` and `https://(www.)linkedin.com/qr/<code>`. The query string is dropped and `/in/` names are lowercased; `/qr/` codes keep their case (they are case-sensitive).

```json
{ "status": "ISSUED", "token": "6f1c…", "rescanId": null, "returningAttendee": false }
```

| `status` | Meaning |
|---|---|
| `ISSUED` | `token` is ready for the personal QR. A new person, or someone whose page was never opened (their old token is retired). |
| `AWAITING_CONFIRMATION` | Their page is already open on a phone. No token; poll `GET /api/admin/rescans/{rescanId}` until the organiser answers. Scanning again while waiting returns the same `rescanId`. |

`returningAttendee` is `true` if they already saved a name.

### `GET /api/admin/tokens/{token}/opened`

`{"opened": true}` once a phone has opened that token's page. Unknown or retired tokens give `false`. The station polls this to close the QR.

### `POST /api/admin/station/state` · `GET /api/admin/station/state`

The station mirror. The station reports what it shows (on every change and every 4 seconds); the dashboard reads it. Never contains the personal QR or its token.

```json
{ "state": "ready", "revealsLeft": 3, "windowEndsAt": 1795219259000, "message": null, "messageKind": null, "staffNote": null, "reportedAt": 1795219079000 }
```

POST: 204. Limits: `state` 20, `message` 200, `messageKind` 10, `staffNote` 300 characters. `reportedAt` is set by the server. GET: 204 if the station never reported.

### `POST /api/admin/station/manual` · `GET /api/admin/station/manual`

Manual add: a one-slot "mailbox" from the dashboard to the station.

- POST `{"linkedinUrl": "…"}` → **202**. Same validation as a scan. A second POST replaces the first.
- GET (the station polls it) → **200** `{"linkedinUrl": "…"}` once, then **204**. A link older than 30 seconds is dropped (204).

---

## Staff: dashboard

### `GET /api/admin/attendees`

Everyone, including hidden and unfinished profiles, oldest first.

```json
[{
  "adminId": "b3e1…",
  "name": "Ana García",
  "message": "Estudiante de DAW.",
  "linkedinUrl": "https://www.linkedin.com/in/ana-garcia",
  "status": "VISIBLE",
  "flagCount": 0,
  "profileComplete": true,
  "pageOpened": true,
  "joinedAt": "2026-11-25T07:15:02Z",
  "possibleHiddenWord": false,
  "hiddenSince": null
}]
```

`adminId` is permanent and only used for moderation (it never opens a page). `possibleHiddenWord`: a swear word hidden inside a longer word, or a name the phone warned about; cleared by accept or approve, back if the text changes. `hiddenSince`: when the person was first hidden; `null` while visible.

### `GET /api/admin/stats`

```json
{ "joined": 42, "completed": 39, "flagged": 1, "pendingReview": 0, "pendingRescans": 0, "totalJoined": 44 }
```

`joined` = people in the list now; `totalJoined` = everyone who joined, including people removed since (reset by `/api/admin/reset`).

### Moderation actions

All return **204**, or 404 for an unknown `adminId`.

| Method and URL | Effect |
|---|---|
| `POST /api/admin/attendees/{adminId}/flag` | Hide the card and ask the person to change their text. From the 3rd flag, their fix needs approval. |
| `POST /api/admin/attendees/{adminId}/approve` | Make visible again (from `FLAGGED` or `PENDING_REVIEW`); also clears the yellow hint |
| `POST /api/admin/attendees/{adminId}/accept` | Clear the yellow "possible hidden word" hint; nothing changes for the person |
| `POST /api/admin/attendees/{adminId}/name` | Rename. Body `{"name": "…"}`, max 60 characters, cleaned like a profile (422 if empty after cleaning). Token and wristband stay the same. |
| `DELETE /api/admin/attendees/{adminId}` | Remove the person |

### `PUT /api/admin/event`

Replace the event details (all fields; a missing field becomes empty). Returns the stored values, as `GET /api/event`.

| Field | Max length |
|---|---|
| `name`, `date` | 80 |
| `location`, `organizers` | 120 |
| `description` | 300 |
| `linkedinUrl` | 300; empty or an `https://…linkedin.com/…` link |

Changes live in memory: a restart brings back `application.properties`.

### Emergency actions

| Method and URL | Effect |
|---|---|
| `POST /api/admin/release-sessions` | Retire every token and wristband (old pages get 410) and reject waiting re-scans. Each person's next scan issues a new code at once, keeping their profile. For when the tunnel restarted with a new address. 204. |
| `POST /api/admin/reset` | Delete all attendees and re-scans. Event details stay. 204. |

---

## Staff: re-scans

| Method and URL | Effect |
|---|---|
| `GET /api/admin/rescans` | Re-scans waiting for an answer, oldest first (the dashboard popup) |
| `GET /api/admin/rescans/{rescanId}` | One re-scan; the station polls it. `status`: `WAITING`, `CONFIRMED` (with the new `token`), `REJECTED` or `EXPIRED` (no answer in 120 s) |
| `POST /api/admin/rescans/{rescanId}/confirm` | Issue a new token; the old page gets 410. 204, or 409 if already answered or expired |
| `POST /api/admin/rescans/{rescanId}/reject` | Nothing changes for the person. 204, or 409 if already answered or expired |

```json
{ "rescanId": "…", "adminId": "…", "name": "Ana García", "linkedinUrl": "https://www.linkedin.com/in/ana-garcia", "status": "CONFIRMED", "requestedAt": "2026-11-25T07:40:11Z", "token": "9a2d…" }
```

---

## Status codes

| Code | Meaning |
|---|---|
| 200 | OK |
| 202 | Accepted (manual add: waiting for the station) |
| 204 | Done, nothing to return |
| 400 | Validation failed, unreadable JSON, or a body of unknown length over 8 KB |
| 401 | Not logged in, or wrong password. Empty body |
| 403 | Missing or wrong wristband cookie |
| 404 | Unknown token, attendee or re-scan; or an address that doesn't exist |
| 405 / 415 | Wrong HTTP method / content type. Empty body |
| 409 | Token already opened on another phone, or re-scan already answered or expired |
| 410 | Token replaced by a re-scan or by "release all sessions" |
| 413 | Request body over 8 KB. Empty body |
| 422 | Inappropriate language, links, a name that is empty after cleaning, or unchanged text while flagged |
| 500 | Unexpected error. Generic message only; details go to the server log |
