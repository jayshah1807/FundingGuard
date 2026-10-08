# Detection evaluation

The dataset in `backend/src/test/resources/detection-scenarios.json` contains ten explicitly labelled synthetic scenarios. Labels are scenario-author assumptions, not observed fraud. `expectedHold` tests the implemented policy separately from the `malicious` label, so known policy gaps do not get hidden by changing labels.

Run `mvn -f backend/pom.xml test` against the disposable test database. `AutomationTest.evaluateLabelledSyntheticDataset` writes `backend/target/detection-evaluation.json`. CI uploads that report alongside tests. Do not execute the test suite against a shared or production database.

## Observed local result, 2026-10-08

| Outcome | Count |
| --- | ---: |
| Threat scenarios held (true positives) | 2 |
| Threat scenarios missed (false negatives) | 3 |
| Legitimate scenarios held (false positives) | 1 |
| Legitimate scenarios not held (true negatives) | 4 |

This deliberately challenging set produces 40% recall (2/5), 67% precision (2/3), and a 20% false-positive rate (1/5). These small, hand-selected numbers are NOT estimates of real-world effectiveness. They explain exactly where a narrow rule succeeds and fails.

Misses: waiting outside the window; insider activity without reported login risk; compromise without a destination change. False positive: a legitimate destination update alongside an unusual but authorized login. Independent payment verification remains necessary even when the detector is silent.

Workload: ten sequential service calls on local PGlite, no parallel clients, no network round trip. Observed processing was approximately 3.3-9.1 ms in the initial run; these are development-machine measurements, not throughput or hosted latency claims. Every rerun records fresh timings.

## Failure evidence

- Forced pre-commit exception rolls back signed receipt, security evidence, case and protective hold. Retrying succeeds once.
- Forced notification-worker pre-commit exception rolls back notification work; replay drains queued work without duplicate notifications, while the existing hold remains enforced.
- Two concurrent ordering tests are reserved for native PostgreSQL. PGlite is single-connection and cannot establish production concurrency behavior. CI is configured to run these, but is not verified until an actual hosted run passes.
- A full database outage and restored backup have not been exercised. Do not equate transaction rollback tests with disaster recovery.
