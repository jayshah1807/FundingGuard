# Hosting FundingGuard on Render and Neon

This is a private-access portfolio demo using synthetic records. The deployment-only Dockerfile builds both the Angular UI and Java backend; Render runs Docker for you. Nothing needs to be installed on your Mac.

## 1. Push the deployment files

From the repository root, review and commit the deployment changes, then push `main`. The required additions are `Dockerfile`, `.dockerignore`, `application-hosted.properties` and this guide; the notification worker and README are also updated.

## 2. Create a Neon Free project

1. Open https://console.neon.tech and select the Free plan.
2. Create a project called `fundingguard-demo`, using PostgreSQL 16 and a region close to your Render region where available.
3. Open Connect. Select the database and role; choose a **direct / unpooled** connection for this small demo and its Flyway migrations.
4. Keep the database name, host, username and password private. Use a dedicated database containing only synthetic demo records.
5. Keep scale-to-zero enabled and monitor the Free plan's current compute/storage allowances.

Convert the connection details into this JDBC shape, without embedding the password:

```text
jdbc:postgresql://YOUR_NEON_HOST:5432/YOUR_DATABASE?sslmode=require
```

Use the real host and database from Neon. Do not paste a `postgresql://user:password@...` URL into the JDBC setting. The password belongs in a separate secret.

## 3. Create the Render web service

1. Open https://dashboard.render.com and choose New > Web Service.
2. Connect GitHub and select `jayshah1807/FundingGuard`.
3. Branch: `main`. Leave Root Directory blank.
4. Language/runtime: **Docker**. Dockerfile path: `./Dockerfile`. Build context: repository root.
5. Choose the **Free** instance and a suitable region. Use the provided `onrender.com` hostname; no purchased domain is necessary.
6. Leave Docker command override blank; the Dockerfile starts Java.
7. Set the health-check path to `/`. This checks the application without issuing a database query.

Before deploying, confirm that the selected service is Free and the dashboard does not require purchasing a plan. Do not create a Render database: the app uses Neon.

## 4. Add environment variables

| Name | Value |
|---|---|
| `DATABASE_URL` | JDBC URL from step 2 |
| `DATABASE_USER` | Neon role name |
| `DATABASE_PASSWORD` | Neon role password, stored as a secret |
| `SEED_DEMO` | `true` |
| `DEMO_PASSWORD` | A new long, random password, stored as a secret |
| `PORT` | `10000` |
| `SPRING_PROFILES_ACTIVE` | `hosted` |

The image enables the hosted profile by default. It listens on all interfaces behind Render's HTTPS proxy, requires secure session cookies, limits the connection pool and closes idle connections. Flyway remains enabled to create the schema. Never disable CSRF or session authentication to fix a hosting error.

The initial seed creates the demo identities only on an empty database. Changing `DEMO_PASSWORD` later does not rotate existing account passwords. Keep access controlled and share credentials privately with trusted reviewers; do not publish them in the README or a LinkedIn post.

## 5. Deploy and verify

Select Deploy Web Service. Watch for successful frontend compilation, Java packaging, Flyway migration, demo seeding and application startup. Open the HTTPS service URL and test:

- Sign in with the selected Operations identity and your new demo password.
- Open the seeded held payout; verify release is blocked.
- Create a synthetic payout, switch to separate verification and approval identities, then release it as Operations.
- Confirm that release ledger and audit history appear.
- Revise another payout's instructions and verify earlier reviews become invalid.
- Sign out and verify protected data requires a login again.

Do not enter real financial, customer or identity data. Shared demo identities demonstrate controls, not production identity assurance. Restrict access while dependency remediation and a public-demo abuse review are outstanding.

## Free-tier behavior

- Render Free sleeps after inactivity; the next visitor may wait for a cold start. Sessions can be lost after restart.
- Data stays in Neon rather than Render's ephemeral filesystem. The local PGlite preview and `npm run preview` are not used in this deployment.
- Hosted notifications process every 10 minutes, beginning 10 minutes after startup. The underlying workflow, audit and release controls remain synchronous; the notification UI may lag. A sleeping service pauses the worker, and queued notifications remain in the database.
- These settings reduce idle database activity but do not guarantee zero compute usage. Visitor traffic, connection checks and scheduled batches consume database allowance. Avoid uptime pings intended to keep the service awake.
- Free limits and eligibility can change. Stay on Free plans, check usage, and do not enable paid upgrades without reviewing charges.

## Troubleshooting

| Symptom | Check |
|---|---|
| Service cannot find a listening port | Hosted profile enabled; port 10000; Dockerfile selected |
| Database connection fails | Direct Neon hostname, JDBC prefix, database name, role/password and TLS query |
| Login fails | Correct identity; seed password used when the database was first created |
| Secure session cookie is missing | Use HTTPS, not HTTP |
| Notification appears late | Hosted batches run every 10 minutes and pause while the service sleeps |
| Java exits due to memory | Inspect Render logs; do not automatically upgrade to a paid instance |

## References and verification

- https://render.com/docs/docker
- https://render.com/docs/free
- https://render.com/docs/configure-environment-variables
- https://neon.com/docs/connect/connect-from-any-app

The Docker image and cloud deployment require verification on Render. Successful local Java tests do not establish that the hosted deployment has passed. Development-tool dependency findings remain recorded in `VERIFICATION.md`.
