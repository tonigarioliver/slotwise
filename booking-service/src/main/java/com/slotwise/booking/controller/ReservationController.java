package com.slotwise.booking.controller;

import com.slotwise.booking.model.Caller;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import com.slotwise.booking.security.Roles;
import com.slotwise.booking.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping("/api/reservations")
    public ResponseEntity<ReservationDto> create(@Valid @RequestBody CreateReservationRequest request, Authentication authentication,
            @RequestHeader(value = IdempotentResponse.KEY_HEADER, required = false) @Nullable String idempotencyKey) {
        return IdempotentResponse.created(this.reservationService.create(caller(authentication), request, idempotencyKey));
    }

    // Translation only: who is calling and are they an admin. What that allows is decided in the service.
    private static Caller caller(Authentication authentication) {
        final boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + Roles.RESERVATION_ADMIN));
        return new Caller(authentication.getName(), admin);
    }

    @GetMapping("/api/resources/{resourceId}/reservations")
    public Page<ReservationDto> listByResource(
            @PathVariable Long resourceId,
            @PageableDefault(size = 20, sort = "startTime") Pageable pageable) {
        return this.reservationService.listByResource(resourceId, pageable);
    }
}
