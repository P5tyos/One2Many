package com.greg.one2many.dto;

import jakarta.validation.constraints.Size;

// What the station screen shows right now, reported by the station itself, so the dashboard
// can mirror it. Never contains the personal QR or its token: the dashboard must not be able
// to show someone's code (anyone glancing at the laptop could scan it).
public record StationState(
        @Size(max = 20) String state,          // idle | processing | ready | revealing | waiting | message
        Integer revealsLeft,
        Long windowEndsAt,                     // epoch ms: when the 3-minute window closes
        @Size(max = 200) String message,       // text of the message on screen, if any
        @Size(max = 10) String messageKind,    // info | ok | error
        @Size(max = 300) String staffNote,     // the small red note for Greg, if any
        Long reportedAt) {                     // set by the server
}
