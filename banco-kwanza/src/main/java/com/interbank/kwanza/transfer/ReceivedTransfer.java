package com.interbank.kwanza.transfer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReceivedTransfer(
        UUID id,
        String destinationAccount,
        BigDecimal amount,
        String currency,
        Status status,
        OffsetDateTime createdAt
) {
    public enum Status { CREDITED, REJECTED }

    boolean matches(CreditRequest request) {
        return destinationAccount.equals(request.destinationAccount())
                && amount.compareTo(request.amount()) == 0;
    }
}
