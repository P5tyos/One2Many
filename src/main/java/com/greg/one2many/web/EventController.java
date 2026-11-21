package com.greg.one2many.web;

import com.greg.one2many.dto.EventInfo;
import com.greg.one2many.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Public, no login: the station advert and every attendee's phone read this.
// Editing is admin-only: PUT /api/admin/event (AdminController).
@RestController
@RequestMapping("/api/event")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public EventInfo event() {
        return eventService.get();
    }
}
