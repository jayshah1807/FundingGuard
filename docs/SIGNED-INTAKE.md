# Signed simulator integration

Disabled by default. This producer sends synthetic events, not real identity-provider or bank telemetry. Do not send customer data.

## Configuration

Set `INGEST_KEYS_JSON` privately in the server environment:

```json
{"sim-current":{"secret":"REPLACE_WITH_A_RANDOM_SECRET_OF_AT_LEAST_32_BYTES","email":"security@demo.fundingguard.local"}}
```

Generate an unpredictable secret locally, for example with `openssl rand -hex 32`. Never use the placeholder or test-suite values. Never commit it or put it in screenshots. The mapped user must exist, be active and have the Security role. Its tenant is selected by the server, not the event payload. Prefer a dedicated simulator identity for a shared deployment.

Restart after environment changes. This route uses HMAC instead of sessions; browser routes retain session authentication and CSRF protection. A session cookie cannot authorize signed intake.

## Contract

`POST /api/integrations/simulator/events` requires `X-FG-Key-Id`, `X-FG-Timestamp` (Unix seconds), `X-FG-Nonce` (16-100 letters, digits, underscores or hyphens), and `X-FG-Signature` (hex HMAC-SHA256).

Sign this UTF-8 prefix followed by the exact body bytes. Each line ends with a newline, including NONCE:

```text
v1
POST
/api/integrations/simulator/events
KEY_ID
TIMESTAMP
NONCE
```

Maximum body: 8192 bytes. Signing timestamps: at most 300 seconds old or 30 seconds ahead. Event timestamps separately require past-24-hour values, not future values. Query parameters are disallowed. The producer requires HTTPS except on loopback.

An event file (replace payout/version with your synthetic workspace values):

```json
{"eventKey":"demo-event-0001","payoutId":"FG-1043","version":1,"kind":"NORMAL_LOGIN","detail":"Synthetic simulator login evidence"}
```

Set `FG_KEY_ID` and `FG_SIGNING_SECRET` privately in the producer environment, then:

```sh
node scripts/send-signed-event.mjs http://127.0.0.1:8095 event.json
```

The script refuses redirects and never prints the secret. For hosting, use your HTTPS app address. Avoid putting secrets in shell history.

## Retry and rotation

- Reusing a committed key-ID/nonce envelope returns 409; rejected authentication cannot create evidence.
- After a lost response, retry the identical payload/eventKey with a fresh nonce/time/signature. It returns the original event, not another playbook. Conflicting event-key reuse returns 409.
- Database failure rolls back receipt and response together. A timeout is not proof of rollback.
- Overlap old/new key IDs mapped to the same identity; switch the producer, remove the old key and restart. Business deduplication spans key rotation.
- Receipts older than a day are pruned on authenticated intake; their timestamps have already expired. Never reuse an old key ID for a different secret or identity.

The endpoint cannot emit application-owned account changes. Keep it disabled unless needed. Public rate limits, key custody, stronger log retention and service-identity lifecycle remain deployment responsibilities.
