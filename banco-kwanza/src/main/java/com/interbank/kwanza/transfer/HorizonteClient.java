package com.interbank.kwanza.transfer;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.interbank.kwanza.transfer.Transfer.Status;

@Component
class HorizonteClient {
    private static final Logger log = LoggerFactory.getLogger(HorizonteClient.class);
    private final RestClient client;

    HorizonteClient(@Value("${interbank.router-url}") String baseUrl,
                    @Value("${interbank.outgoing-secret}") String password) {
        if (!password.matches("[!-~]{32,64}")) {
            throw new IllegalArgumentException("INTERBANK_SHARED_PASSWORD deve ter 32 a 64 caracteres ASCII sem espaços");
        }
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(7));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth("banco-kwanza", password)).build();
    }

    Status send(Transfer transfer) {
        try {
            var request = new CreditRequest(transfer.id(), transfer.destinationAccount(), transfer.amount());
            CreditReceipt receipt = client.post().uri("/api/interbank/credits")
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(CreditReceipt.class);
            if (matches(transfer, receipt)) {
                if ("CREDITED".equals(receipt.status())) {
                    return Status.COMPLETED;
                }
                if ("REJECTED".equals(receipt.status())) {
                    return Status.FAILED;
                }
            }
            log.warn("Recibo inválido do Horizonte para a transferência {}", transfer.id());
        } catch (RestClientException exception) {
            log.warn("Resultado do Horizonte incerto para {} ({})",
                    transfer.id(), exception.getClass().getSimpleName());
        }
        return Status.PENDING;
    }

    CreditReceipt receipt(Transfer transfer) {
        try {
            CreditReceipt receipt = client.get().uri("/api/interbank/credits/{id}", transfer.id())
                    .retrieve().body(CreditReceipt.class);
            if (matches(transfer, receipt)) {
                return receipt;
            }
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recibo inexistente");
        } catch (RestClientException exception) {
            log.warn("Recibo do Horizonte indisponível para {} ({})",
                    transfer.id(), exception.getClass().getSimpleName());
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recibo do Horizonte indisponível");
    }

    private boolean matches(Transfer transfer, CreditReceipt receipt) {
        return receipt != null && transfer.id().equals(receipt.id())
                && transfer.destinationAccount().equals(receipt.destinationAccount())
                && transfer.currency().equals(receipt.currency())
                && receipt.amount() != null && transfer.amount().compareTo(receipt.amount()) == 0;
    }

    private record CreditRequest(UUID id, String destinationAccount, BigDecimal amount) {}

    record CreditReceipt(UUID id, String destinationAccount, BigDecimal amount,
                         String currency, String status, OffsetDateTime createdAt) {}
}
