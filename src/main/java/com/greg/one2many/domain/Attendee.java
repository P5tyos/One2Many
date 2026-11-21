package com.greg.one2many.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attendee {

    private String id;            // single-use token, embedded in the personal QR (changes on re-scan)
    private String adminId;       // permanent ID for moderation only; never opens anything
    private String linkedinUrl;   // normalised, unique — the attendee's real identity
    private String name;
    private String message;

        @Builder.Default
    private AttendeeStatus status = AttendeeStatus.VISIBLE;
    private int flagCount;        // how many times a moderator has flagged them
    
    private boolean redeemed;     // true once the personal page has been opened once
    private boolean textAccepted; // moderator OK'd the current name + message (clears the yellow hint)
    private Instant joinedAt;
    private Instant hiddenSince;  // set when flagged (hidden from the lists), cleared when visible again
    private String sessionSecret; // the "wristband"
}