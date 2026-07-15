import { test, expect } from '@playwright/test';

test.describe('passkey-first example shell', () => {
  test('navigates from the landing page to the accessible identity UI', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { level: 1, name: 'Aether passkey-first identity' })).toBeVisible();
    await expect(page.getByText('Passwords and bearer JWT fallback are intentionally unavailable.')).toBeVisible();
    await expect(page.getByRole('textbox')).toHaveCount(0);

    await page.getByRole('link', { name: 'Open the passkey identity UI' }).click();
    await expect(page).toHaveURL(/\/identity$/);
    await expect(page.getByRole('main', { name: 'Aether identity' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Identity and security' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Passkey name', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Create a passkey named/ })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Sign in with a discoverable passkey' })).toBeEnabled();
  });

  test('renders hydrated bootstrap and recovery entry forms without exposing their inputs', async ({ page }) => {
    await page.goto('/identity/bootstrap');
    await expect(page.getByRole('main', { name: 'Bootstrap the Aether identity example' })).toBeVisible();
    await expect(page.getByLabel('Bootstrap secret')).toHaveAttribute('type', 'password');
    await expect(page.getByRole('button', { name: /Create the first owner and continue/i })).toBeDisabled();

    await page.goto('/identity/recovery');
    await expect(page.getByRole('main', { name: 'Recover an Aether identity' })).toBeVisible();
    await expect(page.getByLabel('Recovery code')).toHaveAttribute('type', 'password');
    await expect(page.getByRole('button', { name: 'Recover account' })).toBeDisabled();
  });

  test('supports phone and desktop viewports without horizontal overflow', async ({ page }) => {
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 1280, height: 800 }
    ]) {
      await page.setViewportSize(viewport);
      await page.goto('/identity');
      await expect(page.getByRole('main', { name: 'Aether identity' })).toBeVisible();
      const overflows = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
      expect(overflows, `${viewport.width}px identity UI must not overflow horizontally`).toBeFalsy();
    }
  });

  test('keeps sign-in keyboard reachable while unnamed registration is disabled', async ({ page }) => {
    await page.goto('/identity');

    await page.getByRole('textbox', { name: 'Passkey name', exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'Sign in with a discoverable passkey' })).toBeFocused();
  });

  test('publishes only browser-safe route configuration', async ({ request }) => {
    const response = await request.get('/identity/v1/client-config');
    expect(response.ok()).toBeTruthy();
    expect(response.headers()['cache-control']).toBe('no-store');

    const body = await response.text();
    const config = JSON.parse(body);
    expect(config.registrationStart).toBe('/identity/v1/passkeys/registration/start');
    expect(config.authenticationFinish).toBe('/identity/v1/passkeys/authentication/finish');
    expect(config.stepUpStart).toBe('/identity/v1/passkeys/step-up/start');
    expect(config.stepUpFinish).toBe('/identity/v1/passkeys/step-up/finish');
    expect(config.bootstrap).toBe('/identity/v1/bootstrap');
    expect(config.recoveryCodeUse).toBe('/identity/v1/recovery/codes/use');
    expect(config.invitationEnrollment).toBe('/identity/v1/invitations/enroll');
    expect(config.deviceVerification).toBe('/identity/v1/device');
    expect(config.deviceApproval).toBe('/identity/v1/device/approve');
    expect(config.deviceDenial).toBe('/identity/v1/device/deny');
    expect(config.deviceAuthorizationEndpoint).toBe('/oauth/device_authorization');
    expect(body.toLowerCase()).not.toMatch(/password|jwt|secretreference|tokendigest/);
  });

  test('returns a generic not-found response for unknown identity resources', async ({ request }) => {
    const response = await request.get('/identity/v1/organizations/01900000-0000-7000-8000-000000000099');
    expect(response.status()).toBe(404);
    expect(response.headers()['content-type']).toContain('application/json');
    expect(await response.json()).toMatchObject({ error: { code: 'not_found', retryable: false } });
  });
});
