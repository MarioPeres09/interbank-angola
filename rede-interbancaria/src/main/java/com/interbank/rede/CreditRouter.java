package com.interbank.rede;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@RestController
class CreditRouter {
    private static final Logger log = LoggerFactory.getLogger(CreditRouter.class);
    private static final String CREDITS = "/api/interbank/credits";
    private final Map<String, RestClient> destinations;

    CreditRouter(@Value("${rede.kwanza.url}") String kwanzaUrl,
                 @Value("${rede.horizonte.url}") String horizonteUrl,
                 @Value("${rede.credencial-kwanza}") String kwanzaPassword,
                 @Value("${rede.credencial-horizonte}") String horizontePassword) {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(3));
        destinations = Map.of(
                SecurityConfig.KWANZA, client(horizonteUrl, SecurityConfig.KWANZA, kwanzaPassword, factory),
                SecurityConfig.HORIZONTE, client(kwanzaUrl, SecurityConfig.HORIZONTE, horizontePassword, factory));
    }

    private RestClient client(String url, String bank, String password, JdkClientHttpRequestFactory factory) {
        return RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth(bank, password)).build();
    }

    @GetMapping("/api/network/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping(CREDITS)
    ResponseEntity<byte[]> forward(@RequestBody @Valid CreditRequest request, Principal bank) {
        return exchange(destinations.get(bank.getName()).post().uri(CREDITS)
                .contentType(MediaType.APPLICATION_JSON).body(request), bank.getName(), request.id());
    }

    @GetMapping(CREDITS + "/{id}")
    ResponseEntity<byte[]> receipt(@PathVariable UUID id, Principal bank) {
        return exchange(destinations.get(bank.getName()).get().uri(CREDITS + "/{id}", id), bank.getName(), id);
    }

    private ResponseEntity<byte[]> exchange(RestClient.RequestHeadersSpec<?> request, String origin, UUID id) {
        try {
            return request.exchange((sent, response) -> {
                log.info("Pedido de {} com referência {} devolveu HTTP {}", origin, id, response.getStatusCode().value());
                return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                        .body(response.getBody().readAllBytes());
            });
        } catch (RestClientException exception) {
            log.warn("Destino indisponível para pedido de {} com referência {} ({})",
                    origin, id, exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Banco de destino indisponível");
        }
    }

    record CreditRequest(@NotNull UUID id,
                         @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String destinationAccount,
                         @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {}
}
