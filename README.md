# Idempotent Payment & Wallet Event Processor

An internal transaction ledger service built with Spring Boot that safely processes debit webhooks under high concurrency, guarantees idempotency against duplicate webhook deliveries, and prevents negative balances using database-level locking.

## Tech Stack
- **Java 17+** (Developed and tested with Java 21)
- **Spring Boot 3.3.4** (Spring Web, Spring Data JPA, Bean Validation)
- **H2 In-Memory Database** (Zero-config setup for instant local evaluation)
- **JUnit 5 & AssertJ**

---

## Architecture & Concurrency Model

1. **Pessimistic Row-Level Locking (`PESSIMISTIC_WRITE`)**:
   - In `WalletRepository`, `findByUserIdForUpdate()` executes `SELECT ... FOR UPDATE` on the user's wallet row.
   - When concurrent debit requests hit the same wallet, they queue sequentially in the database engine.
   - Each request reads the latest committed balance, ensuring debits decrement the balance accurately down to zero before any further requests fail with `400 Bad Request` (Insufficient Funds).

2. **Idempotency Guarantee**:
   - `transaction_id` is defined as the Primary Key on the `transactions` table.
   - In `TransactionService`, an initial `existsById` check avoids locking the wallet for late retries.
   - In-flight simultaneous duplicates wait on the wallet lock and re-check `existsById` once unlocked.
   - Any database constraint collision throws `DataIntegrityViolationException`, which is caught and returned as `409 Conflict`. The wallet balance is never debited twice.

3. **Ledger Auditability**:
   - Every transaction record captures `balanceBefore` and `balanceAfter` to maintain a clear audit trail.

---

## API Specification

**Endpoint:** `POST /api/v1/transactions/process`  
**Content-Type:** `application/json`

### Request Body
```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 250.00,
  "type": "DEBIT"
}
```

### Responses
- **`200 OK`**: Successfully processed debit.
  ```json
  {
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "userId": "123e4567-e89b-12d3-a456-426614174000",
    "amount": 250.00,
    "type": "DEBIT",
    "status": "SUCCESS",
    "currentBalance": 750.00,
    "message": "Transaction processed successfully",
    "timestamp": "2026-09-15T11:46:57.969Z"
  }
  ```
- **`409 Conflict`**: Duplicate transaction received. Balance is not modified.
- **`400 Bad Request`**: Insufficient balance or invalid input payload.
- **`404 Not Found`**: Wallet not found for the specified `userId`.

---

## Running the Tests

The project requires **zero external configuration** (no external databases, Docker, or Postman required).

### Option 1: IntelliJ IDEA
1. Open IntelliJ IDEA -> **File** -> **Open** -> Select this project directory.
2. Navigate to `src/test/java/com/wondrx/ledger/TransactionIntegrationTest.java`.
3. Right-click the class and click **Run 'TransactionIntegrationTest'**.
4. The test console prints the intent and result for each test scenario.

### Option 2: Command Line (Maven Wrapper included)
```bash
# On Linux / macOS:
./mvnw clean test

# On Windows:
.\mvnw.cmd clean test
```

---

## Test Scenarios Verified

| Test Name | Description | Status |
| :--- | :--- | :---: |
| **Happy Path Test** | Processes a single valid debit transaction successfully. | **PASSED** |
| **Idempotency Test** | Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once (1 succeeds with 200, 2 return 409 Conflict). | **PASSED** |
| **Race Condition Test** | Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures final balance is exactly ₹0 and 5 requests fail with insufficient funds. | **PASSED** |
| **Edge Case: Insufficient Funds** | Rejects debit transaction when wallet has insufficient balance. | **PASSED** |
| **Edge Case: Wallet Not Found** | Returns 404 Not Found when wallet does not exist. | **PASSED** |

---

## Decision Log
See [`DECISIONS.md`](./DECISIONS.md) for details on the concurrency locking strategy and an analysis of AI assistant trade-offs.
