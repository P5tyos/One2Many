package com.greg.one2many.web;

import com.greg.one2many.dto.AdminAttendeeView;
import com.greg.one2many.dto.AdminRenameRequest;
import com.greg.one2many.dto.AdminStats;
import com.greg.one2many.dto.EventInfo;
import com.greg.one2many.dto.EventUpdateRequest;
import com.greg.one2many.dto.RescanView;
import com.greg.one2many.service.AttendeeService;
import com.greg.one2many.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AttendeeService attendeeService;
    private final EventService eventService;

    @GetMapping("/attendees")
    public List<AdminAttendeeView> listAll() {
        return attendeeService.getAllForAdmin();
    }

    @GetMapping("/stats")
    public AdminStats stats() {
        return attendeeService.getStats();
    }

    @PostMapping("/attendees/{adminId}/flag")
    public ResponseEntity<Void> flag(@PathVariable("adminId") String adminId) {
        attendeeService.flag(adminId);
        return ResponseEntity.noContent().build();
    }

    // correct a typo in someone's name (the attendee can't change it after the first save)
    @PostMapping("/attendees/{adminId}/name")
    public ResponseEntity<Void> rename(@PathVariable("adminId") String adminId,
                                       @Valid @RequestBody AdminRenameRequest request) {
        attendeeService.renameByAdmin(adminId, request.getName());
        return ResponseEntity.noContent().build();
    }

    // the yellow "possible hidden word" hint: moderator checked it, it's fine
    @PostMapping("/attendees/{adminId}/accept")
    public ResponseEntity<Void> acceptText(@PathVariable("adminId") String adminId) {
        attendeeService.acceptText(adminId);
        return ResponseEntity.noContent().build();
    }

    // edit the event details on the fly (station advert + phone page pick them up)
    @PutMapping("/event")
    public EventInfo updateEvent(@Valid @RequestBody EventUpdateRequest request) {
        return eventService.update(request);
    }

    // works for both FLAGGED and PENDING_REVIEW: makes them visible again
    @PostMapping("/attendees/{adminId}/approve")
    public ResponseEntity<Void> approve(@PathVariable("adminId") String adminId) {
        attendeeService.approve(adminId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/attendees/{adminId}")
    public ResponseEntity<Void> remove(@PathVariable("adminId") String adminId) {
        attendeeService.remove(adminId);
        return ResponseEntity.noContent().build();
    }

    // station: polled while the personal QR is on screen; true once the phone opened the page
    @GetMapping("/tokens/{token}/opened")
    public Map<String, Boolean> pageOpened(@PathVariable("token") String token) {
        return Map.of("opened", attendeeService.isPageOpened(token));
    }

    // ---------- re-scan confirmation ----------

    // dashboard: who is waiting for a yes/no (the popup)
    @GetMapping("/rescans")
    public List<RescanView> waitingRescans() {
        return attendeeService.listWaitingRescans();
    }

    // station: "any news?" — polled every second or two; contains the new token once CONFIRMED
    @GetMapping("/rescans/{rescanId}")
    public RescanView rescan(@PathVariable("rescanId") String rescanId) {
        return attendeeService.getRescan(rescanId);
    }

    @PostMapping("/rescans/{rescanId}/confirm")
    public ResponseEntity<Void> confirmRescan(@PathVariable("rescanId") String rescanId) {
        attendeeService.confirmRescan(rescanId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rescans/{rescanId}/reject")
    public ResponseEntity<Void> rejectRescan(@PathVariable("rescanId") String rescanId) {
        attendeeService.rejectRescan(rescanId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        attendeeService.reset();
        return ResponseEntity.noContent().build();
    }
}