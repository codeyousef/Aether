import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

async function text(relativePath) {
  return readFile(path.join(root, relativePath), 'utf8');
}

test('browser and state-changing live suites are separate from the default contract checks', async () => {
  const packageJson = JSON.parse(await text('package.json'));

  assert.equal(packageJson.scripts.test, 'node --test tests/config/*.test.mjs');
  assert.match(packageJson.scripts['test:browser'], /chromium-ui.*firefox-ui.*webkit-ui/);
  assert.equal(packageJson.scripts['test:live'], 'node scripts/run-live.mjs live');
  assert.equal(packageJson.scripts['test:release'], 'node scripts/run-live.mjs release');
  assert.equal(packageJson.devDependencies['@playwright/test'], '1.58.0');
  assert.equal(packageJson.devDependencies.typescript, '5.9.3');
  assert.ok(packageJson.devDependencies['@types/node']);
});

test('Playwright declares Chromium, Firefox, WebKit, and a Chromium-only live project', async () => {
  const config = await text('playwright.config.ts');

  for (const project of ['chromium-ui', 'firefox-ui', 'webkit-ui', 'chromium-live']) {
    assert.match(config, new RegExp(`name: '${project}'`));
  }
  assert.match(config, /testMatch: \/live\\\/\.\*\\\.spec/);
  assert.match(config, /command: '\.\/gradlew :example-app:run'/);
  assert.match(config, /AETHER_IDENTITY_BOOTSTRAP_SECRET/);
});

test('the secret-bearing live lane cannot retain or upload browser artifacts', async () => {
  const config = await text('playwright.config.ts');
  const workflow = await text('../.github/workflows/publish.yml');
  const gitignore = await text('.gitignore');
  const liveJourney = await text('tests/live/passkey.live.spec.ts');
  const liveRunner = await text('scripts/run-live.mjs');

  assert.match(config, /reporter: liveIdentity\s*\? 'line'/);
  assert.match(config, /retries: liveIdentity \? 0/);
  assert.match(config, /reuseExistingServer: !process\.env\.CI && !liveIdentity/);
  assert.match(config, /outputDir: liveSensitiveOutputDir/);
  assert.match(config, /trace: 'off'/);
  assert.match(config, /screenshot: 'off'/);
  assert.match(config, /video: 'off'/);
  assert.match(config, /PLAYWRIGHT_NO_COPY_PROMPT = '1'/);
  assert.match(liveJourney, /PLAYWRIGHT_NO_COPY_PROMPT = '1'/);
  assert.match(gitignore, /\.live-sensitive-results\//);
  assert.match(liveRunner, /PLAYWRIGHT_NO_COPY_PROMPT: '1'/);
  assert.match(liveRunner, /finally \{/);
  assert.match(liveRunner, /rm\(sensitiveOutput, \{ recursive: true, force: true \}\)/);
  assert.match(liveRunner, /shell: false/);
  assert.match(liveRunner, /process\.exitCode = exitCode/);
  assert.match(liveRunner, /activeChild\?\.kill\(signal\)/);
  assert.match(workflow, /rm -rf e2e-tests\/\.live-sensitive-results/);
  assert.match(workflow, /PLAYWRIGHT_NO_COPY_PROMPT: '1'/);
  assert.doesNotMatch(workflow, /upload-artifact[\s\S]{0,500}\.live-sensitive-results/);
  assert.doesNotMatch(liveJourney, /expect\((deviceGrant|deviceCredentials|recoveryCodes|bootstrapPayload)\)/);
  assert.doesNotMatch(liveJourney, /toContainText\(deviceGrant\.user_code\)/);
  assert.doesNotMatch(liveJourney, /page\.goto\(deviceGrant\.verification_uri_complete\)/);
  assert.doesNotMatch(liveJourney, /\?user_code/);
  assert.match(
    liveJourney,
    /getByLabel\('Device authorization code', \{ exact: true \}\)/
  );
  assert.match(liveJourney, /identityRoutes\.deviceVerification/);
  assert.match(liveJourney, /function assertSensitive/);
});

test('manual publishing requires non-secret hardware-smoke and adversarial-review evidence', async () => {
  const workflow = await text('../.github/workflows/publish.yml');

  for (const gate of [
    'hardware_passkey_smoke_confirmed',
    'hardware_passkey_smoke_evidence',
    'adversarial_review_confirmed',
    'adversarial_review_evidence'
  ]) {
    assert.ok(workflow.includes(gate), `missing release input ${gate}`);
  }
  assert.match(workflow, /Verify adversarial-review release gate/);
  assert.match(workflow, /Independent adversarial identity review is not confirmed/);
});

test('the virtual authenticator models a discoverable user-verified CTAP2 passkey', async () => {
  const fixture = await text('tests/support/virtual-authenticator.ts');

  assert.match(fixture, /WebAuthn\.addVirtualAuthenticator/);
  assert.match(fixture, /protocol: 'ctap2'/);
  assert.match(fixture, /hasResidentKey: true/);
  assert.match(fixture, /hasUserVerification: true/);
  assert.match(fixture, /isUserVerified: true/);
  assert.match(fixture, /automaticPresenceSimulation: true/);
});

test('live authority preflight covers every route needed by the executable passkey journey', async () => {
  const support = await text('tests/support/live-authority.ts');

  for (const route of [
    '/identity/v1/client-config',
    '/identity/v1/bootstrap',
    '/identity/v1/recovery/codes/use',
    '/identity/v1/recovery/codes/replace',
    '/identity/v1/passkeys/registration/start',
    '/identity/v1/passkeys/registration/finish',
    '/identity/v1/passkeys/authentication/start',
    '/identity/v1/passkeys/authentication/finish',
    '/identity/v1/passkeys/step-up/start',
    '/identity/v1/passkeys/step-up/finish',
    '/identity/v1/device/approve',
    '/identity/v1/device/deny',
    '/oauth/device_authorization',
    '/oauth/token'
  ]) {
    assert.ok(support.includes(route), `missing preflight route ${route}`);
  }
  assert.match(support, /data: '\{'/);
  assert.match(support, /'Content-Type': 'application\/json'/);
  assert.doesNotMatch(support, /data: \{\}/);
  assert.match(support, /AuthenticationStart has no request body/);
  assert.match(support, /probing it here would make first-owner/);
  assert.match(support, /data-hydration-ready', 'true'/);
});

test('release prerequisites require an ephemeral target and explicitly enabled recovery flow', async () => {
  const prerequisites = await text('scripts/check-prerequisites.mjs');

  assert.match(prerequisites, /AETHER_E2E_EPHEMERAL/);
  assert.match(prerequisites, /AETHER_E2E_ALLOW_REMOTE/);
  assert.match(prerequisites, /AETHER_E2E_RECOVERY_FLOW/);
  assert.match(prerequisites, /AETHER_IDENTITY_BOOTSTRAP_SECRET/);
  assert.match(prerequisites, /Non-loopback live identity targets must use HTTPS/);
  assert.match(prerequisites, /Missing Playwright browser binaries/);
});
