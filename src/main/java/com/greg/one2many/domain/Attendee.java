package com.greg.one2many.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendee {

    private String id;            // single-use token, embedded in the personal QR
    private String linkedinUrl;   // normalised, unique — the attendee's real identity
    private String name;
    private String message;
    private boolean flagged;      // hidden from the list by a moderator
    private boolean redeemed;     // true once the personal page has been opened once
    private Instant joinedAt;
    private String sessionSecret; // the "wristband": issued on first open, sent as a cookie
}