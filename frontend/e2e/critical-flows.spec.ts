import { expect, test } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DEMO, SEEDED } from './demo-credentials';
import { fillAmount, login } from './helpers';

const samples = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../samples');

test.describe.configure({ mode: 'serial' });

test('login shows dashboard balances and ledger integrity', async ({ page }) => {
  await login(page);
  await expect(page.getByText('Customer available')).toBeVisible();
  await expect(page.getByText('All invariants hold')).toBeVisible();
  await expect(page.getByTestId('role-badge')).toHaveText('OPERATIONS');
});

test('authorize, capture and refund a payment', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Payments' }).first().click();
  await page.getByRole('button', { name: 'New payment' }).click();
  await page.getByLabel('Customer').selectOption({ label: SEEDED.ada });
  await page.getByLabel('Merchant').selectOption({ label: SEEDED.blueBottle });
  await fillAmount(page, 'Amount', '12.50');
  await page.getByLabel('Reference').fill('E2E-CAPTURE');
  await page.getByRole('button', { name: 'Authorize' }).click();

  await expect(page.getByRole('heading', { name: 'E2E-CAPTURE' })).toBeVisible();
  await expect(page.getByText('Authorized', { exact: true }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Capture' }).click();
  await fillAmount(page, 'Amount', '12.50');
  await page.getByRole('dialog').getByRole('button', { name: 'Capture' }).click();
  await expect(page.getByText('Captured', { exact: true }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Refund' }).click();
  await fillAmount(page, 'Amount', '2.50');
  await page.getByLabel('Reason').fill('e2e partial refund');
  await page.getByRole('dialog').getByRole('button', { name: 'Refund' }).click();
  await expect(page.getByText('Partially refunded').first()).toBeVisible();
  await expect(page.getByText('e2e partial refund').first()).toBeVisible();
});

test('open and resolve a dispute', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Payments' }).first().click();
  await page.getByRole('button', { name: 'New payment' }).click();
  await page.getByLabel('Customer').selectOption({ label: SEEDED.ada });
  await page.getByLabel('Merchant').selectOption({ label: SEEDED.blueBottle });
  await fillAmount(page, 'Amount', '8.00');
  await page.getByLabel('Reference').fill('E2E-DISPUTE');
  await page.getByRole('button', { name: 'Authorize' }).click();
  await page.getByRole('button', { name: 'Capture' }).click();
  await fillAmount(page, 'Amount', '8.00');
  await page.getByRole('dialog').getByRole('button', { name: 'Capture' }).click();

  await page.getByRole('button', { name: 'Open dispute' }).click();
  await page.getByLabel('Reason').fill('e2e goods not received');
  await page.getByRole('dialog').getByRole('button', { name: 'Open dispute' }).click();
  await expect(page.getByText('Disputed').first()).toBeVisible();

  await page.getByRole('link', { name: 'e2e goods not received' }).click();
  await page.getByRole('button', { name: 'Resolve' }).click();
  await page.getByLabel('Outcome').selectOption('LOST');
  await page.getByLabel('Note').fill('e2e chargeback');
  await page.getByRole('dialog').getByRole('button', { name: 'Resolve' }).click();
  await expect(page.getByText('Lost').first()).toBeVisible();
});

test('upload a mismatched settlement file and inspect exceptions', async ({ page }) => {
  await login(page);
  await page.getByRole('link', { name: 'Reconciliation' }).first().click();
  await page.getByRole('button', { name: 'Upload settlement file' }).click();
  await page.getByLabel('CSV file').setInputFiles(path.join(samples, 'settlement-mismatches.csv'));
  await page.getByLabel('Period start').fill('2026-09-01');
  await page.getByLabel('Period end').fill('2026-09-03');
  await page.getByRole('button', { name: 'Reconcile' }).click();

  await expect(page.getByRole('link', { name: 'settlement-mismatches.csv' })).toBeVisible();
  await page.getByRole('link', { name: 'settlement-mismatches.csv' }).click();
  await expect(page.getByText('Amount mismatch').first()).toBeVisible();
  await expect(page.getByText('Status mismatch').first()).toBeVisible();
  await expect(page.getByText('Missing internal').first()).toBeVisible();
  await expect(page.getByText('Duplicate external').first()).toBeVisible();
  await expect(page.getByText('Missing external').first()).toBeVisible();
});

test('viewer cannot execute restricted actions', async ({ page }) => {
  await login(page, DEMO.viewer.email, DEMO.viewer.password);
  await expect(page.getByTestId('role-badge')).toHaveText('VIEWER');
  await expect(page.getByRole('link', { name: 'Audit trail' })).toHaveCount(0);

  await page.getByRole('link', { name: 'Payments' }).first().click();
  await expect(page.getByRole('button', { name: 'New payment' })).toHaveCount(0);

  await page.getByRole('link', { name: SEEDED.order1003 }).click();
  await expect(page.getByRole('button', { name: 'Capture' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Void' })).toHaveCount(0);

  await page.getByRole('link', { name: 'Reconciliation' }).first().click();
  await expect(page.getByRole('button', { name: 'Upload settlement file' })).toHaveCount(0);

  await page.goto('/audit');
  await expect(page).toHaveURL('/');
});
