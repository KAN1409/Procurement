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
