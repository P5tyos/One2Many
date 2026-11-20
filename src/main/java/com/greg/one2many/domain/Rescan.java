package com.greg.one2many.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

// A re-scan of someone whose page is already open, waiting for the moderator.
@Data
@AllArgsConstructor
public class Rescan {
    private String id;            // random, used by the station to ask "any news?"
    private Attendee attendee;    // the person being re-scanned
    private Instant requestedAt;
    private RescanStatus status;
    private String newToken;      // only set once CONFIRMED
}
