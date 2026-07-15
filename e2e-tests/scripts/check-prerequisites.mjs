import { createRequire } from 'node:module';
import { existsSync } from 'node:fs';

const mode = process.argv[2] ?? 'browser';
const validModes = new Set(['browser', 'live', 'release']);

if (!validModes.has(mode)) {
  fail(`Unknown prerequisite mode: ${mode}`);
}

if (mode !== 'browser') {
  if (process.env.AETHER_E2E_LIVE_IDENTITY !== '1') {
    fail('Set AETHER_E2E_LIVE_IDENTITY=1 to opt in to state-changing identity tests.');
  }

  if (process.env.AETHER_E2E_EPHEMERAL !== '1') {
    fail('Set AETHER_E2E_EPHEMERAL=1 to confirm the target is disposable and reset for this run.');
  }

  const bootstrapSecret = process.env.AETHER_IDENTITY_BOOTSTRAP_SECRET;
  if (!bootstrapSecret || bootstrapSecret.length < 16 || bootstrapSecret.length > 512) {
    fail('Set AETHER_IDENTITY_BOOTSTRAP_SECRET to the disposable target first-owner secret (16..512 characters).');
  }

  const rawBaseUrl = process.env.AETHER_E2E_BASE_URL;
  if (!rawBaseUrl) {
    fail('Set AETHER_E2E_BASE_URL to the fully wired identity authority under test.');
  }

  let baseUrl;
  try {
    baseUrl = new URL(rawBaseUrl);
  } catch {
    fail('AETHER_E2E_BASE_URL must be an absolute HTTP(S) URL.');
  }

  if (!['http:', 'https:'].includes(baseUrl.protocol)) {
    fail('AETHER_E2E_BASE_URL must use HTTP or HTTPS.');
  }

  const isLoopback = ['localhost', '127.0.0.1', '::1', '[::1]'].includes(baseUrl.hostname);
  if (baseUrl.protocol !== 'https:' && !isLoopback) {
    fail('Non-loopback live identity targets must use HTTPS.');
  }

  if (!isLoopback && process.env.AETHER_E2E_ALLOW_REMOTE !== '1') {
    fail('Set AETHER_E2E_ALLOW_REMOTE=1 to explicitly allow a remote disposable target.');
  }

  if (process.env.AETHER_E2E_EXTERNAL_EXAMPLE !== '1' && baseUrl.hostname !== 'localhost') {
    fail('The built-in example authority uses the exact origin http://localhost:<port>; use localhost or set AETHER_E2E_EXTERNAL_EXAMPLE=1.');
  }

  if (mode === 'release' && process.env.AETHER_E2E_RECOVERY_FLOW !== '1') {
    fail('Set AETHER_E2E_RECOVERY_FLOW=1 for the release gate; recovery is otherwise skipped.');
  }
}

const require = createRequire(import.meta.url);
try {
  require.resolve('@playwright/test/package.json');
} catch {
  fail(
    'The Node Playwright test package is not installed. Run npm install in e2e-tests, ' +
      'commit the generated package-lock.json, then install the declared browser binaries.'
  );
}

const playwright = require('@playwright/test');
const requiredBrowsers = mode === 'browser'
  ? [['Chromium', playwright.chromium], ['Firefox', playwright.firefox], ['WebKit', playwright.webkit]]
  : [['Chromium', playwright.chromium]];
const unavailableBrowsers = requiredBrowsers
  .filter(([, browserType]) => !existsSync(browserType.executablePath()))
  .map(([name]) => name);
if (unavailableBrowsers.length > 0) {
  fail(
    `Missing Playwright browser binaries: ${unavailableBrowsers.join(', ')}. ` +
      'Run npm run install:browsers in e2e-tests.'
  );
}

function fail(message) {
  console.error(`Aether E2E prerequisite failed: ${message}`);
  process.exit(2);
}
