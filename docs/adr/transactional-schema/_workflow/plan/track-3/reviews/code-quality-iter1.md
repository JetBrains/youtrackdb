<!--MANIFEST
dimension: code-quality
track: 3
iteration: 1
range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: CQ1
    sev: should-fix
    anchor: "CQ1"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java:162-189"
    cert: n/a
    basis: "PSI find-usages: reresolvePropertyImpl has no production caller (only its own declaration + 3 test refs)"
  - id: CQ2
    sev: should-fix
    anchor: "CQ2"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutex.java:118-122"
    cert: n/a
    basis: "PSI + grep: isEngagedBy has zero production callers; the engage-order assert uses isWriteLockHeldByCurrentThread, so the Javadoc claim is stale"
  - id: CQ3
    sev: minor
    anchor: "CQ3"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java:162"
    cert: n/a
    basis: "awk line-length scan: signature is 106 chars and unwrapped, unlike its two sibling helpers that wrap the first parameter"
  - id: CQ4
    sev: minor
    anchor: "CQ4"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java:1-19"
    cert: n/a
    basis: "Read of raw header: license block carries 5 leading spaces per line, unlike every sibling file (MetadataWriteMutex.java, SchemaProxedResource.java align at column 2)"
  - id: CQ5
    sev: suggestion
    anchor: "CQ5"
    loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java:80-89"
    cert: n/a
    basis: "Diff read: getChangedClasses returns the live mutable backing set guarded only by a Javadoc caveat"
-->

## Findings

### CQ1 [should-fix] `reresolvePropertyImpl` is production code with no production caller

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java` (line 162-189)
- **Issue**: `reresolvePropertyImpl` is a 28-line `protected static` helper, but PSI find-usages shows its only references are its own declaration and three calls in `SchemaProxyRoutingTest`. No production write path uses it. The sibling write path that links a property's relatives, `SchemaPropertyProxy.setLinkedClass`, re-resolves the *class* argument through `reresolveClassImpl`, never a property impl. Its companion `reresolveClassImpl` (16 prod call sites) and `reresolveClassImplForRead` (2 prod call sites in `SchemaClassProxy`) are both genuinely wired in; `reresolvePropertyImpl` is the odd one out. It is exercised solely by a unit test that calls the static method directly, which gives the appearance of coverage without a real consumer.
- **Why it matters**: It reads as speculative scaffolding for a later track (Track 4/5 commit promotion), but unlike the other staged elements in this track it carries no marker saying so, and a reader cannot tell from the file whether it is dead or pending-wire-up. The track's other forward-staged pieces (`TxSchemaState.getChangedClasses`, the `txLocal`-deferred reconciliation) are documented as staged in their Javadoc and in the episodes.
- **Suggestion**: Either (a) land this helper with the track that first calls it from production, or (b) if it must live here now, add a one-line "staged for Track N — no production caller yet" note to its Javadoc so it matches the self-documenting convention the rest of the track follows, and so a future reader does not mistake the test-only usage for a real wiring. Confirm with the Track 4/5 plan which is correct; do not silently leave an unwired public-surface helper.

### CQ2 [should-fix] Stale Javadoc on `MetadataWriteMutex.isEngagedBy` — names a non-existent production usage

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/MetadataWriteMutex.java` (line 118-122)
- **Issue**: The Javadoc reads *"Whether `session` currently holds the permit. Used by the engage-order assertion and by tests; not part of the release protocol."* PSI and grep both confirm `isEngagedBy` has zero production callers — only its declaration and five `MetadataWriteMutexTest` references. The engage-order assertion lives in `DatabaseSessionEmbedded.engageMetadataWriteMutex` and asserts on `SchemaShared.isWriteLockHeldByCurrentThread()` / `IndexManagerEmbedded.isWriteLockHeldByCurrentThread()` — not on `isEngagedBy`. So the "Used by the engage-order assertion" clause is factually wrong.
- **Why it matters**: CLAUDE.md § Comments and Documentation makes keeping comments in sync a hard rule ("Stale or contradictory comments are worse than no comments"). A reader auditing the lock-order invariant would chase a usage that does not exist.
- **Suggestion**: Change the clause to "Used by tests" (drop the engage-order-assertion claim), or — if the intent was for the assertion to use it — that is a separate design question for the assertion author. The accurate statement today is test-only.

### CQ3 [minor] `reresolvePropertyImpl` signature exceeds 100 chars and is not wrapped like its siblings

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/SchemaProxedResource.java` (line 162)
- **Issue**: The signature line `@Nullable protected static SchemaPropertyImpl reresolvePropertyImpl(@Nonnull SchemaShared txLocalSchema,` is 106 characters — over the project's 100-char limit. The two sibling helpers in the same file (`reresolveClassImpl`, `reresolveClassImplForRead`) wrap the first parameter onto its own continuation line; this one keeps `@Nonnull SchemaShared txLocalSchema` on the signature line. Spotless tolerated it (the build is green), but it is inconsistent with the immediate neighbors and over the documented width cap.
- **Suggestion**: Wrap the first parameter onto the next line to match `reresolveClassImpl` / `reresolveClassImplForRead`, e.g. put `@Nonnull SchemaShared txLocalSchema, @Nullable SchemaPropertyImpl impl` on a continuation line. Run `./mvnw -pl core spotless:apply` afterward.

### CQ4 [minor] `TxSchemaState.java` license header is mis-indented relative to every sibling file

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 1-19)
- **Issue**: Every line of the Apache license block after the opening `/*` carries five leading spaces before the `*` (e.g. `     *  *  Licensed under...`). The two other new files in this track (`MetadataWriteMutex.java`, `SchemaProxedResource.java`) align the `*` at column 2, matching the codebase convention. This is purely cosmetic and Spotless does not reflow license headers, but it stands out as an obvious copy-paste artifact in a brand-new file.
- **Suggestion**: Re-indent the header to align `*` at column 2, matching the sibling new files.

### CQ5 [suggestion] `TxSchemaState.getChangedClasses` exposes the live mutable backing set

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/TxSchemaState.java` (line 80-89)
- **Issue**: `getChangedClasses()` returns the internal `changedClasses` set directly and guards mutation only with a Javadoc caveat ("callers must not mutate it outside `markClassChanged`"). The class otherwise enforces its invariant through the `markClassChanged` mutator. Today the only consumers are tests (PSI-confirmed), so the leak is harmless now, but the set is destined to drive commit-time per-class reconciliation in Track 4, where an accidental mutation by a consumer would corrupt the change set.
- **Why it matters**: Minor encapsulation smell on a field that will become correctness-critical at commit. The doc caveat is the only thing standing between a consumer and a silent corruption.
- **Suggestion**: Consider returning `Collections.unmodifiableSet(changedClasses)` (or a defensive copy) so the invariant is enforced by the compiler rather than by a comment. Optional; the doc caveat is acceptable for an internal holder if the team prefers the zero-allocation read.

## Evidence base

<!-- Evidence-trail-exempt dimension: no refutation or certificate phase to persist. -->
