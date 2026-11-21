package com.greg.one2many.dto;

import com.greg.one2many.domain.AttendeeStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class AdminAttendeeView {
    private String adminId;
    private String name;
    private String message;
    private String linkedinUrl;
    private AttendeeStatus status;  // dashboard: FLAGGED → red, PENDING_REVIEW → popup
    private int flagCount;
    private boolean profileComplete;
    private boolean pageOpened;     // their phone has opened the page (wristband handed out)
    private Instant joinedAt;
    private boolean possibleHiddenWord; // dashboard: yellow row until the moderator accepts it
    private Instant hiddenSince;        // dashboard: "hidden for 4 min"; null while visible
}