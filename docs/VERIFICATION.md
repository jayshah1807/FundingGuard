# Verification record

## Share-ready edition — October 4, 2026

- Fresh root and frontend dependency installation completed.
- Angular production build passed: 302 kB initial bundle before compression.
- Java 21 / Maven `verify` passed against the local PGlite test instance: 26 discovered, 25 passed, one native-PostgreSQL concurrency test skipped.
- The standalone test-database helper now creates its runtime directory on a fresh checkout; startup was checked after this fix.
- README Mermaid syntax, local documentation links and revised CI YAML were validated. The GitHub-hosted workflow has not been executed remotely.
- Frontend runtime dependency audit: zero reported vulnerabilities. Full development-tool audit: 11 affected dependency entries (nine high, two critical), including transitive build-tool findings. These require separate dependency remediation; do not interpret the runtime-only result as a clean full supply-chain audit.
- Source package excludes installed dependencies, runtime databases/logs, local environment files and generated build/test output.

## Earlier baseline

Verified locally on October 2, 2026. This records observed results, not projected capabilities.

## Automated results

- Angular production build: passed, approximately 302 kB initial JavaScript/CSS combined before compression.
- Java 21 / Spring Boot compilation and executable JAR packaging: passed.
- Integration suite: 26 tests discovered; **25 passed, 1 skipped**. Real workflow services, HTTP security filters and the PostgreSQL schema ran against the single-connection PGlite preview database.
- Skipped test: simultaneous releases using multiple native PostgreSQL connections. This edition uses PGlite in CI, so this test remains skipped there. Run it separately against native PostgreSQL. The revised workflow has not been run remotely here.
- Six Playwright CLI browser tests are included. Their CLI run was blocked by Chrome process startup restrictions in this environment, before test assertions executed. They are not claimed as passing.

## Browser verification

Using the available in-app browser and its Playwright interface:

- Signed in with Operations, Verification Officer and Payment Approver identities.
- Inspected desktop overview and held-payout detail screens.
- Searched for Samira and confirmed one matching row.
- Confirmed the held payout's release action is disabled.
- Created a new synthetic obligation, verified it as a separate person, approved it as another person, and released it as Operations.
- Observed the Released state, immutable ledger confirmation and separate receipt/discharge actions.
- Checked the mobile viewport and corrected an overflow caused by an absolutely positioned hidden table label.
- Final 390px mobile viewport: document width 390px, no horizontal page overflow. Mobile navigation, investigations, contacts, controls and audit views verified. Table content scrolls within its own container.
- Frontend runtime dependency audit: zero reported vulnerabilities at verification time; this is not a penetration test.
- No JavaScript console errors observed in the final browser pass.

## Important boundaries

The single-connection preview cannot establish native PostgreSQL concurrency correctness, high availability or throughput. No 1M-request/day, financial compliance, fraud accuracy or production-security claims are made. The 160 test scenarios in the original specification are not all implemented by this core demo.

Run the included CI and Playwright suites and a separate native PostgreSQL concurrency run before making broader verification claims. Use only synthetic information. Container packaging is not included in this edition.
