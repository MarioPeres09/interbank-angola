package com.interbank.horizonte.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountMovement(long id, UUID transferId, String type, BigDecimal amount, OffsetDateTime createdAt) {}
