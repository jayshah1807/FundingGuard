import { PGlite } from '@electric-sql/pglite';
import { PGLiteSocketServer } from '@electric-sql/pglite-socket';
import { readFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const test = process.argv.includes('--test');
const port=Number(process.env.PREVIEW_DB_PORT || (test?55439:55438));
await mkdir('.runtime', { recursive: true });
const db = await PGlite.create(test ? '.runtime/test-db' : `.runtime/preview-db-${port}`);
// Preview uses a single PostgreSQL WASM connection. Deployment uses Flyway and native PostgreSQL.
const schema = await readFile('backend/src/main/resources/db/migration/V1__core.sql', 'utf8');
const hash = createHash('sha256').update(schema).digest('hex');
await db.exec('CREATE TABLE IF NOT EXISTS preview_schema (hash text PRIMARY KEY)');
const previous = await db.query('SELECT hash FROM preview_schema');
if (previous.rows.length && previous.rows[0].hash !== hash) throw new Error('Preview schema changed. Back up the preview database and initialize a new preview directory.');
if (!previous.rows.length) {
  await db.transaction(async tx => {
    await tx.exec(schema);
    await tx.query('INSERT INTO preview_schema VALUES ($1)', [hash]);
  });
}
const server = new PGLiteSocketServer({ db, host: '127.0.0.1', port, maxConnections: 1 });
await server.start();
console.log(`Local ${test ? 'test' : 'preview'} database listening on ${port}`);
async function stop() { await server.stop(); await db.close(); process.exit(0); }
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
