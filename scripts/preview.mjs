import { spawn } from 'node:child_process';
import { mkdir, open, writeFile, copyFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createConnection } from 'node:net';

const root = resolve(import.meta.dirname, '..');
const port=Number(process.env.APP_PORT || 8088), dbPort=Number(process.env.PREVIEW_DB_PORT || 55438);
process.chdir(root);
await mkdir('.runtime', { recursive: true });
function connect(port) { return new Promise(r => { const socket=createConnection({host:'127.0.0.1',port});socket.on('connect',()=>{socket.end();r(true);});socket.on('error',()=>r(false)); }); }
if (await connect(port)) throw new Error(`Port ${port} is occupied. Stop the existing preview or choose another APP_PORT.`);
if (await connect(dbPort)) throw new Error(`Port ${dbPort} is occupied. Stop the existing preview database or choose another PREVIEW_DB_PORT.`);
const dbLog=await open('.runtime/database.log','a');
const db=spawn(process.execPath,['scripts/preview-db.mjs'],{cwd:root,detached:true,stdio:['ignore',dbLog.fd,dbLog.fd]});db.unref();
await writeFile(`.runtime/database-${port}.pid`,String(db.pid));
for(let i=0;i<60;i++){if(await connect(dbPort))break;await new Promise(r=>setTimeout(r,500));if(i===59)throw new Error('Database startup failed. See .runtime/database.log.');}
const appLog=await open('.runtime/application.log','a');
const java=process.env.JAVA_HOME?resolve(process.env.JAVA_HOME,'bin/java'):'java';
const runtimeJar=`.runtime/fundingguard-${port}.jar`;
await copyFile('backend/target/fundingguard-1.0.0.jar',runtimeJar);
const app=spawn(java,['-jar',runtimeJar],{cwd:root,detached:true,stdio:['ignore',appLog.fd,appLog.fd],env:{...process.env,
  DATABASE_URL:`jdbc:postgresql://127.0.0.1:${dbPort}/postgres?sslmode=disable&preferQueryMode=simple`,DATABASE_USER:'postgres',DATABASE_PASSWORD:'',
  SPRING_FLYWAY_ENABLED:'false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:'1',SEED_DEMO:'true',
  DEMO_PASSWORD:process.env.DEMO_PASSWORD||'FundingGuard-Local-2026!',BIND_ADDRESS:'127.0.0.1',PORT:String(port),COOKIE_SECURE:'false'}});app.unref();
await writeFile(`.runtime/application-${port}.pid`,String(app.pid));
for(let i=0;i<60;i++){try{const r=await fetch(`http://127.0.0.1:${port}/api/csrf`);if(r.ok){console.log(`FundingGuard: http://127.0.0.1:${port}`);console.log('Use the selected demo identity and your DEMO_PASSWORD (local default: FundingGuard-Local-2026!).');process.exit(0);}}catch{}await new Promise(r=>setTimeout(r,1000));}
throw new Error('Application startup failed. See .runtime/application.log.');
