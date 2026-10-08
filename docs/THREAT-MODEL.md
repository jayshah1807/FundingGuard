# FundingGuard threat model

Scope: a synthetic mortgage-payment integrity prototype. No bank accounts, actual disbursements, live identity telemetry or claims of regulatory compliance.

## Assets and trust boundaries

Protect payment instructions, current-version verification/approval, hold state, tenant isolation, and investigation evidence. Browser input is untrusted. The application authenticates people through sessions and checks roles and tenant ownership in the backend. The signed simulator is a separate machine trust boundary: possession of a configured HMAC secret permits a narrow set of reported events, not instruction changes or payment release. PostgreSQL and the deployment operator are trusted; a compromised database owner can defeat application controls.

| Threat | Implemented control | Automated evidence | Residual risk |
| --- | --- | --- | --- |
| Compromised account changes destination | Version-bound approvals; COR-01 protective hold when related risk exists | Stale approval tests; signalBeforeRevisionCorrelatesAndCapturesEvidence | A change without reported risk is not detected by COR-01 |
| Insider submits fraudulent instructions | Separate maker, verifier and approver; independent hold clearance | WorkflowTest role and clearance tests | Collusion, dishonest verification or multiple stolen identities |
| Forged event freezes payout | HMAC-SHA256, server-configured tenant identity, short signing window, bounded body | forgedExpiredFutureAndUnknownKeyRequestsAreRejected | Stolen signing key can send false reports; authenticity is not truth |
| Captured request replay | Transactional nonce receipt; business-event idempotency | signedHttpIngestRejectsReplayAndDeduplicatesFreshEnvelope | Malicious authenticated sender can submit fresh event keys |
| Approval reused after change | Revision invalidates previous reviews; release checks current version | WorkflowTest stale-version tests | Database-owner bypass |
| Cross-tenant access | Tenant predicates; server chooses signing identity | WorkflowTest tenant tests; signedRequestsCannotForgeTenantOrInternalEvents | Incorrect key-to-user configuration |
| Release races with hold | Both serialize on tenant row lock | releaseAndAutomaticHoldSerialize (native PostgreSQL only) | Release committed first cannot be recalled; subsequent evidence is observation-only |
| Failure during response | Evidence, case, hold, receipt and outbox share a transaction | failedTransactionRollsBackReceiptEvidenceCaseAndHold | Client timeout creates an ambiguous outcome; retry the same business event |
| Notification worker fails | Transactional outbox; unique notification event ID | notificationFailureRollsBackAndCanBeRetried | In-app notification only; sleeping host delays delivery |

## Explicit limitations

- COR-01 uses a 15-minute processing-time window. It misses delayed evidence and deliberate waiting.
- A legitimate unusual login can cause an unnecessary hold. Evaluation includes this false positive.
- One run per instruction version prevents repeated effects. New risk after authorized clearance on the same version does not cause another automatic hold.
- HMAC verifies sender and bytes, not factual truth. Rotation overlaps key IDs; removal and restart revoke the old key.
- Independent authorization remains necessary for clearance and release. No LLM participates in payment controls.
- Fixed rejection codes are logged without payloads or credentials. These are diagnostic logs, not a durable external security archive.
- Public abuse protection, SSO/MFA, dedicated service identities, external telemetry, disaster recovery and real banking integration are not demonstrated. Passing tests is not proof of these properties.
