CREATE TABLE fg_ingest_receipts (
 key_id text NOT NULL,
 nonce text NOT NULL,
 received_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (key_id, nonce)
);
CREATE INDEX ingest_receipts_time ON fg_ingest_receipts(received_at);
