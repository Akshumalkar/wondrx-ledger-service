package com.wondrx.ledger.exception;

import java.util.UUID;

public class DuplicateTransactionException extends RuntimeException {
    private final UUID transactionId;

    public DuplicateTransactionException(UUID transactionId, String message) {
        super(message);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
