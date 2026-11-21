package com.greg.one2many.web;

import com.greg.one2many.dto.ScanInRequest;
import com.greg.one2many.dto.StationState;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

// Manual add from the dashboard: Greg types a LinkedIn link on the laptop, the STATION picks it
// up and runs its normal scan flow (ready card, hold to reveal, lock), because the attendee
// is standing at the station, not looking at the laptop.
// One slot, like a mailbox with room for one letter: the dashboard drops a link in,
// the station takes it out. Admin only (/api/admin/**).
@Slf4j
@RestController
@RequestMapping("/api/admin/station")
public class StationController {

    private static final Duration MAX_WAIT = Duration.ofSeconds(30); // station closed? don't fire it minutes later

    private record Pending(String linkedinUrl, Instant sentAt) {}

    private final AtomicReference<Pending> slot = new AtomicReference<>();

    // ---------- mirror: the station tells what it shows, the dashboard draws a mini copy ----------

    private final AtomicReference<StationState> lastState = new AtomicReference<>();

    // station: on every change, plus every few seconds as a heartbeat ("I'm still here")
    @PostMapping("/state")
    public ResponseEntity<Void> report(@Valid @RequestBody StationState state) {
        lastState.set(new StationState(state.state(), state.revealsLeft(), state.windowEndsAt(),
                state.message(), state.messageKind(), state.staffNote(), System.currentTimeMillis()));
        return ResponseEntity.noContent().build();
    }

    // dashboard: 204 if the station never reported (not open yet)
    @GetMapping("/state")
    public ResponseEntity<StationState> current() {
        StationState state = lastState.get();
        return state == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(state);
    }

    // ---------- manual add (one-slot mailbox) ----------

    // dashboard: same LinkedIn check as a real scan (ScanInRequest), so mistakes show on the laptop
    @PostMapping("/manual")
    public ResponseEntity<Void> send(@Valid @RequestBody ScanInRequest request) {
        slot.set(new Pending(request.getLinkedinUrl().trim(), Instant.now()));
        return ResponseEntity.accepted().build(); // 202: delivered to the mailbox, not processed yet
    }

    // station: polled every second or two; takes the link out (only once)
    @GetMapping("/manual")
    public ResponseEntity<Map<String, String>> take() {
        Pending pending = slot.getAndSet(null);
        if (pending == null) {
            return ResponseEntity.noContent().build();
        }
        if (Duration.between(pending.sentAt(), Instant.now()).compareTo(MAX_WAIT) > 0) {
            log.warn("Manual add from the dashboard expired (station didn't pick it up in time)");
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("linkedinUrl", pending.linkedinUrl()));
    }
}
