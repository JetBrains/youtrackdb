---
severity: medium
phase: phase-a
source-session: 2026-05-07 /execute-tracks unit-test-coverage
---

# Memory-mode `@BeforeClass` static fixtures abort the surefire JVM via direct-memory page tracker

## Symptom

Track 19's Phase A decomposition modeled the new
`CollectionBasedStorageConfigurationTest` on
`LocalPaginatedCollectionAbstract` — a class-static `@BeforeClass`
opens a `YouTrackDBImpl`, creates a DB, opens a session, holds the
storage in a static field, and `@AfterClass` drops/closes everything.

When the implementer adopted the precedent verbatim but switched
`DatabaseType.DISK` → `DatabaseType.MEMORY` (per the plan's
decomposition for `core.storage.config.CollectionBasedStorageConfiguration`),
the surefire JVM aborted before any test ran:

```
[INFO] Running com.jetbrains.youtrackdb.…CollectionBasedStorageConfigurationTest
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
[ERROR] The forked VM terminated without properly saying goodbye.
        VM crash or System.exit called?
[ERROR] Crashed tests:
[ERROR]   …CollectionBasedStorageConfigurationTest
```

Cause: the `core` module's surefire `argLine` carries
`-Dyoutrackdb.memory.directMemory.trackMode=true`. The page tracker
runs at JVM shutdown and calls `System.exit(1)` when it finds
unreleased direct-memory pages. With a class-static memory storage
holding live pages until the class's `@AfterClass`, those pages are
released *before* JVM shutdown — but the timing race against
shutdown hooks (and against any test that fails to release additional
pages) is fragile. In practice the abort fires during the
`testSetMinimumCollections…` run that takes 900 s due to the
`ScalableRWLock` self-deadlock in `setMinimumCollections`, but a
similar abort can fire on any class-static memory-DB test that
encounters a slow shutdown path.

The disk-mode precedent works because disk lifecycle releases pages
incrementally during normal storage operation; memory mode does not.
Phase A review did not flag this risk, and the planner did not flag
it either.

## Reproduction context

- Phase: phase-a (decomposition); first symptom in phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/track-review.md` § Step Decomposition
    (decomposer reuses precedent classes wholesale)
  - `.claude/workflow/risk-tagging.md` (the risk tag was `low —
    default` for "test-additive only", which is correct in source-code
    risk terms but misses the test-infrastructure risk)
  - `core/pom.xml:49` (`-Dyoutrackdb.memory.directMemory.trackMode=true`)
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: a Phase A decomposition step prescribes a new
  test that (a) holds storage references in static fields across the
  class's lifetime AND (b) creates the DB in `DatabaseType.MEMORY`. The
  combination is the trap; either factor alone is fine.

## Why it's a problem

Page-tracker aborts produce the most confusing surefire failure mode:
no test report, no exception, no hs_err file, no clear stack trace —
just "VM crash or System.exit called?" with `Tests run: 0`. The
debugging cost during this session was ~90 minutes (multiple test
re-runs, agent transcript reading, parallel-launch fallout, manual
restructure to per-method `@Before`/`@After`).

Track 19 has 4 more steps that touch memory-mode storage
(Step 2 explicitly: `MemoryFile`, `DirectMemoryOnlyDiskCache`,
`DirectMemoryStorage`). If those steps repeat the disk-precedent
pattern, the trap reappears. The episode for Step 1 includes a
cross-step warning, but a workflow-level rule would prevent the next
agent from re-deriving it.

## Proposed fix

Add a recipe / rule under `.claude/workflow/track-review.md`
(or as a Phase A "Common test-infrastructure pitfalls" subsection)
that flags the combination explicitly:

> **Memory-mode static-fixture trap.** When a step's decomposed test
> (a) creates a `DatabaseType.MEMORY` DB and (b) holds storage,
> session, or atomic-operations-manager references in `static` fields
> across multiple test methods, the YouTrackDB direct-memory page
> tracker (enabled by `-Dyoutrackdb.memory.directMemory.trackMode=true`
> in `core/pom.xml`) can abort the surefire JVM with `System.exit(1)`
> at shutdown. **Use per-method `@Before`/`@After` lifecycle for
> memory-mode tests** unless the test is shape-only (no DB).
> Disk-mode static-fixture tests (e.g., `LocalPaginatedCollectionAbstract`)
> are NOT a precedent for memory-mode tests — the lifecycle differs.

Complementary fix: the `risk-tagging.md` "Phase A decomposition"
section should include a checklist item asking
*"Does this test hold a memory-mode DB across methods? If yes, escalate
risk to medium and require per-method lifecycle."*

A reference cross-link from `track-review.md` to the new rule keeps
the cost low at decomposition time.

## Acceptance criteria

- `.claude/workflow/track-review.md` (or a sibling doc loaded by
  Phase A) carries the "Memory-mode static-fixture trap" subsection
  with the lifecycle prescription.
- A Phase A test-decomposition that prescribes a class-static memory-DB
  fixture is flagged either by the decomposer or by the technical
  review sub-agent.
- Regression check: grep
  `core/src/test -name "*Test.java" | xargs grep -l "DatabaseType.MEMORY"`
  shows no NEW class-static + memory-mode tests added after this
  rule lands. (Existing ones may keep working — the rule is for new
  decompositions.)
- Optional: add a brief note in
  `.claude/docs/architecture.md` (or wherever testing infrastructure
  is documented) explaining the page-tracker shutdown semantics so
  future authors understand the constraint.
