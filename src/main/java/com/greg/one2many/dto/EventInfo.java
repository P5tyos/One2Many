package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

// The current event, shown on the station advert and the phone page.
// Copied by hand from the event's LinkedIn page into application.properties.
@Data
@AllArgsConstructor
public class EventInfo {
    private String name;
    private String date;          // free text, e.g. "Tue 25 Nov, 16:00"
    private String location;
    private String organizers;    // shown on the station advert, e.g. "IES de Teis · 2º DAM"
    private String description;
    private String linkedinUrl;   // null unless it really points to linkedin.com
}
