# Procurement Delivery Playbook

## Product sequence

This is the fixed implementation order unless a concrete runtime or data-integrity failure forces a change:

1. Parser precision
2. Candidate clustering / duplicate grouping
3. Database-level filters and sorting
4. Sticky controls + Go to top
5. Grouped Trusted Rates view

The product goal remains a procurement control and commercial-memory system, not a generic file browser.

## Delivery model

### Primary roles

- ChatGPT + GitHub: architecture, implementation, repository changes, migrations, tests and debugging.
- GitHub Actions: compile/test/build authority.
- Android Harness: device/runtime/corpus QA only, unless a device-only fix is required.
- User: business judgment and final acceptance.

Harness should not duplicate repository implementation work that is already faster and safer through GitHub.

## One feature, one implementation commit

Feature work should be prepared as a complete batch and committed once where practical:

- production code
- schema/migration changes
- focused tests
- UI wiring
- CI changes if required

Avoid file-by-file commits that repeatedly start and cancel CI.

## CI gates

### Feature branches

Run the fast gate only:

- Android/Kotlin compile path through unit-test task
- app unit tests
- Room/compiler validation reached by the test build

A failed test fails the gate. There is no continue-on-error for tests.

### main

After the fast gate passes:

- build the APK
- verify signing
- record checksum/certificate
- publish the APK artifact

## Concurrency

CI cancellation is scoped per Git ref. New commits cancel stale runs on the same branch, but a feature-branch run must not cancel a main build.

## Runtime acceptance

Do not run broad audits after a green gate unless runtime evidence exposes a failure.

For each milestone, define one short golden runtime workflow. Use Android Harness for repeatable device-side checks where it can save manual work.

Stop and fix immediately only for:

- data loss/corruption
- silent wrong business values
- source misidentification
- misleading commercial results
- broken core workflow

Cosmetic or rare edge issues go to backlog unless they block acceptance.

## Workflow optimization audit (2026-10-11)

Measured sample: 20 most recent candidate-clustering branch CI runs: 9 success,
8 failure, 3 cancelled. The latest successful run took approximately 154 seconds
from creation to completion. These are baseline observations, not a causal
benchmark or a prediction of future performance.

### Milestone preflight (before one push)

1. Inspect all touched source and related declarations/call sites.
2. Check for ambiguous Kotlin identifiers and type mismatches.
3. Include focused regression tests with the implementation whenever practical.
4. Review changed paths, business invariants, and migration implications.
5. Batch cohesive work into one commit/CI cycle; never bypass the CI gate.

The GitHub connector alone cannot run a local Kotlin compiler. Do not claim
that a source review is a compilation pass; GitHub Actions remains build authority.

### CI and handoff

- The candidate-clustering branch deliberately builds an APK after unit tests
  because Android Harness is its runtime acceptance gate. Other feature
  branches normally run tests only. This branch-specific exception supersedes
  the generic fast-gate description above.
- GitHub Actions publishes a step summary on success or failure, with the
  completed gate outcomes and a next action. The summary is informational:
  failed compilation/tests/build/signing still fail the job.
- APK identity and signing checks remain mandatory before any device install.
- Android Harness performs targeted real-device checks, not repository fixes.
- No uninstall, app-data clearing, rescan, commercial-value mutation, or
  main-branch merge as part of workflow optimization.

### Measurement and rollback

Compare the next 20 runs with the audit baseline: success/failure/cancellation
counts, wall-clock duration, and time to device acceptance. Distinguish product
failures from workflow failures. Revert only the workflow-summary change if it
breaks CI; retain the existing test, signing, and APK gates.

### Automated failure hints

The unit-test step now captures its console output while preserving the original
Gradle exit status with `pipefail`. If it fails, the job summary includes up to
12 Kotlin compiler / failed-task hints. The full log and JUnit reports remain
the authority for diagnosis. This is not a replacement for source review or
compilation; it reduces the time spent locating the first actionable error.

The console capture stays inside the ephemeral CI workspace and is not
uploaded as a separate artifact. No application data or test corpus is changed.
