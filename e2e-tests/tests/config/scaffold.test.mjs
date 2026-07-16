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

test('successful main verification publishes automatically and only once per version', async () => {
  const workflow = await text('../.github/workflows/publish.yml');

  assert.match(workflow, /pull_request:\n\s+branches:\n\s+- main/);
  assert.match(workflow, /push:\n\s+branches:\n\s+- main/);
  assert.match(workflow, /workflow_dispatch:\n\s+inputs:/);
  assert.match(workflow, /retry_failed_deployment_id:/);
  assert.match(workflow, /resume_deployment_id:/);
  assert.match(workflow, /resume_commit_sha:/);
  assert.match(workflow, /repair_release_tag:/);
  assert.match(workflow, /acknowledge_unavailable_failed_deployment:/);
  assert.doesNotMatch(workflow, /hardware_passkey_smoke_|adversarial_review_/);
  assert.match(
    workflow,
    /publish:\n\s+if: >-\n\s+github\.event_name == 'workflow_dispatch' \|\|\n\s+\(github\.event_name == 'push' && github\.ref == 'refs\/heads\/main'\)/
  );
  assert.match(workflow, /publish:[\s\S]*?needs: verify/);
  assert.match(
    workflow,
    /concurrency:\n\s+group: publish-aether-\$\{\{ github\.ref \}\}\n\s+cancel-in-progress: false/
  );
  assert.match(
    workflow,
    /Read release version and publication state[\s\S]*?refs\/tags\/v\$\{VERSION\}[\s\S]*?tag_exists=true[\s\S]*?tag_exists=false[\s\S]*?publish_required=true[\s\S]*?publish_required=false/
  );
  assert.match(workflow, /CHANGELOG_VERSION[\s\S]*?does not match version\.properties/);
  assert.match(workflow, /retry_failed_deployment_id and resume_deployment_id are mutually exclusive/);
  assert.match(workflow, /acknowledge_unavailable_failed_deployment is valid only with retry_failed_deployment_id/);
  assert.match(workflow, /Manual publication is recovery-only; provide a failed retry ID or an exact resume ID/);
  assert.match(workflow, /resume_commit_sha must be the exact 40-character upload commit/);
  assert.match(workflow, /Central deployment IDs must use canonical lowercase UUIDs/);
  assert.equal((workflow.match(/ref: \$\{\{ github\.sha \}\}/g) ?? []).length, 2);
  assert.doesNotMatch(workflow, /ref: \$\{\{ inputs\.resume_commit_sha/);
  assert.match(workflow, /RELEASE_COMMIT=\$\(git rev-parse "\$\{RESUME_COMMIT_SHA\}\^\{commit\}"\)/);
  assert.match(workflow, /The current release version does not match the resumed upload commit/);
  assert.doesNotMatch(workflow, /Checkout commit does not match resume_commit_sha/);
  assert.match(
    workflow,
    /elif \[\[ -n "\$RESUME_DEPLOYMENT_ID" \]\]; then[\s\S]*?upload_required=false[\s\S]*?release_mode=resume/
  );
  assert.match(workflow, /The failed-deployment retry commit is not contained in origin\/main/);
  assert.match(workflow, /A rerun may duplicate an accepted Central upload/);
  assert.match(workflow, /Same-version recovery is restricted to reviewed publication metadata/);
  assert.match(workflow, /git diff --name-only "v\$\{VERSION\}"\.\.\."\$\{RELEASE_COMMIT\}"/);
  assert.match(workflow, /git merge-base --is-ancestor "v\$\{VERSION\}" HEAD/);
  assert.match(
    workflow,
    /- name: Upload one Aether bundle to Maven Central[\s\S]*?if: steps\.version\.outputs\.upload_required == 'true'/
  );
  assert.match(
    workflow,
    /- name: Wait for Maven Central publication[\s\S]*?if: steps\.version\.outputs\.publish_required == 'true'/
  );
  assert.match(
    workflow,
    /- name: Create or repair the release tag\n\s+if: steps\.version\.outputs\.publish_required == 'true'/
  );
  assert.match(workflow, /--force-with-lease="refs\/tags\/v\$\{VERSION\}:\$\{EXPECTED_TAG_OBJECT\}"/);
  assert.match(workflow, /- name: Extract latest changelog entry\n\s+id: changelog/);
  assert.match(
    workflow,
    /- name: Create GitHub release\n\s+uses: softprops\/action-gh-release@v2\n\s+if: steps\.version\.outputs\.publish_required == 'true' && steps\.changelog\.outputs\.notes != ''/
  );

  const validateIndex = workflow.indexOf('- name: Validate Central recovery request');
  const claimIndex = workflow.indexOf('- name: Claim the one-time Central upload');
  const uploadIndex = workflow.indexOf('- name: Upload one Aether bundle to Maven Central');
  const waitIndex = workflow.indexOf('- name: Wait for Maven Central publication');
  const tagIndex = workflow.indexOf('- name: Create or repair the release tag');
  assert.ok(
    validateIndex < claimIndex && claimIndex < uploadIndex && uploadIndex < waitIndex && waitIndex < tagIndex
  );
});

test('Maven Central publication requires real sources and waits for PUBLISHED', async () => {
  const pluginBuild = await text('../aether-plugin/build.gradle.kts');
  const rootBuild = await text('../build.gradle.kts');
  const workflow = await text('../.github/workflows/publish.yml');
  const signScript = await text('../sign-artifact.sh');

  assert.match(pluginBuild, /java\s*\{[\s\S]*?withSourcesJar\(\)/);
  assert.match(rootBuild, /verifyCentralPublicationArtifacts by tasks\.registering/);
  assert.match(rootBuild, /sources JAR is missing or empty/);
  assert.match(rootBuild, /sources JAR contains no Kotlin or Java source/);
  assert.match(rootBuild, /val pomOnly = Regex/);
  assert.match(rootBuild, /if \(pomOnly\)/);
  assert.match(rootBuild, /prepareCentralPortalBundle by tasks\.registering/);
  assert.match(rootBuild, /uploadCentralPortalBundle by tasks\.registering/);
  assert.match(rootBuild, /waitForCentralPortalPublication by tasks\.registering/);
  assert.match(rootBuild, /dependsOn\(verifyCentralPublicationArtifacts, writeExpectedCentralPurls\)/);
  assert.match(rootBuild, /publishingType=AUTOMATIC/);
  assert.match(rootBuild, /Authorization: \$\{'\$'\}AETHER_CENTRAL_AUTHORIZATION/);
  assert.match(rootBuild, /"PUBLISHED" -> \{/);
  assert.match(rootBuild, /"FAILED" -> throw GradleException/);
  assert.match(rootBuild, /JsonSlurper\(\)\.parseText/);
  assert.match(rootBuild, /--connect-timeout 15/);
  assert.match(rootBuild, /Central upload outcome is indeterminate/);
  assert.match(rootBuild, /numericHttpCode !in setOf\(408, 409, 425, 429\)/);
  assert.match(rootBuild, /uploadCentralPortalBundle cannot resume an existing deployment/);
  assert.match(rootBuild, /centralExpectedPurls/);
  assert.match(rootBuild, /centralExpectedReportedPurls/);
  assert.match(rootBuild, /writeExpectedCentralReportedPurls by tasks\.registering/);
  assert.match(rootBuild, /central-expected-reported-purls\.txt/);
  assert.match(rootBuild, /artifactId\.endsWith\("-wasm-js"\)/);
  assert.match(rootBuild, /artifactId\.endsWith\("-wasm-wasi"\)/);
  assert.match(rootBuild, /\?type=klib/);
  assert.match(rootBuild, /\?type=pom/);
  assert.match(rootBuild, /centralPortalArtifactIds\.size == 74 && purls\.size == 75/);
  assert.match(rootBuild, /klibArtifactIds\.size == 35/);
  assert.match(rootBuild, /reportedPurls\.size == 111/);
  assert.match(rootBuild, /unexpectedPurls = purls - expectedPurls/);
  assert.match(rootBuild, /Central status purls must contain only strings/);
  assert.equal((rootBuild.match(/inputs\.property\("expectedPurls", expectedPurls\)/g) ?? []).length, 2);
  assert.match(rootBuild, /reportedPurls\.containsAll\(basePurls\)/);
  assert.match(rootBuild, /purlList\.size != purls\.size/);
  assert.match(rootBuild, /"PUBLISHED" -> \{[\s\S]*?purls != expectedPurls/);
  assert.match(signScript, /AETHER_SIGNING_PASSPHRASE/);
  assert.match(signScript, /--passphrase-fd 0/);
  assert.doesNotMatch(signScript, /PASSPHRASE="\$1"|--passphrase "\$PASSPHRASE"/);
  assert.match(workflow, /verifyExpectedSourceTasks verifyCentralPublicationArtifacts check/);
  assert.match(workflow, /retry deployment must be FAILED/);
  assert.match(workflow, /HTTP_CODE" == "200"/);
  assert.match(workflow, /HTTP_CODE" == "404"/);
  assert.match(workflow, /e4df03ff-971d-4b12-b5cb-da68bbefa81a/);
  assert.match(workflow, /KNOWN_VERSION="0\.6\.0\.0"/);
  assert.match(workflow, /KNOWN_OLD_TAG_OBJECT="dc46b140797264f8bcd6378df3c00dbd42e7421f"/);
  assert.match(workflow, /REVIEWED_REPAIR_BASELINE="582adbe30a4791f59547abff2c5e9ed9c8b0fd7e"/);
  assert.match(workflow, /An unavailable deployment cannot be resumed/);
  assert.match(workflow, /--force-with-lease="\$\{CLAIM_REF\}:"/);
  assert.match(workflow, /Central status purls must be an array containing only strings/);
  assert.match(workflow, /b2534c321438153e16a8a4b19c4acbacae071720e76b754e4b7ab95610cd7960/);
  assert.match(workflow, /23593a776b051febe452adc0edffd0fbc5a90730242e6e4dd346641d0f0a6143/);
  assert.match(workflow, /data\.get\("deploymentName"\) != expected_name/);
  assert.match(workflow, /actual_purls != expected_reported_purls/);
  assert.match(workflow, /writeExpectedCentralPurls writeExpectedCentralReportedPurls/);
  assert.match(workflow, /len\(expected_purls\) != 75 or len\(expected_reported_purls\) != 111/);
  assert.match(workflow, /unexpected_purls = actual_purls - expected_reported_purls/);
  assert.match(workflow, /require_complete_manifest = bool\(retry_id\) or state == "PUBLISHED"/);
  assert.match(workflow, /repo\.maven\.apache\.org\/maven2/);
  assert.match(workflow, /Expected to prove 75 coordinates unpublished/);
  assert.match(workflow, /is already public and cannot be replaced/);
  assert.match(workflow, /git merge-base --is-ancestor "v\$\{VERSION\}" "\$RELEASE_COMMIT"/);
  assert.match(workflow, /git show "\$\{RELEASE_COMMIT\}:CHANGELOG\.md"/);
  assert.match(workflow, /The current polling workflow must descend from the resumed release commit/);
  assert.match(workflow, /The remote release tag changed while publication was in progress/);

  const recoveryValidationStep = workflow.match(
    /- name: Validate Central recovery request[\s\S]*?(?=\n\s+- name: Claim the one-time Central upload)/
  )?.[0] ?? '';
  const recoveryClaimStep = workflow.match(
    /- name: Claim the one-time Central upload[\s\S]*?(?=\n\s+- name: Upload one Aether bundle to Maven Central)/
  )?.[0] ?? '';
  assert.match(
    recoveryValidationStep,
    /ACKNOWLEDGE_UNAVAILABLE_FAILED_DEPLOYMENT" == "true"[\s\S]*?DEPLOYMENT_ID" == "\$KNOWN_DEPLOYMENT_ID" && "\$RELEASE_VERSION" == "\$KNOWN_VERSION"/
  );
  assert.match(
    recoveryValidationStep,
    /TAG_NEEDS_REPAIR" == "true" && "\$REPAIR_RELEASE_TAG" == "true"/
  );
  assert.match(
    recoveryValidationStep,
    /KNOWN_OLD_TAG_COMMIT="f55c5a2c14dc444ffe09d0c09a857ea8421dd7ad"[\s\S]*?git rev-list -n 1[\s\S]*?KNOWN_OLD_TAG_COMMIT/
  );
  assert.match(
    recoveryValidationStep,
    /git merge-base --is-ancestor "\$REVIEWED_REPAIR_BASELINE" "\$RELEASE_COMMIT"/
  );
  assert.match(
    recoveryValidationStep,
    /\.github\/workflows\/publish\.yml\|CHANGELOG\.md\|docs\/identity\/deployment\.md\|e2e-tests\/tests\/config\/scaffold\.test\.mjs/
  );
  assert.match(
    recoveryValidationStep,
    /if \[\[ -n "\$RETRY_FAILED_DEPLOYMENT_ID" \]\]; then[\s\S]*?done < build\/central-expected-purls\.txt[\s\S]*?CHECKED_PURLS" == "75"/
  );
  assert.match(
    recoveryClaimStep,
    /if: steps\.version\.outputs\.upload_required == 'true'/
  );
  assert.match(
    recoveryClaimStep,
    /central-retry-v\$\{RELEASE_VERSION\}-\$\{RETRY_FAILED_DEPLOYMENT_ID\}[\s\S]*?central-upload-v\$\{RELEASE_VERSION\}[\s\S]*?git tag --annotate "\$CLAIM_TAG" "\$RELEASE_COMMIT"[\s\S]*?GITHUB_RUN_ID[\s\S]*?GITHUB_RUN_ATTEMPT[\s\S]*?--force-with-lease="\$\{CLAIM_REF\}:"/
  );

  const uploadStep = workflow.match(
    /- name: Upload one Aether bundle to Maven Central[\s\S]*?(?=\n\s+- name: Wait for Maven Central publication)/
  )?.[0] ?? '';
  const signingStep = workflow.match(
    /- name: Configure signing key[\s\S]*?(?=\n\s+- name: Validate Central recovery request)/
  )?.[0] ?? '';
  const waitStep = workflow.match(
    /- name: Wait for Maven Central publication[\s\S]*?(?=\n\s+- name: Delete publishing credentials)/
  )?.[0] ?? '';
  assert.doesNotMatch(uploadStep, /MAX_RETRIES|for i in|waitForCentralPortalPublication/);
  assert.match(signingStep, /if: steps\.version\.outputs\.upload_required == 'true'/);
  assert.match(uploadStep, /uploadCentralPortalBundle/);
  assert.match(uploadStep, /if: steps\.version\.outputs\.upload_required == 'true'/);
  assert.doesNotMatch(waitStep, /uploadCentralPortalBundle|signingPassword|private-key\.asc/);
  assert.match(waitStep, /waitForCentralPortalPublication/);
  assert.match(waitStep, /AETHER_CENTRAL_DEPLOYMENT_ID: \$\{\{ inputs\.resume_deployment_id \|\| steps\.central_upload\.outputs\.deployment_id \}\}/);
  assert.match(waitStep, /AETHER_CENTRAL_DEPLOYMENT_NAME: \$\{\{ steps\.version\.outputs\.deployment_name \}\}/);
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
