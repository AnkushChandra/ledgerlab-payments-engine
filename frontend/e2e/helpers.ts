import { expect, type Page } from '@playwright/test';
import { DEMO } from './demo-credentials';

export async function login(
  page: Page,
  email: string = DEMO.ops.email,
  password: string = DEMO.ops.password,
) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
}

export async function fillAmount(page: Page, label: string, amount: string) {
  await page.getByLabel(label).fill(amount);
}
