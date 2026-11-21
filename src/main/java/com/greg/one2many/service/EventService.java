package com.greg.one2many.service;

import com.greg.one2many.dto.EventInfo;
import com.greg.one2many.dto.EventUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Holds the current event details. Starts from application.properties;
// the dashboard can change them at any time. Changes live in memory only:
// after a restart the values from application.properties come back.
@Slf4j
@Service
public class EventService {

    private static final String LINKEDIN_URL = "^https://([A-Za-z0-9-]+\\.)?linkedin\\.com/\\S*$";

    private volatile EventInfo current; // volatile: a new object replaces the old one, readers always see a whole one

    public EventService(@Value("${one2many.event.name:}") String name,
                        @Value("${one2many.event.date:}") String date,
                        @Value("${one2many.event.location:}") String location,
                        @Value("${one2many.event.organizers:}") String organizers,
                        @Value("${one2many.event.description:}") String description,
                        @Value("${one2many.event.linkedin-url:}") String linkedinUrl) {
        this.current = new EventInfo(name, date, location, organizers, description, safeUrl(linkedinUrl));
    }

    public EventInfo get() {
        return current;
    }

    public EventInfo update(EventUpdateRequest request) {
        current = new EventInfo(
                clean(request.getName()), clean(request.getDate()), clean(request.getLocation()),
                clean(request.getOrganizers()), clean(request.getDescription()),
                safeUrl(clean(request.getLinkedinUrl())));
        log.warn("Moderator updated the event details: '{}'", current.getName());
        return current;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // only pass the link on if it really is a LinkedIn address (same domain rule as scan-in)
    private static String safeUrl(String url) {
        return url != null && url.matches(LINKEDIN_URL) ? url : null;
    }
}
