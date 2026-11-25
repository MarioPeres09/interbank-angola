package com.interbank.horizonte.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.interbank.horizonte.account.AccountRepository;
import com.interbank.horizonte.transfer.Transfer.Status;

@Service
class TransferStore {
    private final JdbcClient db;
    private final AccountRepository accounts;

    TransferStore(JdbcClient db, AccountRepository accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    @Transactional
    public Transfer reserve(long ownerId, UUID id, TransferRequest request) {
        if (!accounts.existsByIdAndOwnerId(request.sourceAccountId(), ownerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta de origem inexistente");
        }

        // Fast path also lets an existing transfer be retried after the account is closed.
        var existing = db.sql("SELECT * FROM transfers WHERE id = :id")
                .param("id", id).query(Transfer.class).optional();
        if (existing.isPresent()) {
            return checkRequest(existing.get(), request);
        }

        int inserted = db.sql("""
                INSERT INTO transfers (id, source_account_id, destination_account, amount, status)
                VALUES (:id, :source, :destination, :amount, 'PENDING')
                ON CONFLICT (id) DO NOTHING
                """).param("id", id).param("source", request.sourceAccountId())
                .param("destination", request.destinationAccount()).param("amount", request.amount())
                .update();

        if (inserted == 1) {
            int reserved = db.sql("""
                    UPDATE accounts
                    SET available_balance = available_balance - :amount
                    WHERE id = :source AND owner_id = :owner AND status = 'ACTIVE' AND available_balance >= :amount
                    """).param("source", request.sourceAccountId()).param("owner", ownerId)
                    .param("amount", request.amount())
                    .update();
            if (reserved == 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Conta de origem inactiva ou saldo disponível insuficiente");
            }
        }

        return checkRequest(get(id), request);
    }

    private Transfer checkRequest(Transfer transfer, TransferRequest request) {
        if (!transfer.matches(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador já usado com outros dados");
        }
        return transfer;
    }

    @Transactional
    public Transfer finish(UUID id, Status outcome) {
        if (outcome == Status.PENDING) {
            throw new IllegalArgumentException("O resultado tem de ser definitivo");
        }
        int changed = db.sql("""
                UPDATE transfers SET status = :status WHERE id = :id AND status = 'PENDING'
                """).param("id", id).param("status", outcome.name()).update();
        Transfer transfer = get(id);
        if (changed == 0) {
            return transfer;
        }

        if (outcome == Status.COMPLETED) {
            db.sql("UPDATE accounts SET balance = balance - :amount WHERE id = :account")
                    .param("amount", transfer.amount()).param("account", transfer.sourceAccountId()).update();
            db.sql("""
                    INSERT INTO transactions (account_id, transfer_id, type, amount)
                    VALUES (:account, :id, 'DEBIT', :amount)
                    """).param("account", transfer.sourceAccountId()).param("id", id)
                    .param("amount", transfer.amount()).update();
        } else {
            db.sql("UPDATE accounts SET available_balance = available_balance + :amount WHERE id = :account")
                    .param("amount", transfer.amount()).param("account", transfer.sourceAccountId()).update();
        }
        return transfer;
    }

    Transfer get(UUID id) {
        return db.sql("SELECT * FROM transfers WHERE id = :id").param("id", id)
                .query(Transfer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transferência inexistente"));
    }

    Transfer getForOwner(long ownerId, UUID id) {
        return db.sql("""
                SELECT t.* FROM transfers t JOIN accounts a ON a.id = t.source_account_id
                WHERE a.owner_id = :owner AND t.id = :id
                """).param("owner", ownerId).param("id", id).query(Transfer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transferência inexistente"));
    }

    List<Transfer> listForOwner(long ownerId) {
        return db.sql("""
                SELECT t.* FROM transfers t JOIN accounts a ON a.id = t.source_account_id
                WHERE a.owner_id = :owner ORDER BY t.created_at DESC, t.id
                """).param("owner", ownerId).query(Transfer.class).list();
    }

    Optional<Transfer> claimPending(long delayMs) {
        return db.sql("""
                UPDATE transfers SET last_attempt_at = now()
                WHERE id = (
                    SELECT id FROM transfers
                    WHERE status = 'PENDING' AND last_attempt_at <= now() - :delay * INTERVAL '1 millisecond'
                    ORDER BY last_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """).param("delay", delayMs).query(Transfer.class).optional();
    }
}
