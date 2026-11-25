package com.interbank.kwanza.transfer;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreditRequest(
        @NotNull UUID id,
        @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String destinationAccount,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount
) {}
