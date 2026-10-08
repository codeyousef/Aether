# Private-suite source consumer

This standalone Gradle build qualifies the exact Aether and Summon artifacts selected for AE-T00. It is not included in Aether's main build.

`source-pins.properties` records the required versions and immutable Summon source commit. The runner receives the Aether commit from the checkout under test, verifies both source identities, publishes selected artifacts into an isolated Maven repository, and then compiles:

- an Aether JVM server consumer and its tests on JDK 21;
- a Kotlin/JS browser consumer and its test sources;
- Summon `0.8.0` from its pinned source checkout.

The browser distribution gate rejects Aether authority, admin, PostgreSQL runtime classes, and the synthetic private marker. The fixture has no WASI target. A separate resolution probe must fail with a real version conflict, and Aether's identity runtime-classpath isolation guard must pass.

Run from the Aether repository root with JDK 21:

```bash
SUMMON_SOURCE_DIR=/absolute/path/to/pinned/summon \
  ./e2e-tests/run-private-suite-consumer.sh
```

The Summon checkout is read-only input; the runner does not edit it. The generated report is written under `.gradle/private-suite/` and is intentionally untracked.
