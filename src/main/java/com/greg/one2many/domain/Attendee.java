package com.greg.one2many.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendee {

    private String id;            // internal random UUID — single-use security token,
                                   // embedded in the personal QR / page URL
    private String linkedinUrl;   // normalised, unique — the attendee's real identity
    private String name;          // required, filled in on their personal landing page
    private String message;       // optional, short intro (~100 chars)
    private boolean flagged;      // true = hidden from public list until they edit & resubmit
    private boolean redeemed;     // true once the personal page has been opened once —
                                   // blocks reuse of a captured/photographed QR code
    private Instant joinedAt;     // when they first scanned in at the station
}
