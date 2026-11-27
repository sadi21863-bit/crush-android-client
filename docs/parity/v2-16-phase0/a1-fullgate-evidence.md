# A1 full-gate evidence (2026-11-27, final)

This file exists because the earlier committed controller XML was stale: it
held 12 tests while the source and the acceptance report claimed 15. All XML
artifacts in this folder were regenerated from the run below and copied from
`app/build/test-results/testDebugUnitTest/` after the run completed.

## Run

- Command: `gradlew.bat :app:testDebugUnitTest` (no filter), JDK = Android
  Studio JBR 25, branch `v2-16-planning-baseline`, working tree including the
  registry-concurrency and publication-ordering fixes.
- Result: `BUILD SUCCESSFUL in 12m 54s`.
- Raw (NUL-stripped) Gradle output: `a1-final-fullgate-run.txt`.

## Totals

Derived by summing the `tests` / `failures` / `errors` / `skipped` attributes of
every `TEST-*.xml` in `app/build/test-results/testDebugUnitTest/` after the run:

| Metric | Value |
|---|---|
| Suites | 121 |
| Tests | 1129 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |

## Committed A1 XML artifacts

| File | Suite | Tests | Failures |
|---|---|---|---|
| `a1-controller-junit.xml` | `ExecutionControllerTest` | 15 | 0 |
| `a1-adapter-junit.xml` | `ExecutionEventAdapterTest` | 11 | 0 |
| `a1-integration-junit.xml` | `AppContainerExecutionInvalidationTest` | 4 | 0 |
| `a1-shutdown-junit.xml` | `AppContainerShutdownTest` | 1 | 0 |
| `a1-terminal-junit.xml` | `EngineTerminalStartupCleanupTest` | 1 | 0 |
| `e5-r6-junit.xml` | `E5InvalidationBarrierTest` | 4 | 0 |

A1 subset total: **36** (15 + 11 + 4 + 1 + 1 + 4).

The other 115 suites are the unchanged app regression set; their XMLs live in
the build output and are not duplicated here.

## Controller pins added after the owner review

- `concurrentStartsAndActiveChecksDoNotRace` — 16 starters + 2 readers released
  by one latch; no exception, all ids registered.
- `invalidationDuringConcurrentStartsMarksEveryRegisteredExecution` — 12
  starters + a concurrent invalidator, settled by a final invalidation.
- `publishedSnapshotsNeverRegressDuringConcurrentStarts` — an observer reads
  `executions.value` while 24 starts publish concurrently; the observed size
  must never decrease.

## Limits

- These are JVM results. No GitHub Actions run exists for this branch, so CI
  did not independently confirm them.
- XMLs are stored results, not a re-run by the reader; the raw log is included
  so the Gradle invocation and outcome can be checked.
