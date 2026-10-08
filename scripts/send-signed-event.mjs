import { createHmac, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

// Synthetic producer only. Secrets come from the environment and are never printed.
const [base, file] = process.argv.slice(2);
if (!base || !file) throw new Error('Usage: node scripts/send-signed-event.mjs BASE_URL event.json');
const url = new URL('/api/integrations/simulator/events', base);
if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['127.0.0.1','localhost','[::1]'].includes(url.hostname)))
  throw new Error('HTTPS is required except on loopback.');
if (url.username || url.password) throw new Error('Do not place credentials in the URL.');
const key = process.env.FG_KEY_ID, secret = process.env.FG_SIGNING_SECRET;
if (!key || !secret || Buffer.byteLength(secret) < 32) throw new Error('Set FG_KEY_ID and FG_SIGNING_SECRET (at least 32 bytes).');
const body = await readFile(file);
if (body.length > 8192) throw new Error('Event exceeds 8192 bytes.');
JSON.parse(body.toString());
const timestamp = String(Math.floor(Date.now()/1000)), nonce = randomUUID();
const signature = createHmac('sha256',secret).update(`v1\nPOST\n${url.pathname}\n${key}\n${timestamp}\n${nonce}\n`).update(body).digest('hex');
const response = await fetch(url, { method:'POST', redirect:'error', signal:AbortSignal.timeout(120000),
  headers:{'Content-Type':'application/json','X-FG-Key-Id':key,'X-FG-Timestamp':timestamp,'X-FG-Nonce':nonce,'X-FG-Signature':signature},body });
console.log(`HTTP ${response.status}: ${await response.text()}`);
if (!response.ok) process.exitCode=1;
