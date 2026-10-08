import { test, expect, Page } from '@playwright/test';

async function login(page: Page, role = 'security') {
  await page.goto('/');
  await page.getByLabel('Workspace identity').selectOption(`${role}@demo.fundingguard.local`);
  await page.getByLabel('Demo password').fill(process.env.DEMO_PASSWORD || 'FundingGuard-Local-2026!');
  await page.getByRole('button', { name: 'Sign in to workspace' }).click();
  await expect(page.getByRole('heading', { name: 'Payout operations', exact: true })).toBeVisible();
  if (await page.getByTitle('Open navigation').isVisible()) await page.getByTitle('Open navigation').click();
  await page.getByRole('navigation').getByRole('button', { name: 'Security automation' }).click();
  await expect(page.getByRole('heading', { name: 'Security automation', exact: true })).toBeVisible();
}

test('replay risk, inspect evidence, and verify release is blocked', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await login(page);
  await page.getByRole('button', { name: 'Correlated risk', exact: true }).click();
  await expect(page.locator('.automation-table tbody tr').first()).toContainText('Passed');
  await expect(page.locator('.automation-table tbody tr').first()).toContainText('Held');
  await page.getByRole('tab', { name: /Playbook runs/ }).click();
  await page.getByRole('button', { name: 'Inspect run' }).first().click();
  await expect(page.locator('.run-inspector')).toContainText('Protective hold enforced');
  await expect(page.locator('.run-inspector')).toContainText('Account changed');
  await expect(page.locator('.run-inspector')).toContainText('Login anomaly');
  await page.screenshot({ path: '../test-results/automation-desktop.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.locator('.automation-table tbody tr').first().getByRole('button').first().click();
  await expect(page.locator('.risk-banner')).toContainText('Automatic COR-01 hold');
  expect(errors).toEqual([]);
});

test('benign and out-of-window replays do not hold', async ({ page }) => {
  await login(page);
  for (const name of ['Benign change', 'Outside window']) {
    await page.getByRole('button', { name, exact: true }).click();
    await expect(page.locator('.automation-table tbody tr').first()).toContainText('Passed');
    await expect(page.locator('.automation-table tbody tr').first()).toContainText('No hold');
    await expect(page.getByRole('button', { name, exact: true })).toBeEnabled();
  }
});

test('manual signal form ingests once and search filters evidence', async ({ page }) => {
  await login(page);
  await page.getByText('Submit simulated signal', { exact: true }).click();
  const form = page.locator('.signal-form');
  await form.getByRole('combobox', { name: 'Payout', exact: true }).selectOption({ index: 1 });
  await form.getByRole('combobox', { name: 'Signal', exact: true }).selectOption('NORMAL_LOGIN');
  const note = 'Synthetic browser evidence ' + Date.now();
  await form.getByRole('textbox', { name: 'Evidence note', exact: true }).fill(note);
  await form.getByRole('button', { name: 'Submit signal' }).click();
  await expect(page.getByRole('tab', { name: /Event stream/ })).toHaveAttribute('aria-selected','true');
  await page.getByLabel('Search automation records').fill(note);
  await expect(page.locator('.automation-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.automation-table')).toContainText(note);
});

test('auditor can inspect but cannot replay or ingest', async ({ page }) => {
  await login(page, 'auditor');
  await expect(page.getByRole('button', { name: 'Correlated risk', exact: true })).toHaveCount(0);
  await expect(page.locator('.signal-form')).toHaveCount(0);
});

test('mobile automation remains usable without page overflow', async ({ page }) => {
  await page.setViewportSize({width:390,height:844});
  await login(page);
  await page.getByRole('button', { name: 'Correlated risk', exact: true }).click();
  await expect(page.locator('.automation-table tbody tr').first()).toContainText('Passed');
  await page.getByText('Submit simulated signal', { exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({path:'../test-results/automation-mobile.png',fullPage:true});
});
