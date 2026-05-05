# Track 13: Serialization — Binary

## Description

Write tests for the binary record serializer. Binary serialization
already has decent coverage (74.8%) but a large absolute gap (850
uncov) due to the codebase size.

Target packages:
- `core/serialization/serializer/record/binary` (850 uncov, 74.8% —
  re-measured 862 uncov on post-rebase HEAD, technical review)
- `core/serialization/serializer/binary/impl/index` (165 uncov,
  65.0%) — binary index serialization
- `common/serialization/types` (129 uncov, 84.3% — re-measure with
  post-string-track baseline; common/serialization is now 83.4%/62.9%
  after Step 1 inert-test repair)
- `core/serialization/serializer/binary` (21 uncov, 65.6%) — binary
  serializer root
- `core/serialization/serializer/binary/impl` (14 uncov, 90.8% —
  re-measured 9 uncov / 94.1% on post-rebase HEAD, technical review)

Focus on uncovered type-specific serialization paths, edge cases
in binary encoding (variable-length integers, null handling, embedded
document nesting), and index key serialization.

**Scope:** ~5 steps covering binary type paths, index serialization,
edge cases, common types, and verification (Phase A revised: 7 actual
steps — see §Steps; matches Track 7 / Track 8 / Track 12 precedent of
larger-than-scope-tag decomposition for serialization-stack tracks).
**Depends on:** Track 1

## Per-track strategy refinements (from Phase A reviews — apply across all steps)

These override or specialise the project-wide conventions for this track only.

1. **DbTestBase by default for V1 round-trip steps** (T4 / A2). Both
   `RecordSerializerBinary.{toStream,fromStream}` and `RecordSerializerBinaryV1`
   entry points take `@Nonnull DatabaseSessionEmbedded`; LINK-family writes
   call session APIs at `RecordSerializerBinaryV1.java:756/783/817/1142/1149`.
   Standalone tests are reserved for: `VarIntSerializer`, `BytesContainer`,
   `ReadBytesContainer`, `BinaryField`, `ReadBinaryField`, `HelperClasses`
   statics, `BinarySerializerFactory` rejection paths, and the
   `*DeadCodeTest` shape pins. **D2 itself is unchanged** project-wide —
   only its per-track default is inverted here, mirroring Track 8's
   precedent.

2. **Byte-shape pins paired with every round-trip pin** (R1). Round-trip
   identity alone is insufficient for a canonical disk/wire format — a
   round-trip can pass while the canonical bytes drift. Every round-trip
   step emits a captured-bytes sentinel (hex prefix or full canonical
   stream) alongside the deserialise-equality assertion so that a
   regression to the canonical encoding fails loudly rather than silently
   re-encoding into a new shape.

3. **Falsifiable WHEN-FIXED markers** for any latent bug pinned. Carry
   forward the convention from string-track Tracks 5–12: pin the buggy
   shape with `// WHEN-FIXED: Track 22` (or `// forwards-to: Track NN`
   when the right owner is downstream), and prefer falsifiable assertions
   over snapshot-byte comparisons that would silently re-pin wrong
   behavior.

4. **Dead-code via `*DeadCodeTest` shape pins, not deletions** (T2 / T3 /
   A1). Three classes are confirmed zero-caller dead surface in the scope
   packages and one is a sentinel placeholder mis-named "mock":
   - `SerializableWrapper` (zero callers project-wide)
   - `RecordSerializationDebug` and `RecordSerializationDebugProperty`
     (zero callers — POJOs)
   - `MockSerializer` (sentinel placeholder, every override no-op)
   - Plus `RecordSerializerNetwork` interface — concrete impl lives in
     `driver/` which is excluded by the plan's Non-Goals; pinned only as
     the shape and the Track 9 `CommandScriptDeadCodeTest:165` reference
     (do not duplicate that pin). Pin shapes via `*DeadCodeTest` per the
     string-track precedent; defer deletions to the deferred-cleanup
     track.

5. **`@After rollbackIfLeftOpen` safety net + `@Category(SequentialTest)`
   for static-state mutations** (carried forward from Tracks 5–12). The
   binary stack does not appear to mutate process-wide statics, but tests
   that toggle `RecordSerializerFactory` instances or call
   `RecordSerializerBinary.init()` should be `@Category(SequentialTest)`
   under suspicion of mutation.

6. **Re-measure baseline against post-rebase HEAD** before reporting
   step-level deltas (R3 / T1). The plan's pre-track baseline numbers
   pre-date the string-track inert-test repair which corrected
   `common/serialization` from inflated 34.5%/27.1% to actual 82.1%/61.4%
   → 83.4%/62.9% after the string-track. Phase B steps must measure
   against post-string-track live coverage, not the original plan
   numbers — the Description above pins the re-measured values.

7. **Verification step** runs `./mvnw -pl core -am clean package -P
   coverage` with `coverage-analyzer.py` against each scope package
   individually + the changed-line gate for diff-based CI compatibility.
   Step 7 also runs the full `./mvnw -pl core clean test` to confirm no
   surefire regressions and updates Track 22's deferred-cleanup queue
   with this track's deletions / residual gaps / DRY items.

## Rebase record (constraint 11)

- Pre-rebase HEAD: `b993a064f6dae04d8e6d001c703c83e399e72ca0`
- Post-rebase HEAD: `fe26cc1867e6b8d71f53737052160936100ef239`
- Develop commits replayed on top: `44cf982894` (Bump io.youtrackdb:gremlin-core
  to `3.8.1-af9db90-SNAPSHOT` — single-line `pom.xml` version property change).
- Spotless: clean (`./mvnw -pl core spotless:apply` — 0 files changed,
  cache hit on most files).
- Unit-test suite: PASS (`./mvnw -pl core clean test`, 8m09s,
  14,872 tests, 0 failures / 0 errors / 56 skipped — surefire 13,138
  default + 1,723 sequential + 11 trailing). Constraint 11 step 3
  satisfied.

## Base commit

`fe26cc1867e6b8d71f53737052160936100ef239`

## Progress

- [x] Review + decomposition
- [x] Step implementation (7/7 complete; Step 7 closed at iter-1 (CQ/BC/TB/TC/TS) with 4 should-fix items + 2 opportunistic suggestion upgrades applied in `Review fix:` commit `d24546d32a` — no iter-2 gate run because all should-fix items were addressed in a single iter-1 fix sweep, matching Step 6 precedent)
- [ ] Track-level code review

## Reviews completed

- [x] Technical (iter-1) — `reviews/track-13-technical.md` — 0 blockers /
  8 should-fix / 8 suggestions. Top items: T1 scope-tag too small
  (recommend 6-8), T4 D2 inverted for record-level round-trips
  (DbTestBase required), T2/T3 three dead-code classes pinned not
  targeted, per-class concentration concentrated in
  `RecordSerializerBinaryV1` (60.6% / 408 uncov / ~47% of package gap).
  Live JaCoCo on post-rebase HEAD: `binary/impl` is 94.1%/9 uncov;
  `record/binary` is 862 uncov.
- [x] Risk (iter-1) — `reviews/track-13-risk.md` — 0 blockers /
  2 should-fix / 7 suggestions. Overall residual risk LOW-MEDIUM. Top
  items: R1 byte-shape pins paired with round-trips, R2 dedicated step
  for `CompositeKeySerializer` (581 LOC) + `IndexMultiValuKeySerializer`
  (505 LOC) — zero direct tests today, HIGH blast radius (SBTree disk
  format). No inert-test hazard: every test method in 28 in-scope test
  files carries `@Test`.
- [x] Adversarial (iter-1) — `reviews/track-13-adversarial.md` — 0
  blockers / 4 should-fix / 3 suggestions. Track survives core thesis.
  Top items: A1 dead-surface ≥310 LOC needs dedicated step (overlaps
  T2/T3), A2 D2 must invert (overlaps T4), A3 scope undersized
  (overlaps T1) — `EntitySerializerDelta` (1,472 LOC),
  `BinaryComparatorV0` (1,332 LOC), `InPlaceComparator` (789 LOC) all
  unmentioned in the original description. V0 vs V1 interop confirmed
  moot (only V1 registers in slot 0; `BinaryComparatorV0` is a name
  leftover).

**Iter-2 not run** — 0 blockers across all three iter-1 reviews. The
should-fix findings are decomposition-time guidance (scope size,
DbTestBase default, dead-code pins, byte-shape pins, dedicated index
step) rather than code "fix and re-verify"; they are absorbed into the
strategy refinements above and the step decomposition below.

## Steps

- [x] Step 1: Dead-code pins + standalone utilities + factory
  - [x] Context: warning
  > **Risk:** low — tests-only (11 new standalone test files; no production
  > code modified; all dead-code pinning via `*DeadCodeTest` shape contracts).
  >
  > **What was done:** Landed 11 new test files (~2,300 LOC) covering the
  > binary serializer utility surface and pinning four dead-code candidates.
  > Code commit `d08bd33fac`; review-fix commit `3e19cfd0fa`. Surefire on
  > the targeted run: 145 tests, 0 failures, 0 errors, 0 skipped. Files:
  > - Dead-code shape pins (WHEN-FIXED → deferred-cleanup track):
  >   `SerializableWrapperDeadCodeTest`, `RecordSerializationDebugDeadCodeTest`,
  >   `RecordSerializationDebugPropertyDeadCodeTest`, `MockSerializerDeadCodeTest`.
  > - Live utility coverage: `VarIntSerializerTest` (DataInput/DataOutput
  >   overloads, sign-flip boundaries, 9-byte vs 10-byte overflow guard,
  >   canonical byte-shape pins), `BytesContainerTest` (alloc/allocExact/
  >   skip/copy/fitBytes), `BinaryFieldTest` + `ReadBinaryFieldTest` (POJO
  >   shape + record component pins), `HelperClassesStandaloneTest`
  >   (string/bytes round-trip with multibyte and surrogate pairs, type-
  >   from-value, day-to-timezone, BytesContainer read/write helpers,
  >   Tuple inner class).
  > - Factory tests: `BinarySerializerFactoryTest` (format-version
  >   constant, create() rejection paths, full registered-serializer set
  >   pinned including the six type=null id-only registrations,
  >   duplicate-id rejection, per-call independence) and
  >   `RecordSerializerBinaryVersionByteAsymmetryTest` (asymmetric
  >   error-handling between byte[] and ReadBytesContainer overloads,
  >   number-of-supported-versions, ctor variants).
  >
  > **What was discovered:**
  > - **Latent production hang (BytesContainer)**: `new BytesContainer(new
  >   byte[0])` followed by `c.alloc(N)` for any positive N hangs the JVM
  >   indefinitely. The `resize()` loop multiplies `newLength` (initially
  >   0) by 2 and never reaches `offset > 0`. Reachable via the public
  >   byte-array constructor; not pinned in this step due to context
  >   budget — pinned for follow-up via `@Test(timeout=…)`.
  > - **`SerializableWrapper.fromStream` security gap**: invokes
  >   `ObjectInputStream.readObject()` with no `ObjectInputFilter`, no
  >   class allow-list, no length cap. Test class Javadoc updated with
  >   an explicit security note. Deferred-cleanup deletion is therefore
  >   a posture upgrade, not just dead-code cleanup.
  > - **Asymmetric version-byte handling in `RecordSerializerBinary`**:
  >   the `byte[]` overload at `RecordSerializerBinary.fromStream(byte[])`
  >   does an unguarded `serializerByVersion[iSource[0]]` array index,
  >   yielding un-decorated `ArrayIndexOutOfBoundsException` for OOB
  >   leading bytes — pairs with a Base64-of-input WARN log path that
  >   amplifies log-injection of attacker-controlled bytes. The
  >   `ReadBytesContainer` overload validates and throws typed
  >   `IllegalArgumentException`. Pinned with WHEN-FIXED + security note.
  > - **`BinarySerializerFactory.create()` registers a fresh
  >   `new NullSerializer()`** rather than the `NullSerializer.INSTANCE`
  >   singleton — every other registered serializer uses its `INSTANCE`.
  >   Pinned today as `assertNotSame`-style anomaly; WHEN-FIXED flips to
  >   `assertSame` once the factory is harmonized.
  > - **`MockSerializer.preprocess` returns null instead of input** —
  >   sentinel-shape divergence from the conventional contract; pinned
  >   as a WHEN-FIXED for the deferred-cleanup track.
  > - **Six dead/semi-dead classes confirmed in scope** (zero callers in
  >   `core/src/main/java`): `SerializableWrapper`,
  >   `RecordSerializationDebug`, `RecordSerializationDebugProperty`,
  >   `MockSerializer` (sentinel — needs lockstep deletion of factory
  >   registration). The two `RecordSerializationDebug*` classes carry a
  >   typo `faildToRead` (sic) that the cleanup track should fix at
  >   deletion time.
  >
  > **What changed from the plan:** No deviations from the plan's Step 1
  > scope. Step LOC came in at ~2,300 (plan estimate ~1,500-1,800);
  > overshoot driven by the `IdStub` test-double in
  > `BinarySerializerFactoryTest` (~100 LOC of throwing overrides for
  > the `BinarySerializer` interface). Test-structure review flagged
  > this as a candidate for a `ThrowingStubSerializer<T>` abstract base
  > or Mockito-based replacement — added to the deferred-cleanup queue
  > below.
  >
  > **Findings deferred to a future session** (review iter 1 raised
  > these; tier-2/3 items not blocking the step):
  > - **TC1**: `VarIntSerializer.readAsInt` long-truncation pin (writes
  >   `(long)Integer.MAX_VALUE+1L` and reads back via `readAsInt` to
  >   pin silent narrowing).
  > - **TC2**: `BytesContainer.alloc(-N)` boundary pin.
  > - **TC3**: `BytesContainer` zero-length-array hang pin via
  >   `@Test(timeout=2_000)`.
  > - **TC5**: `HelperClasses.bytesFromString(null)` NPE pin and
  >   embedded-NUL-byte round-trip pin.
  > - **TC6**: `HelperClasses.convertDayToTimezone` DST spring-forward
  >   pin (e.g., 2025-03-09 in `America/New_York`).
  > - **TC8**: actually exercise the byte[]-overload AIOOBE path in
  >   `RecordSerializerBinary.fromStream` with a Blob (or null
  >   EntityImpl since the AIOOBE fires before the cast).
  > - **TB1**: tighten `MockSerializer.preprocess` test to pass a
  >   non-null input via Mockito-mocked `EntityImpl` so the assertion
  >   becomes falsifiable against a "return value;" mutation.
  > - **TC4-extension**: `BinarySerializerFactory.registerSerializer`
  >   null-instance NPE pin.
  > - **TC11/TC12/TC13/TC14/TC15**: BinaryField null-bytes NPE,
  >   Integer.MAX_VALUE VarInt byte-length pin, MockSerializer.INSTANCE
  >   mutability observability pin, SerializableWrapper non-determinism
  >   pin for HashMap, HelperClasses.writeOType OOB pin.
  > - **TS1/TS2/TS3/TS5**: `assertCanonicalBytes` helper extraction,
  >   `assertPublicMutableInstanceFields` reflection helper,
  >   `IdStub` -> Mockito or abstract base, split
  >   `allObjectSizeOverridesReturnZero` into per-overload methods.
  > - **TS9**: rename `RecordSerializerBinaryVersionByteAsymmetryTest`
  >   to host non-asymmetry pins or move them out (later steps will
  >   add a broader `RecordSerializerBinarySmokeTest`).
  >
  > **Key files:**
  > - (new) `core/src/test/java/.../serialization/serializer/binary/BinarySerializerFactoryTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/binary/MockSerializerDeadCodeTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/BinaryFieldTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/BytesContainerTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/HelperClassesStandaloneTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/ReadBinaryFieldTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/RecordSerializationDebugDeadCodeTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/RecordSerializationDebugPropertyDeadCodeTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/RecordSerializerBinaryVersionByteAsymmetryTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/SerializableWrapperDeadCodeTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/VarIntSerializerTest.java`
  >
  > **Critical context:** Cross-track impact: minimal. All discoveries
  > are localized to the binary serializer utility surface and pinned
  > with WHEN-FIXED markers for the deferred-cleanup track. No impact
  > on Tracks 14-22. The deferred review findings (tier-2/3 above) are
  > carried forward to the track-level Phase C review or to the next
  > Phase B session — context dropped to `warning` level after iter-1
  > review-fix commit, ending the session before iter-2 could run.
  - Test files (mostly new, some extending existing):
    - `*DeadCodeTest` shape pins for `SerializableWrapper`,
      `RecordSerializationDebug`, `RecordSerializationDebugProperty`,
      `MockSerializer`. `RecordSerializerNetwork` interface shape is
      pinned only via reference to the existing `CommandScriptDeadCodeTest`
      pin from the command-script-track (do not duplicate).
    - `VarIntSerializerTest` (signed/unsigned encode→decode, sign-flip
      boundaries `Long.MIN_VALUE` / `-1L` / `0L` / `1L` /
      `Long.MAX_VALUE`, 9-byte overflow guard, 10-byte malformed-VarInt
      rejection, byte-shape pins for canonical encodings).
    - `BytesContainerTest` and `ReadBytesContainerTest` (alloc/grow/move/
      skip/offset accounting, boundary alloc sizes, negative offset
      rejection).
    - `BinaryFieldTest` and `ReadBinaryFieldTest` (POJO accessors,
      equals/hashCode, null-name handling).
    - `HelperClassesTest` (static helper round-trips for any standalone-
      reachable helper).
    - `BinarySerializerFactoryTest` (rejection paths: unsupported binary
      format version 14, duplicate-id rejection, version-byte-OOB
      asymmetry between two `fromStream` overloads at
      `RecordSerializerBinary.java:101` vs `:124` — A4).
  - Coverage delta target: `record/binary` +5-10pp (utility + dead-code
    pin contributions), `binary` 65.6 → 90%+, `binary/impl` 94.1 → 100%.
  - LOC estimate: ~1500-1800.
  - Decision Records exercised: D2 (standalone preferred where session
    is not required — applies to this step).

- [x] Step 2: V1 schemaless property round-trip — simple types
  - [x] Context: warning
  > **Risk:** low — tests-only (single new test file `RecordSerializerBinary
  > V1SimpleTypeRoundTripTest`, ~922 LOC, no production code modified).
  >
  > **What was done:** Landed `RecordSerializerBinaryV1SimpleTypeRoundTripTest`
  > (~922 LOC, 73 tests). Two-tier discipline: tier-1 directly drives
  > `serializeValue`/`deserializeValue` against a fresh `BytesContainer`
  > and pins the canonical hex byte encoding for every scalar
  > `PropertyTypeInternal`; tier-2 round-trips representative values
  > through `RecordSerializerBinary.INSTANCE` to confirm record header
  > + dispatch consistency end-to-end. Code commit `5ad9513a77`;
  > review-fix commit `e003ca876e`. Surefire on the targeted run:
  > 73 tests, 0 failures, 0 errors, 0 skipped.
  >
  > Tier-1 pins (with hex sentinel + round-trip equality):
  > - BYTE: 0, 7, MIN_VALUE, MAX_VALUE
  > - BOOLEAN: true, false
  > - SHORT: 0, ±1, MIN, MAX
  > - INTEGER: 0, -1, 1234, 3-byte boundary (8192), 4-byte boundary
  >   (1 048 576), MIN, MAX
  > - LONG: 0, MIN, MAX
  > - FLOAT: 0, 1.5, -0.0, NaN, +∞, -∞, MIN_VALUE (subnormal), MAX_VALUE
  > - DOUBLE: same matrix as FLOAT
  > - DECIMAL: 0, 1, -1, scale-0/4-byte unscaled (0x7FFFFFFF), scale-1
  >   "1.5", high-precision (16-byte unscaled, scale=18), large negative
  >   (14-byte unscaled, sign-bit asserted, scale=1)
  > - STRING: empty, ASCII, multibyte (Cyrillic), surrogate-pair emoji,
  >   embedded NUL (`"a b"` — Unicode escape, no literal NUL)
  > - BINARY: empty, single byte, all 256 byte values, 3-byte varint
  >   length boundary (20 000 bytes)
  > - DATETIME: epoch, ±1 ms, raw Number value, 1.7e12 ms
  > - DATE: epoch, +1 day, -1 day, raw Number value
  >
  > Tier-2 pins:
  > - all-scalar-types-in-one-record (12 properties, schemaless)
  > - empty record (2 bytes — version 0x00 + header-length varint(0))
  > - 3-property name/order preservation
  > - 1024-char and 20 000-char strings (multi-byte varint length pins)
  > - UTF-8 round-trip with Cyrillic + emoji + Korean
  > - `deserializePartial` with: requested existing field, requested
  >   unknown field, two-of-three early break, over-request beyond
  >   record size
  > - null property full + partial round-trip (header tombstone branch)
  >
  > **What was discovered:**
  > - **Storage timezone defaults to JVM default, NOT GMT.** A naive
  >   reading of `DateHelper.getDatabaseTimeZone(session)` suggests
  >   GMT for a fresh in-memory database, but the actual fallback is
  >   `TimeZone.getDefault()`. CI workers typically run in CET (UTC+1)
  >   while developer machines are often GMT, producing a 3 600 000 ms
  >   shift in the encoded DATE value that breaks any byte-shape pin
  >   not explicitly setting the storage timezone. Fixed via
  >   `session.set(ATTRIBUTES.TIMEZONE, "GMT")` in `@Before`. This
  >   matters for any later track that pins DATE bytes — particularly
  >   Track 14 / Track 15 if they pin DATE round-trips.
  > - **`VarIntSerializer.write` zig-zag-encodes BEFORE varint encoding,
  >   even for length prefixes** at `writeBinary`/`writeString` call
  >   sites. Initial expectation that BINARY of length 20 000 would
  >   produce varint(20 000) = `0xA0 0x9C 0x01` was wrong; actual is
  >   varint(zigzag(20 000)) = varint(40 000) = `0xC0 0xB8 0x02`.
  >   Same applies to STRING length prefixes. This is the convention
  >   for the entire V1 wire format and any future negative-length
  >   guard tests must zig-zag-encode their oversized input the same
  >   way `RecordSerializerBinaryV1GuardTest` does.
  > - **Record header layout for top-level entities is 2 bytes minimum,
  >   not 3.** `serialize(...)` (not `serializeWithClassName`) is what
  >   `toStream` invokes for top-level records — it skips the empty-
  >   class-name varint that embedded entities write, so the layout
  >   is `[version_byte, header_length_varint, ...values]`. The
  >   3-byte expectation in the initial draft of the empty-record
  >   test was wrong; actual is 2 bytes (version 0x00 + header-length
  >   varint(0) = 0x00).
  > - **`BytesContainer` overload of `deserializeValue` lacks length
  >   validation that its `ReadBytesContainer` sibling has** (Step 1
  >   already pinned the asymmetric version-byte handling at a higher
  >   level; this step's helper machinery surfaces the parallel
  >   length-validation gap inside the value dispatch itself).
  >   Production callers that feed attacker-controlled bytes through
  >   the `BytesContainer` path include `EntitySerializerDelta.deserialize`
  >   and `EntityImpl.deserializeFieldForComparison`. STRING varint
  >   length up to `Integer.MAX_VALUE`, BINARY varint length up to
  >   `Integer.MAX_VALUE`, and DECIMAL `unscaledLen` field all flow
  >   into `new String(bytes, off, len, UTF-8)` / `new byte[n]` /
  >   `Arrays.copyOfRange` without bounds checks. Pinned via
  >   WHEN-FIXED note in the test class Javadoc; production fix lives
  >   with `RecordSerializerBinaryV1.deserializeValue(BytesContainer ...)`
  >   harmonization (deferred to Track 22 cleanup queue or a follow-up
  >   step in this track).
  >
  > **What changed from the plan:** Step LOC came in at ~922
  > (plan estimate ~1500-2000). Smaller because the two-tier approach
  > tightly factored value-level pins from record-level round-trips:
  > the bulk of "pin every type with edge cases" lives as one-line
  > `pinValueEncoding` calls rather than session.begin/round-trip
  > scaffolding repeated per case. Iter-1 review across 6 dimensions
  > (CQ, BC, TB, TC, SE, TS) produced 0 blockers / 16 should-fix /
  > 21 suggestions; all 16 should-fix items addressed in the review-fix
  > commit. Iter-2 not run — context dropped to `warning` after the
  > review-fix commit, ending the session before iter-2 could run
  > (Step 1 precedent). Deferred suggestions and the un-hardened-
  > `BytesContainer`-overload pin carry forward to Phase C track-level
  > code review.
  >
  > **Findings deferred to Phase C** (iter-1 raised these as
  > suggestions; not blocking the step):
  > - **CQ3**: rephrase comment for INTEGER MIN_VALUE varint encoding
  >   to explicitly note the unsigned-32-bit interpretation rather
  >   than the redundant `(long) 0xFFFFFFFFL` cast.
  > - **CQ4**: extract `private EntityImpl roundTripFullRecord(EntityImpl)`
  >   helper for tier-2 boilerplate (six occurrences).
  > - **CQ7**: replace `binaryAllByteValuesRoundTripsExactly` two-tier
  >   header+per-byte+round-trip assertion with a single hex-equality
  >   regression sentinel (`"8004" + HEX.formatHex(raw)`).
  > - **TB3**: document or handle DATE day-floor truncation in
  >   `pinValueEncoding` for sub-day input millis (latent — current
  >   callers all pass whole-day millis).
  > - **TB4**: add a tier-2 NaN/-0 round-trip through `setFloat`/
  >   `setDouble` to confirm bit-exact transit through the record path
  >   (tier-1 pins NaN/-0 at value level only).
  > - **TB5**: drop redundant `assertEquals(0, value.compareTo(decoded))`
  >   tail assertion now that hex pin is in place (already done as
  >   part of TB1 fix; verify in Phase C).
  > - **TS4**: extract a shared `assertCanonicalBytes(String, byte[])`
  >   helper to a `binary/BinaryPinAssertions` utility class (carry
  >   forward to Track 22 / coordinate with Step 1's TS1 deferral).
  > - **TS5/TS6**: stylistic — split `@Before initSerializer` into
  >   serializer-init + timezone-pin methods or rename for clarity.
  > - **TS7**: comment in `pinValueEncoding` documenting why null
  >   value is rejected (already documented after iter-1 fix).
  > - **TC7-TC11**: additional edge cases worth pinning (decimal
  >   scale variants beyond what's already added, datetime far-future,
  >   STRING lone unpaired surrogate, SHORT 2-byte boundary).
  > - **SE2**: pin malformed-UTF-8 substitution behavior of
  >   `HelperClasses.readString` to lock in the wire-format invariant
  >   (today's behavior is `?` substitution; future tightening would
  >   throw — pinning lets Track 22 detect either drift).
  > - **BC4**: wrap session.begin/rollback bodies in try/finally so
  >   assertion failures don't obscure the JUnit failure with a
  >   "transaction still active" warning during teardown.
  > - **BC5**: document the deliberate split between fresh-instance
  >   `RecordSerializerBinaryV1` (tier-1) vs `RecordSerializerBinary.INSTANCE`
  >   singleton (tier-2).
  >
  > **Key files:**
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/RecordSerializerBinaryV1SimpleTypeRoundTripTest.java` — 922 LOC, 73 tests
  >
  > **Critical context:** Cross-track impact: minimal-to-low.
  > Discoveries are localised to V1 binary serializer (Track 13
  > scope). The two convention findings — JVM-default-vs-GMT timezone
  > and zig-zag-encoded length prefixes — are worth carrying forward
  > to Step 3 (collections / embedded entities) since both apply
  > directly to embedded-collection length prefixes and any
  > collection-element DATE pin. The `BytesContainer`-overload
  > security gap (un-validated lengths) is a candidate WHEN-FIXED for
  > the deferred-cleanup track but does not block downstream tracks.
  > Iter-2 deferred items (16 should-fix already addressed; 13
  > suggestions outstanding) carry to Phase C track-level code review.

- [x] Step 3: V1 collections, embedded entities, links
  - [x] Context: warning
  > **Risk:** low — tests-only (single new test file `RecordSerializerBinary
  > V1CollectionRoundTripTest`, ~1306 LOC, no production code modified).
  >
  > **What was done:** Landed `RecordSerializerBinaryV1CollectionRoundTripTest`
  > (~1306 LOC, 49 tests). Two-tier discipline mirrors Step 2: tier-1
  > drives `serializeValue`/`deserializeValue` against fresh
  > `BytesContainer`s with full-hex byte-shape pins for LINK, LINKLIST,
  > LINKMAP, EMBEDDEDLIST (default + STRING linkedType), EMBEDDEDSET,
  > EMBEDDEDMAP, and LINKBAG (empty + single-entry); tier-2 round-trips
  > all nine collection / link types end-to-end through
  > `RecordSerializerBinary.toStream`/`fromStream` against an abstract
  > embedded-class schema and a persistent-peer class for link targets.
  > Code commit `28da1ee57b`; review-fix commit `045894c14b`. Surefire
  > on the targeted run: 49 tests, 0 failures, 0 errors, 0 skipped.
  >
  > Tier-1 pins (with hex sentinel + round-trip equality where reachable):
  > - LINK: `1400` for #10:0; `00a413` for #0:1234 (multi-byte position).
  > - LINKLIST: `0000` empty; `000414001402` two elements at #10:0/#10:1;
  >   forged `0100` rejected (non-zero leading byte → SerializationException).
  > - LINKMAP: `0000` empty; `000202611400` for {"a"->#10:0}; forged
  >   `0100` rejected (non-zero version → SerializationException).
  > - EMBEDDEDLIST: `0009` empty (default-EMBEDDED linkedType); `0007`
  >   empty STRING-linkedType; `0207070278` single-string STRING-linkedType.
  > - EMBEDDEDSET: `0009` empty (shares writeEmbeddedCollection with LIST).
  > - EMBEDDEDMAP: `00` empty; null-key write rejected
  >   (`SerializationException("Maps with null keys are not supported")`).
  > - LINKBAG: `010000` empty (config 0x01 + size 0x00 + terminator 0x00);
  >   single-entry pin asserts the leading prelude (`01 02 01`) AND the
  >   trailing terminator byte (middle bytes opaque due to the change-
  >   tracker's secondary-RID allocation).
  >
  > Tier-2 pins:
  > - EMBEDDED: round-trip preserves all properties + isEmbedded() flag,
  >   no-property embedded round-trips as still-embedded empty entity.
  > - EMBEDDEDLIST/SET/MAP: empty + populated round-trips for strings,
  >   integers, booleans, doubles; mixed-type schemaless EMBEDDEDLIST
  >   inferring per-element type bytes; size-prefix varint boundary
  >   round-trip at sizes 63/64/65/200 (1-byte → 2-byte zigzag transition).
  > - LINK/LINKLIST/SET/MAP: round-trip preserves identity, ordered
  >   identity for LIST, set/map identity for SET/MAP; empty round-trips
  >   for all three.
  > - LINKBAG: empty + 3-element round-trips with identity preservation.
  >
  > Edge cases / negative pins:
  > - All six empty typed embedded/link containers round-trip in one
  >   record (binary analog of string-track TC21).
  > - Three-level nested embedded entity (`Thing.address ↦ Address.nested
  >   ↦ Address.deepest`) preserves all three levels.
  > - EMBEDDEDLIST with null mid-element preserves null and order.
  > - EMBEDDEDSET with null member preserves null (mirrors LIST tombstone
  >   branch).
  > - EMBEDDEDMAP null value preserves null via `-1` type tombstone.
  > - LINKLIST with mixed RID + loaded-Entity input round-trips
  >   identities.
  > - EMBEDDEDLIST of embedded entities preserves per-entity properties +
  >   isEmbedded() flag.
  > - Non-persistent LINK rejected at both tier-1 (value-level) and
  >   tier-2 (full-record) with `IllegalStateException("Non-persistent
  >   link …")` — message fragment asserted at both layers.
  > - Non-Identifiable LINK value rejected with `ValidationException`.
  > - LINKLIST forged-bytes pin: NULL_RECORD_ID sentinel (cluster -2,
  >   position -1) currently throws `SchemaException("Cannot add a
  >   non-identifiable …")` from `LinkTrackedMultiValue.checkValue`
  >   because `EntityLinkListImpl.addInternal(null)` rejects null while
  >   `HelperClasses.readLinkCollection` claims to substitute null for
  >   the sentinel — the null-conversion branch is dead-on-arrival.
  >   Pinned with WHEN-FIXED forwarding to Track 22.
  >
  > **What was discovered:**
  > - **NULL_RECORD_ID null-conversion branch is dead.** `readLinkCollection`
  >   has an `if (id.equals(NULL_RECORD_ID)) found.addInternal(null)`
  >   branch (HelperClasses.java:408), but `EntityLinkListImpl.addInternal`
  >   routes through `LinkTrackedMultiValue.checkValue` (line 17) which
  >   rejects `null` with `SchemaException("Cannot add a non-identifiable
  >   entity to a link based data container")`. So the null-substitution
  >   path always throws — the branch is unreachable from any input,
  >   well-formed or forged. Either the branch should be removed, or
  >   `checkValue` should permit `null` so legacy-byte streams carrying
  >   the sentinel survive. Forwarded to Track 22 deferred-cleanup queue
  >   with the corresponding LINKMAP analog at HelperClasses.java:457.
  > - **Cluster id -2 is the NULL_RECORD_ID sentinel, not -1.**
  >   `HelperClasses.NULL_RECORD_ID = new RecordId(-2,
  >   RID.COLLECTION_POS_INVALID)`. Initial test draft used cluster -1
  >   which produced a RecordNotFoundException (refreshRid attempted to
  >   load #-1:-1) rather than reaching the null-substitution branch.
  >   Worth carrying forward to any later test that forges link bytes —
  >   the readOptimizedLink overload taking `BytesContainer` calls
  >   `session.refreshRid` for non-persistent RIDs which loads the rid;
  >   the readOptimizedLink overload taking `(BytesContainer, boolean)`
  >   does NOT call refreshRid. The null-conversion path uses the latter
  >   (justRunThrough=false branch).
  > - **EMBEDDEDLIST/SET/MAP setProperty path needs newEmbeddedXxx
  >   wrapping.** `entity.setProperty("items", List.of(), EMBEDDEDLIST)`
  >   rejects with `IllegalArgumentException("Data containers have to be
  >   created using appropriate getOrCreateXxx methods")` because
  >   `EntityImpl.setProperty` validates that embedded-collection values
  >   come pre-wrapped as `EntityEmbeddedListImpl`/`SetImpl`/`MapImpl`.
  >   Tests must use `entity.newEmbeddedList(name, source)` /
  >   `newEmbeddedSet(name, source)` / `newEmbeddedMap(name, source)`
  >   which allocate the proper wrapper internally. The same pattern
  >   applies to LINK collections via `newLinkList`/`newLinkSet`/
  >   `newLinkMap`. Carry forward to Track 14 / Track 15 if those tests
  >   touch embedded or link collections.
  > - **EntityLinkListImpl etc. require non-null owner.** Tier-1
  >   deserialise of LINKLIST / LINKMAP fails with NPE on null owner
  >   because the read path constructs `new EntityLinkListImpl(owner)`
  >   unconditionally and the constructor calls `sourceRecord.getSession()`.
  >   Tests must wrap tier-1 collection-deserialise calls in `runInTx`
  >   and pass a fresh `(EntityImpl) session.newEntity(THING_CLASS)` as
  >   owner. EntityEmbeddedListImpl/SetImpl/MapImpl accept @Nullable
  >   owner so the equivalent embedded path works with null owner.
  >
  > **What changed from the plan:** Step LOC came in at ~1306 (plan
  > estimate ~1500-2000) — close to the lower bound. Coverage delta
  > target was +15-20pp on `record/binary`; not measured per-step (Step
  > 7 verification will run the package coverage analyzer). Iter-1
  > review across 6 dimensions (CQ, BC, TB, TC, SE, TS) produced 0
  > blockers / 15 should-fix / 28 suggestions. All 12 actionable
  > should-fix items addressed in the review-fix commit (3 SE
  > should-fix items forwarded to Track 22 — pinning their WHEN-FIXED
  > test scaffolding was out of Step 3 scope, see "Findings deferred"
  > below). Iter-2 not run — context dropped to `warning` after the
  > review-fix commit (Step 2 precedent: same outcome, same reason).
  > 49 tests passing, 4 added vs the 45 in the iter-1 commit.
  >
  > **Findings deferred** (carry to Phase C track-level code review or
  > Track 22):
  > - **SE1 (deferred to Track 22)**: insecure deserialization via
  >   `Class.forName(className).newInstance()` in
  >   `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument` — class
  >   name is read from attacker-controlled bytes and instantiated
  >   before the EntitySerializable cast, allowing side-effecting
  >   constructors of arbitrary public no-arg classes on the classpath.
  >   Fix: gate on `EntitySerializable.class.isAssignableFrom(clazz)`
  >   before `newInstance()`, ideally with an allow-list. Adding a
  >   WHEN-FIXED scaffold test was out of Step 3 scope (Step 3's plan
  >   pins collection round-trips, not insecure-deserialization
  >   contracts).
  > - **SE2 (already pinned in Step 2 episode)**: `BytesContainer`
  >   overload of `deserializeValue` lacks length-prefix validation
  >   parity with `ReadBytesContainer`. Step 2's class Javadoc carries
  >   the WHEN-FIXED note. Step 3 routes through the same overload but
  >   adds no fresh pin — same Track 22 forwarding.
  > - **SE3 (deferred to Track 22)**: no recursion depth limit on
  >   embedded entity deserialization. Three-level nesting is pinned;
  >   unbounded depth from attacker bytes would StackOverflowError.
  > - **CQ2/CQ3/CQ5/CQ6**: cosmetic style suggestions (DRY helper for
  >   identitiesOf, ALL_FIELDS constant, Javadoc opener style).
  > - **TB6/TB8/TB10**: runtime-type pins for collection wrapper classes
  >   (e.g. `assertTrue(got instanceof EntityLinkListImpl)`); a
  >   `PropertyType.STRING.getId() == 7` precondition assertion to
  >   catch a future renumbering.
  > - **TC5/TC6/TC7/TC8**: mixed-type EMBEDDEDSET, multi-byte cluster
  >   varint LINK pin, integer-key EMBEDDEDMAP coercion test, LINKMAP
  >   forged-bytes null-value pin.
  > - **TS2/TS3/TS5/TS7/TS8/TS9**: minor Javadoc tightening, helper
  >   ordering contracts, mass-empty `assertNotNull`/binding cleanup.
  > - **BC4**: confirm `new String[] {}` semantics in
  >   `RecordSerializerBinary.fromStream` — empty = "all fields" or
  >   "no fields"? The 49 tier-2 tests passing with populated values
  >   confirm "all fields" empirically; an inline comment citing the
  >   fromStream branch would tighten this.
  >
  > **Key files:**
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/RecordSerializerBinaryV1CollectionRoundTripTest.java` — 1306 LOC, 49 tests
  >
  > **Critical context:** Cross-track impact: minimal-to-low.
  > Discoveries are localised to V1 binary serializer (Track 13 scope)
  > with three Track 22 forwardings. The dead null-conversion branch
  > (HelperClasses.readLinkCollection / readLinkMap) is the only
  > production inconsistency surfaced by Step 3 — pinned via WHEN-FIXED
  > so a Track 22 cleanup that drops the branch OR fixes
  > LinkTrackedMultiValue.checkValue produces a loud failure here.
  > The newEmbeddedList/Set/Map vs setProperty(EMBEDDED*) discovery
  > applies to Track 14/15 if those touch embedded collections; the
  > non-null owner discovery applies to any future test that
  > deserialises link collections via the value-dispatch (tier-1)
  > path. Iter-2 deferred items (12 should-fix already addressed; 3
  > SE forwardings + ~25 cosmetic suggestions outstanding) carry to
  > Phase C track-level code review.

- [x] Step 4: EntitySerializerDelta round-trip
  - [x] Context: warning
  > **Risk:** low — tests-only (single new test file `EntitySerializerDelta
  > RoundTripTest`, ~1098 LOC, no production code modified).
  >
  > **What was done:** Landed `EntitySerializerDeltaRoundTripTest`
  > (~1098 LOC, 41 tests) as a targeted complement to the pre-existing
  > 1950-LOC `EntitySerializerDeltaTest` in `core/record/impl`. Code
  > commit `71b127c670`; review-fix commit `9b863a98c4`. Surefire on the
  > targeted run: 41 tests, 0 failures, 0 errors, 0 skipped.
  >
  > Coverage strategy was to leave the existing test alone (it covers
  > happy-path scenarios end-to-end) and pin everything that test does
  > not reach: wire-format constants (DELTA_RECORD_TYPE, CREATED,
  > REPLACED, CHANGED, REMOVED), the public static helpers
  > (writeNullableType / readNullableType / writeOptimizedLink /
  > readOptimizedLink) with hex byte-shape pins, every scalar
  > PropertyTypeInternal through the public serializeValue /
  > deserializeValue dispatch (BOOLEAN, BYTE, SHORT, INTEGER, LONG,
  > FLOAT bit-exact, DOUBLE bit-exact, STRING UTF-8, BINARY,
  > DECIMAL, DATETIME-from-Long, DATETIME-from-Date, DATE-from-Date,
  > DATE-from-Long), the full-form `serialize`/`deserialize` entry
  > points (the existing test focuses on `serializeDelta`/
  > `deserializeDelta`), and the plan-mandated delta scenarios
  > (type-changed STRING->INTEGER property, removed property, replaced
  > RID, delta-of-delta cumulative apply, embedded list append/remove,
  > embedded set add+remove, embedded map replace, link list add, link
  > map replace, link bag add+remove, nested embedded inner-only
  > change with sibling-untouched assertions).
  >
  > Forged-byte tests pin the non-embedded LinkBag/LinkSet read paths
  > (mode byte != 1 → B-tree pointer triple) by feeding a four-byte
  > payload `[mode=0x02, size=0x00, fileId=zigzag(-1)=0x01,
  > linkBagId=zigzag(-1)=0x01]` through the public deserializeValue
  > dispatch and asserting the exact "LinkBag with invalid pointer was
  > found" / "LinkSet with invalid pointer was found" diagnostic
  > strings. Extracted as a shared `FORGED_MODE_TWO_INVALID_POINTER`
  > constant after iter-1 review feedback flagged the magic bytes as
  > opaque.
  >
  > **What was discovered:**
  > - **`session.newEntity()` defaults to a class, not null.** The
  >   schemaless factory path `session.newEntity()` resolves to
  >   `newInstance(Entity.DEFAULT_CLASS_NAME)` — typically class "O"
  >   on a fresh in-memory database. The empty-record test
  >   (`emptyEntityFullFormRoundTrips`) initially asserted
  >   `getSchemaClassName() == null`, which fails because the round-
  >   trip preserves whatever class `newEntity()` set. Fixed by
  >   asserting the class name survives unchanged through the round
  >   trip, plus `getPropertiesCount() == 0` for the empty-property
  >   contract. Carry forward to Track 14/15 if those tests pin
  >   class-name absence on schemaless entities.
  > - **The B-tree-backed LinkBag/LinkSet write paths are not
  >   reachable from in-memory unit tests.** `writeLinkBag` /
  >   `writeLinkSet` mode-2 branches require an
  >   `BTreeBasedLinkBag` with a valid `LinkBagPointer`, which in
  >   turn requires `session.getBTreeCollectionManager()` to be
  >   non-null — only a real disk-backed storage emits this shape.
  >   Step 4 pins the read side of the same encoding via forged
  >   bytes, so a regression that desynchronised the wire format
  >   between writers and readers fails at the read site; the write
  >   side remains exercised only via integration tests. The 9-line
  >   `else` branches in writeLinkBag/writeLinkSet (~16 lines total)
  >   stay uncovered by Step 4 and roll up into the Track 22
  >   deferred-cleanup queue or get covered when integration-level
  >   B-tree tests run.
  > - **Iter-1 review surfaced a real test bug in delta-of-delta.**
  >   The first transaction in `deltaOfDeltaAppliesCumulatively`
  >   committed (line 696), so the post-commit reload at line 710
  >   already reflected the firstDelta's mutations. The subsequent
  >   `delta.deserializeDelta(session, firstDelta, rebuilt)` was
  >   tautological — its assertions held even if firstDelta were a
  >   no-op or buffer-corrupted. Fixed in the review-fix commit by
  >   rolling back both source transactions instead of committing
  >   the first, then re-loading the v0 baseline before applying
  >   both deltas in order, with intermediate v1-state assertions
  >   between the two applies so a regression in firstDelta is
  >   caught independently of secondDelta.
  > - **Loose `contains("invalid pointer")` exception assertions.**
  >   Iter-1 flagged that the message substring matches all three
  >   production diagnostics (LinkBag, LinkSet, RidBag); a regression
  >   that swapped the LINKBAG dispatch into `readLinkSet` would
  >   silently pass. Fixed by tightening to exact `assertEquals` on
  >   the full diagnostic string. Future tests that pin error
  >   messages should assert the full string, not a substring, when
  >   the production class has multiple sibling throw sites with
  >   the same fragment.
  >
  > **What changed from the plan:** Step LOC came in at ~1098
  > (plan estimate ~700-1000) — close to the upper bound. Coverage
  > delta target was +5-8pp on the delta-class section; not measured
  > per-step (Step 7 verification will run the package coverage
  > analyzer). Iter-1 review across 6 dimensions (CQ, BC, TB, TC, SE,
  > TS) produced 0 blockers / 9 should-fix / 22 suggestions. All 9
  > should-fix items addressed in the review-fix commit (1 real bug —
  > tautological delta-of-delta — plus 6 falsifiability tightenings,
  > 1 stale-comment, 1 javadoc accuracy fix). Iter-2 not run —
  > context dropped to `warning` after the review-fix commit
  > (Step 1/2/3 precedent: same outcome, same reason). Deferred
  > suggestions carry to Phase C track-level code review or to
  > Track 22.
  >
  > **Findings deferred** (carry to Phase C track-level code review
  > or Track 22):
  > - **TC1**: extra `LinkBagPointer` boundary cases ((-1, 0) and
  >   (0, -1)) — only the (-1, -1) "both-halves-negative" case is
  >   pinned; weakened predicate regressions (`||` instead of `&&`)
  >   are not currently caught.
  > - **TC2**: non-persistent-RID `writeOptimizedLink` /
  >   `readOptimizedLink` round-trip exercising `session.refreshRid`
  >   — the persistent #10:0 pin bypasses the refresh branch.
  > - **TC3**: DECIMAL extra fixtures (scale=0, negative,
  >   high-precision >16 unscaled bytes, BigDecimal.ZERO, MIN_VALUE
  >   unscaled) — the delta dispatch differs from the V1 dispatch
  >   pinned in Step 2.
  > - **TC4**: `default -> throw SerializationException("delta not
  >   supported for type:" + type)` in serializeDeltaValue /
  >   deserializeDeltaValue — fires only when CHANGED dispatch hits
  >   a scalar type; not reachable from public delta API today.
  > - **TC5**: `deserializeDelta(session, bytes, null)` dry-run path
  >   — used by network transport for byte validation; 15+ guarded
  >   `if (toUpdate != null)` branches across 8 collection delegates
  >   are not exercised today.
  > - **TC6**: empty-delta (begin + commit with zero mutations)
  >   round-trip — count=0 short-circuit in deserialize loop.
  > - **TC7**: delta-of-delta on collections (current pin only
  >   covers scalar properties; tracker timeline reset between
  >   transactions is the higher-risk path).
  > - **TC8**: `readNullableType` for an out-of-range type id
  >   (e.g. 0x77) — current behavior is silent null + warn log,
  >   forward-compat contract worth pinning.
  > - **TC9**: EMBEDDED `EntitySerializable` reflective branch
  >   (Class.forName + newInstance + fromDocument) — the only
  >   non-EmbeddedEntityImpl EMBEDDED path; overlaps with SE1
  >   (deferred-cleanup security item).
  > - **SE1 (forwarded to Track 22)**: insecure deserialization via
  >   `Class.forName(className).newInstance()` in the EMBEDDED branch
  >   of `EntitySerializerDelta.deserializeValue` (lines 1185-1201)
  >   — same gadget vector as Step 3's SE1 finding for V1, present
  >   here too. Reachable only from embedded transport today.
  > - **SE2 (forwarded to Track 22)**: unbounded item-count loops in
  >   readEmbeddedList/Set/Map and readLinkList/Map/Set/Bag —
  >   `varint(zigzag(MAX_INT))` followed by trailing bytes drives a
  >   tight loop. Reachable only from embedded transport today;
  >   pinnable via @Test(timeout=…) WHEN-FIXED scaffolding.
  > - **SE3**: extend the mode-2 forged-byte tests to cover mode-1
  >   with oversized in-band size — same DoS shape but higher
  >   reachability since mode-1 is the default-path dispatch.
  > - **TS3**: `runInTx` and the value-byte-dispatch helpers are
  >   duplicated across the three Track-13 round-trip test files
  >   (`RecordSerializerBinaryV1SimpleTypeRoundTripTest`,
  >   `RecordSerializerBinaryV1CollectionRoundTripTest`, and now
  >   `EntitySerializerDeltaRoundTripTest`) — extract to a shared
  >   `BinarySerializerTestSupport` base class in a follow-up.
  > - **CQ2/CQ4/CQ5/CQ6/TS6/TS8/TS9**: cosmetic items
  >   (section-banner alignment, `instance() returnsSingleton`
  >   misnamed, `linkBagAddRemoveDeltaRoundTrips` LinkBag
  >   constructor vs factory pattern, "Plan-mandated delta
  >   scenarios" section banner is workflow jargon).
  >
  > **Key files:**
  > - (new) `core/src/test/java/.../serialization/serializer/record/binary/EntitySerializerDeltaRoundTripTest.java` — 1098 LOC, 41 tests
  >
  > **Critical context:** Cross-track impact: minimal-to-low.
  > Discoveries are localised to EntitySerializerDelta (Track 13
  > scope) with two Track 22 forwardings (SE1 / SE2 — both shared
  > with V1 binary serializer paths and consistent with Step 3's
  > security-deferral note). The
  > `session.newEntity()`-defaults-to-class-O finding applies to
  > Track 14/15 if those tests pin class-name absence on schemaless
  > entities — flagged for the next session's strategy refresh.
  > Iter-2 deferred items (9 should-fix already addressed, 22
  > suggestions outstanding spread across TC/SE/TS/CQ dimensions)
  > carry to Phase C track-level code review.

- [x] Step 5: Binary comparators (BinaryComparatorV0 + InPlaceComparator)
  - [x] Context: info
  > **Risk:** low — tests-only (three test files, ~1450 LOC across the
  > BinaryComparatorV0 isEqual / compare arms; no production code
  > modified).
  >
  > **What was done:** Landed three test files covering the binary
  > comparator `isEqual` and `compare` arms. Across iter-1 and iter-2,
  > the step contributed:
  > - `BinaryComparatorV0IsEqualCrossTypeTest` (~370 LOC, 14 tests):
  >   per-source-type isEqual matrix for INTEGER / LONG / SHORT / BYTE /
  >   FLOAT / DOUBLE / STRING / DECIMAL plus the cross-arm pairs missing
  >   from the pre-existing same-type sweep. Iter-2 added DATE-destination
  >   matrix cells for INT / LONG / SHORT / STRING source — pinning the
  >   `value1 == readAsLong(fv2) * MILLISEC_PER_DAY` arm; aligned the
  >   field-name convention with `AbstractComparatorTest` (`null`).
  > - `BinaryComparatorV0DateSourceTest` (~640 LOC after iter-2, 17
  >   tests): DATE source paths uncovered by the pre-existing
  >   `testCompareNumber` sweep. Tests pin TIMEZONE=GMT in `@Before` so
  >   the encoding and `convertDayToTimezone` behavior are reproducible
  >   across CI/dev workers. Iter-2 hardened the suite: switched the
  >   `dateIsEqualStringThrowsBecauseOfDecimalDeserializerMisuse` pin
  >   from `@Test(expected=...)` to `assertThrows` with a `BigInteger`
  >   message-content assertion (defends against a partial-fix regression
  >   that throws a different NFE); added ordering pins around
  >   `"1969-12-31"` / `"1970-01-02"` to the date-format compare path
  >   (defends against a stub `return 0` regression); strengthened the
  >   four `dateIsEqualWith*IsUnsupported` tests with multi-input
  >   disambiguation (BYTE/BOOLEAN(true,false)/BINARY(empty,nonempty)/
  >   LINK(#1:2,#99:99)) so the compare sentinel `1` is no longer
  >   indistinguishable from a hypothetical real-arm "left strictly
  >   greater" return; added the missing `+1` case to `dateCrossDecimal`
  >   isEqual; dropped the `freshCopy()` helper in favor of inline
  >   `field(...)` calls (the comparator's own try/finally already
  >   resets offsets); aligned the field-name convention.
  > - `BinaryComparatorV0EdgeCasesTest` (~440 LOC, 21 tests, NEW in
  >   iter-2): five branch families uncovered by the iter-1 files —
  >   LINK same-cluster / different-position (compare position
  >   disambiguation at `BinaryComparatorV0.java:1252-1263`; isEqual
  >   fall-through to false), BINARY length-tiebreak paths (lines
  >   1235-1239, including the empty-vs-single-byte boundary), DATETIME
  >   source × DATE destination for both isEqual (lines 524-527) and
  >   compare (lines 1075-1078) including the intra-day asymmetry pin,
  >   BOOLEAN × non-canonical / uppercase STRING (case-insensitive
  >   `Boolean.parseBoolean` for isEqual; all three arms of the compare
  >   three-way ternary at lines 1054-1056), and the DECIMAL × BYTE
  >   compare-only positive pin (lines 1312-1315) paired with a
  >   companion paired-`isEqual=false` re-assertion so a regression that
  >   adds the missing isEqual arm without updating the companion pin
  >   fails locally. Pins TIMEZONE=GMT in `@Before` for the DATETIME ×
  >   DATE pin reproducibility.
  >
  > Step total: 3 test files, ~1450 LOC, 52 tests passing (14 + 17 +
  > 21). Two commits: code commit `8152a48659` (iter-1, 2 files,
  > 27 tests) and review-fix commit `3e146c8e57` (iter-2, +1 file +
  > tightening, +25 tests). Spotless: clean. Surefire on the targeted
  > run: 52 tests, 0 failures, 0 errors, 0 skipped (~9s).
  >
  > **What was discovered:**
  > - **Latent DATE × STRING isEqual NFE crash (iter-1 discovery)**:
  >   `BinaryComparatorV0` line 501 routes STRING and DECIMAL through
  >   the same arm, calling `DecimalSerializer.deserialize` on the
  >   STRING-encoded bytes. STRING wire format is `varint(length) + UTF-8
  >   bytes`, NOT `int scale + int unscaledLen + unscaled bytes`, so the
  >   deserialiser interprets the leading varint+UTF8 bytes as
  >   scale+length+payload — even for canonical `"0"` it crashes with
  >   `NumberFormatException("Zero length BigInteger")` from inside
  >   `new BigInteger`. This is a DoS / crash-on-bad-input risk for any
  >   server fed an attacker-controlled STRING field value reaching a
  >   DATE-vs-STRING isEqual check. Pinned with WHEN-FIXED →
  >   deferred-cleanup track; iter-2 tightened the pin so a partial fix
  >   that throws a different NFE is caught.
  > - **Latent DATE × LONG isEqual flooring asymmetry (iter-1
  >   discovery)**: the isEqual arm at line 478 calls
  >   `convertDayToTimezone(databaseTZ, GMT, value2)` which floors the
  >   LONG value to the start of its day — but only for positive
  >   intra-day values. Negative intra-day values floor to the previous
  >   day's start. Pinned: `isEqual(DATE 0, LONG 1) → true` (positive
  >   intra-day rounds to 0); `isEqual(DATE 0, LONG -1) → false`
  >   (negative -1 ms rounds to -86_400_000, not 0). The matching
  >   `compare` arm at line 1140 does literal `Long.compare` without
  >   flooring, so intra-day ±1 ms produces a non-zero ordering — pinned
  >   in `dateCompareLongIntradaySignsAreLiteralLongCompare`. WHEN-FIXED
  >   → deferred-cleanup track.
  > - **DECIMAL × BYTE asymmetry (iter-1 discovery)**: `compare` supports
  >   DECIMAL × BYTE via line 1312-1315; `isEqual` does NOT (line 633's
  >   DECIMAL switch lacks the BYTE arm). Pinned `assertFalse(isEqual)`
  >   AND `compareTo == 0` for the equal-value case so a regression that
  >   adds the isEqual arm without updating the companion pin fails
  >   loudly.
  > - **BOOLEAN × STRING case-insensitivity (iter-1 + iter-2)**:
  >   `Boolean.parseBoolean` is case-insensitive, so `"TRUE"`, `"True"`,
  >   `"tRuE"` all match BOOLEAN(true) on isEqual; any non-`"true"`
  >   string parses to false on the BOOLEAN(false) arm. Pinned for both
  >   isEqual and the compare three-way ternary's three arms (0, +1, -1).
  > - **DATETIME × DATE asymmetry (iter-2)**: DATE side is multiplied by
  >   `MILLISEC_PER_DAY` for both isEqual and compare; DATETIME side is
  >   NOT floored. So `isEqual(DATETIME 1 ms, DATE 0 days)` returns false
  >   (1 != 0) and `compare(DATETIME 1 ms, DATE 0 days)` returns 1 —
  >   pinned in `datetimeCrossDateIntradayDifference`. Symmetric flip
  >   of the iter-1 DATE × LONG flooring asymmetry: when DATETIME is on
  >   the left, no flooring; when DATE is on the left and LONG on the
  >   right, flooring. WHEN-FIXED → deferred-cleanup track for whoever
  >   normalises the comparator.
  >
  > **What changed from the plan:** Step 5's plan called for a 12×12
  > parameterized `@RunWith(Parameterized.class)` cross-product. The
  > delivered shape is hand-written matrix tests instead — the
  > parameterized form was rejected at iter-1 for two reasons documented
  > in the iter-1 review's deferred suggestions (TC6/TC7): (a) the
  > 12×12 product would re-cover ~70% of cells already pinned in the
  > pre-existing `BinaryComparatorEqualsTest` and `testCompareNumber`,
  > and (b) several cells require type-specific edge-case assertions
  > (NaN/+∞/-0 for FLOAT/DOUBLE, MIN_VALUE for INTEGER/LONG, the
  > NumberFormatException pin for DATE × STRING isEqual) that don't fit
  > a single parameterized template. The hand-written matrix kept each
  > arm pin focused on its specific edge cases. **InPlaceComparator
  > coverage was not touched in this step** — it does not appear on
  > production read paths in the binary serializer's coverage report
  > and was reclassified during iter-1 as belonging to a future
  > deferred-cleanup item; its pre-existing test infrastructure already
  > covers the live arms. Step LOC came in at ~1450 (plan estimate
  > 1000-1500; in-band).
  >
  > Iter-1 review was 5 dimensions (CQ/BC/TB/TC/TS): 0 blockers /
  > ~18 should-fix / ~25 suggestions. Iter-2 gate check on the two
  > FIX-REQUIRED dimensions (TB and TC) PASSED — all 5 TB and all 5
  > TC iter-1 should-fix items VERIFIED, 0 new findings, 0 blockers.
  > CQ / BC / TS were APPROVED in iter-1 and not re-run.
  >
  > **Findings deferred to Phase C / Track 22** (iter-1 + iter-2
  > suggestions, not blocking the step):
  > - CQ4/CQ6/CQ7/CQ8/CQ9/CQ10/CQ11/CQ12 — naming, banners, javadoc
  >   tightening, BigDecimal constructor style.
  > - CQ1/TS1/TS4 — `field()` helper duplication across the new files
  >   AND `AbstractComparatorTest` (cross-package refactor; deferred).
  > - BC1/BC2/BC3/BC4 — comment-accuracy nits about parse-format paths
  >   and `Date.toString()` lex ordering.
  > - TB6/TB7/TB8/TB9/TB10 — assertion-message format, BOOLEAN
  >   case-insensitivity supplemental, `Date.toString()` locale
  >   brittleness, DATETIME intra-day flooring symmetry.
  > - TC6/TC7 — STRING × STRING redundancy with existing tests,
  >   plan-deviation observation (parameterized 12×12 vs hand-written
  >   matrix).
  > - TS5/TS6/TS7/TS8/TS9/TS10 — `@Before` split, assertion message
  >   format, teardown comment, locale pinning, isEqual+compare
  >   asymmetry annotation, file naming.
  >
  > **Cross-track impact:** Minor. The latent-bug pins (DATE × STRING
  > isEqual NFE crash, DATE × LONG flooring asymmetry, DATETIME × DATE
  > flooring asymmetry, DECIMAL × BYTE asymmetry, BOOLEAN × STRING
  > case-insensitivity surface) are all confined to the binary
  > comparator path. The Index track (downstream) may use the
  > comparator for SBTree key ordering — reviewers should be aware
  > the DATE × STRING isEqual path is currently latent-buggy, but the
  > pin is informational and the WHEN-FIXED owner is the
  > deferred-cleanup track. No impact on Tracks 14-21. Recommendation:
  > **Continue**.
  >
  > **Key files:**
  > - (new, iter-1) `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/binary/BinaryComparatorV0DateSourceTest.java`
  > - (new, iter-1) `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/binary/BinaryComparatorV0IsEqualCrossTypeTest.java`
  > - (new, iter-2) `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/binary/BinaryComparatorV0EdgeCasesTest.java`
  > - (modified, iter-2) the two iter-1 files above for TB/TC/CQ2/CQ5/TS2/TS3 fixes
  >
  > **Critical context:** Iter-1 review file `reviews/track-13-step-5-iter1.md`;
  > iter-2 gate-check file `reviews/track-13-step-5-iter2.md`. Two-failure
  > rule does not apply — no `[!]` retry pattern; the only commits are
  > the iter-1 code (`8152a48659`) and the iter-2 review-fix
  > (`3e146c8e57`).

- [x] Step 6: Index serializers (CompositeKey + IndexMultiValuKey + cleanup)
  - [x] Context: warning
  > **Risk:** low — tests-only (two new test files plus extensions to two
  > existing ones, ~2,000 LOC of test code; no production code modified).
  >
  > **What was done:** Added two new standalone test files plus extensions
  > to two existing ones, totalling 99 new tests across ~2,000 LOC. Code
  > commit `2d9bc449d3`; review-fix commit `322ec0521f`. Surefire on the
  > four targeted classes: 99 tests, 0 failures, 0 errors, 0 skipped.
  > Files:
  > - (new) `CompositeKeySerializerTest` (~870 LOC, 38 tests): every byte[]
  >   portable / native / ByteBuffer-at-position / ByteBuffer-at-offset /
  >   WAL-overlay variant; compareInByteBuffer + compareInByteBufferWithWALChanges
  >   in-page comparison (equal/less/greater/partial-prefix/null at each
  >   side/at same position); preprocess (null short-circuit, fresh-instance
  >   allocation, null-entry preservation, hint vs default-from-class
  >   dispatch, Map size==1 flattening); WAL shadow test (pre-fill buffer
  >   with wrong bytes, stage right bytes via overlay, confirm overlay
  >   surfaces staged bytes — closes the gap where a regression bypassing
  >   pageChunks would still pass against a zero-filled underlying buffer).
  > - (new) `IndexMultiValuKeySerializerTest` (~880 LOC, 36 tests): every
  >   supported scalar type (BOOLEAN, BYTE, SHORT, INTEGER, LONG, FLOAT,
  >   DOUBLE, DATE, DATETIME, BINARY, STRING, DECIMAL, LINK) on every code
  >   path; null-sentinel encoding `(byte) -(typeId + 1)` pinned for five
  >   types; per-type WAL-overlay deserialise loop with BINARY assertArrayEquals
  >   side-band; preprocess fast path (assertSame on no-DATE / RID-only
  >   LINK input) vs slow path (DATE truncation to local-midnight via
  >   Calendar default-tz; LINK identity extraction for non-RID Identifiable);
  >   negative-path tests (EMBEDDED type → IndexException at serialise +
  >   deserialise); WAL shadow test mirroring the CompositeKey variant;
  >   default-order byte[] `getObjectSize` test closing the previously-
  >   untested big-endian byte[] reader.
  > - (modified) `LinkSerializerTest` (+~50 LOC, 5 new tests): preprocess
  >   null + non-null branches; getId(=9) + isFixedLength(=true, 10) +
  >   staticGetObjectSize pins.
  > - (modified) `CompactedLinkSerializerTest` (+~80 LOC, 5 new tests):
  >   ID(=22) + INSTANCE singleton + variable-length contract; preprocess
  >   identity extraction; WAL-overlay deserialise round-trip; numberSize
  >   boundary 0..8 (positions 0L through Long.MAX_VALUE — closes the
  >   previously-untested numberSize=0 zero-payload bucket and numberSize=8
  >   max-payload bucket on both portable and native paths).
  >
  > Per-track strategy refinements honoured:
  > - **Standalone tests** (Per-track strategy refinement §1) — both serializers
  >   resolve per-key serializers through `BinarySerializerFactory.create(...)`
  >   without any session lookup, so the static factory in `@BeforeClass`
  >   exercises the full production code path. No DbTestBase required.
  > - **Byte-shape pins paired with every round-trip pin** (Per-track
  >   strategy refinement §2) — every per-type round-trip test asserts the
  >   typeId byte position, length-prefix bytes, and payload bit pattern
  >   alongside the round-trip equality. Iter-2 review fixes added these to
  >   SHORT / INTEGER / LONG / DATE / DATETIME / LINK / BINARY / DECIMAL —
  >   the iter-1 versions had pure round-trip-only assertions which would
  >   silently pass a byte-order regression that swapped both encode and
  >   decode in tandem.
  >
  > **What was discovered:**
  > - **Endianness is split across paths**: the byte[] portable path
  >   (`serialize(...)` → `IntegerSerializer.serializeLiteral` for
  >   CompositeKey, default-order `ByteBuffer.wrap(stream)` for
  >   IndexMultiValu) writes the header big-endian; the native path uses
  >   `ByteOrder.nativeOrder()`. The two encodings round-trip independently
  >   through their matching deserialiser but are NOT byte-identical (per-
  >   type payloads also use the buffer's byte order — INT/LONG/SHORT/
  >   string-length-prefix all differ between paths on x86). Pinned with a
  >   host-conditional little-endian assertion in
  >   `byteShapeMismatchAcrossPortableAndNativeEndianness`.
  > - **Map-flatten guard is fragile** (`CompositeKeySerializer.preprocess`
  >   lines 282-292): a four-condition AND-guard (`instanceof Map`, type
  >   ≠ EMBEDDEDMAP/LINKMAP, `size() == 1`, key class assignable from
  >   `type.getDefaultJavaType()`). Standalone factory has no registered
  >   serializer for EMBEDDEDMAP/LINKMAP, so the negative-branch tests for
  >   "type IS EMBEDDEDMAP" hit an NPE in the factory dispatch — those
  >   negative branches are deferred to Track 22 (DRY/coverage queue) rather
  >   than worked around with a custom factory. The positive flatten branch
  >   IS pinned via `preprocessMapWithSingleKeyMatchingTypeIsFlattened`.
  > - **`IndexMultiValuKeySerializer.getId() = -1`** is a deliberate
  >   sentinel — the serializer is NOT registered with `BinarySerializerFactory`
  >   and is dispatched explicitly by SBTreeMultiValueV3/V4. Pinned to catch
  >   a regression to a positive id that would conflict with the registered
  >   serializer ids.
  > - **Pre-existing `CompositeKeyTest.java`** (under `core/index/`) exists
  >   but covers `CompositeKey` (the data structure) plus four CompositeKey
  >   serialisation round-trips — leaving most of the byte-shape and the
  >   compare/preprocess/WAL paths untested. Step 6's
  >   `CompositeKeySerializerTest` lives next to the production class
  >   (`core/serialization/serializer/binary/impl/index/`) and focuses on
  >   the serializer contract, complementing rather than duplicating the
  >   CompositeKeyTest fixture.
  > - **WAL-overlay primary contract**: iter-1 tests staged bytes via
  >   `WALPageChangesPortion.setBinaryValue(buffer, data, offset)` into a
  >   zero-filled underlying buffer and asserted overlay reads. Iter-2
  >   review (TY1) flagged that this DOES NOT exercise the overlay's
  >   primary purpose — surfacing staged bytes when the underlying buffer
  >   holds a *different* sequence at the same offsets. Added
  >   `walOverlayShadowsConflictingUnderlyingBytes` to both serializer
  >   tests: pre-fill buffer with wrong bytes, stage right bytes via
  >   overlay, confirm overlay surfaces staged bytes. Hardens crash-recovery
  >   coverage at zero production cost.
  >
  > **What changed from the plan:** No track-level deviations. Step 6 came
  > in at ~2,000 LOC vs the plan estimate of 1000-1300; overshoot driven by
  > the per-type comprehensiveness of `IndexMultiValuKeySerializerTest` (13
  > types × 5 paths = 65 type/path combinations) and the byte-shape
  > strengthening from iter-2 review. The two `binary/impl` residual-gap
  > additions to `LinkSerializerTest` and `CompactedLinkSerializerTest` were
  > carved out from the original scope's "+cleanup" goal and amount to ~120
  > LOC. The numberSize 0..8 boundary test (TC1) was added in iter-2 and
  > catches a previously untested branch family on `CompactedLinkSerializer`.
  >
  > **Findings deferred to Track 22 / future sessions** (review iter 1
  > raised these; resolved or deferred per severity):
  > - **TY2/TY3/TY4** (production-code asserts): add `assert keysSize >= 0`
  >   after reading the keyCount header in CompositeKeySerializer's
  >   deserialise/compare paths and IndexMultiValuKeySerializer's WAL
  >   variant; add `assert serializerId >= 0` for CompositeKeySerializer
  >   non-null entries (NOT for IndexMultiValuKeySerializer because that
  >   one uses negative typeIds as null sentinels); add post-condition
  >   `(startPosition - oldStartPosition) == getObjectSize(...)` assert at
  >   the end of CompositeKeySerializer.serialize. Production-code change,
  >   deferred.
  > - **CQ4** (tautological `assertSame(INSTANCE, INSTANCE)` in
  >   CompositeKeySerializerTest:104 and CompactedLinkSerializerTest:271)
  >   — minor; consider replacing with `assertSame(CompositeKeySerializer.INSTANCE,
  >   factory.getObjectSerializer((byte) 14))` pin.
  > - **CQ5** (FQN `java.nio.ByteOrder.nativeOrder()` in CompactedLinkSerializerTest)
  >   — add import for consistency with sibling tests.
  > - **CQ6/TS5** (instance reuse in `testIdAndVariableLengthContract`).
  > - **CQ7/TS4** (FQN `Identifiable`/`RID` in `IdentifiableHolder` —
  >   add imports).
  > - **CQ8** (deliberately-not-pinning-null comment for
  >   CompactedLinkSerializer.preprocess — symmetric with LinkSerializer's
  >   null-pin test).
  > - **TS3** (`Object[][]` `{type, value}` pairs vs parallel arrays in
  >   `byteBufferAtOffsetCoversEveryType` and `walOverlayDeserialiseCoversEveryType`).
  > - **TC3** (empty-vs-empty compareInByteBuffer / compareInByteBufferWithWAL).
  > - **TC4** (negative-scale BigDecimal fixtures).
  > - **TC5** (Map-flatten negative branches with EMBEDDEDMAP/LINKMAP — see
  >   factory-NPE discussion above).
  > - **TC6** (LinkSerializer boundary RIDs — `clusterId == 0`,
  >   `Short.MAX_VALUE`, `position == 0`, `Long.MAX_VALUE`).
  > - **TB6** (`collectionId` vs `clusterId` terminology — update doc
  >   strings to use `clusterId`/`clusterPosition`).
  > - **TB7** (byte-shape pins on offset / hints variants of round-trip
  >   tests — currently only offset-0 fixtures pin bytes).
  >
  > **Key files:**
  > - (new) `core/src/test/java/.../serialization/serializer/binary/impl/index/CompositeKeySerializerTest.java`
  > - (new) `core/src/test/java/.../serialization/serializer/binary/impl/index/IndexMultiValuKeySerializerTest.java`
  > - (modified) `core/src/test/java/.../serialization/serializer/binary/impl/LinkSerializerTest.java`
  > - (modified) `core/src/test/java/.../serialization/serializer/binary/impl/CompactedLinkSerializerTest.java`

- [x] Step 7: common/serialization/types (UUIDSerializer + remainder) +
      verification
  - [x] Context: warning
  > **Risk:** low — tests-only (modified `UUIDSerializerTest` plus new
  > `NullSerializerTest`, ~620 LOC; verification step only updates the
  > deferred-cleanup queue in the plan; no production code modified).
  >
  > **What was done:** Closed the smallest scope package
  > (`common/serialization/types`) by substantially expanding
  > `UUIDSerializerTest` (4 tests → 25 tests, ~430 LOC added) and
  > creating `NullSerializerTest` from scratch (9 tests, ~190 LOC).
  > Code commit `e8a44a6a4f`; review-fix commit `d24546d32a`. UUID
  > additions: heap byte[] paths (heap round-trip + non-zero-offset
  > round-trip), static helper round-trip + cross-API equivalence with
  > the instance method, dispatcher-metadata pins (`getId` UOE,
  > `isFixedLength`, `getFixedLength`, `getObjectSize(byte[])`,
  > `getObjectSizeNative`, `preprocess` identity + null-input pin,
  > `INSTANCE` and `UUID_SIZE` constants), canonical 16-byte
  > big-endian byte-shape pins against a deterministic fixture
  > (`UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L)`), boundary
  > fixtures (all-zero UUID, all-ones UUID), native-heap round-trip
  > + LSB-offset variant + a half-swap pin reading bytes back through
  > `ByteBuffer.wrap` with native order (catches a regression that
  > swapped MSB-half / LSB-half slots without coupling to a specific
  > endianness), and a ByteBuffer canonical-bytes pin against the
  > default big-endian buffer order. NULL additions: dispatcher
  > metadata (`INSTANCE` + `ID == 11` + `isFixedLength` true +
  > `getFixedLength == 0` + all `getObjectSize*` variants return zero
  > + `preprocess` always returns null regardless of input), heap
  > byte[] no-op contract pinned via sentinel-fill (`0xAA` portable,
  > `0x55` native) so any stray write would flip a known byte, and
  > ByteBuffer + WALChanges no-op contract similarly pinned via
  > sentinel-fill on the underlying buffer (review-fix iter-1)
  > catching any write through the absolute-index API or
  > `walChanges.setBinaryValue` that would not advance position.
  > Surefire on the targeted run: 34 tests, 0 failures, 0 errors,
  > 0 skipped.
  >
  > **Coverage outcome** (post-Step-7 vs. plan-time / Phase A live
  > re-measure baseline; live aggregate via coverage-analyzer.py):
  > - `common/serialization/types`: **88.7% line / 86.7% branch /
  >   93 uncov** (was 84.2% / 86.7% / 130; +4.5pp line, target met).
  > - `record/binary`: **82.8% line / 80.2% branch / 582 uncov** (was
  >   74.5% / 71.5% / 862; +8.3pp line, +8.7pp branch from prior
  >   Steps 2–5; live subset above 85% once the (a)+(b)+(c)+(d) dead
  >   surface in the deferred-cleanup queue is removed — current
  >   numerator includes ~80 LOC across `SerializableWrapper`,
  >   `RecordSerializationDebug{,Property}`, `MockSerializer`).
  > - `binary/impl/index`: **98.9% line / 93.6% branch / 5 uncov**
  >   (was 65.0% / 67.6% / 165 — Step 6 closed the gap; the residual
  >   is the Map-flatten preprocess negative branches deferred to
  >   Track 22 (x) on factory-NPE infrastructure).
  > - `binary`: **98.4% line / 100% branch / 1 uncov** (was 65.6% /
  >   66.7% / 21 — Step 1 closed; residual is one inert-utility
  >   constant initializer line).
  > - `binary/impl`: **99.3% line / 100% branch / 1 uncov** (was
  >   94.1% / 88.9% / 9 — Step 6 LinkSerializer / CompactedLinkSerializer
  >   additions closed; residual is one path-detection branch).
  >
  > Coverage gate on changed production lines: **PASSED** — 100% line /
  > 100% branch (purely test-additive; no production code modified
  > across Step 7).
  >
  > **What was discovered:**
  > - The pre-existing `UUIDSerializerTest` had only 4 tests, all
  >   ByteBuffer-shaped — leaving the heap byte[] surface, the static
  >   helper API, the entire dispatcher-metadata block, and the
  >   native-deserialise direction completely unpinned. The 24-line
  >   gap on `UUIDSerializer` came from this missing surface, not
  >   from any hard-to-reach code path.
  > - **Half-swap regressions on the native heap path are invisible
  >   to round-trip identity alone**: a regression that swapped MSB
  >   and LSB slots round-trips cleanly because both write and read
  >   flip together. The review-fix added
  >   `nativeHeapPinsLongHalvesViaNativeOrder` — read the serialised
  >   stream back through `ByteBuffer.wrap(stream).order(nativeOrder)`
  >   and assert each long half independently — which catches the
  >   swap without pinning a specific platform endianness. Worth
  >   carrying forward to any future native-byte-order test
  >   (e.g. native paths in other `*Serializer` classes).
  > - **No-op tests need byte-content invariance pins**: the iter-1
  >   `walChangesOverloadsAreNoOps` and `byteBufferAtCurrentPositionAreNoOps`
  >   asserted only the documented return values (0 / null /
  >   position-unchanged). A regression that wrote bytes through the
  >   absolute-index ByteBuffer API or `walChanges.setBinaryValue`
  >   would not advance position and would still pass. Sentinel-fill
  >   on the underlying buffer + post-call equality scan on every
  >   byte closes the gap. The same pattern applies to any future
  >   no-op contract test.
  > - **`UUIDSerializer.getId()` throws `UnsupportedOperationException`**
  >   because UUID is unregistered with `BinarySerializerFactory` and
  >   has no dispatcher slot. This is intentional — `BinarySerializerFactory`
  >   slot allocations are reserved for types in
  >   `PropertyTypeInternal` and UUID is not one. The pin protects
  >   against a silent re-introduction (any future "fix" must update
  >   the factory dispatcher in lockstep).
  > - **`NullSerializer.ID == 11` is genuinely wire-format-significant**:
  >   `CompositeKeySerializer` reads/writes `ID` as a byte marker to
  >   discriminate null components in composite keys (verified at
  >   `CompositeKeySerializer.java:488/492/496/551/555/559`). A
  >   renumbering would silently corrupt every persisted composite
  >   key carrying a null component. The new pin makes the
  >   wire-format dependency explicit.
  >
  > **What changed from the plan:** Plan called for `UUIDSerializerTest`
  > (round-trip + byte-shape pin + all-zero/max-bits boundaries)
  > "plus targeted extensions to existing `*BinarySerializerTest`
  > files for any residual gap in the `types` subpackage". Targeted
  > extensions to the sibling `*SerializerTest` files
  > (`Boolean/Byte/Char/Short/Float`) were NOT done — the residual
  > 50 lines split across 5 files would have been a uniform
  > `getId` / `isFixedLength` / `getFixedLength` / `preprocess` /
  > primitive-overload extension across each, ~10 LOC apiece, and
  > the package already exceeds the 85% line / 70% branch target
  > without them (88.7% / 86.7%). Forwarded to Track 22 as item (bb)
  > in the DRY-cleanup section, where it pairs naturally with the
  > sibling-class deletions. Step LOC came in at ~620 (plan
  > estimate ~500-800; in-band).
  >
  > Iter-1 review across 5 dimensions (CQ / BC / TB / TC / TS):
  > 0 blockers / 4 should-fix / multiple suggestions. Should-fix items
  > addressed in commit `d24546d32a`:
  > - **Test-behavior**: WAL no-op test lacked sentinel-fill on the
  >   underlying buffer — added.
  > - **Test-completeness**: `UUIDSerializer.preprocess(null)` was
  >   unpinned — added `preprocessReturnsNullForNullInput`.
  > - **Test-structure**: `heapByteArrayRoundTripIdentity` used the
  >   legacy random `OBJECT` fixture — switched to
  >   `DETERMINISTIC_UUID` so failure messages are stable across runs.
  > - **Test-structure / Code-quality**: `java.util.Arrays.fill`
  >   used FQN — replaced with explicit import.
  >
  > Two TB suggestions also picked up opportunistically: the half-swap
  > native-order pin (TB3) and the no-op byte-content invariance for
  > the non-WAL ByteBuffer surface (F5 — same pattern as the WAL fix
  > but on `byteBufferAtCurrentPositionAreNoOps`). The remaining
  > suggestions (mixed `Assert.X` vs `assertX` static-import style,
  > section-banner introduction in two files of a sibling-test package
  > that doesn't use them, asymmetric near-zero UUID fixture, hex
  > literal `0x0B` for the NULL ID, lambda → method reference for the
  > UOE assert, out-of-bounds offset on the NullSerializer offset
  > overload, alphabetic instance/static byte-order argument
  > ambiguity in `instanceAndStaticSerializeProduceIdenticalBytes`)
  > are aesthetic / refuted by stronger pins already in place / or
  > legitimately defer to a sweep across the entire `common/
  > serialization/types` test family — forwarded to Track 22 as
  > suggestions (bb).
  >
  > Iter-2 not run — all should-fix items addressed in a single
  > iter-1 fix sweep (Step 6 precedent: same outcome, same reason —
  > tighter scope + cleaner closure than running iter-2 just to
  > re-verify).
  >
  > **Findings deferred** (carry to Phase C track-level code review
  > or Track 22):
  > - **CQ1**: Mixed assertion-import style (`Assert.assertEquals`
  >   qualifier on the legacy 4 tests vs static-imported `assertEquals`
  >   on all new code) — aesthetic; defer to Track 22 unification.
  > - **CQ5**: The legacy `testFieldSize` is now strictly weaker than
  >   the new `getFixedLengthIsUuidSize` /
  >   `getObjectSizeOnByteArrayReturnsUuidSize` /
  >   `getObjectSizeNativeOnByteArrayReturnsUuidSize` pins — could be
  >   moved up under the dispatcher-metadata block and renamed;
  >   defer.
  > - **TB2**: All-zero UUID is a weak boundary (a positional
  >   shuffle would be invisible since every byte is `0x00`); refuted
  >   by the deterministic fixture (which catches positional shuffles)
  >   but a near-zero asymmetric fixture (e.g. `UUID(0L, 1L)`) is a
  >   cheap upgrade — defer.
  > - **TB4 / F4**: NullSerializer offset-overload could pin
  >   out-of-bounds offset (`getObjectSizeInByteBuffer(factory, 999,
  >   buffer)` on an 8-byte buffer) so a regression that started
  >   dereferencing the offset would throw rather than silently
  >   short-circuit — defer.
  > - **TS3**: The `// --- xxx ---` section-banner comments are new
  >   in this package; sibling `*SerializerTest` files use a flat
  >   list. Either drop the banners (per-test Javadoc + naming
  >   already documents intent) or retrofit across the package as
  >   part of (bb).
  > - **TC4 / F6**: `getObjectSize(factory, byte[], int)` and
  >   `getObjectSizeNative(factory, byte[], int)` are called only
  >   with `startPosition = 0`; production methods are unconditional
  >   `return UUID_SIZE` so a regression has no branch to fail, but
  >   defending against future bounds-check additions is cheap —
  >   defer.
  >
  > **Key files:**
  > - (modified) `core/src/test/java/.../common/serialization/types/UUIDSerializerTest.java`
  > - (new) `core/src/test/java/.../common/serialization/types/NullSerializerTest.java`
  > - (modified) `docs/adr/unit-test-coverage/_workflow/implementation-plan.md` —
  >   added "From Track 13" section to the Track 22 deferred-cleanup
  >   queue (item (a)–(bb) covering 4 dead-code deletions, 14 latent
  >   production-bug WHEN-FIXED markers, 3 production-code asserts,
  >   3 residual-coverage gaps, and 4 DRY items including the
  >   sibling-test extension).
  >
  > **Cross-track impact:** Minimal-to-none. The step is purely
  > test-additive on `common/serialization/types` — no production
  > code modified, no plan assumptions affected, no downstream tracks
  > impacted. The `getId` UOE pin documents UUID's unregistered
  > status in `BinarySerializerFactory`; future tracks adding a UUID
  > slot would need to update both the factory dispatcher and the
  > pin in lockstep (today's structure makes the lockstep dependency
  > visible). The Track 22 queue update consolidates 14 latent
  > production-bug pins from earlier Track 13 steps so the cleanup
  > track has a single authoritative list of WHEN-FIXED markers
  > forwarded from the binary serializer surface — useful when the
  > cleanup track is sequenced. Recommendation: **Continue**.
  - Goal: close the smallest scope package (`common/serialization/types`,
    ~129 uncov / 84.3%) — primarily `UUIDSerializer` (51%, the largest
    gap there). Then run the full coverage gate, the full unit-test
    suite, and update the deferred-cleanup track's queue with this
    track's deletions / residual gaps / DRY items.
  - Test additions: `UUIDSerializerTest` (round-trip with random UUID
    fixtures, byte-shape pin for the canonical 16-byte encoding,
    boundary cases — all-zero UUID, max-bits UUID), plus targeted
    extensions to existing `*BinarySerializerTest` files for any
    residual gap in the `types` subpackage.
  - Verification:
    1. `./mvnw -pl core -am clean package -P coverage` (full coverage
       build).
    2. `python3 .github/scripts/coverage-analyzer.py` per scope
       package: `record/binary`, `binary/impl/index`,
       `common/serialization/types`, `binary`, `binary/impl`. Target
       85% line / 70% branch on the live (non-pinned-dead) subset of
       each package.
    3. `python3 .github/scripts/coverage-gate.py --line-threshold 85
       --branch-threshold 70 --compare-branch origin/develop
       --coverage-dir .coverage/reports` — should be trivially 100%
       since the track is purely test-additive.
    4. `./mvnw -pl core clean test` — full unit-test suite, confirm
       zero new failures.
    5. Update the deferred-cleanup track's queue (the bottom of
       `implementation-plan.md`) with this track's contributions:
       - Dead-code deletions: `SerializableWrapper`,
         `RecordSerializationDebug`, `RecordSerializationDebugProperty`,
         `MockSerializer`.
       - Residual-coverage gaps with deferred-cleanup-track rationale
         (any class that fell short of 85%/70% with concrete
         justification).
       - DRY / cleanup items observed during decomposition (e.g.
         `assertCanonicalBytes` helper, byte-shape pin convention).
       - Production bugs pinned with WHEN-FIXED markers (if any
         surfaced during steps 2–6).
  - Coverage delta target: `common/serialization/types` 84 → 92%+;
    aggregate scope-package gate PASS.
  - LOC estimate: ~500-800.

**Step sequencing.** Steps run sequentially — each step's commit is the
base for the next. None of the steps are independent (shared fixture
patterns, sequential coverage delta), so no parallel annotations.

**Mid-track checkpoint.** After Step 4 (4 of 7 steps complete) the
session may end and resume at Step 5 if context consumption reaches the
warning threshold; the step file's Progress section is the resume
anchor. Step 4 is a natural break point because the V1 dispatch coverage
(Steps 2–4) closes most of the `record/binary` gap; the comparator and
index-serializer steps that follow are largely independent of the V1
test infrastructure built in Steps 2–4.
