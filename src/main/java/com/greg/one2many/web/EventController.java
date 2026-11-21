package com.greg.one2many.web;

import com.greg.one2many.dto.EventInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public, no login: the station advert and every attendee's phone read this.
@RestController
@RequestMapping("/api/event")
public class EventController {

    @Value("${one2many.event.name:}")
    private String name;

    @Value("${one2many.event.date:}")
    private String date;

    @Value("${one2many.event.location:}")
    private String location;

    @Value("${one2many.event.organizers:}")
    private String organizers;

    @Value("${one2many.event.description:}")
    private String description;

    @Value("${one2many.event.linkedin-url:}")
    private String linkedinUrl;

    @GetMapping
    public EventInfo event() {
        // only pass the link on if it really is a LinkedIn address (same domain rule as scan-in)
        String safeUrl = linkedinUrl.matches("^https://([A-Za-z0-9-]+\\.)?linkedin\\.com/\\S*$")
                ? linkedinUrl : null;
        return new EventInfo(name, date, location, organizers, description, safeUrl);
    }
}
