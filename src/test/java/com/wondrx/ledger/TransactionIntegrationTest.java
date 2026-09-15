package com.wondrx.ledger;

import com.wondrx.ledger.dto.TransactionRequest;
import com.wondrx.ledger.dto.TransactionResponse;
import com.wondrx.ledger.entity.TransactionType;
import com.wondrx.ledger.entity.Wallet;
import com.wondrx.ledger.repository.TransactionRepository;
import com.wondrx.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TransactionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private String getEndpointUrl() {
        return "http://localhost:" + port + "/api/v1/transactions/process";
    }

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void testHappyPath() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST INTENT] Processes a single valid debit transaction successfully.");
        System.out.println("Checking standard debit workflow with sufficient funds and valid payload.");
        System.out.println("=".repeat(80));

        // 1. Arrange
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal debitAmount = new BigDecimal("250.00");
        BigDecimal expectedFinalBalance = new BigDecimal("750.00");

        walletRepository.saveAndFlush(new Wallet(userId, initialBalance));

        TransactionRequest request = new TransactionRequest(
                transactionId,
                userId,
                debitAmount,
                TransactionType.DEBIT
        );

        // 2. Act
        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                getEndpointUrl(),
                new HttpEntity<>(request, createHeaders()),
                TransactionResponse.class
        );

        // 3. Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().currentBalance().setScale(2, RoundingMode.HALF_UP))
                .isEqualTo(expectedFinalBalance.setScale(2, RoundingMode.HALF_UP));

        Wallet updatedWallet = walletRepository.findById(userId).orElseThrow();
        assertThat(updatedWallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .isEqualTo(expectedFinalBalance.setScale(2, RoundingMode.HALF_UP));
        assertThat(transactionRepository.existsById(transactionId)).isTrue();

        System.out.println("[TEST RESULT] Status Code: " + response.getStatusCode());
        System.out.println("[TEST RESULT] Initial Balance: " + initialBalance + ", Debit: " + debitAmount
                + ", Resulting Balance: " + updatedWallet.getBalance());
        System.out.println("[TEST RESULT] PASSED - Transaction processed and balance accurately debited.");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void testIdempotentWebhookIngestion() throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST INTENT] Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.");
        System.out.println("Simulating 3 duplicate gateway webhooks arriving concurrently within milliseconds.");
        System.out.println("=".repeat(80));

        // 1. Arrange
        UUID userId = UUID.randomUUID();
        UUID sharedTransactionId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.00");
        BigDecimal debitAmount = new BigDecimal("250.00");
        BigDecimal expectedFinalBalance = new BigDecimal("750.00");

        walletRepository.saveAndFlush(new Wallet(userId, initialBalance));

        TransactionRequest request = new TransactionRequest(
                sharedTransactionId,
                userId,
                debitAmount,
                TransactionType.DEBIT
        );

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Wait for all threads to be ready so they fire simultaneously
                startLatch.await(5, TimeUnit.SECONDS);

                return restTemplate.exchange(
                        getEndpointUrl(),
                        HttpMethod.POST,
                        new HttpEntity<>(request, createHeaders()),
                        String.class
                );
            }));
        }

        // Wait for all threads to reach the gate, then trigger all at the exact same instant
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // 2. Act & Collect
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<HttpStatus> responseStatuses = Collections.synchronizedList(new ArrayList<>());

        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> res = future.get();
            responseStatuses.add((HttpStatus) res.getStatusCode());
            if (res.getStatusCode() == HttpStatus.OK) {
                successCount.incrementAndGet();
            } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                conflictCount.incrementAndGet();
            }
        }

        // 3. Assert
        Wallet updatedWallet = walletRepository.findById(userId).orElseThrow();

        System.out.println("[TEST RESULT] Response Statuses Received: " + responseStatuses);
        System.out.println("[TEST RESULT] HTTP 200 OK count: " + successCount.get());
        System.out.println("[TEST RESULT] HTTP 409 Conflict count: " + conflictCount.get());
        System.out.println("[TEST RESULT] Wallet Initial Balance: " + initialBalance + ", Final Balance: " + updatedWallet.getBalance());

        assertThat(successCount.get())
                .withFailMessage("Exactly one transaction must succeed")
                .isEqualTo(1);

        assertThat(conflictCount.get())
                .withFailMessage("The remaining duplicate transactions must return 409 Conflict")
                .isEqualTo(2);

        assertThat(updatedWallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .withFailMessage("Balance should only be deducted exactly once")
                .isEqualTo(expectedFinalBalance.setScale(2, RoundingMode.HALF_UP));

        assertThat(transactionRepository.count()).isEqualTo(1);

        System.out.println("[TEST RESULT] PASSED - Exactly 1 succeeded, 2 rejected as 409 Conflict. Balance deducted once.");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void testRaceConditionUnderConcurrentDebits() throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST INTENT] Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance.");
        System.out.println("Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.");
        System.out.println("=".repeat(80));

        // 1. Arrange
        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("100.00");
        BigDecimal expectedFinalBalance = new BigDecimal("0.00");

        walletRepository.saveAndFlush(new Wallet(userId, initialBalance));

        int totalRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            UUID txId = UUID.randomUUID();
            TransactionRequest request = new TransactionRequest(
                    txId,
                    userId,
                    debitAmount,
                    TransactionType.DEBIT
            );

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Synchronize all threads so they race simultaneously
                startLatch.await(5, TimeUnit.SECONDS);

                return restTemplate.exchange(
                        getEndpointUrl(),
                        HttpMethod.POST,
                        new HttpEntity<>(request, createHeaders()),
                        String.class
                );
            }));
        }

        // Release all 10 threads simultaneously
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        executor.shutdown();
        boolean completed = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // 2. Act & Collect
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        List<HttpStatus> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> res = future.get();
            statusCodes.add((HttpStatus) res.getStatusCode());
            if (res.getStatusCode() == HttpStatus.OK) {
                successCount.incrementAndGet();
            } else if (res.getStatusCode() == HttpStatus.BAD_REQUEST) {
                insufficientFundsCount.incrementAndGet();
            }
        }

        // 3. Assert
        Wallet finalWallet = walletRepository.findById(userId).orElseThrow();

        System.out.println("[TEST RESULT] Total Requests: " + totalRequests);
        System.out.println("[TEST RESULT] Statuses Received: " + statusCodes);
        System.out.println("[TEST RESULT] Successful Debits (HTTP 200): " + successCount.get());
        System.out.println("[TEST RESULT] Failed Debits (HTTP 400 Insufficient Funds): " + insufficientFundsCount.get());
        System.out.println("[TEST RESULT] Initial Balance: " + initialBalance + ", Final Balance: " + finalWallet.getBalance());

        assertThat(successCount.get())
                .withFailMessage("Exactly 5 debit requests should succeed")
                .isEqualTo(5);

        assertThat(insufficientFundsCount.get())
                .withFailMessage("Exactly 5 debit requests should fail due to insufficient funds")
                .isEqualTo(5);

        assertThat(finalWallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .withFailMessage("Final balance must be exactly ₹0.00")
                .isEqualTo(expectedFinalBalance.setScale(2, RoundingMode.HALF_UP));

        assertThat(transactionRepository.count()).isEqualTo(5);

        System.out.println("[TEST RESULT] PASSED - Exactly 5 succeeded, 5 failed with insufficient funds. Final balance is ₹0.");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Edge Case: Rejects debit when wallet has insufficient balance on single request.")
    void testSingleDebitInsufficientFunds() {
        UUID userId = UUID.randomUUID();
        walletRepository.saveAndFlush(new Wallet(userId, new BigDecimal("50.00")));

        TransactionRequest request = new TransactionRequest(
                UUID.randomUUID(),
                userId,
                new BigDecimal("100.00"),
                TransactionType.DEBIT
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                getEndpointUrl(),
                new HttpEntity<>(request, createHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Wallet wallet = walletRepository.findById(userId).orElseThrow();
        assertThat(wallet.getBalance().setScale(2, RoundingMode.HALF_UP))
                .isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("Edge Case: Rejects debit when wallet does not exist (returns 404 Not Found).")
    void testWalletNotFound() {
        UUID nonExistentUserId = UUID.randomUUID();

        TransactionRequest request = new TransactionRequest(
                UUID.randomUUID(),
                nonExistentUserId,
                new BigDecimal("50.00"),
                TransactionType.DEBIT
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                getEndpointUrl(),
                new HttpEntity<>(request, createHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
