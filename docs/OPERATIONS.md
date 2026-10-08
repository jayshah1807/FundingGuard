# Demo operations and verification

## Build and local tests

Use Java 21, Maven, and a Node version supported by Angular 20 (local verification used Node 20.20). Run `npm ci` at the root and in `frontend`, then `npm run build` inside `frontend`. In separate terminals run `npm run db:test` and `mvn -f backend/pom.xml verify`. Tests create synthetic data and must use a disposable database. Local PGlite skips two native concurrency tests.

Package with `mvn -f backend/pom.xml package`. Run `APP_PORT=8095 PREVIEW_DB_PORT=55447 npm run preview`, choosing unused ports. The signed integration remains disabled unless configured as described in [SIGNED-INTAKE.md](SIGNED-INTAKE.md).

## CI evidence

Local verification on 2026-10-08: Maven verify passed 46 tests; two native-PostgreSQL concurrency tests skipped. Production frontend build and packaged app startup passed. Both npm audits reported zero findings. The standalone browser suite could not launch Chromium because macOS denied its bootstrap permission; one launch failure and ten unexecuted tests are not a browser-suite pass. A new preview runs independently of older previews.

The workflow uses a disposable PostgreSQL 16 service with Flyway, multi-connection concurrency tests, a production frontend build, and Chromium browser tests. It uploads test reports and evaluation results. Gitleaks scans repository history; npm audit gates high/critical findings; Dependabot checks Maven, npm and action updates. Hosted CI is configured, not certified: inspect an actual successful run after pushing. Maven dependencies have not received a completed vulnerability scan in this iteration.

Frontend audit on 2026-10-08 initially found 12 findings, including transitive build-tool issues in piscina, the MCP SDK and http-cache-semantics. Narrow overrides select patched versions; installation and production build succeeded and npm audit reported zero findings. This does not mean the whole application is vulnerability-free. Recheck overrides when upgrading Angular.

## Hosted deployment

Retain the existing Render/Neon configuration. Back up Neon before deployment; Flyway applies V2 (automation) and V3 (signed receipts) once. Never edit an applied migration or enable Flyway clean. Startup/migration failure means stop and investigate, not reset a live database.

Use private environment variables for database credentials, demo password and optional intake keys. Keep all stored records synthetic. Never put signing keys in the frontend or GitHub. Restrict event-simulator access, add edge abuse controls before broad sharing, and monitor replay storage growth. The demo's users/roles are not production identity management.

Render sleep means no always-on detection/notification promise. Store durable state in Neon, not the service filesystem. In-app notifications can lag; the hold commits independently of worker delivery. Free-plan limits may change; verify account allowances and spending controls before deployment. No automatic cloud deployment is performed by local edits.

## Recovery procedure (requires a rehearsal)

Before migration, take a PostgreSQL backup using `pg_dump --format=custom`, with connection credentials provided privately, not committed or printed. Store the archive outside the repository with restricted access. Restore using `pg_restore` into a NEW disposable database first, never over the live database. Point an isolated app at that restored database, verify migration history, user access, payout versions, holds, ledger counts and evidence. Review pending outbox work before resuming processing. This is a runbook, not proof a restore has been tested.

During an outage, fail closed: do not bypass database checks, clear holds manually in SQL, or assume a timed-out release failed. After recovery, read the payout/ledger and retry with the original business idempotency key. A notification outage must not remove a hold.

## Demonstration script

1. Show a synthetic payout and current instruction version.
2. Change its destination through the authorized operations workflow.
3. Send a signed synthetic login-risk event for that payout/version.
4. Show the case, captured evidence and automatic hold in Security automation.
5. Show that release controls block the payout.
6. Show a replay rejection and a fresh-envelope retry that creates no duplicate.
7. End with the labelled evaluation, including misses and the false positive.

A recording has not been produced in this iteration. No real funds or customer information should appear in any demonstration.
