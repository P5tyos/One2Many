package com.greg.one2many.web;

import com.greg.one2many.domain.Attendee;
import com.greg.one2many.dto.*;
import com.greg.one2many.service.AttendeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/attendees")
@RequiredArgsConstructor
public class AttendeeController {

    private static final String SESSION_COOKIE = "o2m_session";

    private final AttendeeService attendeeService;

    @Value("${one2many.cookie.secure:true}")
    private boolean secureCookie;

    @PostMapping("/scan")
    public ScanInResponse scanIn(@Valid @RequestBody ScanInRequest request) {
        Attendee attendee = attendeeService.scanIn(request.getLinkedinUrl());
        return new ScanInResponse(attendee.getId(), attendee.getName() != null);
    }

    @GetMapping("/{token}")
    public ResponseEntity<PersonalPageResponse> openPage(
            @PathVariable("token") String token,
            @CookieValue(name = SESSION_COOKIE, required = false) String session) {

        Attendee attendee = attendeeService.openPersonalPage(token, session);

        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, attendee.getSessionSecret())
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/attendees")
                .maxAge(Duration.ofHours(12))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new PersonalPageResponse(
                        attendee.getName() != null, attendee.getName(), attendee.getMessage()));
    }

    @PostMapping("/{token}/profile")
    public PersonalPageResponse submitProfile(
            @PathVariable("token") String token,
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            @Valid @RequestBody ProfileDetailsRequest request) {

        Attendee attendee = attendeeService.completeProfile(
                token, session, request.getName(), request.getMessage());
        return new PersonalPageResponse(true, attendee.getName(), attendee.getMessage());
    }

    @GetMapping("/{token}/list")
    public List<AttendeeSummary> getList(
            @PathVariable("token") String token,
            @CookieValue(name = SESSION_COOKIE, required = false) String session) {
        return attendeeService.getPublicList(token, session);
    }
}