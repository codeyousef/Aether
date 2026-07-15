import { spawn } from 'node:child_process';
import { rm } from 'node:fs/promises';
import { constants as osConstants } from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const testRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sensitiveOutput = path.join(testRoot, '.live-sensitive-results');
const mode = process.argv[2];
const environment = {
  ...process.env,
  AETHER_E2E_LIVE_IDENTITY: '1',
  PLAYWRIGHT_NO_COPY_PROMPT: '1'
};
const require = createRequire(import.meta.url);
const playwrightCli = require.resolve('@playwright/test/cli');
const prerequisiteScript = path.join(testRoot, 'scripts', 'check-prerequisites.mjs');
const handledSignals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
let activeChild;
let interruptedSignal;

function handleSignal(signal) {
  interruptedSignal ??= signal;
  activeChild?.kill(signal);
}

const signalHandlers = new Map(handledSignals.map((signal) => [signal, () => handleSignal(signal)]));
for (const [signal, handler] of signalHandlers) process.on(signal, handler);

let exitCode = 1;
try {
  await rm(sensitiveOutput, { recursive: true, force: true });
  if (mode !== 'live' && mode !== 'release') {
    throw new Error('Usage: node scripts/run-live.mjs <live|release>');
  }
  const prerequisite = await run(process.execPath, [prerequisiteScript, mode]);
  if (prerequisite.code !== 0 || prerequisite.signal) {
    exitCode = statusCode(prerequisite);
  } else {
    exitCode = statusCode(await run(process.execPath, [playwrightCli, 'test', '--project=chromium-live']));
  }
} catch {
  console.error('Aether live E2E runner could not start.');
  exitCode = 1;
} finally {
  await rm(sensitiveOutput, { recursive: true, force: true }).catch(() => {
    console.error('Aether live E2E runner could not delete sensitive output.');
    exitCode = 1;
  });
}

if (interruptedSignal) {
  for (const [signal, handler] of signalHandlers) process.removeListener(signal, handler);
  try {
    process.kill(process.pid, interruptedSignal);
  } catch {
    process.exitCode = 128 + (osConstants.signals[interruptedSignal] ?? 0);
  }
} else {
  process.exitCode = exitCode;
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: testRoot,
      env: environment,
      shell: false,
      stdio: 'inherit'
    });
    activeChild = child;
    child.once('error', (error) => {
      activeChild = undefined;
      reject(error);
    });
    child.once('exit', (code, signal) => {
      activeChild = undefined;
      resolve({ code, signal });
    });
  });
}

function statusCode(status) {
  if (typeof status.code === 'number') return status.code;
  if (status.signal && osConstants.signals[status.signal]) {
    return 128 + osConstants.signals[status.signal];
  }
  return 1;
}
