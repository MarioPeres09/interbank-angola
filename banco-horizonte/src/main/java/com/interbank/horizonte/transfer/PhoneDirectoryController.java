package com.interbank.horizonte.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@SecurityRequirement(name = "banco")
class PhoneDirectoryController {
    private final JdbcClient db;

    PhoneDirectoryController(JdbcClient db) {
        this.db = db;
    }

    @PostMapping("/api/interbank/aliases/phone")
    PhoneDestination resolve(@RequestBody @Valid PhoneRequest request) {
        return db.sql("""
                SELECT account_number FROM accounts
                WHERE transfer_phone = :phone AND status = 'ACTIVE'
                """).param("phone", request.phone()).query(PhoneDestination.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Telemóvel sem conta activa"));
    }

    record PhoneRequest(@NotNull @Pattern(regexp = "[+]2449[0-9]{8}") String phone) {}
}
