package com.greg.one2many.dto;

import com.greg.one2many.domain.ScanStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScanInResponse {
    private ScanStatus status;          // ISSUED → show the QR; AWAITING_CONFIRMATION → wait for the moderator
    private String token;               // only when ISSUED: used by the station to build the personal QR
    private String rescanId;            // only when AWAITING_CONFIRMATION: the station polls this
    private boolean returningAttendee;  // true if they already filled in their name before
}
