import { test as base, expect, type CDPSession } from '@playwright/test';

export interface VirtualAuthenticator {
  readonly id: string;
  credentials(): Promise<ReadonlyArray<{ credentialId: string; isResidentCredential: boolean }>>;
  removeCredential(credentialId: string): Promise<void>;
  setUserVerified(verified: boolean): Promise<void>;
}

type Fixtures = {
  virtualAuthenticator: VirtualAuthenticator;
};

export const test = base.extend<Fixtures>({
  virtualAuthenticator: async ({ browserName, page }, use) => {
    expect(browserName, 'The WebAuthn CDP virtual authenticator is Chromium-only').toBe('chromium');

    const cdp = await page.context().newCDPSession(page);
    await cdp.send('WebAuthn.enable', { enableUI: false });
    const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2',
        ctap2Version: 'ctap2_1',
        transport: 'internal',
        hasResidentKey: true,
        hasUserVerification: true,
        hasLargeBlob: false,
        hasCredBlob: false,
        hasMinPinLength: false,
        hasPrf: false,
        automaticPresenceSimulation: true,
        isUserVerified: true,
        defaultBackupEligibility: false,
        defaultBackupState: false
      }
    });

    const authenticator: VirtualAuthenticator = {
      id: authenticatorId,
      async credentials() {
        const response = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
        return response.credentials;
      },
      async removeCredential(credentialId) {
        await cdp.send('WebAuthn.removeCredential', { authenticatorId, credentialId });
      },
      async setUserVerified(verified) {
        await cdp.send('WebAuthn.setUserVerified', {
          authenticatorId,
          isUserVerified: verified
        });
      }
    };

    try {
      await use(authenticator);
    } finally {
      await removeAuthenticator(cdp, authenticatorId);
    }
  }
});

async function removeAuthenticator(cdp: CDPSession, authenticatorId: string): Promise<void> {
  await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId }).catch(() => undefined);
  await cdp.send('WebAuthn.disable').catch(() => undefined);
  await cdp.detach().catch(() => undefined);
}

export { expect } from '@playwright/test';
