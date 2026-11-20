package com.greg.one2many.domain;

public enum AttendeeStatus {
    VISIBLE,         // shown in the list
    FLAGGED,         // hidden by a moderator; attendee is asked to change their text
    PENDING_REVIEW   // hidden until the moderator approves (after too many flags)
}