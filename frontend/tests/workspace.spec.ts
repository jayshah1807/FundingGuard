import { test, expect, Page } from "@playwright/test";

async function login(page: Page, role = "operations") {
  await page.goto("/");
  await page
    .getByLabel("Workspace identity")
    .selectOption(`${role}@demo.fundingguard.local`);
  await page
    .getByLabel("Demo password")
    .fill(process.env.DEMO_PASSWORD || "FundingGuard-Local-2026!");
  await page.getByRole("button", { name: "Sign in to workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Payout operations", exact: true }),
  ).toBeVisible();
}

async function switchRole(page: Page, role: string) {
  await page.getByRole("button", { name: "Account menu", exact: true }).click();
  const names: Record<string, string> = {
    operations: "Jay Shah",
    verifier: "Noah Singh",
    approver: "Maya Chen",
    security: "Elena Brooks",
  };

  await page
    .locator(".account-popover")
    .getByRole("button", { name: new RegExp(names[role]) })
    .click();
  await page
    .getByLabel("Demo password")
    .fill(process.env.DEMO_PASSWORD || "FundingGuard-Local-2026!");
  await page.getByRole("button", { name: "Sign in to workspace" }).click();
  await expect(page.locator(".decision-panel")).toBeVisible();
}

test("desktop workspace renders without browser errors", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await expect(page.locator("table tbody tr").first()).toBeVisible();
  await page.screenshot({
    path: "../test-results/desktop-overview.png",
    fullPage: true,
  });

  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  expect(errors).toEqual([]);
});

test("search filters and clears the payout queue", async ({ page }) => {
  await login(page);
  await page.getByRole("textbox", { name: "Search payouts" }).fill("Samira");
  await expect(page.locator("table tbody tr")).toHaveCount(1);
  await expect(page.locator("table")).toContainText("Samira Malik");
});

test("held payout has a disabled release action", async ({ page }) => {
  await login(page);
  await page
    .getByRole("row")
    .filter({ hasText: "FG-1042" })
    .getByRole("button", { name: "Samira Malik", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Release simulation", exact: true }),
  ).toBeDisabled();
  await expect(page.locator(".risk-banner")).toBeVisible();
  await page.screenshot({
    path: "../test-results/payout-detail.png",
    fullPage: true,
  });
});

test("all primary views load and audit exports", async ({ page }) => {
  await login(page);
  for (const [nav, heading] of [
    ["Investigations", "Investigations"],
    ["Release ledger", "Release ledger"],
    ["Audit trail", "Audit trail"],
    ["Trusted contacts", "Trusted contacts"],
  ]) {
    await page
      .getByRole("navigation")
      .getByRole("button", { name: nav })
      .click();
    await expect(
      page.getByRole("heading", { name: heading, exact: true }),
    ).toBeVisible();
  }
  await page
    .getByRole("button", { name: "Control center", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Control center", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Audit trail" })
    .click();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export CSV" }).click();
  expect((await download).suggestedFilename()).toBe("fundingguard-audit.csv");
});

test("mobile layout has no document overflow and navigation works", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "../test-results/mobile-overview.png",
    fullPage: true,
  });
  await page.getByTitle("Open navigation").click();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Investigations" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Investigations", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});

test("three independent identities can prepare verify approve and release", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "New payout", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Borrower name").fill("E2E Synthetic Borrower");
  await dialog.getByLabel("Existing loan reference").fill("E2E-" + Date.now());
  await dialog
    .getByLabel("Property address")
    .fill("100 Synthetic Avenue, Ottawa");
  await dialog.getByLabel("Amount · CAD").fill("450000.25");
  await dialog.getByLabel("Synthetic receiving account").fill("1234567890");
  await dialog.getByRole("button", { name: "Create payout" }).click();
  await expect(page.locator(".decision-panel")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Release simulation", exact: true }),
  ).toBeDisabled();
  await switchRole(page, "verifier");
  await page
    .getByRole("button", { name: "Record verification", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("textbox")
    .fill(
      "SIMULATED: established contact confirmed exact amount and destination.",
    );
  await page.getByRole("button", { name: "Save decision" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await switchRole(page, "approver");
  await page.getByRole("button", { name: "Approve instructions" }).click();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Save decision" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await switchRole(page, "operations");
  await expect(
    page.getByRole("button", { name: "Release simulation", exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: "Release simulation", exact: true })
    .click();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Confirm simulation" }).click();
  await expect(
    page.getByText("Simulation recorded", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../test-results/released-payout.png",
    fullPage: true,
  });
});
