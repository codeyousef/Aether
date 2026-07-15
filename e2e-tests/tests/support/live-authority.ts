import { expect, type APIRequestContext, type Page, type Response } from '@playwright/test';

export const identityRoutes = {
  clientConfig: '/identity/v1/client-config',
  bootstrap: '/identity/v1/bootstrap',
  recoveryCodeUse: '/identity/v1/recovery/codes/use',
  recoveryCodesReplace: '/identity/v1/recovery/codes/replace',
  registrationStart: '/identity/v1/passkeys/registration/start',
  registrationFinish: '/identity/v1/passkeys/registration/finish',
  authenticationStart: '/identity/v1/passkeys/authentication/start',
  authenticationFinish: '/identity/v1/passkeys/authentication/finish',
  stepUpStart: '/identity/v1/passkeys/step-up/start',
  stepUpFinish: '/identity/v1/passkeys/step-up/finish',
  deviceVerification: '/identity/v1/device',
  deviceApproval: '/identity/v1/device/approve',
  deviceDenial: '/identity/v1/device/deny',
  deviceAuthorization: '/oauth/device_authorization',
  deviceToken: '/oauth/token'
} as const;

const safelyProbeablePostRoutes = [
  identityRoutes.bootstrap,
  identityRoutes.recoveryCodeUse,
  identityRoutes.recoveryCodesReplace,
  identityRoutes.registrationStart,
  identityRoutes.registrationFinish,
  identityRoutes.authenticationFinish,
  identityRoutes.stepUpStart,
  identityRoutes.stepUpFinish,
  identityRoutes.deviceVerification,
  identityRoutes.deviceApproval,
  identityRoutes.deviceDenial,
  identityRoutes.deviceAuthorization,
  identityRoutes.deviceToken
];

// AuthenticationStart has no request body and every successful call intentionally persists a ceremony.
// The live journey exercises it immediately after bootstrap, so probing it here would make first-owner
// bootstrap fail closed on a non-empty identity store.

/**
 * Refuses to start a state-changing journey against an incompletely wired authority. The deliberately
 * malformed JSON must fail during request decoding, before a handler can create a ceremony or mutate
 * any other identity state. Any stable 4xx response is acceptable, but 404/405/501 proves that the
 * required handler is absent.
 */
export async function assertLiveAuthorityReady(request: APIRequestContext): Promise<void> {
  const configuredBaseUrl = process.env.AETHER_E2E_BASE_URL;
  expect(configuredBaseUrl, 'AETHER_E2E_BASE_URL must be set for live tests').toBeTruthy();
  const origin = new URL(configuredBaseUrl!).origin;

  const config = await request.get(identityRoutes.clientConfig);
  expect(config.ok(), 'client-config must be available').toBeTruthy();
  expect(config.headers()['content-type']).toContain('application/json');

  const identityPage = await request.get('/identity');
  expect(identityPage.ok(), 'the Summon identity page must be mounted').toBeTruthy();
  const identityHtml = await identityPage.text();
  expect(identityHtml).toContain('id="aether-identity"');
  const browserAssetPath = identityHtml.match(/<script[^>]+src="([^"]+)"[^>]*><\/script>/)?.[1];
  expect(browserAssetPath, 'the live identity page must include its wasmJs hydration asset').toBeTruthy();
  const browserAsset = await request.get(browserAssetPath!);
  expect(browserAsset.ok(), 'the wasmJs hydration asset must be served').toBeTruthy();

  const bootstrapPage = await request.get('/identity/bootstrap');
  expect(bootstrapPage.ok(), 'the first-owner bootstrap page must be mounted').toBeTruthy();
  expect(await bootstrapPage.text()).toContain('id="aether-bootstrap"');

  for (const route of safelyProbeablePostRoutes) {
    const response = await request.post(route, {
      data: '{',
      headers: {
        Origin: origin,
        'Content-Type': 'application/json'
      }
    });
    expect(
      [404, 405, 501],
      `${route} is not mounted on the selected live identity authority`
    ).not.toContain(response.status());
  }
}

export function waitForSuccessfulResponse(page: Page, path: string): Promise<Response> {
  return page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === path && response.request().method() === 'POST' && response.ok();
  });
}

export async function waitForIdentityHydration(page: Page): Promise<void> {
  await expect(page.locator('#summon-app')).toHaveAttribute('data-hydration-ready', 'true');
}
