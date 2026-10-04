import { readFile, unlink } from 'node:fs/promises';
import { resolve } from 'node:path';
process.chdir(resolve(import.meta.dirname,'..'));
for(const name of ['application','database']){
  try{
    const path=`.runtime/${name}-${process.env.APP_PORT||8088}.pid`;
    const pid=Number(await readFile(path,'utf8'));
    if(!Number.isInteger(pid)||pid<2)throw new Error('Invalid process ID');
    process.kill(pid,'SIGTERM');
    await unlink(path);
    console.log(`Stopped ${name} (${pid}).`);
    await new Promise(r=>setTimeout(r,1500));
  }catch(e){if(e.code!=='ENOENT'&&e.code!=='ESRCH')throw e;}
}
