package com.interbank.horizonte.transfer;

import org.springframework.stereotype.Service;

import com.interbank.horizonte.transfer.Transfer.Status;

@Service
class TransferDelivery {
    private final TransferStore transfers;
    private final KwanzaClient kwanza;

    TransferDelivery(TransferStore transfers, KwanzaClient kwanza) {
        this.transfers = transfers;
        this.kwanza = kwanza;
    }

    Transfer deliver(Transfer transfer) {
        if (transfer.status() != Status.PENDING) {
            return transfer;
        }
        // The reservation is already committed. Never hold a database transaction during HTTP.
        Status outcome = kwanza.send(transfer);
        return outcome == Status.PENDING ? transfers.get(transfer.id()) : transfers.finish(transfer.id(), outcome);
    }
}
