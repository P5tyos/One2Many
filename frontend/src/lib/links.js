// No links in names or sentences (same rule as the backend, AttendeeService.containsLink):
// their LinkedIn is already on their card, and a link could lead anywhere.
// The ending must be lowercase ("prácticas.Es mi sueño" is a sentence, not an address).
// Developer words that look like addresses are fine: Node.js, Vue.js, ASP.NET, socket.io
const LINK_START = /https?:\/\/|www\./i
// endings that are also everyday words ("Hola.me llamo") only count before a "/"
const LINK_ADDRESS =
  /\b[A-Za-z0-9-]+\.((com|es|net|org|io|gal|eu|info|ly|app|link|site|online|club|shop|top|biz|xyz|tk|ru|cn|gg|cc)\b|(me|to|co|tv|gl)\/)/
const LINK_LOOKALIKES = /asp\.net|ado\.net|vb\.net|socket\.io/gi

export function hasLink(text) {
  const t = text ?? ''
  return LINK_START.test(t) || LINK_ADDRESS.test(t.replace(LINK_LOOKALIKES, ' '))
}
