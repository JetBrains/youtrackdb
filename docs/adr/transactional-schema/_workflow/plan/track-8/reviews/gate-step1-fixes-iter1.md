# Gate verification — Track 8 Step 1 review fixes, iteration 1

- **Diff under gate:** `550eed6673..1bc71db713` on `transactional-schema` — two commits:
  `931e264f48` (code + tests, 6 files) and `1bc71db713` (track-file / design-doc records,
  2 files).
- **Inputs:** compact finding index + full review reports
  `track-8/reviews/baseline-step1-iter1.md` and `track-8/reviews/crash-safety-step1-iter1.md`
  (both read in full).
- **Mode:** read-only; no Maven runs; no file modification outside this report. All build/test
  numbers in the review-fix Episode are treated as *reported, not re-verified*. Every claim
  below is my own code trace (file:line at `1bc71db713`) — not a restatement of the reviews.

---

## 1. Decision criteria

- **VERIFIED** — the approved remedy is fully delivered, the delivered artifact is factually
  correct per my own trace, and (for tests) the assertion would go red on the regression it
  pins.
- **REJECTED** — the delivered artifact claims to discharge the remedy but is factually wrong
  or would not catch the pinned regression.
- **STILL OPEN** — the remedy (or a required part of it) is absent from the diff.
- **MOOT** — the underlying finding no longer applies at this HEAD.
- **RG findings** — any breakage the fixes themselves introduced (code or doc-consistency),
  numbered from RG1.
- Scope check passes only if every hunk of the code diff maps to an approved remedy, and the
  doc edits do not contradict adjacent (untouched) text.

## 2. Own-trace premises

- **T1 (persistence mechanism).** `SchemaShared.addBlobCollection`
  (`SchemaShared.java:1598-1607`): `acquireSchemaWriteLock` → `checkCollectionCanBeAdded` →
  `blobCollections.add` → `releaseSchemaWriteLock(session)` — the 1-arg overload delegates
  with `iSave=true` (`:791-793`); at `modificationCounter == 1` with an `AbstractStorage`
  backend it calls `saveInternal(session)` (`:795-805`). `saveInternal`
  (`:1498-1518`): tx-local copy returns early (`:1500-1506`); an ACTIVE transaction throws
  `SchemaException("Cannot change the schema while a transaction is active…")`
  (`:1508-1513` — the doc citations are line-exact); otherwise
  `session.executeInTx(transaction -> toStream(session))` — a synchronous, per-iteration,
  self-committed root save.
- **T2 (no downstream save).** After the register loop in `SharedContext.create`
  (`SharedContext.java:223-227`) only the geospatial-listener try/catch and `loaded = true`
  follow (`:229-243`); nothing schema-saving follows in
  `DatabaseSessionEmbedded.createMetadata` either (`DatabaseSessionEmbedded.java:598-603`,
  which calls `shared.create(this)` with NO wrapping transaction — contrast `loadMetadata`
  `:605-613`, which wraps in `executeInTx`). So today no tx is active at genesis and each
  registration self-commits — the CORRECTED record is TRUE and the struck-through original
  ("loop only mutates the in-memory set; persistence rides a downstream save") was indeed
  false.
- **T3 (routing).** `SharedContext.schema` is field-typed `SchemaShared`, instantiated
  `SchemaEmbedded` (`SharedContext.java:47`, `:90`); `SchemaEmbedded` does NOT override
  `addBlobCollection` (grep of `SchemaEmbedded.java`: no match). The loop's call at `:225` is
  therefore a direct committed-instance call — it does NOT route through
  `SchemaProxy.addBlobCollection` (`SchemaProxy.java:460-462` →
  `resolveForWrite().addBlobCollection(session, collectionId)`). `resolveForWrite()`
  (`SchemaProxedResource.java:108-124`): no active tx → returns the committed delegate
  (behaviorally identical to the direct call); active tx → returns the tx-local copy whose
  `saveInternal` returns early (T1). Both halves of the seam annotation are mechanism-exact.
- **T4 (guard + rethrow path).** The negative-count guard sits at
  `AbstractStorage.java:1529-1536`, after the single config read (`:1520-1522`) and before the
  creator loop (`:1537-1539`), inside the storage-create atomic-op lambda (`:1491-1543`); it
  throws `StorageException(name, "Invalid value <n> of configuration parameter
  '<key>': …can not be negative")` where `<key>` is
  `GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT.getKey()`. `StorageException` extends
  `CoreException` → `BaseException` → `RuntimeException`. `create()`'s
  `catch (StorageException e)` rethrows it directly after `closeIfPossible()`
  (`AbstractStorage.java:1412-1416`), so the knob-naming message survives to the caller;
  the throw propagates out of the lambda → WAL rollback of the whole create op (the
  pre-existing `endAtomicOperation(op, error)` mechanism). Zero passes the guard and makes
  the loop a no-op.
- **T5 (single production read / prefix constant).** Grep `\$blob` over `core/src/main` +
  `server/src/main`: only `MetadataDefault.BLOB_COLLECTION_NAME_PREFIX = "$blob"`
  (`MetadataDefault.java:49`) plus comments. Consumers: creator loop
  (`AbstractStorage.java:1538`) and the registrar pattern
  (`SharedContext.java:42-43`, `Pattern.quote(prefix) + "\\d+"` — semantically identical to
  the old `\$blob\d+`, `matches()` still anchored). Grep `STORAGE_BLOB_COLLECTIONS_COUNT`:
  production = declaration + the one `doCreate` read + comments; all other hits are the new
  test class.
- **T6 (live view).** `AbstractStorage.getCollectionNames()` returns
  `Collections.unmodifiableSet(collectionMap.keySet())` (`AbstractStorage.java:~2337-2352`)
  — a live view, iterated after the internal `stateLock` is released; the `List.copyOf`
  snapshot rationale in the new comment is accurate.
- **T7 (importer window).** `DatabaseImport.importSchema` (`DatabaseImport.java:495`; the
  blob block `:528-543`) parses the dump's raw blob-collection ids and resolves each via
  `session.getCollectionNameById(collection)` in the TARGET id space before registering —
  the CS48 bullet's mechanism and line citation are accurate; the window is armed by the
  renumbering commit `6611cbf6b2` and its fix is Step 5's §A3 (matching design-drafts).

## 3. Per-finding verdicts

### CS47 — should-fix — VERIFIED

Criteria: (a) corrected Surprises/Episode record is TRUE per my own trace; (b) Step-3 seam
annotation sits in Step 3's spec where an implementer cannot miss it; (c) matching as-built
note at design-drafts §G2.b; (d) NO routing code change.

- (a) `track-8.md:87-98` (Surprises bullet, strikethrough of the wrong claim + corrected
  mechanism) and `track-8.md:509-512` (Episode Surprises paragraph correction): both state the
  per-iteration `releaseSchemaWriteLock`→`saveInternal`→`executeInTx(toStream)` self-commit
  and "NOTHING downstream of the loop saves the schema" — confirmed by T1+T2. The
  Step-3-consequence claim (direct call throws `SchemaException` under an active tx; proxy
  route lands on the tx-local copy) — confirmed by T1+T3, line citations exact.
- (b) The annotation is embedded in Step 3's spec body at `track-8.md:268-277`, inline in the
  very clause that orders the tx-wrap ("the blob registration (Step 1's loop, now tx-wrapped —
  **CS47 seam annotation:** the registration MUST be re-routed through
  `SchemaProxy.resolveForWrite()`/the session…"). Unmissable at the implementation site. The
  Step-1 seam-ownership bullet is also corrected (`track-8.md:226-229`: "'unchanged' was
  inaccurate").
- (c) `track-8-design-drafts.md:141-147`: the §G2.b as-built correction directly follows the
  superseded proxy-routing sentence, explicitly marks it as what "Step 3's tx-wrap must
  INTRODUCE", cites `SchemaShared.java:1508-1513`, and points at the track-8.md Step-3 seam
  annotation. The adjacent CN50 single-read paragraph is untouched and remains accurate (T5).
- (d) The `SharedContext.java` diff contains only the CQ13 pattern derivation and the CQ14
  `List.copyOf`; the registration call at `:225` is still the direct `SchemaShared` call (T3).
  No routing change shipped. ✔

One residual: a third, untouched "unchanged" claim survives at `track-8.md:318` — see **RG1**.
It does not negate CS47's remedy (all four demanded items delivered and true) but leaves an
internal contradiction the orchestrator should queue for the next doc commit.

### CS48 — suggestion — VERIFIED

Criteria: bullet exists and is accurate. `track-8.md:101-106`: names the window
(`DatabaseImport.importSchema`, `DatabaseImport.java:528-541`), the arming commit
(`6611cbf6b2`), the closer (Step 5 §A3), the design pin (FM-M16 / M.5 #13), and the practical
consequence ("do not trust blob-bearing legacy-dump imports on this branch mid-track").
Mechanism and citation confirmed by T7 (block spans `:528-543`; the cited `528-541` covers the
resolution/registration lines — accurate). ✔

### CS49 — suggestion — VERIFIED

Criteria: disposition recorded. Episode item (3) (`track-8.md:532-535`) records that the
enlarged create op's first direct crash-path tests arrive with Step 3's pin G.5 #9. Step 3's
Tests row (`track-8.md:307-314`) does carry pin #9 (W-state containment tests, both profiles),
so the obligation is genuinely lodged in Step 3's spec. Minor wording imprecision, no verdict
impact: the Episode's parenthetical "(both carried in the Step-3 seam annotation)" overstates —
the seam annotation (`:271-277`) carries the TQ12 re-grep and the CQ14 note, while the CS49
crash-path item is carried by Step 3's pre-existing Tests row, not the annotation. The
obligation is not lost; only the pointer is loose.

### TQ12 — suggestion — VERIFIED

Criteria: disposition recorded — Step-3 reviewer must re-grep the single production read.
Recorded twice: Episode item (3) (`track-8.md:532-535`) and, operationally, inside the Step-3
seam annotation itself (`track-8.md:271-274`: "re-grep that `STORAGE_BLOB_COLLECTIONS_COUNT`
still has exactly ONE production read after the tx-wrap (TQ12/CN50)"). Current state re-checked
myself: exactly one production read (T5). ✔

### BG12 — suggestion — VERIFIED

Criteria: guard placement + message name the knob; 0 stays valid; both new tests assert the
pinned behaviors and would fail on regression.

- Guard: `AbstractStorage.java:1529-1536` — after the single read, before the creator loop,
  inside the atomic op; message embeds the value and `…STORAGE_BLOB_COLLECTIONS_COUNT.getKey()`;
  the accompanying comment correctly states the rollback consequence (T4). Zero passes → loop
  no-op → valid blob-less DB.
- `negativeBlobCollectionsCountIsRejectedAtCreateTime`
  (`StorageEmbeddedBlobCollectionsTest.java:249-279`): regression power traced — if the guard
  is removed, create succeeds and `fail(...)` throws `AssertionError`, which the
  `catch (RuntimeException e)` does NOT swallow → red. If the message stops naming the knob,
  the cause-chain walk (`for (Throwable t = e; …; t = t.getCause())`) plus
  `contains(…getKey())` goes red. The rethrow path preserves the message (T4:
  `catch (StorageException e) … throw e`). ✔
- `zeroBlobCollectionsCountCreatesBlobLessDatabase` (`:281-300`): pins that create SUCCEEDS at
  count 0 (would error if the guard ever rejected 0) and that both the physical `$blob*` set
  and the schema registration are empty (would go red if 0 started producing collections or
  bogus registrations). ✔

### CQ13 — suggestion — VERIFIED

Criteria: single source of truth; no remaining stringly production duplicates; grep `$blob`
across production + the step's tests.

- Production: exactly one `"$blob"` literal — the constant (`MetadataDefault.java:49`);
  creator loop uses `BLOB_COLLECTION_NAME_PREFIX + i` (`AbstractStorage.java:1538`); registrar
  pattern derives via `Pattern.quote(...)` (`SharedContext.java:42-43`), semantics unchanged
  (T5). ✔
- Tests: `$blob` literals remain in `StorageEmbeddedBlobCollectionsTest` (the `BLOB_NAME`
  pattern `:39` and the `"$blob" + i` layout pins) — DELIBERATE independent pins per the
  constant's javadoc ("tests pin the literal independently on purpose", ruling R3) and the
  Episode record; this is the correct shape (a test deriving from the constant could not catch
  a constant-value regression). Other test-file hits are comments only
  (`EntityImplTest.java:545`, `CommandExecutorSQLTruncateTest.java:39`,
  `StringsTest.java:117`). ✔

### CQ14 — suggestion — VERIFIED

`SharedContext.java:223`: `for (var collectionName : List.copyOf(storage.getCollectionNames()))`
— a true snapshot taken before the first self-committing registration; the source IS a live
view (T6), so the defensive copy is meaningful, and the new comment (`:216-222`) states the
rationale accurately (safe today / future-proofing). `List.copyOf` is safe here (non-null
`String` keys). ✔

### TQ11 — suggestion — VERIFIED

Criteria: assertion strength genuinely restored (distinctness + RID text format), dynamic.
`StringsTest.java:123-135`:
- `assertNotEquals(rid, ridTwo, …)` restores the distinctness pin — a bug handing both
  entities the same provisional RID now goes red (it passed under the pre-fix self-referential
  assertion).
- `rid.toString().matches("#-?\\d+:-\\d+")` (both RIDs) restores the text-format pin —
  requires the `#`, a numeric collection id, `:`, and a NEGATIVE (provisional) position — with
  no hard-coded collection id (the exact defect the Step-1 casualty fix was removing). A
  rendering-format change (e.g. dropping `#`) now goes red.
- The original shape assertion (`"O" + rid + "{ref:…"`) is retained, so nothing previously
  pinned was dropped. Both lost incidental pins of the old `#7:-2`/`#7:-3` literal are back,
  minus only the layout-dependent ids — exactly the approved remedy. ✔

### TQ13 — suggestion — VERIFIED

- (a) `blobLayoutSurvivesDiskReopen` (`StorageEmbeddedBlobCollectionsTest.java:213-247`):
  `create` precedes the `try`; ONE outer `try { … } finally { youTrackDB.drop(dbName); }` now
  spans both session blocks AND the mid-test context close/reopen; a first-block failure drops
  through the still-open first context. ✔ Residual (no action, strictly better than pre-fix):
  in the exotic case where the mid-test `createContext()` itself threw, the `finally` would
  invoke `drop` on the already-closed first context and could mask the original exception —
  pre-fix the same failure leaked the directory outright, so this is not a regression.
- (b) `CommandExecutorSQLTruncateTest.java:41-43`: the `OSecurityPolicy` query is now
  try-with-resources; `session.load(...)` executes inside the block, so ordering is sound. ✔

## 4. Scope check — PASS

`git show --stat` per commit: `931e264f48` touches exactly `SharedContext.java`,
`MetadataDefault.java`, `AbstractStorage.java`, `StorageEmbeddedBlobCollectionsTest.java`,
`CommandExecutorSQLTruncateTest.java`, `StringsTest.java`; `1bc71db713` touches exactly
`track-8.md` + `track-8-design-drafts.md`. Every hunk read; mapping is 1:1 onto the approved
remedies — SharedContext (CQ13 pattern derivation + CQ14 copy + matching comment updates),
MetadataDefault (CQ13 constant), AbstractStorage (BG12 guard + CQ13 constant use),
StorageEmbedded…Test (BG12's two tests + TQ13a restructure), Truncate test (TQ13b), StringsTest
(TQ11), docs (CS47/CS48/CS49/TQ12 records + Episode/progress entries). Nothing extraneous; in
particular NO production routing change (CS47's "no routing code change now" honored, T3) and
the valid-config create path is byte-identical apart from the same-value constant extraction
(T5) — the Episode's IT-skip rationale rests on facts I could confirm statically.

## 5. Doc-consistency check

- Surprises bullet, Episode correction, review-fix Episode, progress-log entry, Step-1 seam
  bullet correction, Step-3 annotation, §G2.b note: mutually consistent and consistent with
  the code (T1-T5). The only remaining "downstream save / only mutates in-memory" text sits
  inside the strikethrough of the corrected bullet — intentional.
- **One contradiction found (RG1, below):** `track-8.md:318`.
- Minor, no action: the Episode's "(both carried in the Step-3 seam annotation)" pointer for
  CS49 is imprecise (see CS49 verdict).

## 6. Alternative-hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| A1 | The corrected CS47 record could itself be wrong (e.g. a tx IS active at genesis, so "self-commit per iteration" would throw today) | Rejected — `createMetadata` calls `shared.create` with no `executeInTx` wrapper (T2); the green persisted-root test corroborates |
| A2 | `Pattern.quote(prefix) + "\\d+"` could match differently than `\$blob\d+` | Rejected — `\Q$blob\E\d+` with anchored `matches()` is semantically identical; ASCII `\d` unchanged |
| A3 | `List.copyOf` could throw or change iteration semantics | Rejected — non-null `String` set; snapshot-then-iterate is strictly safer; genesis single-threaded |
| A4 | The negative-count test could pass even without the guard (create failing for another reason) | Rejected — count `-1` pre-fix produced a silently-successful create (loop no-op); only the guard makes create throw, and `fail()`'s `AssertionError` escapes the `catch (RuntimeException)` |
| A5 | The guard could break a legitimate caller that relies on negative counts | Rejected — grep: no production or test site sets a negative count except the new test; default is 8 |
| A6 | `StorageException` might not surface with its message through the create wrapper | Rejected — `create()` rethrows `StorageException` as-is (T4); the test walks the full cause chain anyway |
| A7 | The StringsTest regex could be too loose to pin the format (e.g. accept a non-provisional RID) | Rejected — `:-\d+` requires a negative position; the retained shape assertion still pins the full rendering |
| A8 | The seam annotation might sit only in review/Episode prose where a Step-3 implementer could miss it | Rejected — it is inline in Step 3's binding spec clause (`track-8.md:268-277`) |

## 7. Regression findings

### RG1 — minor (doc-only) — `track-8.md:318`

Step 3's "Depends on / seam ownership" bullet still reads "OWNS the tx-wrapping of
`SharedContext.create` **(Step 1's loop mechanics unchanged)**" — a decomposition-era phrase
(introduced in `b63af8874a`, untouched by the fix commits, verified via `git log -L`). The CS47
fix corrected the equivalent claim at the Step-1 bullet (`:226-229`, "'unchanged' was
inaccurate") and added the contradicting seam annotation twenty lines above (`:268-277`, the
registration MUST be re-routed, i.e. the loop mechanics DO change at Step 3), leaving the
document internally contradictory within Step 3's own spec. Pre-fix the three sites were
consistently (if wrongly) aligned; the partial correction created the inconsistency — hence
filed as a fix-introduced regression, though the stale line itself predates the fix. Blast
radius: a reader of the Depends-on line alone inherits the refuted claim; the bold annotation
in the same numbered item dominates for any full reader. Remedy (next doc commit, one line):
reword to e.g. "(Step 1's enumeration mechanics; the registration call is re-routed per the
CS47 seam annotation above)".

No code regressions found: guard, constant extraction, pattern derivation, defensive copy, and
all four test edits are behavior-preserving on every valid-configuration path (T4-T6), and the
two behavior changes that exist (negative-count rejection; snapshot iteration) are exactly the
approved remedies.

## 8. Verdict summary

| ID | Verdict | Evidence gist |
|---|---|---|
| CS47 | VERIFIED | Corrected record TRUE by own trace (`SchemaShared:1598→791-805→1498-1518` per-iteration self-commit; nothing downstream saves — `SharedContext:229-243`, `createMetadata` tx-free); seam annotation inline in Step 3's spec (`track-8.md:268-277`) + §G2.b as-built note (`drafts:141-147`); direct-call/proxy divergence confirmed (`SchemaProxedResource:108-124`); no routing code change shipped. Residual → RG1 |
| CS48 | VERIFIED | Bullet at `track-8.md:101-106`; mechanism + citation confirmed against `DatabaseImport.java:495/528-543` (raw dump ids resolved in target space); armed-since/fix-owner/pin all match design |
| CS49 | VERIFIED | Disposition in Episode item (3) (`track-8.md:532-535`); Step 3's Tests row carries pin G.5 #9 (`:307-314`); "(both carried in the seam annotation)" pointer slightly loose, obligation not lost |
| TQ12 | VERIFIED | Re-grep obligation in the seam annotation (`track-8.md:271-274`) + Episode; current single production read re-confirmed by grep |
| BG12 | VERIFIED | Guard `AbstractStorage:1529-1536` inside the create op, names knob via `getKey()`, 0 valid; rethrow path preserves message (`:1412-1416`); negative test red-on-guard-removal (`fail()` escapes `catch(RuntimeException)`) and red-on-message-loss; zero test pins create-success + empty sets |
| CQ13 | VERIFIED | Single production `"$blob"` = `MetadataDefault:49`; creator `AbstractStorage:1538` + registrar `SharedContext:42-43` both derive; only deliberate independent literals remain in tests (documented intent) |
| CQ14 | VERIFIED | `List.copyOf` snapshot at `SharedContext:223`; source confirmed a live keySet view (`AbstractStorage.getCollectionNames`); comment accurate |
| TQ11 | VERIFIED | `StringsTest:123-135` — `assertNotEquals` restores distinctness; `#-?\d+:-\d+` regex restores provisional-RID text format, no hard-coded ids; original shape assertion retained |
| TQ13 | VERIFIED | Outer try/finally spans both session blocks + context swap (`StorageEmbedded…Test:213-247`); ResultSet try-with-resources (`Truncate…Test:41-43`); exotic closed-context-drop masking noted, strictly better than pre-fix leak |
| RG1 | new — minor | `track-8.md:318` still says "(Step 1's loop mechanics unchanged)", contradicting the CS47 seam annotation at `:268-277` and the corrected Step-1 bullet at `:226-229`; one-line doc reword needed |
| Scope | PASS | Both commits' hunks map 1:1 to approved remedies; no routing change; docs consistent except RG1 |
