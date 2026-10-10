# Quarantined JVM tests

These two legacy tests were never executable in the previous CI because the unit-test
step was allowed to fail silently.

They are intentionally outside the active `src/test` source set until rewritten
against testable seams:

- `ContentIndexerTest.kt`: mocks Android Context/ContentResolver incompletely and
  asserts DAO effects without a real readable source stream.
- `DocumentTreeScannerTest.kt`: verifies reconciliation DAO calls without actually
  invoking the SAF scan path.

They are preserved here as behavioral notes, not counted as passing coverage.
When either subsystem is changed, rewrite the relevant test around a deterministic
adapter/fake rather than simply moving the stale test back.
