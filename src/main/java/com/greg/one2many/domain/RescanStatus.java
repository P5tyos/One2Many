package com.greg.one2many.domain;

public enum RescanStatus {
    WAITING,    // shown on the dashboard, station is waiting
    CONFIRMED,  // moderator said yes: new token issued, old session killed
    REJECTED,   // moderator said no: nothing changed for the attendee
    EXPIRED     // nobody answered in time: treated as rejected
}
