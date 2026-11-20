package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScanInResponse {
    private String token;              // used by the station to build the personal QR
    private boolean returningAttendee; // true if they already filled in their name before
}