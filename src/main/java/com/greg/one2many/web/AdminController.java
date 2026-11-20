package com.greg.one2many.web;

import com.greg.one2many.dto.AdminAttendeeView;
import com.greg.one2many.dto.AdminStats;
import com.greg.one2many.dto.RescanView;
import com.greg.one2many.service.AttendeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AttendeeService attendeeService;

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