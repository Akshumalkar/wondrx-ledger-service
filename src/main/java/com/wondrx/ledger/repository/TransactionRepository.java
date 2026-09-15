package com.wondrx.ledger.repository;

import com.wondrx.ledger.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    boolean existsByTransactionId(UUID transactionId);
}
