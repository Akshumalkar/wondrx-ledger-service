package com.wondrx.ledger.service;

import com.wondrx.ledger.dto.TransactionRequest;
import com.wondrx.ledger.dto.TransactionResponse;
import com.wondrx.ledger.entity.Transaction;
import com.wondrx.ledger.entity.TransactionStatus;
import com.wondrx.ledger.entity.Wallet;
import com.wondrx.ledger.exception.DuplicateTransactionException;
import com.wondrx.ledger.exception.InsufficientFundsException;
import com.wondrx.ledger.exception.WalletNotFoundException;
import com.wondrx.ledger.repository.TransactionRepository;
import com.wondrx.ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Ingests and processes a debit webhook transaction.
     * Uses pessimistic locking (SELECT ... FOR UPDATE) on the wallet to guarantee sequential execution
     * across concurrent requests, strictly avoiding dirty reads or balance underflows.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(TransactionRequest request) {
        UUID txId = request.transactionId();
        UUID userId = request.userId();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_EVEN);

        log.info("Processing debit transaction [txId={}, userId={}, amount={}]", txId, userId, amount);

        // Fast non-locking pre-check for already committed transactions (avoids lock contention on late retries)
        if (transactionRepository.existsById(txId)) {
            log.warn("Idempotency violation: transaction {} was already committed", txId);
            throw new DuplicateTransactionException(txId,
                    "Transaction " + txId + " has already been processed.");
        }

        // Acquire exclusive row-level lock on the target wallet
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));

        // Re-evaluate idempotency after obtaining lock to serialize concurrent in-flight duplicates
        if (transactionRepository.existsById(txId)) {
            log.warn("Idempotency violation post-lock: transaction {} exists", txId);
            throw new DuplicateTransactionException(txId,
                    "Transaction " + txId + " has already been processed.");
        }

        BigDecimal balanceBefore = wallet.getBalance().setScale(2, RoundingMode.HALF_EVEN);

        if (balanceBefore.compareTo(amount) < 0) {
            log.warn("Debit rejected: insufficient funds for user {} [current={}, requested={}]",
                    userId, balanceBefore, amount);
            throw new InsufficientFundsException(String.format(
                    "Insufficient balance in wallet. Current: %s, Requested: %s", balanceBefore, amount));
        }

        wallet.debit(amount);
        walletRepository.save(wallet);

        BigDecimal balanceAfter = wallet.getBalance().setScale(2, RoundingMode.HALF_EVEN);

        Transaction transaction = new Transaction(
                txId,
                userId,
                amount,
                request.type(),
                TransactionStatus.SUCCESS,
                balanceBefore,
                balanceAfter,
                Instant.now()
        );

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Constraint violation saving transaction {}: duplicate detected", txId);
            throw new DuplicateTransactionException(txId,
                    "Transaction " + txId + " has already been processed.");
        }

        log.info("Debit successful for user {} [txId={}, balance: {} -> {}]",
                userId, txId, balanceBefore, balanceAfter);

        return new TransactionResponse(
                transaction.getTransactionId(),
                transaction.getUserId(),
                transaction.getAmount(),
                transaction.getType(),
                transaction.getStatus(),
                balanceAfter,
                "Transaction processed successfully",
                transaction.getCreatedAt()
        );
    }

    @Transactional
    public Wallet createOrUpdateWallet(UUID userId, BigDecimal initialBalance) {
        Wallet wallet = walletRepository.findById(userId)
                .orElse(new Wallet(userId, BigDecimal.ZERO));
        wallet.setBalance(initialBalance.setScale(2, RoundingMode.HALF_EVEN));
        return walletRepository.saveAndFlush(wallet);
    }

    @Transactional(readOnly = true)
    public BigDecimal getWalletBalance(UUID userId) {
        return walletRepository.findById(userId)
                .map(w -> w.getBalance().setScale(2, RoundingMode.HALF_EVEN))
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
