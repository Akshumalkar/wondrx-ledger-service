package com.wondrx.ledger.dto;

import com.wondrx.ledger.entity.TransactionStatus;
import com.wondrx.ledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID userId,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        BigDecimal currentBalance,
        String message,
        Instant timestamp
) {
}
