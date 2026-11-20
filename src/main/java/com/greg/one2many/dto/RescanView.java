package com.greg.one2many.dto;

import com.greg.one2many.domain.RescanStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

// What the dashboard and the station see about a waiting re-scan (admin only)
@Data
@AllArgsConstructor
public class RescanView {
    private String rescanId;
    private String adminId;
    private String name;          // may be null if they opened the page but never filled it in
    private String linkedinUrl;
    private RescanStatus status;
    private Instant requestedAt;
    private String token;         // only when CONFIRMED: the station shows the new personal QR
}
