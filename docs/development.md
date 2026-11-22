# Development

How to run One2Many while working on it, test it, and change it safely.

**Contents:** [Requirements](#requirements) · [Running in development](#running-in-development) · [Tests](#tests) · [Building the event jar](#building-the-event-jar) · [Project layout](#project-layout) · [Conventions](#conventions) · [Where to change things](#where-to-change-things) · [Gotchas](#gotchas)

---

## Requirements

- **Java 21** (the Gradle wrapper downloads Gradle itself)
- **Node.js 22.18+ or 24.12+** for the frontend
- Any editor. VS Code with the Java and Vue extensions works well.

The examples use Windows (`gradlew.bat`). On macOS or Linux use `./gradlew`.

## Running in development

Development uses two programs side by side: **Spring** (the API, port 8080) and **Vite** (the Vue screens with instant reload, port 5173).

**1. Backend.** In VS Code, press ▶ on `One2manyApplication`. Or, in a terminal in the project folder:

```bash
gradlew.bat bootRun
```

The console prints a random admin password at every start: `Using generated security password: …`. The user is `admin`.

**2. Frontend.** In a second terminal:

```bash
cd frontend
npm install        # the first time, and after new packages
npm run dev
```

Open **`http://localhost:5173`**. Vite forwards `/api`, `/login` and `/logout` to Spring (see `frontend/vite.config.js`), so the browser sees a single address: cookies work and no CORS setup is needed. `.vue` changes appear instantly; changes to `vite.config.js` need `npm run dev` again.

**3. Test data.** With Spring running:

```bash
powershell -ExecutionPolicy Bypass -File scripts\demo-data.ps1
```

It asks for the admin password and creates 10 attendees covering every dashboard row colour, plus one person who has not opened their page yet (it prints that link). Options: `-Reset` wipes the app first; `-BaseUrl` and `-FrontendUrl` point it at other addresses.

| Page | Address (dev) |
|---|---|
| Dashboard | `http://localhost:5173/dashboard` |
| Station | `http://localhost:5173/station` |
| Phone page | `http://localhost:5173/p/<token>` |
| About | `http://localhost:5173/about` |

**Testing on a real phone** in development: start a quick tunnel to Vite with `cloudflared tunnel --url http://localhost:5173` and open the station on the tunnel address (Vite already allows `*.trycloudflare.com`).

## Tests

```bash
gradlew.bat test
```

About 2 minutes, 219 tests (JUnit 5 + MockMvc), in `src/test/java/com/greg/one2many`. They start the whole app in memory with the `test` profile (`src/test/resources/application-test.properties`: admin password `test`, a fixed host card, logs in `build/test-logs`).

| Test class | Covers |
|---|---|
| `AttendeeApiTest` | Joining, validation, security, language filter, moderation |
| `RescanApiTest`, `RescanTimeoutTest` | Re-scan confirmation and its timeout |
| `NameLockTest` | Name typed once, admin rename |
| `StationApiTest` | Page-opened check, staff login, unknown addresses |
| `EventApiTest` | Event details |
| `HiddenWordTest` | The yellow "possible hidden word" hint |
| `ManualAddAndStatsTest` | Manual-add mailbox, `totalJoined` |
| `HiddenSinceAndStationStateTest` | `hiddenSince`, the station mirror |
| `ReviewFixesTest` | Hardening: request size, quiet errors, log cap, text cleaning, release sessions, links |

`ApiTestSupport` holds the shared helpers. Its `Phone` class plays an attendee's phone: it keeps the token and the wristband cookie like a real browser.

> Stop the app in VS Code before running the tests: a running app can lock `build/`, and the run fails with "Failed to clean up stale outputs".

## Building the event jar

```bash
gradlew.bat bootJar
```

This runs `npm run build` in `frontend/` and packs the result into `build/libs/one2many.jar`, so the jar alone runs everything. `start-event.bat` starts it with the `event` profile (port 8090, Secure cookies, settings for running behind cloudflared). See the [event guide](event-guide.md).

## Project layout

```
src/main/java/com/greg/one2many/   Spring backend (see architecture.md for each package)
src/main/resources/
  application.properties           settings (host card, event, moderation, logging)
  application-event.properties     extra settings for the event (start-event.bat)
  messages*.properties             error messages: English, Spanish, Galician
  profanity/{en,es,pt}.txt         word lists
src/test/                          tests and the test profile
frontend/src/                      Vue app: views/, api.js, router/, assets/
scripts/demo-data.ps1              fake attendees for local testing
start-event.bat                    event-day launcher (app + tunnel)
docs/                              this documentation
```

## Conventions

- **A new Vue route needs two more lines.** Add it to `SpaController` (so a page refresh works in the built app), and to `SecurityConfig` if it is a public page.
- **Every `fetch` goes through `api.js`.** It sends `X-Requested-With: XMLHttpRequest`, which keeps the browser's own password popup away on a 401.
- **`@PathVariable` always names its variable**: `@PathVariable("token")`. VS Code's compiler doesn't keep parameter names, so without the name Spring can't match `{token}`.
- **User-facing backend messages are translation keys.** Exceptions carry a key, and the text lives in `messages.properties`, `messages_es.properties` and `messages_gl.properties`. Add new keys to all three.
- **The interface is in Spanish**; code, comments and documentation are in English.
- **The files in `src/main` use Windows line endings (CRLF).** Keep them.
- **Secrets never go in code or config.** The admin password comes from an environment variable.

## Where to change things

| To change… | Look in |
|---|---|
| Event details, host card | `application.properties` (`one2many.event.*`, `one2many.host.*`) |
| Flags before an approval is needed (3) | `one2many.moderation.flags-before-review` |
| Time to answer a re-scan (120 s) | `one2many.moderation.rescan-timeout-seconds` |
| Blocked words | `src/main/resources/profanity/*.txt` (one word or phrase per line) |
| Name / sentence length (60 / 100) | `dto/ProfileDetailsRequest` and `NAME_MAX` / `MESSAGE_MAX` in `PersonalView.vue` |
| Reveals and reveal window (3, 3 min) | `MAX_REVEALS`, `REVEAL_WINDOW_MS` in `StationView.vue` |
| Scanner timing | `SCANNER_MAX_GAP_MS`, `SCANNER_MIN_LENGTH` in `StationView.vue` |
| Accepted LinkedIn links | the `@Pattern` in `dto/ScanInRequest` (a more relaxed pattern is there, commented out) |
| Colours, font, radius | `frontend/src/assets/main.css` |

## Gotchas

- **Restarting Spring removes everyone.** Data lives only in memory, and DevTools restarts the dev app whenever a Java file is saved.
- **Testing several "people" on one computer:** each needs a different LinkedIn link (use the dashboard's manual add with made-up `/in/test-…` links) **and its own browser or browser profile**. Tabs and private windows of one browser share one cookie jar, so a new person overwrites the previous one's wristband.
- **Scanner = keyboard.** The Windows keyboard layout must match the scanner's. Garbled text like `https;--` means a layout mismatch.
- **The station builds the personal QR from its own address.** On `localhost` only that computer can open the codes; the built station shows a red warning there.
- **The LinkedIn app's scanner refuses non-LinkedIn QR codes,** so attendees must scan their personal code with the phone camera.
- **`/qr/` LinkedIn codes are case-sensitive.** `normalize()` in `AttendeeService` lowercases only the domain and `/in/` names.
