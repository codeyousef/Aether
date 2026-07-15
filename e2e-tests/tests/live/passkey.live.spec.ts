import { test, expect } from '../support/virtual-authenticator.js';
import type { APIRequestContext, APIResponse, Locator, Page } from '@playwright/test';
import {
  assertLiveAuthorityReady,
  identityRoutes,
  waitForIdentityHydration,
  waitForSuccessfulResponse
} from '../support/live-authority.js';

const liveEnabled = process.env.AETHER_E2E_LIVE_IDENTITY === '1';
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

function assertSensitive(condition: unknown, redactedMessage: string): asserts condition {
  if (!condition) {
    throw new Error(redactedMessage);
  }
}

async function fillSensitive(locator: Locator, value: string, redactedMessage: string): Promise<void> {
  try {
    await locator.fill(value);
  } catch {
    throw new Error(redactedMessage);
  }
}

async function waitForSensitiveHydration(page: Page, redactedMessage: string): Promise<void> {
  try {
    await waitForIdentityHydration(page);
  } catch {
    throw new Error(redactedMessage);
  }
}

async function postSensitiveForm(
  request: APIRequestContext,
  path: string,
  form: Record<string, string>,
  redactedMessage: string
): Promise<APIResponse> {
  try {
    return await request.post(path, { form });
  } catch {
    throw new Error(redactedMessage);
  }
}

async function getWithBearer(
  request: APIRequestContext,
  path: string,
  token: string,
  redactedMessage: string
): Promise<APIResponse> {
  try {
    return await request.get(path, { headers: { Authorization: `Bearer ${token}` } });
  } catch {
    throw new Error(redactedMessage);
  }
}

test.describe('live passkey, step-up, and recovery identity authority', () => {
  test.skip(!liveEnabled, 'Set AETHER_E2E_LIVE_IDENTITY=1 and use npm run test:live to opt in.');
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ request }) => {
    await assertLiveAuthorityReady(request);
  });

  test('bootstraps, authenticates, steps up, and completes restricted recovery enrollment', async ({
    context,
    page,
    request,
    virtualAuthenticator
  }) => {
    const bootstrapSecret = process.env.AETHER_IDENTITY_BOOTSTRAP_SECRET!;
    await page.goto('/identity/bootstrap');
    await waitForIdentityHydration(page);
    await fillSensitive(
      page.getByLabel('Bootstrap secret', { exact: true }),
      bootstrapSecret,
      'Could not enter the redacted bootstrap secret.'
    );
    await page.getByLabel('Owner display name').fill('Playwright Owner');
    await page.getByLabel('Owner email').fill('owner@example.test');
    await page.getByLabel('Organization name').fill('Playwright Organization');
    await page.getByLabel('Organization slug').fill('playwright-org');

    const bootstrap = waitForSuccessfulResponse(page, identityRoutes.bootstrap);
    await page.getByRole('button', { name: /Create the first owner and continue/i }).click();
    const bootstrapPayload = await (await bootstrap).json();
    await expect(page).toHaveURL(/\/identity$/);
    await waitForIdentityHydration(page);
    assertSensitive(
      await page.evaluate(() => sessionStorage.getItem('aether.identity.csrf.v1')) ===
        bootstrapPayload.csrfToken,
      'The session-bound CSRF value did not match after bootstrap.'
    );

    await page.getByRole('textbox', { name: 'Passkey name', exact: true })
      .fill('Playwright virtual passkey');

    const registrationStart = waitForSuccessfulResponse(page, identityRoutes.registrationStart);
    const registration = waitForSuccessfulResponse(page, identityRoutes.registrationFinish);
    const initialAuthenticationStart = waitForSuccessfulResponse(page, identityRoutes.authenticationStart);
    const initialAuthentication = waitForSuccessfulResponse(page, identityRoutes.authenticationFinish);
    await page.getByRole('button', { name: 'Create a passkey named Playwright virtual passkey' }).click();
    const registrationStartResponse = await registrationStart;
    const registrationOptions = await registrationStartResponse.json();
    expect(registrationOptions.publicKey.pubKeyCredParams).toEqual([{ type: 'public-key', alg: -7 }]);
    expect(registrationOptions.publicKey.authenticatorSelection).toEqual({
      residentKey: 'required',
      requireResidentKey: true,
      userVerification: 'required'
    });
    expect(registrationOptions.publicKey.attestation).toBe('none');
    assertSensitive(
      registrationStartResponse.request().headers()['x-csrf-token'] === bootstrapPayload.csrfToken,
      'The passkey start request did not carry the session-bound CSRF value.'
    );
    const registrationResponse = await registration;
    expect(registrationResponse.request().postDataJSON().credentialName)
      .toBe('Playwright virtual passkey');
    await initialAuthenticationStart;
    await initialAuthentication;
    await expect(page.getByRole('status')).toContainText('Recovery codes replaced. Save these ten codes now.');
    expect(await page.evaluate(() => sessionStorage.getItem('aether.identity.csrf.v1'))).toBeTruthy();

    const initialCodeRegion = page.getByRole('region', { name: 'New recovery codes' });
    await expect.poll(
      async () => initialCodeRegion.evaluate((element) => element === document.activeElement),
      { message: 'The one-time recovery-code region must receive focus.' }
    ).toBe(true);
    const recoveryCodes = await initialCodeRegion.locator('code').allTextContents();
    assertSensitive(recoveryCodes.length === 10, 'Exactly ten redacted recovery codes were expected.');
    assertSensitive(new Set(recoveryCodes).size === 10, 'Recovery codes must be unique.');
    await page.getByRole('button', { name: 'Confirm that the recovery codes are saved' }).click();
    await expect.poll(() => initialCodeRegion.isHidden(), {
      message: 'The one-time recovery-code region must be hidden after confirmation.'
    }).toBe(true);

    const credentials = await virtualAuthenticator.credentials();
    assertSensitive(credentials.length === 1, 'Exactly one redacted virtual credential was expected.');
    expect(credentials[0].isResidentCredential).toBeTruthy();

    await context.clearCookies();
    await page.goto('/identity');
    await waitForIdentityHydration(page);
    const authenticationStart = waitForSuccessfulResponse(page, identityRoutes.authenticationStart);
    const authentication = waitForSuccessfulResponse(page, identityRoutes.authenticationFinish);
    await page.getByRole('button', { name: 'Sign in with a discoverable passkey' }).click();
    const authenticationOptions = await (await authenticationStart).json();
    expect(authenticationOptions.publicKey.allowCredentials).toEqual([]);
    expect(authenticationOptions.publicKey.userVerification).toBe('required');
    await authentication;
    await expect(page.getByRole('status')).toContainText('Identity operation completed.');

    const deviceStart = await request.post(identityRoutes.deviceAuthorization, {
      form: {
        client_id: 'aether-playwright-cli',
        client_name: 'Playwright CLI',
        scope: 'identity.profile identity.organizations organization.read'
      }
    });
    expect(deviceStart.ok()).toBeTruthy();
    const deviceGrant = await deviceStart.json();
    expect(deviceGrant.expires_in).toBe(600);
    expect(deviceGrant.interval).toBe(5);
    expect(deviceGrant.verification_uri)
      .toBe(`${new URL(process.env.AETHER_E2E_BASE_URL!).origin}/identity`);
    expect('verification_uri_complete' in deviceGrant).toBe(false);
    assertSensitive(
      /^[A-Za-z0-9_-]{43}$/.test(deviceGrant.device_code),
      'The redacted device code had an invalid wire format.'
    );
    assertSensitive(
      /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$/.test(
        deviceGrant.user_code
      ),
      'The redacted user code had an invalid wire format.'
    );

    await page.goto(deviceGrant.verification_uri);
    await waitForSensitiveHydration(
      page,
      'The device verification page did not hydrate.'
    );
    expect(new URL(page.url()).search).toBe('');
    await fillSensitive(
      page.getByLabel('Device authorization code', { exact: true }),
      deviceGrant.user_code,
      'Could not enter the redacted device user code.'
    );
    const inspection = waitForSuccessfulResponse(page, identityRoutes.deviceVerification);
    await page.getByRole('button', { name: 'Continue with this device authorization code' }).click();
    const inspectionRequest = (await inspection).request().postDataJSON();
    assertSensitive(
      inspectionRequest.userCode === deviceGrant.user_code,
      'The device inspection request did not contain the expected redacted user code.'
    );
    const devicePanel = page.getByRole('region', { name: 'Device authorization approval' });
    await expect.poll(() => devicePanel.isVisible(), {
      message: 'The device authorization approval panel must be visible.'
    }).toBe(true);
    await expect.poll(async () => (await devicePanel.textContent())?.includes('Playwright CLI') ?? false)
      .toBe(true);
    await expect.poll(
      async () => (await devicePanel.textContent())?.includes(deviceGrant.user_code) ?? false,
      { message: 'The approval panel did not show the redacted device user code.' }
    ).toBe(true);
    const deviceOrganization = devicePanel.locator('[data-identity-action="select-device-organization"]');
    if (await deviceOrganization.getAttribute('aria-checked') !== 'true') {
      await deviceOrganization.click();
    }
    await expect(deviceOrganization).toHaveAttribute('aria-checked', 'true');
    for (const capability of ['identity.profile', 'identity.organizations', 'organization.read']) {
      const scope = devicePanel
        .locator(`[data-identity-action="toggle-device-scope"][data-capability="${capability}"]`);
      await scope.click();
      await expect(scope).toHaveAttribute('aria-checked', 'true');
    }
    const approval = waitForSuccessfulResponse(page, identityRoutes.deviceApproval);
    await devicePanel.locator('[data-identity-action="approve-device"]').click();
    const approvalRequest = (await approval).request().postDataJSON();
    assertSensitive(
      approvalRequest.userCode === deviceGrant.user_code,
      'The approval request did not contain the expected redacted user code.'
    );
    expect(approvalRequest.capabilities).toEqual(expect.arrayContaining([
      'identity.profile',
      'identity.organizations',
      'organization.read'
    ]));

    const deviceToken = await postSensitiveForm(
      request,
      identityRoutes.deviceToken,
      {
        grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
        device_code: deviceGrant.device_code,
        client_id: 'aether-playwright-cli'
      },
      'The redacted device-code exchange could not be sent.'
    );
    expect(deviceToken.ok()).toBeTruthy();
    const deviceCredentials = await deviceToken.json();
    expect(deviceCredentials.token_type).toBe('Bearer');
    expect(deviceCredentials.scope)
      .toBe('identity.organizations identity.profile organization.read');
    // RFC 8628 returns the remaining lifetime. A second may elapse between atomic issuance and
    // serializing the response, so assert the configured 15-minute window rather than one tick.
    expect(deviceCredentials.expires_in).toBeGreaterThanOrEqual(895);
    expect(deviceCredentials.expires_in).toBeLessThanOrEqual(900);
    assertSensitive(
      /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(deviceCredentials.access_token),
      'The redacted access token had an invalid wire format.'
    );
    assertSensitive(
      /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(deviceCredentials.refresh_token),
      'The redacted refresh token had an invalid wire format.'
    );

    const deviceMe = await getWithBearer(
      request,
      '/identity/v1/me',
      deviceCredentials.access_token,
      'The bearer-authenticated identity request failed.'
    );
    expect(deviceMe.ok()).toBeTruthy();
    expect(await deviceMe.json()).toMatchObject({ displayName: 'Playwright Owner' });
    const deviceOrganizations = await getWithBearer(
      request,
      '/identity/v1/organizations',
      deviceCredentials.access_token,
      'The bearer-authenticated organization request failed.'
    );
    expect(deviceOrganizations.ok()).toBeTruthy();
    expect(await deviceOrganizations.json()).toHaveLength(1);

    await page.goto('/identity');
    await waitForIdentityHydration(page);
    const stepUp = page.getByRole('button', { name: 'Verify this session with a passkey' });
    await expect(
      stepUp,
      'The live host must re-render signed-in management state after authentication'
    ).toBeVisible();

    await virtualAuthenticator.setUserVerified(false);
    await stepUp.click();
    await expect(page.getByRole('alert')).toContainText('could not be completed');

    await virtualAuthenticator.setUserVerified(true);
    const stepUpStart = waitForSuccessfulResponse(page, identityRoutes.stepUpStart);
    const stepUpCompletion = waitForSuccessfulResponse(page, identityRoutes.stepUpFinish);
    await stepUp.click();
    await stepUpStart;
    await stepUpCompletion;
    await expect(page.getByRole('status')).toContainText('Identity operation completed.');

    await context.clearCookies();
    await page.goto('/identity/recovery');
    await waitForIdentityHydration(page);
    await fillSensitive(
      page.getByLabel('Recovery code', { exact: true }),
      recoveryCodes[0],
      'Could not enter the redacted recovery code.'
    );
    const recovery = waitForSuccessfulResponse(page, identityRoutes.recoveryCodeUse);
    await page.getByRole('button', { name: 'Recover account' }).click();
    const recoveryPayload = await (await recovery).json();
    await expect(page).toHaveURL(/\/identity$/);
    await waitForIdentityHydration(page);
    assertSensitive(
      await page.evaluate(() => sessionStorage.getItem('aether.identity.csrf.v1')) ===
        recoveryPayload.csrfToken,
      'The session-bound CSRF value did not match after recovery.'
    );
    await expect(page.getByText(/restricted recovery session/i)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in with a discoverable passkey' })).toHaveCount(0);

    // Recovery represents loss of the original authenticator. Keeping its credential in the same
    // virtual authenticator correctly triggers WebAuthn's excludeCredentials InvalidStateError.
    for (const credential of await virtualAuthenticator.credentials()) {
      await virtualAuthenticator.removeCredential(credential.credentialId);
    }
    const credentialsBeforeRecoveryEnrollment = await virtualAuthenticator.credentials();
    await page.getByRole('textbox', { name: 'Passkey name', exact: true })
      .fill('Recovery replacement passkey');
    const replacementRegistration = waitForSuccessfulResponse(page, identityRoutes.registrationFinish);
    const replacementAuthentication = waitForSuccessfulResponse(page, identityRoutes.authenticationFinish);
    await page.getByRole('button', { name: 'Create a passkey named Recovery replacement passkey' }).click();
    await replacementRegistration;
    await replacementAuthentication;
    await expect.poll(async () => (await virtualAuthenticator.credentials()).length)
      .toBe(credentialsBeforeRecoveryEnrollment.length + 1);
    await expect(page.getByText(/recovery codes replaced/i)).toBeVisible();

    await context.clearCookies();
    await page.goto('/identity/recovery');
    await waitForIdentityHydration(page);
    await fillSensitive(
      page.getByLabel('Recovery code', { exact: true }),
      recoveryCodes[0],
      'Could not enter the redacted consumed recovery code.'
    );
    await page.getByRole('button', { name: 'Recover account' }).click();
    await expect(page.getByLabel('Recovery error')).toContainText(/invalid|used|expired/i);
  });
});
