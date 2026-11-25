package com.interbank.kwanza.transfer;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CreditService {
    private final JdbcClient db;

    CreditService(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public ReceivedTransfer receive(CreditRequest request) {
        // Reserve the identifier first. Concurrent requests wait for the committed receipt.
        int inserted = db.sql("""
                INSERT INTO received_transfers (id, destination_account, amount, status)
                VALUES (:id, :destination, :amount, 'REJECTED')
                ON CONFLICT (id) DO NOTHING
                """).param("id", request.id()).param("destination", request.destinationAccount())
                .param("amount", request.amount()).update();

        if (inserted == 1) {
            creditAccount(request);
        }

        ReceivedTransfer receipt = get(request.id());
        if (!receipt.matches(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador já usado com outros dados");
        }
        return receipt;
    }

    private void creditAccount(CreditRequest request) {
        var account = db.sql("""
                UPDATE accounts SET balance = balance + :amount, available_balance = available_balance + :amount
                WHERE account_number = :destination AND status = 'ACTIVE'
                  AND balance + :amount <= 99999999999999999.99
                RETURNING id
                """).param("destination", request.destinationAccount()).param("amount", request.amount())
                .query(Long.class).optional();
        if (account.isEmpty()) {
            return;
        }
        db.sql("INSERT INTO transactions (account_id, transfer_id, type, amount) VALUES (:account, :id, 'CREDIT', :amount)")
                .param("account", account.get()).param("id", request.id()).param("amount", request.amount()).update();
        db.sql("UPDATE received_transfers SET status = 'CREDITED' WHERE id = :id")
                .param("id", request.id()).update();
    }

    ReceivedTransfer get(UUID id) {
        return db.sql("SELECT * FROM received_transfers WHERE id = :id").param("id", id)
                .query(ReceivedTransfer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recibo inexistente"));
    }
}
