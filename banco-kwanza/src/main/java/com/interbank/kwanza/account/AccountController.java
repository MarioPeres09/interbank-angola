package com.interbank.kwanza.account;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/accounts")
@SecurityRequirement(name = "cliente")
public class AccountController {
    private final AccountRepository accounts;
    private final JdbcClient db;

    public AccountController(AccountRepository accounts, JdbcClient db) {
        this.accounts = accounts;
        this.db = db;
    }

    @GetMapping
    public List<AccountResponse> list(JwtAuthenticationToken authentication) {
        return accounts.findByOwnerIdOrderByIdAsc(Long.parseLong(authentication.getName())).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable Long id, JwtAuthenticationToken authentication) {
        return ResponseEntity.of(accounts.findByIdAndOwnerId(id, Long.parseLong(authentication.getName()))
                .map(AccountResponse::from));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<AccountMovement>> transactions(@PathVariable Long id,
                                                              JwtAuthenticationToken authentication) {
        long ownerId = Long.parseLong(authentication.getName());
        if (!accounts.existsByIdAndOwnerId(id, ownerId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(db.sql("""
                SELECT t.id, t.transfer_id, t.type, t.amount, t.created_at
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE t.account_id = :account AND a.owner_id = :owner
                ORDER BY t.created_at DESC, t.id DESC
                """).param("account", id).param("owner", ownerId).query(AccountMovement.class).list());
    }
}
