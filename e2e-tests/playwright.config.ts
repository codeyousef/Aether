import { defineConfig, devices } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testRoot, '..');
const liveIdentity = process.env.AETHER_E2E_LIVE_IDENTITY === '1';
if (liveIdentity) process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const exampleBaseUrl = process.env.AETHER_E2E_EXAMPLE_BASE_URL ?? 'http://localhost:8080';
const liveBaseUrl = process.env.AETHER_E2E_BASE_URL;
const baseURL = liveIdentity && liveBaseUrl ? liveBaseUrl : exampleBaseUrl;
const baseUrl = new URL(baseURL);
const startExample = process.env.AETHER_E2E_EXTERNAL_EXAMPLE !== '1';
const liveSensitiveOutputDir = path.join(testRoot, '.live-sensitive-results');

export default defineConfig({
  testDir: path.join(testRoot, 'tests'),
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: liveIdentity ? 0 : process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 30_000,
  expect: { timeout: 7_500 },
  outputDir: path.join(testRoot, 'test-results'),
  reporter: liveIdentity
    ? 'line'
    : process.env.CI
      ? [['line'], ['html', { open: 'never', outputFolder: path.join(testRoot, 'playwright-report') }]]
      : 'list',
  use: {
    baseURL,
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    locale: 'en-US',
    timezoneId: 'UTC'
  },
  webServer: startExample
    ? {
        command: './gradlew :example-app:run',
        cwd: repositoryRoot,
        env: {
          ...process.env,
          PORT: baseUrl.port || '8080',
          AETHER_IDENTITY_BOOTSTRAP_SECRET:
            process.env.AETHER_IDENTITY_BOOTSTRAP_SECRET ?? 'aether-example-browser-test-secret'
        },
        url: new URL('/health', baseURL).toString(),
        reuseExistingServer: !process.env.CI && !liveIdentity,
        timeout: 240_000,
        stdout: 'pipe',
        stderr: 'pipe'
      }
    : undefined,
  projects: [
    {
      name: 'chromium-ui',
      testMatch: /ui\/.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] }
    },
    {
      name: 'firefox-ui',
      testMatch: /ui\/.*\.spec\.ts/,
      use: { ...devices['Desktop Firefox'] }
    },
    {
      name: 'webkit-ui',
      testMatch: /ui\/.*\.spec\.ts/,
      use: { ...devices['Desktop Safari'] }
    },
    {
      name: 'chromium-live',
      testMatch: /live\/.*\.spec\.ts/,
      outputDir: liveSensitiveOutputDir,
      use: {
        ...devices['Desktop Chrome'],
        baseURL: liveBaseUrl ?? exampleBaseUrl,
        permissions: [],
        trace: 'off',
        screenshot: 'off',
        video: 'off'
      }
    }
  ]
});
