import { PGlite } from "@electric-sql/pglite";
import { PGLiteSocketServer } from "@electric-sql/pglite-socket";
import { readFile, mkdir, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";

const test = process.argv.includes("--test");
const port = Number(process.env.PREVIEW_DB_PORT || (test ? 55439 : 55438));
await mkdir(".runtime", { recursive: true });
const db = await PGlite.create(
  test ? ".runtime/test-db" : `.runtime/preview-db-${port}`,
);
// Preview uses a single PostgreSQL WASM connection. Deployment uses Flyway and native PostgreSQL.
const schema = await readFile(
  "backend/src/main/resources/db/migration/V1__core.sql",
  "utf8",
);
const hash = createHash("sha256").update(schema).digest("hex");
await db.exec(
  "CREATE TABLE IF NOT EXISTS preview_schema (hash text PRIMARY KEY)",
);
const previous = await db.query("SELECT hash FROM preview_schema");
if (previous.rows.length && previous.rows[0].hash !== hash)
  throw new Error(
    "Preview schema changed. Back up the preview database and initialize a new preview directory.",
  );
if (!previous.rows.length) {
  await db.transaction(async (tx) => {
    await tx.exec(schema);
    await tx.query("INSERT INTO preview_schema VALUES ($1)", [hash]);
  });
}
// Keep existing V1 previews intact and apply later migrations exactly once.
await db.exec("CREATE TABLE IF NOT EXISTS preview_migrations (name text PRIMARY KEY, hash text NOT NULL)");
const migrationDir = "backend/src/main/resources/db/migration";
const migrations = (await readdir(migrationDir)).filter(n => /^V\d+__.*\.sql$/.test(n) && !n.startsWith("V1__"))
  .sort((a,b) => Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]));
for (const name of migrations) {
  const sql = await readFile(`${migrationDir}/${name}`, "utf8");
  const checksum = createHash("sha256").update(sql).digest("hex");
  const applied = await db.query("SELECT hash FROM preview_migrations WHERE name=$1", [name]);
  if (applied.rows.length && applied.rows[0].hash !== checksum) throw new Error(`Applied migration changed: ${name}`);
  if (!applied.rows.length) await db.transaction(async tx => {
    await tx.exec(sql);
    await tx.query("INSERT INTO preview_migrations VALUES ($1,$2)", [name,checksum]);
  });
}
const server = new PGLiteSocketServer({
  db,
  host: "127.0.0.1",
  port,
  maxConnections: 1,
});
await server.start();
console.log(`Local ${test ? "test" : "preview"} database listening on ${port}`);
async function stop() {
  await server.stop();
  await db.close();
  process.exit(0);
}
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
