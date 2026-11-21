package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminStats {
    private long joined;          // scanned in at the station
    private long completed;       // filled in their name
    private long flagged;         // currently hidden by a moderator
    private long pendingReview;   // dashboard polls this: > 0 → show the approval popup
    private long pendingRescans;  // dashboard polls this: > 0 → show the re-scan popup
    private long totalJoined;     // everyone who joined this event, including people removed since
}
