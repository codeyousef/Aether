#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$repo_root/e2e-tests/fixtures/private-suite-consumer"
pins_file="$fixture_dir/source-pins.properties"
summon_source="${SUMMON_SOURCE_DIR:-$(cd "$repo_root/../summon" 2>/dev/null && pwd)}"
consumer_repository="$repo_root/build/ae-t00-m2"

if [[ ! -d "$summon_source/.git" ]]; then
    echo "SUMMON_SOURCE_DIR must identify the pinned Summon checkout" >&2
    exit 2
fi

if [[ "$(java -XshowSettings:properties -version 2>&1 | awk -F= '/java.specification.version/ { gsub(/ /, "", $2); print $2; exit }')" != "21" ]]; then
    echo "AE-T00 requires the consuming Gradle process to run on JDK 21" >&2
    exit 2
fi

aether_commit="$(git -C "$repo_root" rev-parse HEAD)"
summon_commit="$(git -C "$summon_source" rev-parse HEAD)"
aether_version="$(awk -F= '$1 == "AETHER_VERSION" { print $2 }' "$pins_file")"
summon_version="$(awk -F= '$1 == "SUMMON_VERSION" { print $2 }' "$pins_file")"
expected_summon_commit="$(awk -F= '$1 == "SUMMON_COMMIT" { print $2 }' "$pins_file")"
if [[ "$summon_commit" != "$expected_summon_commit" ]]; then
    echo "Summon source pin mismatch: expected $expected_summon_commit, found $summon_commit" >&2
    exit 2
fi

rm -rf "$consumer_repository"
mkdir -p "$consumer_repository"
"$summon_source/gradlew" --no-daemon --stacktrace \
    -p "$summon_source" \
    -Dmaven.repo.local="$consumer_repository" \
    :summon-core:publishToMavenLocal
"$repo_root/gradlew" --no-daemon --stacktrace \
    -Dmaven.repo.local="$consumer_repository" \
    :aether-core:publishToMavenLocal \
    :aether-web:publishToMavenLocal \
    :aether-browser-client:publishToMavenLocal

common_args=(
    --no-daemon
    --stacktrace
    -p "$fixture_dir"
    "-PaetherRepository=$consumer_repository"
    "-PaetherSource=$repo_root"
    "-PaetherCommit=$aether_commit"
    "-PsummonSource=$summon_source"
    "-PsummonCommit=$summon_commit"
)

"$repo_root/gradlew" "${common_args[@]}" --write-locks verifyAeT00 jvmTest

set +e
conflict_output="$("$repo_root/gradlew" "${common_args[@]}" resolveDuplicateVersionProbe 2>&1)"
conflict_status=$?
set -e
printf '%s\n' "$conflict_output"
if [[ $conflict_status -eq 0 ]]; then
    echo "Expected duplicate dependency versions to fail resolution" >&2
    exit 1
fi
if [[ "$conflict_output" != *"kotlinx-coroutines-core between versions 1.10.2 and 1.9.0"* ]]; then
    echo "Duplicate-version probe failed for an unexpected reason" >&2
    exit 1
fi

echo "Observed expected duplicate-version resolution failure (exit $conflict_status)."
"$repo_root/gradlew" --no-daemon --stacktrace verifyIdentityRuntimeClasspaths

report_dir="$repo_root/.gradle/private-suite"
mkdir -p "$report_dir"
printf '{\n  "schemaVersion": 1,\n  "case": "AE-T00",\n  "aether": {"version": "%s", "commit": "%s", "source": "%s"},\n  "summon": {"version": "%s", "commit": "%s", "source": "%s"},\n  "jdk": "21",\n  "jvmMain": "passed",\n  "jvmTest": "passed",\n  "jsMain": "passed",\n  "jsTestCompilation": "passed",\n  "browserDistributionBoundary": "passed",\n  "runtimeClasspathIsolation": "passed",\n  "duplicateVersionResolution": "rejected",\n  "productionWasi": "absent",\n  "optionalWasmJs": "not_run",\n  "hardwareRows": "not_applicable"\n}\n' \
    "$aether_version" "$aether_commit" "$repo_root" \
    "$summon_version" "$summon_commit" "$summon_source" \
    > "$report_dir/ae-t00-report.json"

echo "AE-T00 qualification passed; report: $report_dir/ae-t00-report.json"
