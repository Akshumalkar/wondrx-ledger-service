# Architecture & Technical Decision Log (DECISIONS.md)

**Project**: Internal Transaction Ledger Service (Idempotent Payment/Wallet Event Processor)  
**Candidate**: Java Backend Developer  
**Tech Stack**: Java 21, Spring Boot 3.3.4, Spring Data JPA, H2 In-Memory Database, JUnit 5  

---

## 1. How did you handle the concurrency race condition?

### The Challenge
When multiple debit requests hit the ledger service simultaneously:
1. **Balance Race Condition (Check-Then-Act)**: If user wallet has ₹500 and 10 concurrent requests of ₹100 arrive, reading the balance simultaneously without locking would cause all 10 threads to see ₹500, debit ₹100, and end up with negative balance or lost updates (the classic double-spending / race condition problem).
2. **Duplicate Ingestion Race Condition**: When network timeouts cause the payment gateway to retry identical webhook payloads within 50ms, duplicate requests can execute concurrently before the first request finishes persisting, risking duplicate balance deduction.

### Our Solution: Defense-in-Depth Concurrency Architecture

We implemented a multi-layered concurrency and idempotency defense model:

#### A. Database-Level Pessimistic Locking (`PESSIMISTIC_WRITE`)
To serialize concurrent debit requests against the same wallet without application-level bottlenecks:
- We utilized Spring Data JPA's `@Lock(LockModeType.PESSIMISTIC_WRITE)` in `WalletRepository`:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
  Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
  ```
- **Underlying SQL Generated**:
  ```sql
  SELECT user_id, balance FROM wallets WHERE user_id = ? FOR UPDATE;
  ```
- **Execution Mechanism**:
  1. The first thread acquires an exclusive row-level lock on the target wallet row in the database engine.
  2. Competing concurrent threads for the same `userId` are placed in a database wait queue until the active transaction commits or rolls back.
  3. Subsequent threads evaluate the updated, committed balance sequentially:
     - The first 5 requests deduct ₹100 each, bringing the balance to ₹0.00.
     - The remaining 5 requests read `balance = 0.00 < 100.00` and immediately trigger an `InsufficientFundsException` (HTTP 400), rolling back without debiting.

#### B. Multi-Stage Idempotency Control (Unique Constraint + In-Lock Verification)
For duplicate requests with the exact same `transactionId`:
1. **Pre-Lock Check**: An initial read check (`existsById(transactionId)`) prevents unnecessary lock acquisition for delayed retries.
2. **Post-Lock Check**: If duplicate requests arrive simultaneously within a fraction of a millisecond, Thread A acquires the wallet row lock first while Threads B and C wait. Once Thread A commits the transaction, Threads B and C acquire the lock and re-check `existsById(transactionId)`, catching the duplicate before any debit logic executes.
3. **Primary Key / Unique Constraint Enforcement**: The `transaction_id` column is defined as the Primary Key in the `transactions` table. In the edge event of distributed concurrent commits across separate nodes, any secondary insert triggers a `DataIntegrityViolationException`, which our `GlobalExceptionHandler` intercepts and translates to **HTTP 409 Conflict** with zero double-debiting.

#### C. Database-Level Engine Check Constraint
As a hard safeguard against negative balances:
- The database schema enforces `CHECK (balance >= 0)`.
- Even in catastrophic application-level failure, the database engine itself rejects negative balance updates.

---

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

During the design and implementation phases, AI assistants proposed three sub-optimal or incorrect approaches:

### 1. Suggesting Optimistic Locking (`@Version`) Instead of Pessimistic Locking
- **The AI Suggestion**:  
  The AI suggested using optimistic locking with an `@Version` column on the `Wallet` entity, stating it provides higher throughput without holding database locks.
- **Why It Was Sub-Optimal**:  
  The assignment specifically tested **10 concurrent debit requests of ₹100 for a wallet with ₹500 balance**, requiring that **exactly 5 requests succeed and 5 requests fail with insufficient funds**.  
  Under optimistic locking, the first thread commits version 1, and the other 9 concurrent threads immediately crash with an `OptimisticLockException` due to version conflict. As a result, only 1 transaction would succeed and 9 would fail with concurrency errors (rather than the business logic check of insufficient funds), completely failing the assignment's acceptance criteria.  
- **Correction Applied**:  
  Replaced optimistic locking with database-level pessimistic write locking (`SELECT ... FOR UPDATE`), which queues requests and allows valid debits to drain the balance sequentially down to ₹0.00 before rejecting with insufficient funds.

### 2. Suggesting In-Memory Synchronization (`synchronized` / `ReentrantLock`)
- **The AI Suggestion**:  
  The AI suggested synchronizing Java methods (`synchronized(userId.toString().intern())`) or using a `ConcurrentHashMap` of locks in the service layer.
- **Why It Was Sub-Optimal**:  
  In-memory synchronization only works within a single JVM instance. In production cloud environments, backend services run behind load balancers with multiple container instances (horizontal scaling). Two concurrent requests routed to different containers would bypass Java memory locks entirely, resulting in double-spending. Furthermore, string interning for locking risks memory leaks and deadlocks.
- **Correction Applied**:  
  Adhered strictly to the assignment requirement: **"Prevent negative balances on simultaneous debits using database-level locking."** The database acts as the single source of truth for synchronization across all instances.

### 3. Placing Transaction Record Creation After Modifying the Wallet Balance Without Atomic Flush
- **The AI Suggestion**:  
  The initial AI code drafted the idempotency verification as a plain `findById` read at the top of the method, followed by `walletRepository.save(wallet)`, and only persisted `transactionRepository.save(tx)` at the very end of the method without calling `saveAndFlush`.
- **Why It Was Incorrect**:  
  JPA defers SQL `INSERT` statements to the flush phase right before transaction commit. If two requests with the same `transactionId` executed within 50ms, both passed the initial read check, debited the balance in memory, and then raised integrity errors during deferred commit. This left dirty state and ambiguous exception handling.
- **Correction Applied**:  
  Enforced a post-lock idempotency check and called `saveAndFlush(transaction)` to validate unique constraints immediately inside the transactional boundary.
