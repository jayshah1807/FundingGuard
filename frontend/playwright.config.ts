import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests', workers: 1, timeout: 45000,
  use: { baseURL: process.env.BASE_URL || 'http://127.0.0.1:8088', viewport:{width:1440,height:1000},
    launchOptions: process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {}, screenshot:'only-on-failure', trace:'retain-on-failure' },
  reporter: [['list'],['html',{open:'never'}]]
});
