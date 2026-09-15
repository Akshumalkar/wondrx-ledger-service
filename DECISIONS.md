# DECISIONS.md

### 1. How did you handle the concurrency race condition?

To handle concurrency and prevent negative balances or double-deductions, I implemented database-level pessimistic locking (`SELECT ... FOR UPDATE`) using Spring Data JPA.

Here was my thought process:
- When multiple requests hit the same wallet at the exact same time (like the 10 concurrent requests of ₹100 against a ₹500 balance), there is a classic check-then-act race condition if we simply read the balance with a normal `findById`. Multiple threads would see ₹500 simultaneously, pass the balance check, and each deduct ₹100, which can easily push the balance negative or cause lost updates.
- To fix this, I added `@Lock(LockModeType.PESSIMISTIC_WRITE)` to `walletRepository.findByUserIdForUpdate()`. When a thread starts processing a transaction, it acquires an exclusive write lock on that specific user's wallet row in the database. Any other incoming requests for the same wallet are queued by the database engine until the active transaction commits.
- Because the operations are serialized at the row level:
  - The first 5 requests acquire the lock one by one, deduct ₹100, and commit.
  - The 6th request gets the lock, sees the balance has reached ₹0.00, and throws an `InsufficientFundsException` (returning HTTP 400).
  - The remaining requests similarly observe ₹0.00 and fail cleanly without mutating state. The final balance is guaranteed to be strictly ₹0.00.
  - I also added a lock timeout hint (`jakarta.persistence.lock.timeout = 5000ms`) to avoid threads hanging indefinitely in case of unexpected contention.

For duplicate transactions arriving concurrently (idempotency):
- I set `transaction_id` as the primary key in the `transactions` table.
- In `TransactionService`, I first do a fast `existsById` pre-check so already-committed retries don't even need to wait for the wallet lock.
- If duplicate webhooks arrive simultaneously within milliseconds, the first thread holds the wallet lock while the others wait. Once the first thread commits, the waiting threads acquire the lock and re-check `existsById(txId)`. Finding the record already present, they throw `DuplicateTransactionException` (returning HTTP 409 Conflict) without touching the wallet.
- If two transactions ever bypassed the check in a distributed setup, the database primary key constraint throws a `DataIntegrityViolationException`, which is caught and converted to HTTP 409 Conflict, ensuring the balance is never deducted twice.

---

### 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

While discussing design approaches with an AI assistant, it gave me three suggestions that turned out to be either incorrect or unsuitable for production:

1. **Suggesting Optimistic Locking (`@Version`) instead of Pessimistic Locking:**
   When I asked about handling concurrent balance updates, the AI's first response was to add a `@Version` column to the `Wallet` entity, arguing that optimistic locking is more scalable because it avoids database locks.
   However, when I wrote the 10-thread test (10 requests of ₹100 against a ₹500 wallet), optimistic locking failed completely. Since all 10 threads read version 0 at the same time, the first thread committed version 1 and the other 9 threads crashed immediately with an `OptimisticLockException`.
   As a result, only 1 request succeeded and 9 failed with database concurrency errors, rather than 5 succeeding and 5 failing due to "insufficient funds". For debiting a balance, threads need to queue and drain the balance sequentially, so database-level pessimistic locking (`SELECT ... FOR UPDATE`) was the right solution.

2. **Suggesting Java In-Memory Locks (`synchronized` / `ConcurrentHashMap`):**
   The AI also suggested synchronizing on the user ID using `synchronized (userId.toString().intern())` in the service layer.
   This had two major problems:
   - In production, backend services are deployed across multiple instances/containers behind a load balancer. A JVM-level lock only coordinates threads within that single process; requests hitting different containers would still run concurrently and corrupt the balance.
   - Calling `.intern()` on incoming request strings can easily lead to memory leaks in the JVM String Pool.
   The assignment explicitly asked for database-level locking, which ensures a single source of truth across all instances.

3. **Missing immediate JPA flushing (`saveAndFlush`):**
   The AI placed a basic `transactionRepository.save(tx)` at the end of the method without flushing. Because Hibernate defers SQL inserts until transaction commit time (at the end of the method), constraint violations for duplicate transactions were thrown during the commit phase after the wallet balance had already been mutated in memory.
   I fixed this by using `saveAndFlush(transaction)` inside the method, ensuring that any uniqueness collision is caught immediately and handled within our try-catch block.
