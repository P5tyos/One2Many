package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AttendeeSummary {

    private String name;
    private String message;
    private String linkedinUrl;
    private boolean host; // true for the pinned organiser card
    // deliberately excludes: id (security token), flagged, redeemed, joinedAt
}
