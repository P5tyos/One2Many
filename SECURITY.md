# Security

One2Many runs at real events, with real people's names. If you find a way to get past its
protections, thank you for looking. Please tell me **privately** first, so it can be fixed
before anyone uses it.

## What One2Many protects

### Who gets in

- Only genuine LinkedIn links are accepted at the station; look-alike or fake addresses are rejected.
- Every card in the list links to LinkedIn, and disguised redirects or hidden characters in a link are neutralised.
- The same person is recognised even if their link is written in different ways, so nobody appears twice.

### Personal pages

- Each personal code works only once: the first phone that opens it keeps it.
- After that, the page only works on that phone, so a photo of someone's code is useless.
- The personal code is only visible on the station while the attendee holds a key, for a limited number of times and minutes.
- The station won't accept the next person until the current one has opened their page.
- If someone scans a person who is already in, the organiser must confirm it's really them before the old page is replaced.
- Old pages clearly say why they stopped working: a new session started, or the event has ended.
- If the connection has to be restarted, the organiser can let everyone back in with one button.

### The organiser's controls

- The station and the dashboard need a login, and the password is typed at startup and never saved anywhere.
- Repeated wrong passwords from outside pause new logins, and the dashboard warns the organiser.
- The organiser always keeps a way to log in on the laptop itself, even during an attack.
- The app can only be reached through a secure (HTTPS) connection, never directly over the Wi-Fi.
- Other websites can't use the organiser's or attendees' logins behind their backs.

### What people write

- Inappropriate language is caught twice: instantly on the phone while typing, and again on the server.
- Swear words hidden inside other words are highlighted for the organiser to check.
- Links are not allowed in names or sentences.
- Invisible tricks are removed, like text that flips backwards or letters piled on top of each other.
- A name is typed once; after that only the organiser can correct it.
- The organiser can hide, approve, rename or remove anyone, and someone hidden again and again needs approval before reappearing.

### Keeping the app stable

- Oversized requests are refused before they're even read.
- Strange requests get a quiet answer and can't flood the log, which also has a size limit.
- Error messages never reveal how the app works inside.

### Privacy

- No accounts, no app to install, no ads and no tracking.
- Nothing about attendees is kept after the event, apart from a short moderation log for 7 days.
- Nobody else ever sees someone's personal code: not in lists, not on the dashboard, and never on a projector.

## How to report

- Use GitHub's **"Report a vulnerability"** button (the **Security** tab of this repository), or
- send me a message on LinkedIn: [Gergely (Greg) Horvath](https://www.linkedin.com/in/gergely-horvath-p5tyos).

Please include what you did, what happened, and what you expected. Please don't test against a
live event with real attendees; run your own copy instead (see [Development](docs/development.md)).

## What happens next

I'll reply as soon as I can, fix what's real, and credit you in the release notes if you'd like.

Ideas that make it safer are welcome too, as issues or pull requests.
