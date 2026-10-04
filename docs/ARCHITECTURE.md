# Architecture and design decisions

## User problem

A mortgage payout arrives with a last-minute replacement account. An urgent email asks operations to release funds before the closing deadline. Even if the email looks legitimate, the bank destination has changed and the earlier approval no longer applies.

FundingGuard records the new instruction version, invalidates old verification/approval, and blocks release until independent reviewers check the current details. A security analyst can apply a hold. Resolving an investigation does not automatically clear that hold.

## Runtime flow

```text
Angular operations workspace
        |
        | HTTPS in deployment; same-origin session + CSRF
        v
Spring Security -> tenant/user resolution -> typed API requests
        |
        v
WorkflowService transaction
  - authorize role
  - lock tenant row
  - read current payout version
  - verify role independence, freshness, hold and contact version
  - atomically write state + immutable ledger/audit + outbox
        |
        v
PostgreSQL
  payout summary | immutable versions | verification | approvals
  unique simulated ledger | idempotency | cases | audit | outbox
        |
        v
Scheduled outbox worker -> persistent in-app notifications
```

No live bank, insurer, lender, email service, AI model, or external verification system is contacted.

## State model

`REVIEW_REQUIRED -> independently verified -> APPROVED -> RELEASED_SIMULATED`

Verification is represented by version-bound evidence, not a separate payout state. A hold is an independent flag and overrides release eligibility. A revision returns an unreleased payout to review and invalidates prior attestations. Two-person hold clearance also requires a fresh approval. Receipt and discharge are separate post-release evidence flags.

## Why these choices

- A modular monolith keeps the transaction boundary straightforward. Unnecessary microservices would make release consistency harder without improving this small demo.
- PostgreSQL transactions and unique constraints are the source of truth, not disabled UI buttons.
- A tenant row lock intentionally serializes writes within a tenant. This is simple and conservative but limits throughput; a production design should evaluate finer-grained obligation locks and concurrency tests.
- Monetary amounts are integer cents; currencies, partial payouts and multi-leg settlement are explicitly out of scope.
- The local outbox side effect is another database insert, so processing and acknowledgement can share one transaction. External delivery would need retries, deduplication, dead-letter handling and operational alerts.
- The frontend consumes masked destinations. It does not receive full account numbers in payout, version or ledger responses.
- Append-only triggers protect against ordinary application updates, not privileged database administrators. No claim of cryptographic immutability is made.
- The optional PGlite preview is for accessibility and low-cost demonstration only. Native PostgreSQL is the target deployment and concurrency-test environment.

## Threats and limitations

Demonstrated threats: stale approval reuse, last-minute instruction changes, self-review, cross-tenant reads/writes, duplicate release requests, expired evidence and holds bypassed through direct API calls.

Not solved by this prototype: colluding reviewers, stolen reviewer sessions, convincing but false callback evidence, compromised contact-registry administrators, malicious database administrators, actual banking reversals, real fraud classification, DDoS or identity-provider compromise.

Public or production operation would additionally require SSO/MFA, step-up confirmation, account lifecycle administration, least-privilege database accounts, reliable backups, monitoring, abuse protection, retention policy, legal/privacy review and independent security assessment.
