package com.interbank.horizonte.transfer;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.interbank.horizonte.transfer.Transfer.Status;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transfers")
@SecurityRequirement(name = "cliente")
public class TransferController {
    private final TransferStore transfers;
    private final KwanzaClient kwanza;
    private final TransferDelivery delivery;

    TransferController(TransferStore transfers, KwanzaClient kwanza, TransferDelivery delivery) {
        this.transfers = transfers;
        this.kwanza = kwanza;
        this.delivery = delivery;
    }

    @PostMapping
    public ResponseEntity<Transfer> create(
            @RequestHeader("Idempotency-Key") UUID id, @RequestBody @Valid TransferRequest request,
            JwtAuthenticationToken authentication) {
        Transfer transfer = delivery.deliver(transfers.reserve(Long.parseLong(authentication.getName()), id, request));
        HttpStatus status = transfer.status() == Status.PENDING ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).location(URI.create("/api/transfers/" + id)).body(transfer);
    }

    @GetMapping
    public List<Transfer> list(JwtAuthenticationToken authentication) {
        return transfers.listForOwner(Long.parseLong(authentication.getName()));
    }

    @GetMapping("/{id}")
    public Transfer get(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return transfers.getForOwner(Long.parseLong(authentication.getName()), id);
    }

    @GetMapping("/{id}/receipt")
    public KwanzaClient.CreditReceipt receipt(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return kwanza.receipt(get(id, authentication));
    }
}
