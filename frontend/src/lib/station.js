// The station lock: can a new person be scanned right now?
// Locked while someone's code is pending. It frees itself when their phone opens the page (auto),
// when the 3 reveals are used up, when the 3 minutes run out, or with "Cancelar".
// (The old rule "free after the first reveal" was from before we could detect the opening.)
export function canAcceptScan(state) {
  return state === 'idle' || state === 'message'
}
