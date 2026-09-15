# Technical Decisions & Engineering Log (`DECISIONS.md`)

**Role**: Java Backend Intern Assignment  
**Service**: Idempotent Payment & Wallet Event Processor  
**Author**: Akshay Malkar  
**Date**: September 2026  

---

## 1. How did you handle the concurrency race condition?

When dealing with money and ledger entries, the cost of an error is asymmetric—a false negative (rejecting a request) can be retried, but a false positive (double spending or negative wallet balance) corrupts the accounting book and leads to financial loss.

I addressed the concurrency race conditions through a **defense-in-depth architecture** operating across three distinct layers:

### A. Database-Level Row Locking (`SELECT ... FOR UPDATE`)
The primary challenge is the classic **Check-Then-Act** race condition:
- Thread 1 and Thread 2 both read `balance = ₹500.00` concurrently.
- Both verify that ₹500 >= ₹100.
- Both deduct ₹100 and write back ₹400, resulting in ₹100 lost or double spending.

To solve this deterministically without relying on fragile in-memory locking, I used JPA's `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `WalletRepository.findByUserIdForUpdate(userId)`.
- At the SQL engine level, this issues:
  ```sql
  SELECT user_id, balance FROM wallets WHERE user_id = ? FOR UPDATE;
  ```
- **How it behaves under concurrency**:
  When 10 concurrent debit requests of ₹100 hit a wallet with a ₹500 balance, the database grants the exclusive write lock to the first thread. The remaining 9 threads are held in the database transaction queue.
  - As each thread acquires the lock in turn, it reads the freshly committed balance:
    - Threads 1 through 5 observe balances ₹500 -> ₹400 -> ₹300 -> ₹200 -> ₹100 -> ₹0.00 and succeed.
    - Threads 6 through 10 acquire the lock, observe `balance = ₹0.00 < ₹100.00`, and immediately throw `InsufficientFundsException` (mapped to HTTP `400 Bad Request`).
  - To prevent thread starvation or connection leaks in edge deadlock conditions, I added a lock timeout hint (`jakarta.persistence.lock.timeout = 5000ms`).

### B. Two-Stage Idempotency Protection (Pre-Check + Post-Lock + DB Constraint)
Payment gateways frequently retry webhooks when network latency spikes. When 3 identical `transactionId` payloads arrive within 50ms:
1. **Pre-Lock Check (`transactionRepository.existsById`)**: If a retry arrives minutes later, this non-blocking check immediately avoids acquiring the wallet lock, saving database connection pool resources.
2. **Post-Lock Check**: If 3 duplicates hit at the *exact same millisecond*, Thread A acquires the wallet lock first. Threads B and C queue behind it. Once Thread A commits, Threads B and C acquire the lock and re-evaluate `existsById(txId)`. Seeing the record committed, they reject with `DuplicateTransactionException` (HTTP `409 Conflict`) without touching the balance.
3. **Engine-Level Primary Key Constraint**: The `transactions` table uses `transaction_id` as the primary key. In a multi-node cluster where requests might touch different replicas or connections, any concurrent duplicate insert attempt is stopped dead by a `DataIntegrityViolationException`, caught and converted into HTTP `409 Conflict`.

### C. Double-Entry Audit Trail
Rather than merely updating a number, every successful debit records:
- `balanceBefore` and `balanceAfter`
- `createdAt` UTC timestamp
- `status = SUCCESS`
This provides full ledger traceability for reconciliation.

---

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

While using an AI assistant to scaffold ideas and explore design patterns, I noticed several critical flaws and sub-optimal suggestions that would have caused the system to fail in production or fail the assignment's explicit test criteria:

### Issue 1: Recommending Optimistic Locking (`@Version`)
- **The AI's Proposal**: The AI initially suggested adding a `@Version Long version` column to `Wallet` and using optimistic concurrency control, arguing that "optimistic locking provides higher throughput and avoids row locks."
- **Why It Broke**: In our test scenario (10 simultaneous debit requests of ₹100 against a ₹500 balance), optimistic locking completely failed. Because all 10 threads read version 0 simultaneously, Thread 1 committed version 1, and the other 9 threads immediately failed with `OptimisticLockException`.  
  Only 1 request succeeded instead of 5, and the remaining 9 failed with database concurrency collisions rather than the required business validation (`Insufficient balance`).
- **The Fix**: I discarded optimistic locking and implemented pessimistic write locking (`SELECT ... FOR UPDATE`). In balance debits, queuing is the exact desired behavior so funds are drained predictably down to zero.

### Issue 2: Recommending In-Memory Java Locks (`synchronized` / `ConcurrentHashMap`)
- **The AI's Proposal**: The AI recommended synchronizing on user ID using `synchronized (userId.toString().intern())` in the service layer to avoid database lock overhead.
- **Why It Broke**:
  1. **Microservice Scalability**: In any production deployment (such as WonDRx services running in Kubernetes/containers behind an AWS ALB), requests are distributed across multiple JVM pods. In-memory locks only protect threads inside a single JVM; two requests on Pod 1 and Pod 2 would execute in parallel and corrupt the balance.
  2. **String Interning Risks**: Calling `.intern()` on user-supplied UUID strings pollutes the JVM String Pool, risking PermGen/Metaspace memory pressure and potential cross-tenant lock collisions.
- **The Fix**: I adhered strictly to the brief's requirement to use **database-level locking**, making the database the single, distributed source of truth.

### Issue 3: Missing `saveAndFlush` and Deferred JPA Constraint Triggers
- **The AI's Proposal**: The AI placed `transactionRepository.save(tx)` at the very end of the method with standard deferred execution.
- **Why It Broke**: Hibernate defaults to dirty-checking and defers SQL inserts until transaction commit time (at the end of the `@Transactional` boundary). If two concurrent duplicate transactions reached the commit phase, the unique constraint exception was thrown *after* the wallet balance was modified in the session, resulting in confusing transaction rollback semantics.
- **The Fix**: I added `transactionRepository.saveAndFlush(transaction)` to force the SQL INSERT immediately within the guarded block, ensuring unique constraint violations are surfaced and handled cleanly.
