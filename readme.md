# One2Many

**Find your people.**

A self-service networking station for events. Attendees scan their LinkedIn QR code at a station, get a personal page on their own phone, add their name and a short intro, and see everyone else who has joined, so they can connect at their own pace. Nothing about attendees is kept after the session, except a short moderation log.

The name is a nod to the JPA `@OneToMany` relationship: one person, many connections.

## Status (20 Nov 2026)

Backend features complete and manually tested (16 test groups passing). Remaining backend work is hardening. Frontend not started yet.

## Stack

- Java 21, Spring Boot 4 (Gradle)
- Spring Security, Lombok, Jakarta Validation, DevTools
- Planned frontend: Vue
- No database: everything lives in memory for the duration of the event

## Architecture

| Package | Contents |
|---|---|
| `domain` | `Attendee` (internal data shape), `AttendeeStatus` (`VISIBLE` / `FLAGGED` / `PENDING_REVIEW`) |
| `dto` | What the API accepts and returns. Gatekeeps what clients can see and set |
| `service` | `AttendeeService`: business logic, in-memory storage, moderation, session summary |
| `moderation` | `ProfanityService`: whole-word filter over the word lists |
| `exception` | Custom exceptions carrying i18n message keys |
| `web` | `AttendeeController`, `AdminController`, `SecurityConfig`, `GlobalExceptionHandler` |

## API

### Station (admin login required)

| Method | URL | Body | Returns |
|---|---|---|---|
| POST | `/api/attendees/scan` | `{"linkedinUrl"}` | `{"status", "token", "rescanId", "returningAttendee"}` |

`status` is `ISSUED` (new person, or a re-scan of someone who never opened their page: `token` is ready to show) or `AWAITING_CONFIRMATION` (their page is already open on a phone: no token, the station polls `rescanId` until the moderator answers; see [Re-scan](#re-scan-confirmation)). `returningAttendee` is true if they already filled in their name.

### Attendee (token in the URL + session cookie after first open)

| Method | URL | Body | Returns |
|---|---|---|---|
| GET | `/api/attendees/{token}` | | `{"profileComplete", "name", "message", "status"}` + sets the session cookie |
| POST | `/api/attendees/{token}/profile` | `{"name", "message"}` | `{"profileComplete", "name", "message", "status"}` |
| GET | `/api/attendees/{token}/list` | | `[{"name", "message", "linkedinUrl", "host"}]` |

### Moderator (admin login required)

| Method | URL | Returns |
|---|---|---|
| GET | `/api/admin/attendees` | Everyone, including hidden and unfinished: `adminId`, name, message, link, status, flagCount, joinedAt |
| GET | `/api/admin/stats` | `{"joined", "completed", "flagged", "pendingReview", "pendingRescans"}` |
| POST | `/api/admin/attendees/{adminId}/flag` | 204 |
| POST | `/api/admin/attendees/{adminId}/approve` | 204 (works for FLAGGED and PENDING_REVIEW) |
| DELETE | `/api/admin/attendees/{adminId}` | 204 |
| POST | `/api/admin/reset` | 204 |
| GET | `/api/admin/rescans` | Re-scans waiting for an answer, oldest first: `rescanId`, `adminId`, name, link, status, requestedAt |
| GET | `/api/admin/rescans/{rescanId}` | One re-scan (the station polls this): `status` `WAITING` / `CONFIRMED` / `REJECTED` / `EXPIRED`; `token` only when `CONFIRMED` |
| POST | `/api/admin/rescans/{rescanId}/confirm` | 204: new token issued, old session closed. 409 if already answered or expired |
| POST | `/api/admin/rescans/{rescanId}/reject` | 204: nothing changes for the attendee. 409 if already answered or expired |

### Re-scan confirmation

Scanning someone whose page is already open doesn't kill that page straight away: it could be a stranger with a photo of their LinkedIn QR.

- The scan returns `AWAITING_CONFIRMATION` and a `rescanId`; the dashboard sees it in `/api/admin/rescans` and `pendingRescans` goes up (show a popup when > 0).
- **Confirm**: the station's poll returns `CONFIRMED` with the new token. The old token and wristband stop working: the old page gets **410** with "A new session was started with your LinkedIn profile...". The new token opens a fresh page with the profile kept, so the form is skipped. Flags and status are kept too.
- **Reject**, or no answer within `one2many.moderation.rescan-timeout-seconds` (default 120, then `EXPIRED`): nothing changes, the original phone keeps working.
- Scanning the same person again while a re-scan is waiting returns the **same** `rescanId`.
- The timeout is checked lazily, whenever a re-scan is looked at (poll, list, stats, next scan); no background timer.

### Status codes

All errors return `{"error": "..."}`, translated. Validation errors also include `"fields": {"fieldName": "message"}`.

| Status | Meaning |
|---|---|
| 204 | Done, nothing to return (moderator actions) |
| 400 | Validation failed (missing name, not a LinkedIn URL...) or unreadable JSON |
| 401 | Not logged in, or wrong password. Comes from Spring Security, **empty body** |
| 403 | Missing or wrong session cookie |
| 404 | Unknown token, or unknown attendee (admin) |
| 409 | Token already used by another phone, or re-scan already answered / expired |
| 410 | Old token replaced by a confirmed re-scan ("A new session was started...") |
| 422 | Inappropriate language, or resubmitting unchanged text while flagged |
| 500 | Unexpected error. Generic message only; details go to the server log |

## Security design

- **Only LinkedIn profile URLs accepted** at scan-in (`^https://(www\.)?linkedin\.com/(in|qr)/[A-Za-z0-9_%\-]+/?(\?[^\s#]*)?$`). Both LinkedIn QR formats pass: `https://www.linkedin.com/in/someone?fromQR=1` and `https://www.linkedin.com/qr/AbCdEf123`. The query is dropped and `/in/` names are lowercased, but `/qr/` tokens keep their exact case (they are case-sensitive). Stops random or malicious QR codes, since the link is later shown to every attendee. A relaxed fallback pattern (any `linkedin.com` subdomain and path) is commented out in `ScanInRequest`.
- **Single-use token**: each scan creates a random UUID that goes into the attendee's personal QR. The first open redeems it; any other phone opening it later gets 409.
- **Session cookie ("wristband")**: the first open also issues a second secret as an HttpOnly, SameSite=Lax cookie (12h). `/profile` and `/list` require it, so a photographed token alone can't open, edit or read anything, and the real attendee can refresh freely. Constant-time comparison; first open is `synchronized` against two phones racing.
- **Re-scan to recover**: a lost page means coming back to the station. If the page was never opened, re-scanning issues a new token straight away. If it is open on a phone, the moderator must confirm first (see [Re-scan confirmation](#re-scan-confirmation)); once confirmed, the old token and cookie are dead and return 410. The LinkedIn scan is the only identity check, which is why an open page isn't replaced without a human looking.
- **Station login**: `/scan` and `/api/admin/**` need the admin role (Spring Security). Anything not explicitly opened requires login. Password never in code or config: random in dev, `SPRING_SECURITY_USER_PASSWORD` environment variable at the event. IP-based restriction rejected: through a tunnel, every request appears to come from the laptop itself.
- **CSRF**: disabled; protection comes from SameSite cookies (staff session `Strict`, wristband `Lax`).
- **Hold-to-reveal** (station screen, not built yet): the personal QR is only visible while the attendee holds the spacebar or the left mouse button. Max 2–3 reveals or 3 minutes. The scanner won't accept the next person until the current QR has been revealed at least once.
- **The personal QR is never shown on a projector.**
- **Nothing secret reaches other screens**: the public list has no tokens; the dashboard uses a separate permanent `adminId` and never shows tokens.
- **Free security headers** from Spring Security: `X-Frame-Options: DENY`, `nosniff`, `no-store`.

## Moderation

- **Automatic**: name and message are checked on submit. Inappropriate language → rejected (422), nothing saved; the attendee fixes it and resubmits.
- **Manual**: the moderator flags entries that slipped through. Flagged entries are hidden from the list and the attendee sees `status: FLAGGED`. Moderators flag, they never edit someone else's words.
- **Flag counter**: after flags 1 and 2, fixed text becomes visible automatically. From flag 3 (`one2many.moderation.flags-before-review`), fixed text goes to `PENDING_REVIEW` until approved. Resubmitting unchanged text while flagged is rejected.
- **Word lists**: `src/main/resources/profanity/` (`en`, `es`, `pt`), from [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) (see their repo for license terms). Spanish expanded by hand to ~740 entries. No Galician list exists in LDNOOBW; Portuguese partly covers it.
- **Matching**: lowercase, accents removed, basic leetspeak replaced (`0→o`, `4→a`...), 3+ repeated letters collapsed, punctuation turned into spaces, then **whole-word** check. Trade-off: glued-on forms ("idiots" when only "idiot" is listed) slip through; manual flags cover that.

## Host card

The organiser is pinned at the top of every attendee's list, set in `one2many.host.*`. Not a real attendee: no token, can't be flagged, not counted in stats. Leave the name or URL empty to disable.

## Persistent log

`logs/one2many.log`: warnings and errors only, 7 days of archives. Contains flags, approvals, removals, errors, and a summary (running totals) at every reset and shutdown. `logs/` is in `.gitignore`. Old files are only deleted while the app is running, so delete the folder by hand after the event.

## Languages (i18n)

Backend messages in English (default), Spanish and Galician: `messages.properties`, `messages_es.properties`, `messages_gl.properties`. Picked automatically from the browser's `Accept-Language` header; if none is sent, the server machine's own language is used.

## Settings (`application.properties`)

| Setting | Purpose |
|---|---|
| `one2many.cookie.secure` | Wristband only over HTTPS. `false` for local testing, `true` at the event |
| `server.servlet.session.cookie.secure` | Same, for the staff login cookie |
| `one2many.host.name` / `linkedin-url` / `message` | Host card |
| `one2many.moderation.flags-before-review` | Flag number from which fixes need approval (default 3) |
| `one2many.moderation.rescan-timeout-seconds` | Seconds the moderator has to answer a re-scan before it expires (default 120) |
| `spring.security.user.name` / `roles` | Admin user. Password from the environment variable only |
| `logging.file.name`, `logging.threshold.file`, `logging.logback.rollingpolicy.max-history` | Persistent log |

## Running it

```bash
./gradlew bootRun
```
If you get "permission denied": `chmod +x gradlew` once, or `bash gradlew bootRun`. The IDE's ▶ button on `One2manyApplication` also works.

The app runs on `http://localhost:8080`. In dev, the admin password is printed at startup ("Using generated security password"). Log in with curl using `-u admin:PASSWORD`.

Restarting (including DevTools' automatic restart when a file is saved) **wipes all attendees**.

## Notes and gotchas

- `@PathVariable` uses an explicit name (`@PathVariable("token")`). VS Code's built-in compiler doesn't keep parameter names, so without it Spring can't match `{token}` and every request fails with a 500.
- In Spring Framework 7, `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated; use `UNPROCESSABLE_CONTENT`.
- The HTTP `Date` header is always in GMT, one hour behind Spain in winter.
- DevTools logs every handled exception as a warning, so they appear in the log file during development. Without DevTools (at the event) they don't.
- VS Code warns that `one2many.*` settings are "unknown". Harmless; fixed by the `@ConfigurationProperties` tidy-up.
- To test with curl like a phone, keep cookies in a jar file: `-c phone.txt -b phone.txt`.

## Manual test checklist (all passed 20 Nov 2026)

1. Scan in a new person → 200, token, `returningAttendee: false`
2. Open the page → 200, `profileComplete: false`
3. Open it again from another phone → 409
4. Submit with a swear word → 422
5. Submit with an empty name → 400 with `fields`
6. Submit properly → 200, `profileComplete: true`
7. Scan a non-LinkedIn URL → 400
8. Send broken JSON → 400, no details leaked
9. Use a made-up token → 404
10. Same with `Accept-Language: es` / `gl` → translated error
11. Two people: each sees the other in the list, not themselves
12. Re-scan the same person → page never opened: new token at once; page open: `AWAITING_CONFIRMATION` until the moderator confirms → new token, `returningAttendee: true`, old token gives 410, new token skips the form
13. Session cookie: real phone opens and refreshes (200, same cookie); stranger opening → 409; stranger editing or reading, with no cookie or a fake one → 403
14. Station login: scan without login or with wrong password → 401; correct password → 200; attendee side still open
15. Moderation: flag → hidden from others; fixes after flags 1–2 → visible; fix after flag 3 → pending; approve, remove, reset all work; log summary shows correct totals
16. Whole-word filter: "Student, curious, fixed it" accepted; "mierda!" rejected

## To do

### Before the event
- [ ] Put your real LinkedIn URL in `one2many.host.linkedin-url`
- [ ] Set both cookie `secure` settings to `true` (or set up the dev profile, below)
- [ ] Set the `SPRING_SECURITY_USER_PASSWORD` environment variable
- [ ] Proofread the Galician messages; fix `profile.inappropriate` ("Detectouse linguaxe inapropiada. Por favor, utiliza un ton axeitado.")
- [ ] Review the expanded Spanish word list for entries that don't belong (e.g. "Aguafiestas")
- [ ] Tunnel setup (ngrok or similar), test early
- [ ] Test the barcode scanner with real phones

### After the event
- [ ] Delete the `logs/` folder

### Backend hardening
- [ ] Set up a `dev` profile so test-only settings can't leak into the event
- [ ] Decide: back up the attendee list to a file, so a crash mid-event doesn't wipe everyone?
- [ ] Rate limiting on public endpoints (e.g. Bucket4j)
- [ ] CORS locked to the tunnel URL, not `*`
- [ ] Decide: should profanity rejections be logged on purpose?
- [ ] Tidy-up: move all `one2many.*` settings into a `@ConfigurationProperties` class
- [x] Turn the manual checklist into automated tests (JUnit + MockMvc): `gradlew.bat test`, 60 tests

### Frontend (Vue)
- [ ] Station screen (second monitor): advert, instructions, hold-to-reveal (spacebar or left mouse button), scanner lock until revealed
- [ ] Dashboard (laptop): attendee table, red rows for FLAGGED, popup when `pendingReview > 0`, flag / approve / remove / reset, joined counter
- [ ] Personal page (phone): form, list with host card on top, visited section, FLAGGED / PENDING_REVIEW messages
- [ ] Handle 401 by status code (empty body)
- [ ] Frontend i18n (`vue-i18n`)
- [ ] AllProfanity for instant typing feedback
- [ ] Optional: projector page (advert + counter only, never a QR)

### Done
- [x] Protect `/scan` (Spring Security)
- [x] Moderator API
- [x] Joined counter data (`/api/admin/stats`)
- [x] False positives ("curious", "fixed it") fixed with whole-word matching