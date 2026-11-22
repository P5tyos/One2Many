package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class AdminStats {
    private long joined;          // scanned in at the station
    private long completed;       // filled in their name
    private long flagged;         // currently hidden by a moderator
    private long pendingReview;   // dashboard polls this: > 0 → show the approval popup
    private long pendingRescans;  // dashboard polls this: > 0 → show the re-scan popup
    private long totalJoined;     // everyone who joined this event, including people removed since
    private long loginFailures;   // failed admin logins from outside since the app started (dashboard warning)
    private Instant loginPausedUntil; // new outside logins refused until then; null = no pause
    private Instant lastLoginFailureAt; // last failed outside login; null = none (red banner only if recent)
}
