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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
     * Processes an incoming debit transaction idempotently and safely under concurrency.
     * Uses pessimistic locking (SELECT ... FOR UPDATE) on the wallet row to serialize balance deductions.
     * Enforces idempotency via unique transactionId constraints and pre-checks.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(TransactionRequest request) {
        log.info("Processing transaction: id={}, userId={}, amount={}, type={}",
                request.transactionId(), request.userId(), request.amount(), request.type());

        // 1. Fast idempotency pre-check
        if (transactionRepository.existsById(request.transactionId())) {
            log.warn("Duplicate transaction detected in pre-check: {}", request.transactionId());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Transaction " + request.transactionId() + " has already been processed.");
        }

        // 2. Acquire database-level exclusive lock on the wallet
        Wallet wallet = walletRepository.findByUserIdForUpdate(request.userId())
                .orElseThrow(() -> new WalletNotFoundException(request.userId()));

        // 3. Re-check idempotency once exclusive lock is acquired (prevents race window)
        if (transactionRepository.existsById(request.transactionId())) {
            log.warn("Duplicate transaction detected after lock acquisition: {}", request.transactionId());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Transaction " + request.transactionId() + " has already been processed.");
        }

        // 4. Validate sufficient balance
        if (wallet.getBalance().compareTo(request.amount()) < 0) {
            log.warn("Insufficient funds for user {}: current balance={}, requested debit={}",
                    request.userId(), wallet.getBalance(), request.amount());
            throw new InsufficientFundsException("Insufficient balance in wallet. Current: "
                    + wallet.getBalance() + ", Requested: " + request.amount());
        }

        // 5. Debit the wallet balance
        wallet.debit(request.amount());
        walletRepository.save(wallet);

        // 6. Record the transaction ledger entry
        Transaction transaction = new Transaction(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type(),
                TransactionStatus.SUCCESS,
                Instant.now()
        );

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Unique constraint violation for transaction: {}", request.transactionId());
            throw new DuplicateTransactionException(request.transactionId(),
                    "Transaction " + request.transactionId() + " has already been processed.");
        }

        log.info("Transaction {} succeeded. New balance for user {}: {}",
                request.transactionId(), request.userId(), wallet.getBalance());

        return new TransactionResponse(
                transaction.getTransactionId(),
                transaction.getUserId(),
                transaction.getAmount(),
                transaction.getType(),
                transaction.getStatus(),
                wallet.getBalance(),
                "Transaction processed successfully",
                transaction.getCreatedAt()
        );
    }

    /**
     * Helper to create or top up a wallet for testing and initialization.
     */
    @Transactional
    public Wallet createOrUpdateWallet(UUID userId, BigDecimal initialBalance) {
        Wallet wallet = walletRepository.findById(userId)
                .orElse(new Wallet(userId, BigDecimal.ZERO));
        wallet.setBalance(initialBalance);
        return walletRepository.saveAndFlush(wallet);
    }

    @Transactional(readOnly = true)
    public BigDecimal getWalletBalance(UUID userId) {
        return walletRepository.findById(userId)
                .map(Wallet::getBalance)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
