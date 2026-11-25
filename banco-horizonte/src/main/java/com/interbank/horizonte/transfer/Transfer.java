package com.interbank.horizonte.transfer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(
        UUID id,
        long sourceAccountId,
        String destinationAccount,
        String destinationBank,
        BigDecimal amount,
        String currency,
        Status status,
        OffsetDateTime createdAt
) {
    public enum Status { PENDING, COMPLETED, FAILED }

    boolean matches(TransferRequest request) {
        return sourceAccountId == request.sourceAccountId()
                && destinationAccount.equals(request.destinationAccount())
                && amount.compareTo(request.amount()) == 0;
    }
}
