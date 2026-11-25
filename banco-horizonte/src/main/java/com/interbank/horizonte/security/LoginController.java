package com.interbank.horizonte.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
class LoginController {
    private final JdbcClient db;
    private final BCryptPasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;

    LoginController(JdbcClient db, BCryptPasswordEncoder passwords, JwtEncoder encoder) {
        this.db = db;
        this.passwords = passwords;
        this.encoder = encoder;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<Session> login(@Valid @RequestBody Credentials credentials) {
        if (credentials.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Palavra-passe demasiado longa");
        }
        var customer = db.sql("SELECT id, name, password_hash FROM users WHERE email = :email AND role = 'CUSTOMER'")
                .param("email", credentials.email()).query(Customer.class).optional();
        String hash = customer.map(Customer::passwordHash).orElse(dummyHash);
        boolean matches = passwords.matches(credentials.password(), hash);
        if (!matches || customer.isEmpty() || customer.get().passwordHash() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
        }

        Customer user = customer.get();
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(30));
        var claims = JwtClaimsSet.builder()
                .issuer(SecurityConfig.ISSUER)
                .subject(Long.toString(user.id()))
                .issuedAt(now)
                .expiresAt(expiry)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Session(token, expiry, user.id(), user.name()));
    }

    public record Credentials(@NotBlank @Email @Size(max = 254) String email,
                              @NotBlank @Size(max = 72) String password) {}

    public record Session(String token, Instant expiresAt, long customerId, String customerName) {}

    public record Customer(long id, String name, String passwordHash) {}
}
