## Working flow

1. A payout revision emits an application event with the instruction version. Account and amount changes are distinguished; account values are never included in the event.
2. Security can submit synthetic login anomalies, normal logins, or reports of contact changes through the workspace or authenticated API. These reports do not change trusted contacts or represent telemetry from a real identity provider.
3. COR-01 v1 looks for an account-change event for the current version plus a login anomaly or reported contact change for the same tenant and payout. Both must have occurred within the last 15 minutes, using the server's clock. Signals can arrive before or after the revision. Events older than 24 hours or in the future are rejected on ingestion.
4. A match captures evidence references and opens a case. On an active payout it enforces a protective hold, invalidates pending clearance and approval, records an audit event and queues a notification. An existing manual hold and its reason are preserved. Terminal payouts get an observation-only case, never a retroactive hold or changed ledger.
5. A deterministic summary describes the evidence and outcome. It does not call a model. The inspector exposes the source records and each playbook step.
6. Independent security/approver hold clearance and fresh review remain necessary. No playbook can approve or release funds.


## Replay

Sign in as Security, open **Security automation**, and select:

| Scenario | Expected result |
|---|---|
| Benign change | Account change and normal login; no automatic hold |
| Correlated risk | Account change and login anomaly; case and automatic hold |
| Outside window | Anomaly 16 minutes old; no automatic hold |

Each replay creates a separate synthetic payout marked `scenario_run=true`. It cannot modify an existing payout. Replay rows show expected vs observed hold results and server processing duration. These are test-fixture checks, not real-world detection accuracy, false-positive statistics, or a latency benchmark. Records persist, so repeated replays consume database storage.

## API

All routes use the existing authenticated session and tenant context; POST requests require CSRF. Only Security can submit events or run scenarios. Other authenticated roles can inspect their tenant's records.

- `GET /api/automation`: latest 100 events/runs and 50 scenario results, tenant scoped.
- `GET /api/automation/runs/{id}`: captured evidence, case reference and ordered actions.
- `POST /api/automation/events`: `{ "eventKey": "unique-client-key", "payoutId": "existing-id", "version": 2, "kind": "LOGIN_ANOMALY", "detail": "Synthetic evidence note" }`. Optional `occurredAt` is an ISO instant; omission uses server time. Supported external kinds: `LOGIN_ANOMALY`, `CONTACT_CHANGE_REPORTED`, `NORMAL_LOGIN`.
- `POST /api/automation/replay`: `{ "scenario": "CORRELATED" }` (also `BENIGN`, `OUTSIDE_WINDOW`).

Retries with the same event key and identical payload/actor return the existing event. Conflicting key reuse returns 409. Instruction-change events are application-owned and cannot be submitted through the ingestion API. Validation rejects unsupported types, stale instruction versions, excessive text, future events and cross-tenant payout access.

## Deliberate limits

- One playbook run per payout/version/rule. Duplicate or subsequent signals do not repeatedly place the same hold after authorized clearance. A later instruction version can trigger a new run; new evidence on an already processed version stays visible in the event stream but is not added to its immutable evidence snapshot.
- Correlation is bounded to the last 15 minutes at processing time, not historical replay over arbitrary late-arriving telemetry.
- No real telemetry connectors, AI summaries, configurable playbook editor, background rule scheduler, real bank calls or automated disbursement. Optional authenticated synthetic intake is described in [SIGNED-INTAKE.md](SIGNED-INTAKE.md).
- Synthetic signals are trusted operator reports, not verified facts. Submitting one is an authorized action that can block a payout. Use only demo data.
- One tenant-wide lock favors consistent controls over high-volume ingestion. Public deployment still needs abuse protection, retention, identity hardening and dependency remediation.

## Deployment and migration

Flyway applies `V2__security_automation.sql` after V1 on hosted PostgreSQL. The local PGlite helper retains its V1 checksum and applies later numbered migrations once, without resetting existing records. Back up a hosted database before deploying; do not edit an applied migration.

The earlier architecture images describe the baseline application. This document describes the automation extension; the diagrams have not yet been regenerated.

## Verification (2026-10-05)

Historical snapshot below. The October 8 hardening work adds signed intake and tests, remediates frontend dependency findings, and configures native-PostgreSQL/browser CI. See [current evaluation](EVALUATION.md) and [operations notes](OPERATIONS.md); hosted CI still requires a real run.

- Backend: 38 tests passed and one skipped across WorkflowTest and AutomationTest (13 automation tests).
- Frontend production build and backend package completed successfully.
- In-app browser: correlated replay created an investigation and protective hold; benign and outside-window replays passed without a hold. Manual normal-event submission and evidence search were verified. The run inspector displayed the captured events and ordered playbook actions.
- Layout checked at 1440px desktop and 390px mobile widths, without page-level horizontal overflow; no browser console errors observed during these checks.
- Standalone Playwright tests were added, but could not execute because macOS denied Chromium's launch. These tests are not reported as passing.
- Dependency installation reported 11 audit vulnerabilities (9 high, 2 critical). Dependency remediation remains separate work before treating this demo as production-ready.

These checks used synthetic local records. No live banking integration or hosted deployment was tested.
