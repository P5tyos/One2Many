# One2Many

**Find your people.**

A self-service networking station for events. Attendees scan their LinkedIn QR code at a station, get a personal page on their own phone, add their name and a short intro, and see everyone else who has joined, so they can connect at their own pace. Nothing is stored after the session ends.

The name is a nod to the JPA `@OneToMany` relationship: one person, many connections.

## Status (20 Nov 2026)

Backend core loop is built and manually tested (12/12 tests passing). Frontend not started yet.

## Stack

- Java 21, Spring Boot 4 (Gradle)
- Lombok, Jakarta Validation, DevTools
- Planned frontend: Vue
- No database: everything lives in memory for the duration of the event

## Architecture

| Package | Role |
|---|---|
| `domain` | `Attendee`: the internal data shape, no logic |
| `dto` | What the API accepts and returns. Gatekeeps what clients can see and set |
| `service` | `AttendeeService`: business logic and in-memory storage |
| `moderation` | `ProfanityService`: word-list filter |
| `exception` | Custom exceptions carrying i18n message keys |
| `web` | `AttendeeController` (endpoints) and `GlobalExceptionHandler` (clean, translated errors) |

## API

| Method | URL | Body | Returns |
|---|---|---|---|
| POST | `/api/attendees/scan` | `{"linkedinUrl": "..."}` | `{"token", "returningAttendee"}` |
| GET | `/api/attendees/{token}` | | `{"profileComplete", "name", "message"}` |
| POST | `/api/attendees/{token}/profile` | `{"name", "message"}` | `{"profileComplete", "name", "message"}` |
| GET | `/api/attendees/{token}/list` | | `[{"name", "message", "linkedinUrl"}]` |

All errors return `{"error": "..."}`. Validation errors also include `"fields": {"fieldName": "message"}`.

| Status | Meaning |
|---|---|
| 400 | Validation failed (missing name, not a LinkedIn URL...) or unreadable JSON |
| 404 | Unknown or expired token |
| 409 | Token already used (page already opened once) |
| 422 | Inappropriate language detected |
| 500 | Unexpected error. Generic message only; details go to the server log |

## Security design

- **Only LinkedIn profile URLs accepted** at scan-in (`^https://(www\.)?linkedin\.com/in/.+$`). Stops random or malicious QR codes, since the link is later shown to every attendee.
- **Single-use token**: each scan creates a random UUID that goes into the attendee's personal QR. Opening the page marks it used, so a photographed QR stops working once the real attendee has used it.
- **Re-scan to recover**: if someone loses their page, they come back to the station and get scanned again. This issues a new token and kills the old one. The LinkedIn scan is the only identity check.
- **Hold-to-reveal** (station screen, not built yet): the personal QR is only visible while the attendee holds the spacebar. Max 2-3 reveals or 3 minutes. The scanner won't accept the next person until the current QR has been revealed at least once.
- **Public list never exposes tokens**: only name, message and LinkedIn URL.
- **No leaked internals**: all errors are caught and returned as short, translated messages.

## Moderation

- **Automatic**: name and message are checked on submit. If inappropriate language is found, the submission is rejected (422) and nothing is saved. The attendee fixes it and resubmits.
- **Manual (not built yet)**: a moderator dashboard where Greg can flag entries that slipped through. Flagged entries are hidden from the list until fixed (`Attendee.flagged`). Moderators flag, they never edit someone else's words.
- **Word lists**: `src/main/resources/profanity/` (`en`, `es`, `pt`), from the [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) project (see their repo for license terms). The Spanish list was expanded by hand to ~740 entries. There is no Galician list in LDNOOBW; Portuguese partly covers it.
- **Matching**: lowercase, accents removed, basic leetspeak replaced (`0→o`, `4→a`...), repeated letters collapsed, then substring check.

## Languages (i18n)

Backend messages in English (default), Spanish and Galician: `messages.properties`, `messages_es.properties`, `messages_gl.properties`. The language is picked automatically from the browser's `Accept-Language` header. If none is sent, the server machine's own language is used.

## Running it

```bash
./gradlew bootRun
```
If you get "permission denied": `chmod +x gradlew` once, or run `bash gradlew bootRun`. Starting it from the IDE's ▶ button on `One2manyApplication` also works.

The app runs on `http://localhost:8080`. Restarting it (including DevTools' automatic restart when a file is saved) **wipes all attendees**.

## Notes and gotchas

- `@PathVariable` uses an explicit name (`@PathVariable("token")`). VS Code's built-in compiler doesn't keep parameter names, so without it Spring can't match `{token}` and every page load fails with a 500.
- In Spring Framework 7, `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated; use `UNPROCESSABLE_CONTENT`.
- The HTTP `Date` header is always in GMT, one hour behind Spain in winter.

## Manual test checklist (all passed 20 Nov 2026)

1. Scan in a new person → 200, token, `returningAttendee: false`
2. Open the page → 200, `profileComplete: false`
3. Open it again → 409
4. Submit with a swear word → 422
5. Submit with an empty name → 400 with `fields`
6. Submit properly → 200, `profileComplete: true`
7. Scan a non-LinkedIn URL → 400
8. Send broken JSON → 400, no details leaked
9. Use a made-up token → 404
10. Same with `Accept-Language: es` / `gl` → translated error
11. Two people: each sees the other in the list, not themselves
12. Re-scan the same person → new token, `returningAttendee: true`, old token gives 404, new token skips the form

## To do

- [ ] Review the expanded Spanish word list for entries that don't belong (e.g. "Aguafiestas")
- [ ] False-positive check: does "Classic assembly expert" get blocked?
- [ ] Proofread Galician messages (machine-translated)
- [ ] Protect `/scan` so only the station can call it (Spring Security)
- [ ] Decide: back up the attendee list to a file, so a crash mid-event doesn't wipe everyone?
- [ ] Moderator dashboard: list, flag, remove attendee, protected login
- [ ] Rate limiting on public endpoints (e.g. Bucket4j)
- [ ] CORS locked to the tunnel URL, not `*`
- [ ] Tunnel setup (ngrok or similar), test early
- [ ] Vue frontend: station screen (hold-to-reveal, scanner lock), personal page, list, frontend i18n (`vue-i18n`), AllProfanity for instant typing feedback
- [ ] Turn the manual checklist into automated tests (JUnit + MockMvc)
- [ ] Joined counter on the main screen once more than 10 people have scanned in