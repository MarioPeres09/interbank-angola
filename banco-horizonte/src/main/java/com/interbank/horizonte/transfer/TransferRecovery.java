package com.interbank.horizonte.transfer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TransferRecovery {
    private final TransferStore transfers;
    private final TransferDelivery delivery;
    private final long delayMs;

    TransferRecovery(TransferStore transfers, TransferDelivery delivery,
                     @Value("${interbank.recovery-delay-ms}") long delayMs) {
        if (delayMs < 1000) {
            throw new IllegalArgumentException("INTERBANK_RECOVERY_DELAY_MS deve ser de pelo menos 1000 ms");
        }
        this.transfers = transfers;
        this.delivery = delivery;
        this.delayMs = delayMs;
    }

    @Scheduled(fixedDelayString = "${interbank.recovery-delay-ms}")
    void recover() {
        for (int processed = 0; processed < 10; processed++) {
            var next = transfers.claimPending(delayMs);
            if (next.isEmpty()) {
                return;
            }
            if (delivery.deliver(next.get()).status() == Transfer.Status.PENDING) {
                // Stop this cycle when the destination cannot confirm a request.
                return;
            }
        }
    }
}
