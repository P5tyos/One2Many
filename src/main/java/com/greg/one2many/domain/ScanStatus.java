package com.greg.one2many.domain;

public enum ScanStatus {
    ISSUED,                 // a personal QR token is ready to show
    AWAITING_CONFIRMATION   // this person's page is already open somewhere: the moderator must confirm
}
