package com.greg.one2many.dto;

import com.greg.one2many.domain.AttendeeStatus;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PersonalPageResponse {
    private boolean profileComplete; // false → show the form, true → show the list
    private String name;
    private String message;
    private AttendeeStatus status; // VISIBLE / FLAGGED / PENDING_REVIEW → frontend shows the right message
}