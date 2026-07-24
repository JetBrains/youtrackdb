# Adversarial pass 13 (scoped) — durability lens

Attacks only the fresh text of this session's durability-lens settlements: F118, F119, F120, F121, and the D20 sentences they changed (diff `0c4646c350..HEAD`). Passes 1–12 ground are out of scope and not re-attacked. The concurrency-lens findings of pass 12 (F114/F115/F116/F117) are a sibling agent's scope.

Code grounding read: `DatabaseExport` (legacy exporter, the baseline the ledger reasons about), `DatabaseImport` + `JSONReader` (import parse path), `JSONSerializerJackson.recordToJson` (per-record render call tree), `SLF4JLogManager.log` (category resolution), `FileUtils` (helper log sites).

---

### U1 — F120's "whole-or-nothing isolation" claim does not survive the copy-out step: a stream-in / buffer-flush failure mid-record strands the shared generator at object context, and on the fail-fast path `close()`'s `writeEndObject` then *promotes* the truncated record [MAJOR]

**The fix's own angle 5, unaddressed.** F120 bounds the per-record *render* into an isolated buffer (in-memory ≤ threshold, spill-to-temp beyond). But the record still has to leave that buffer and enter the shared `jsonGenerator`: the resolution text says the spilled record is "streamed into the dump on success." The render step is now isolated; the *copy-out* step is not, and it is a fresh partial-write surface the fix introduced (for spilled records) and never closed (for in-memory records either).

Failure sequence:
1. Record k renders fully and correctly to its isolated buffer/spill (render succeeds — isolation did its job).
2. The exporter copies the buffer/spill into the shared generator: a sequence of `writeFieldName`/value calls, or a raw token-stream copy, against the gzip-backed shared generator.
3. Disk fills (or the gzip stream throws) partway through the copy — after `writeFieldName("name")` but before its value, or between two complete fields.
4. The shared generator is now stranded **at object context, mid-record** — the exact state per-record isolation was sold to prevent. F120's text says isolation makes "a swallowed broken record … never strand the shared generator at object context"; the copy-out re-introduces that strand from a *healthy* record under an environment failure.
5. On the fail-fast path, the `:221`-equivalent rethrow propagates and `close()` runs `writeEndObject` (`DatabaseExport:277`). At object context that **succeeds** (jackson 2.21.4 `writeEndObject` checks only `inObject()`, per F118's own grounding) — it auto-closes the half-written record and every enclosing scope, then `atomicMoveWithFallback` promotes. The dump reaches the final name carrying a truncated record `{"@rid":"#k:0","name":"foo"}` missing the rest of its fields — structurally valid JSON, silently accepted at import (the importer's `fromStringWithMetadata` parses it as a complete-but-short record; section presence / F75 sees all six sections; the record-loop terminates normally at `]`).

This is precisely the dangerous case the prompt flags under F118 angle 3 (a fault between two complete properties leaves valid JSON missing the rest of the record, not caught by section-presence), reached through F120's new copy-out surface even after isolation lands. F100's "fail-fast abort strands at *array* context, so `writeEndObject` throws and no file is promoted" holds only when the strand is *between* records; a copy-out failure *inside* a record strands at object context and inverts that guarantee.

**Why it matters:** F120 claims to *strengthen* the isolation invariant ("whole or discarded whole … for any record size") while actually relocating the stranding risk from render-time to copy-out-time and leaving the object-context promote (F118's silently-accepted-bad-data case) reachable on the fail-fast path. The migration's whole purpose is faithful data transfer; a silently truncated record is the worst outcome the design set out to forbid.

**Resolution direction:** the isolation invariant must cover the *copy-out*, not just the render. Either (a) pin that a copy-out failure is itself a fail-fast abort that must leave **no file at the final name** — which forces the `close()`-path contract to detect "generator left mid-record" and refuse promotion (the F100 outcome pin "no-file-at-final-name whatever the close-path implementation" must be extended to object-context strands caused by copy-out, not only array-context strands), or (b) make the shared generator's per-record write transactional at the gzip-stream level (not free), or (c) restate F120's guarantee honestly: isolation bounds *render* failures whole-or-nothing; copy-out failures are environment faults handled by the no-file-at-final-name + primary-exception pins (F119), and the object-context-promote-of-a-truncated-record must be explicitly excluded by a render-then-atomic-append discipline. As written, the resolution overclaims.

---

### U2 — F120 is silent on spill-file lifecycle (cleanup on success / discard / exception) and on naming collision; the resolution asserts "discarded on render failure" but pins no delete site, no exception-path delete, and no collision-free name [MAJOR]

**The fix's angles 2 and 3, unaddressed in the ledger text.** The F120 resolved block and every D20/F109/F94 carrier sentence say only "spill to a transient temp file … rendered there, streamed into the dump on success, discarded on render failure." Three lifecycle holes the text never closes:

1. **Stream-in-failure cleanup.** "Discarded on render failure" names the render-fault path. The copy-out-failure path of U1 is unaddressed: when stream-in throws (U1 step 3), is the spill temp file deleted? On the fail-fast abort the process tears down, but a best-effort run continues to the next record — an orphaned spill temp accumulates per failed copy-out. No delete site is pinned for this path.
2. **Success-path cleanup.** After a spilled record streams into the dump successfully, the spill temp must be deleted. The text says "streamed into the dump on success" but never "and then deleted." A best-effort export of a DB with many oversized records leaks one temp file per oversized record for the run's duration (or forever, if the process is killed). On a migration of a large DB this can itself exhaust the spill volume.
3. **Naming / collision.** The legacy exporter's only temp is `<name>.json.gz.tmp` (`DatabaseExport:87`). The spill temp needs a *distinct* name. The text gives none. Two collision risks: (a) a fixed spill name collides with `<name>.json.gz.tmp` or with a sibling spill if two records spill concurrently (they can't in the single-threaded exporter, but a careless implementer reusing one spill file across records without truncation re-derives U1); (b) a concurrent export of the *same* DB to the same directory (two operators, or a retry overlapping a stuck run) collides on a non-unique spill name and one corrupts the other's spill. The project's own file-isolation rule (unique suffix per process) is exactly this hazard.

**Why it matters:** an offline migration tool that leaks temp files or collides on spill names can fail a large migration for reasons unrelated to the data — the opposite of F120's stated goal ("a migration tool must export any record the storage holds"). These are the standard temp-file-discipline obligations; the resolution claims the spill path is sound without stating them.

**Resolution direction:** pin the spill temp lifecycle as a try/finally (or close-with-delete) that deletes on *all three* exits — success-after-stream-in, render-discard, and copy-out/exception — and pin a per-export-unique spill name (e.g., `Files.createTempFile` with a DB-and-export-scoped prefix, or `<name>.json.gz.tmp.spill.<uuid>`), distinct from the dump `.tmp` and collision-free against a concurrent same-DB export. State that a leaked spill never promotes (it is not the dump `.tmp`).

---

### U3 — F120's O(threshold) claim is violated by a single field value larger than the threshold: the in-memory buffer must hold the whole value in one allocation before any spill can trigger [MINOR]

**The fix's angle 4.** F120 claims "O(threshold) memory for any record size." But the threshold gates the *buffer*, and the buffer is fed by the renderer one token at a time. A single scalar field whose *value* exceeds the threshold — a large base64 blob (F120's own grounding cites base64 inflation), a multi-megabyte string property, a `maxRidbagStringSizeBeforeLazyImport`-class ridbag string (the import side caps these at 100 MB, `DatabaseImport:123`, so the export side can emit them) — cannot be split across the spill boundary mid-value. Jackson's `writeString` for one value is a single call; to spill it you must already have the whole `String`/`byte[]` materialized in heap (it came from the record object `rec` itself, which is already fully in memory). So:

1. The threshold is, say, 8 MB. A record has one 40 MB string property.
2. The renderer reaches that property. To write it (to buffer or spill), the 40 MB `String` is already resident (it is a field of the in-memory record).
3. Whether the buffer is in-memory or spilled, the value is held whole at least once in heap during the `writeString` call.

The peak heap for that record is O(largest single value), not O(threshold). The "O(threshold) for any record size" claim is false for a record whose *single field* exceeds the threshold; it is only true for records whose size comes from *many small fields* (where spill caps the accumulation).

**Why it matters:** F120 sold spill as the fix for the OOME class ("a too-large record spills rather than exhausting the heap, so 'discarded whole' is always deliverable"). A too-large *single value* still exhausts the heap regardless of spill, because the record object holding it is already in memory before render begins. The OOME class is *narrowed* (many-small-fields records are bounded) but not *eliminated*, and the unqualified "any record size … no longer OOME" over-claims.

**Resolution direction:** restate the bound as O(threshold + largest-single-field-value), and note that the largest-single-value floor is already paid by the resident record object itself (the record is loaded whole before export — `rec` at `DatabaseExport:200`), so spill adds no new floor but cannot lower the existing one. Alternatively pin that the general-OOME-stays-fail-closed clause (promote-only-on-success, `.tmp` orphan) covers the too-large-single-value case, which makes spill's contribution "bounds accumulation across fields" rather than "bounds any record." As written, "any record size" is wrong.

---

### U4 — F121 routes the sentinel by `DatabaseExport.class` but the real error lines route by `this.getClass()`; the fix pins no `final` on `DatabaseExport`, so a future subclass silently re-opens the false-pass one category level down [MINOR]

**The fix's angle 1.** `SLF4JLogManager.log` resolves the logger category two ways (`SLF4JLogManager:48`–`:53`): for a `Class<?>` requester it uses `((Class<?>)requester).getName()` (`:50`); for an object requester it uses `requester.getClass().getName()` (`:52`). The proposed sentinel passes a Class — `LogManager.error(DatabaseExport.class, …)` — so it resolves to exactly `…db.tool.DatabaseExport`. Every real export error site passes `this` (`DatabaseExport:152/213/225/281/293/606`, all `error(this, …)`), so today they resolve to `…DatabaseExport` too, because no subclass exists. The match holds **only** because `this.getClass() == DatabaseExport.class`.

`DatabaseExport` is declared `public class … extends DatabaseImpExpAbstract` — **not `final`** (`DatabaseExport:57`). If any future work subclasses it (the sibling tool `DatabaseCompare` already extends the same abstract base; a `TransactionalSchemaExport` subclass for this very branch is plausible), then at runtime `this.getClass().getName()` is the subclass category, while the sentinel's `DatabaseExport.class.getName()` stays the parent. The sentinel then provokes its known line through the *parent* category, the real errors route through the *subclass* category, the destination check verifies the parent's destination — and a subclass whose category is filtered out (or routed elsewhere) **false-passes the liveness control**: the very failure mode F121 set out to close, displaced one inheritance level down.

**Why it matters:** F121's whole contribution is "verify the category the error lines actually travel, not a different one." A `DatabaseExport.class` sentinel hard-codes the assumption `this.getClass() == DatabaseExport.class`, which the design does not enforce. The fix closed the root-vs-export-category gap and opened a parent-vs-subclass-category gap with the same shape.

**Resolution direction:** either (a) pin `DatabaseExport` (and any export entry-point the migration ships) as `final`, so `this.getClass()` is provably `DatabaseExport.class` and the sentinel category provably matches; or (b) provoke the sentinel through the *instance* (`LogManager.error(this, …)` from inside the export tool, the same requester the real error lines use) rather than through the literal `DatabaseExport.class`, so the sentinel and the error lines share whatever the runtime category is, subclass or not. Option (b) is the more robust pin and matches the error sites exactly. The PSI item below settles whether (a) is already true.

---

### U5 — F121 says "every export error line travels the DatabaseExport category," but helper-emitted log lines on the export call tree route through their own requester categories; a `DatabaseExport`-only sentinel does not vouch for the helper categories [MINOR]

**The fix's angle 2.** F121's resolved text asserts "every `DatabaseExport` error site logs with `requester = this`" and that "every export error line travels the `…DatabaseExport` category." The first is true; the second is the over-claim. The export call tree reaches helpers that log under *their own* categories:

- `JSONSerializerJackson:1364` logs `warn(this, …)` — category `…JSONSerializerJackson`, not `DatabaseExport`. (It is `warn`, and the "Unknown format option" path is unreachable with the fixed export format string at `DatabaseExport:586`, so it is benign *today* — but it proves the call tree logs under non-export categories.)
- `FileUtils:286`/`:311` log `warn(requester, …)` where `requester` is the *caller-supplied* object. `DatabaseExport` passes `this` (`atomicMoveWithFallback(…, this)` at `:291`; `prepareForFileCreationOrReplacement(…, this, …)` at `:85`/`:88`), so those route through `DatabaseExport` — but only because the caller threads `this`; a helper that constructs its own requester would not.

F121's claim is correct *for the error level on the legacy exporter as it stands* — the actual export-failure `error()` lines all use `this`. But the resolution states it as an invariant ("every export error line travels the `DatabaseExport` category") without pinning *why* (no helper logs at error level on the export path, and the IO helpers thread the caller as requester). The new exporter (D20, YTDB-1115) adds gzip-spill IO, the `.tmp` delete, and copy-out paths (U1/U2) — fresh IO surfaces whose error logging an implementer could route under a helper category (e.g., a spill-IO utility logging `error(this=SpillBuffer, …)`). A `DatabaseExport.class` sentinel would then miss exactly the new-exporter error lines the control most needs to vouch for.

**Why it matters:** the liveness control's promise is "the error capture is wired to the place export errors land." If a new-exporter error line lands in a helper category the sentinel never exercised, the control passes while that channel is dark — the F102/F113 failure the whole control exists to prevent.

**Resolution direction:** state the invariant as a *requirement on the new exporter*, not an observation about the legacy one: every export-failure error line MUST be emitted with the export tool's own requester (so they share one category), OR the control must provoke and verify *each* distinct error category the export path uses. Given U4's option (b) (sentinel through `this`), the cleanest pin is "all export error logging goes through the export tool's requester; helpers that must log route the caller's requester through (the `FileUtils` pattern)." Then one sentinel category covers the path by construction.

---

## Failed attacks

These I attacked and they held:

- **F118 — is `"name":}` actually parse-rejected at import?** Held. The importer reads each record as a string via `JSONReader.readNext(NEXT_IN_ARRAY)` (`DatabaseImport:989`) then parses it through `jsonSerializer.fromStringWithMetadata` (`:995`), which is Jackson-based. A pending-field-name auto-closed to `"name":}` is malformed JSON (a name with no value, then end-object); Jackson's parser rejects it, the `catch (Throwable)` at `:1054` fires, and a non-`DatabaseException` rethrows (`:1066`) → import hard-fails. F118's "parse-rejected at import, not well-formed" is accurate.

- **F118 — does the head-clause classifier mislabel any innermost-context state?** Held. The classifier is "innermost generator context" (object → promote; array → `writeEndObject` throws first → no promote). `recordToJson`'s nesting (top-level object `:701`, embedded object recursion `:936`, embedded arrays `:944`/`:951`/…, embedded maps `serializeEmbeddedMap:1016`) only ever strands at an object or array scope, both of which the head clause classifies correctly. The between-sections / inside-section-object listings are genuinely subsumed illustrations, as F118 states.

- **F118 — does the F109 swallow-arm condition hold (mid-embedded-array swallow does not promote)?** Held. A legacy `exportRecord` swallow (`:597`) leaves the shared generator wherever `recordToJson` threw. If that is inside an embedded array, the next `close()` `writeEndObject` (`:277`) throws at array context (F100's verified jackson behavior) before promotion. F118's "strands at array context and does not promote" is correct. (Note: this is the *legacy* swallow, which the new exporter replaces with isolation — but U1 shows the new copy-out re-introduces the object-context strand, which is a *different* finding.)

- **F119 — can a `finally` cleanup throw fully replace the scan failure with no `addSuppressed`?** Held *as a finding*, which is exactly why F119 keeps the F94(b) discipline load-bearing. The legacy `exportDatabase` `finally { close(); }` (`:157`) does replace the in-flight exception (`close()` rethrows `DatabaseExportException` at `:284`/`:297`), which is the very hazard F119 declines to declare trivialized. F119 correctly refuses to drop the suppression discipline; the wording change "trivializes → narrows" is sound. No residual gap in F119's *reasoning* (the spill/copy-out cleanup additions land under U1/U2, not against F119's logic).

- **F119 — correlated disk-full (scan fails, then cleanup also fails).** Held. F119 explicitly names "the live case is correlated disk-full" and pins the F94(b) `addSuppressed`/log-then-rethrow discipline to keep the primary (scan) exception as the thrown one. The reasoning is internally consistent; the only thing F119 leaves to an implementer is *actually wiring* `addSuppressed` on every cleanup path — a Phase-1 obligation, not a ledger gap.

- **F121 — level direction (a threshold-dropped sentinel).** Held. F121 notes "level direction was already safe (a threshold-dropped sentinel fails the control)." Confirmed by `SLF4JLogManager:66` (`isEnabledForLevel` gate): a sentinel at error level that the category drops produces no capture, failing the control closed.

- **F121 — `SLF4JLogManager` line citations (`:48`–`:64`, per-requester cache).** Held. Verified: `loggersCache` field `:24`, category resolution `:48`–`:53`, `compute`-populated cache `:55`–`:64`. The cited line range and the per-requester-class routing claim are accurate.

- **F120 — does spill resolve the *many-small-fields* OOME class?** Held for that sub-class. A record large because of many small fields (deep embedded recursion, large collections of small entries) accumulates in the buffer and trips the threshold, spilling before the heap is exhausted. F120's OOME claim is correct *for this sub-class*; U3 only defeats the *single-oversized-value* sub-class, so F120 is narrowed, not refuted.

---

## PSI-VERIFY items

1. **`DatabaseExport` is not `final` and has no production subclass (U4).** PSI-VERIFY: `core/.../db/tool/DatabaseExport.java` — confirm `class DatabaseExport` is declared non-`final` (grep shows `public class DatabaseExport extends DatabaseImpExpAbstract<DatabaseSessionEmbedded>` at `:57`, no `final`), and run `ClassInheritorsSearch` on `DatabaseExport` to confirm zero current subclasses (grep found none, but PSI is authoritative for the deletion/extension-safety claim driving U4's resolution choice between option (a) `final` and option (b) sentinel-through-`this`).

2. **Every `DatabaseExport` error site uses `requester = this` (U4/U5).** PSI-VERIFY: `DatabaseExport.java` — confirm the six `LogManager.instance().error(this, …)` sites are at `:152`/`:213`/`:225`/`:281`/`:293`/`:606` and that none passes `DatabaseExport.class` or a helper object. (Grep confirms `error(this, …)` at those lines; PSI find-usages on `LogManager.error` scoped to the file is the authoritative check.)

3. **`SLF4JLogManager.log` category resolution is `requester.getClass().getName()` for objects vs `((Class<?>)requester).getName()` for Class requesters (U4).** PSI-VERIFY: `SLF4JLogManager.java:48`–`:53` — confirm the `requester instanceof Class<?>` branch (`:49`–`:50`) vs the else branch (`:51`–`:52`), establishing that an instance requester and a `.class` requester of a *subclass* relationship resolve to *different* category names.

4. **No error-level helper log on the export call tree under a non-`DatabaseExport` category (U5).** PSI-VERIFY: call-hierarchy from `DatabaseExport.exportDatabase` / `exportRecords` / `exportSchema` / `exportIndexDefinitions` over `JSONSerializerJackson.recordToJson`, `IndexDefinition.toJson`, `serializeEmbeddedMap`, and the gzip/IO layer — confirm none emits `LogManager.error(<own-this>, …)` (grep found only `JSONSerializerJackson:1364` `warn(this,…)` and `FileUtils:286`/`:311` `warn(requester,…)` with the caller threading `this`; PSI confirms no *error*-level helper site escapes the `DatabaseExport` category on the export path).
