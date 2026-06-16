# Pass-2 code-grounded author — round 1 change summary

Edited `design.md` in place (additive, current-state-then-change grounding). The
five worklist ranges in `readability-feedback-design-pass2.md` (76 findings, 0
GAPs) drove the work.

## Important context: a prior uncommitted Part-1 round was already on disk

When I opened the file, the working tree already held an uncommitted rewrite of
**Part 1 (Range 2, design.md:212-355)** relative to HEAD `387f37bec8`. That round
had already applied the exact code-grounded translations Range 2 needed
(`polymorphicCollectionIds` gloss, the `owner`-final + object-reference reasoning
for why `fromStream` re-parse is required, the `executeInTxInternal` self-commit
hazard, the `<= -2` provisional-id rationale, the `commitChanges` deferral, the
`ScalableRWLock` non-reentrancy deadlock, the global-table/counter persist
failures). The file was also being actively written during my first minutes
(size grew 52665 → 53949, md5 changed 4×, the `Edit` tool returned
"modified since read" repeatedly) before it stabilized. I treated that on-disk
Part-1 content as my base and **did not revert or re-author it** — it aligns with
the same code facts I independently verified below, so Range 2 is considered
already addressed by that round. All my own edits are in **Ranges 1, 3, 4, 5**.

If the verifier finds the on-disk Part-1 round unsatisfactory, that is a separate
question from my Ranges 1/3/4/5 work; flag it but do not attribute it to this
round.

## Findings addressed (by range)

### Range 1 (design.md:1-211) — Overview / Core Concepts / Class Design / Workflow

- **6-12** (run-on comma-splice): split the three consequences into separate
  sentences under "That order has three consequences." Overview stays 39 lines
  (≤40 cap).
- **38-41** (verbless roadmap): rewrote as one sentence with a verb ("The document
  defines … lays out … then develops …").
- **46-47** (split-predicate meta-instruction): "so the delta … is visible at a
  glance" → "so the change from today is explicit."
- **134-141** (Class Design derived-state ripple bare assertion + parenthetical
  gloss): linearized the `fromStream`-vs-clone reasoning with the grounded
  `owner`-final + object-reference cause; dropped the trailing parenthetical.
- **172-176** (nominalized "reconciliation, promotion, overlay publication" copula
  subject; "four-lock sequence" named as pre-defined): gave the subject a real
  actor (`AbstractStorage.commit` "does its work: it reconciles … promotes …
  publishes …") and named the four-lock order inline.
- **205-210** (Workflow: "lock-free inner primitives" + "fires one forceSnapshot"
  terse): named the inner primitives as the no-lock collection/engine creation
  primitives; glossed `forceSnapshot` as clearing the cached schema snapshot.

**Deliberately left in Range 1 (guardrail):** the two inline inventories at
**23-29** ("Four primitives …") and **32-36** ("Several subsystems …"), and the
sub-findings inside them (**24-25, 32-33**). The prompt caps Overview at ≤40 lines
and marks these two inventories as cap-constrained forward-pointers to glossed
downstream sections. Fixing them as full lists/linearized prose would breach 40
lines, so they stay. The Core Concepts entries (**54-58, 64-69, 71-75**) are
genuine term→definition list items, which house-style § Inline-header lists
explicitly permits; left as-is. **198-199** ("four-lock order" vs two named locks)
lives inside a Mermaid sequence-diagram body, which the guardrail forbids editing;
left untouched (the four-lock order is now named in prose at 172-176 and in Part 3).

### Range 3 (design.md:426-572) — Part 2 (index transactionality)

- **428-431** (Part 2 intro 50-word run-on): split; lead with the plain claim
  (indexes need the same isolation, reached via an overlay).
- **451** (passive "is sourced from the index manager"): named the actor ("The
  snapshot build reads its per-class index list from the index manager").
- **457-458** (negative parallelism "lazy invalidation, not eager reconstruction"):
  restated positively ("The rebuild only discards the stale cached set and lets
  the next read rebuild it on demand").
- **461-462** (dropped relative pronoun): "the per-record tracking that the
  rebuild surfaces."
- **466-471** (three-step commit as disconnected one-liners + "are written"
  passive): converted to a numbered list per § Mechanism traces.
- **493-495** (TL;DR participial fragments "accepting the stall", "justified by"):
  rewrote into full sentences.
- **499-501** (roundabout negation "uses no X and no Y" + dense chain +
  `stateLock` reentrancy): grounded the deadlock in `ScalableRWLock` being
  non-reentrant; positive framing ("It does the scan directly, without a copied
  session or a nested batch transaction. Both of those would re-acquire …").
- **502-508** (two-source mechanism, negation-then-exception "final-state puts
  only … never a deleted row"): linearized; "with deleted rows left out"; grounded
  the heap-bound rationale (forward build + recovery replay both hold the unit's
  rows in heap).
- **512-514** (engine-less throw subjectless fragment): rejoined into a clause with
  a subject.
- **548-552** (roundabout negation "never `<className>_<counter>`" + "non-WAL-safe
  rename path" named before defined): added the current-state baseline (today's
  name is `<className>_<counter>`, so a class rename renames the collection file
  through the one un-journaled rename path) then the change.
- **554-555** ("cosmetically stale" placeholder-adjective): rewrote to "Only the
  index's own stored name lags … which changes no query result."
- **559-561** (dangling "so" clause "a uniform WAL replay model"): gave it a verb
  ("replays the WAL through one uniform path").

### Range 4 (design.md:573-745) — Part 3 (mutex + lifecycle)

- **575-580** (Part 3 intro run-on): split into one concern per sentence; glossed
  the pool-teardown-wedge concern.
- **584-588** (TL;DR forward-ref "the four locks"): named the four locks inline in
  the TL;DR.
- **596-602** ("write-routed mutation", "`SchemaProxy`/index-routing layer"
  unglossed; deadlock chain folded): split into its own paragraph; linearized the
  park-while-holding-write-lock deadlock one link per sentence.
- **608-616** ("commit-side promotion", "lock-guarded shared maps", "one registry
  over", `reload`): grounded the schema-lock-before-stateLock ordering in
  `reload` taking `SchemaShared.lock` then the state read lock; named the
  index-manager lock as "the other shared registry the commit publishes into."
- **616-620** ("legal embedded session alternation" / "self-deadlock on its own
  hold"): rewrote to explain the embedded-session-inside-another scenario plainly.
- **663-666** ("stale presenter" used before glossed): added a one-clause gloss at
  first use.
- **680-686** (mid-flight deadlock derivation folded): linearized into stepwise
  prose; grounded `checkOpenness` (throws once status reads CLOSED, guards commit
  and rollback).
- **710-714** ("the normal heal presents nothing" reuses undefined "present";
  `rollbackInternal` `clear()`/`close()` wedged in): rewrote to "the release path
  reads the acquisition's ordinal from a session-side record …"; grounded the
  `clear()`/`close()` field-wipe.
- **716-720** (bare five-item resource list): glossed "snapshot-floor holder
  accounting" inline; "Teardown runs only on the owning thread" (de-nominalized).
- **730-733** ("commit-phase zombie" telegraphic gloss): full gloss as a
  transaction whose session was closed mid-commit.
- **733-737** ("no memory edge … late-visible status" happens-before compressed):
  linearized; named it a non-volatile field with no happens-before edge.

**Deliberately left in Range 4:** the **686-694** store-then-load (Dekker) pair and
its surrounding handshake prose are the irreducible-density core the prompt warns
will not reach zero. They are already linearized and carry a Mermaid flowchart;
the per-field "What the pieces are" block is a genuine definition list. I made
targeted gloss/linearize fixes around them (663-666, 680-686, 710-714) but did not
rewrite the handshake itself.

### Range 5 (design.md:746-889) — Part 3 freezer + Part 4 (migration)

- **758-759 / 759-763 / 748-753** (freezer terms "registration property", "entrant
  choice", "registration sites", "in-window gate" unglossed; TL;DR four undefined
  entities): added a current-state baseline for `OperationsFreezer` (one
  undifferentiated gate today; `freezeRequests` count; park-vs-throw on a
  throw-exception supplier) then the change; named the registration sites
  (`DatabaseSessionEmbedded.freeze` for operator, `doSynch`/backup/rebuild for
  transient). TL;DR left as the section's first-glance summary now that the body
  grounds its terms.
- **769-773** ("held metadata locks", "never finishes queueing"): named the three
  metadata locks released on re-probe; explained that the write-lock request is
  never issued, so no writer queues ahead of in-flight reads.
- **791-797 / 797-801** ("operator-kind arm", "cuts and unparks", ambiguous "it"):
  grounded "cuts and unparks" in the existing `cutWaitingList` + `LockSupport.unpark`
  release step that the operator arm reuses at engage time; resolved the ambiguous
  "it" to "the operator arm's unpark".
- **846-848** (roundabout negation "loudly incomplete, and never silently
  partial"): restated positively as a loud verification failure that cannot leave
  a silently half-migrated target.
- **850-855** (whole-or-fatal run-on with stacked compounds; "promotes" no object;
  "the shared dump" unintroduced): split into First/Second; named the dump file;
  gave "promote" its object (the dump promoted to its final name).
- **856-858** (spill threshold omitted; "shedding it" idiom): "spills the overflow
  to a transient file" once buffered bytes pass a size threshold; "rather than
  skipped" replaces "shedding it".
- **863-868 / 866-868** ("structurally-closed-but-malformed", "pending field name",
  "best-effort-marked" compounds; "not accepted as a clean record" negation;
  run-on): glossed each (well-formed JSON but malformed content; dangling field
  name from a write failure between a field's name and value; a dump the exporter
  marked best-effort); grounded `EXPORTER_VERSION` 14 → 15.

## New current-state claims added, with code sources (for verifier accuracy check)

All verified by Read/grep in this worktree; PSI used where noted. Source root:
`core/src/main/java/com/jetbrains/youtrackdb/internal/`.

1. **`SchemaClassImpl.owner` is `protected final SchemaShared owner`; superclass/
   subclass links are `List<SchemaClassImpl>` (direct object references).**
   `SchemaClassImpl.java:72` (`protected final SchemaShared owner`), `:78`
   (`protected List<SchemaClassImpl> superClasses`), `:80`
   (`protected List<SchemaClassImpl> subclasses`), `:79`
   (`protected int[] polymorphicCollectionIds`). Grounds the Class Design 134-141
   re-parse-vs-clone reasoning. (Part-1 round used the same facts.)

2. **`fromStream` re-parses the schema from an `EntityImpl`, rebuilding classes,
   properties, and the inheritance tree, with superclasses referenced by name.**
   `SchemaShared.java:487` `fromStream(...)` takes `lock.writeLock()`, clears and
   rebuilds `classes`/`properties`, re-links superclasses by name
   (`superClassNames`, `legacySuperClassName`, lines ~530-560).

3. **`forceSnapshot()` clears the cached schema snapshot under `snapshotLock`, so
   the next reader rebuilds from committed state.** `SchemaShared.java:218-229`
   (`snapshot = null` under `snapshotLock`). Grounds the Workflow 205-210 gloss.

4. **`SchemaProxy` holds a `delegate` (the shared `SchemaShared`) + a `session` and
   delegates each call (`extends ProxedResource<SchemaShared>`); `getClass`/
   `createClass` wrap results in a fresh `SchemaClassProxy(cls, session)`.**
   `SchemaProxy.java:45-48, 70-73, 212-220`. Grounds the "routing seam" / captured-
   delegate framing (Part-1 round).

5. **`stateLock` is a `ScalableRWLock`, declared `protected final ScalableRWLock
   stateLock` and constructed `new ScalableRWLock()`; `ScalableRWLock` is
   explicitly Not Reentrant.** `AbstractStorage.java:341` (decl), `:447` (ctor);
   `common/concur/lock/ScalableRWLock.java:64` (Javadoc "Not Reentrant"), `:78`
   (`implements ReadWriteLock`). This is the load-bearing reference-accuracy fact
   behind every "re-acquiring `stateLock` deadlocks" claim (Range 3 build, Part 3
   lock order). **Verified by Read of the class Javadoc, not just grep.**

6. **`createIndex` throws "Cannot create a new index inside a transaction" when a
   tx is active.** `IndexManagerEmbedded.java:306-307`
   (`if (session.getTransactionInternal().isActive()) throw …`). Grounds the
   Part-1 throw-guard list (Part-1 round) and confirms the de-guard target.

7. **`addCollectionToIndex` / `removeCollectionFromIndex` self-commit via
   `session.executeInTxInternal(...)`** (a nested transaction that commits on
   return). `IndexManagerEmbedded.java:99-128` and `:131-...`. Grounds the
   membership self-commit hazard (Part-1 round) and the lock-order ripple.

8. **`rollbackInternal()` calls `clear()` during rollback.**
   `FrontendTransactionImpl.java:356-385` (`clear()` at `:385`), `close()` at
   `:948`, `clear()` defined at `:972`. Grounds the Part-3 710-714 "must survive
   `rollbackInternal`'s `clear()`/`close()` wipes" claim.

9. **`checkOpenness()` throws if `status == STATUS.CLOSED`; it guards commit/
   rollback and reads a plain status field.** `DatabaseSessionEmbedded.java:3341-
   3345`. Grounds the Part-3 680-686 wedge derivation and the 733-737 happens-
   before edge-case (non-volatile read, no memory edge to the close write).

10. **`OperationsFreezer` is one undifferentiated gate today: `freezeRequests`
    (AtomicInteger) is raised by `freezeOperations`; `startOperation` parks a
    starting op via `LockSupport.park` when `freezeRequests > 0`, and
    `throwFreezeExceptionIfNeeded` throws when a throw-exception supplier was
    registered; `releaseOperations` decrements and, at zero, calls
    `cutWaitingList()` then `LockSupport.unpark` on each node.**
    `OperationsFreezer.java:18-19, 30-57, 72-86, 88-112, 114-118`. Grounds the
    entire freezer current-state baseline (Range 5 freezer body) and "cuts and
    unparks the waiting list" (= `cutWaitingList` + per-node `unpark`).

11. **Operator freeze vs transient quiesce registration sites.** Operator:
    `DatabaseSessionEmbedded.freeze(throwException)` → `storage.freeze(db,
    throwException)` → `freezeWriteOperations(() -> new
    ModificationOperationProhibitedException(...))` when throwException
    (`AbstractStorage.java:3887, 3900-3906`; `DatabaseSessionEmbedded.java:2167-
    2172`). Transient: `doSynch()` → `freezeWriteOperations(null)`
    (`AbstractStorage.java:3747-3749`). Grounds the freeze-kind taxonomy
    registration-site naming and the park-vs-throw distinction.

12. **`reload` takes `SchemaShared.lock` write lock and runs "inside a storage's
    shared lock".** `SchemaShared.java:355` (`lock.writeLock().lock()`) with
    Javadoc "Reloads the schema inside a storage's shared lock" (`:353-354`).
    Grounds the Part-3 608-616 lock-order rationale (schema lock before
    `stateLock` because the data path can hold them in the opposite nesting).

13. **`commitChanges` is the WAL-apply step; the only path that applies the
    atomic operation, skipped on rollback.** `AtomicOperationsManager.java:341`
    (`lsn = operation.commitChanges(commitTs, writeAheadLog)`), with comments at
    `:323, :339, :360` confirming it is success-path-only. Grounds the Part-1
    "deferred to the post-`commitChanges` success path" (Part-1 round). Confirmed
    while editing; not newly added by me but verified.

14. **Today's collection name is `<className>_<counter>`, and a class rename
    invokes `renameCollection`.** `SchemaClassEmbedded.java:562`
    (`name.toLowerCase(Locale.ENGLISH) + "_" + ...`), `:311`
    (`renameCollection(session, oldName, this.name)`). Grounds the Range 3
    548-552 current-state baseline for base-keyed engine files.

15. **`EXPORTER_VERSION` is currently 14.** `db/tool/DatabaseExport.java:59`
    (`public static final int EXPORTER_VERSION = 14;`), written at `:367`. Grounds
    the Range 5 "bumps … from 14 to 15" current-state baseline.

16. **The schema version check on open already rejects-and-redirects to export/
    import.** `SchemaShared.java:501-508` (version mismatch → ConfigurationException
    "Please export your old database … and reimport it"), `CURRENT_VERSION_NUMBER
    = 4` (`:62`). Grounds the Part-4 "version check rejects with a redirect
    message" as the existing pattern the design reuses (claim was already in the
    doc; verified accurate, not newly asserted).

## Code-ambiguous places where I preserved the doc verbatim (did not guess)

- **`MetadataWriteMutex`, `TxSchemaState`, `IndexOverlay`, `copyForTx`,
  `gateSchemaCommit`, the cut-and-unpark operator arm, the Dekker teardown-intent
  mark, the commit-local allocator** are design-introduced (net-new) symbols with
  no current implementation. I did not invent current-state behavior for them; I
  described only the *current* mechanisms they build on (the bare `Semaphore`
  `release()`, `OperationsFreezer`'s existing count/park/cut, `forceSnapshot`,
  `commitChanges`) and left the design's own behavior as the doc states it.
- **The "snapshot-floor holder accounting" / snapshot-pin model and the two
  remaining lock-based read sites (one per-record, one per-MATCH)** are referenced
  by the design but I did not locate and verify their exact call sites; I glossed
  "snapshot-floor holder accounting" only with the doc's own meaning (the
  per-transaction record of which snapshot the transaction pinned) and left the
  per-record/per-MATCH edge-case bullet verbatim.
- **`ClassIndexManager`'s exact cached-set field** — I confirmed it reads
  `cls.getRawIndexes()` (`ClassIndexManager.java:58, 78, 421`) but did not pin the
  precise snapshot-init materialization field, so I kept the doc's "cached index
  set that the snapshot materializes once, at snapshot init" wording without
  asserting a field name.

## Verifier scrutiny checklist

- Mechanical `dsc-ai-tell` (`design-mechanical-checks.py --scope whole-doc
  --target design`) returns PASS, 0 findings.
- Overview is 39 lines (≤40 cap). Line 1 `workflow-sha` stamp untouched. No Mermaid
  body, no D-record/invariant list, no heading edited.
- No decision, invariant, mechanism, or the four-lock order changed — verify the
  four-lock order still reads metadata-mutex → `SchemaShared.lock` → index-manager
  lock → `stateLock.writeLock` everywhere (Core Concepts, Class Design 172-176,
  Part 3 TL;DR + body, freezer 769-773).
- Scrutinize claims #5 (ScalableRWLock non-reentrant — load-bearing), #10/#11
  (freezer current-state and registration sites — largest new grounding block),
  and #14/#15 (collection-name derivation, EXPORTER_VERSION) for accuracy.
- Note the prior uncommitted Part-1 (Range 2) round on disk is NOT my work; judge
  it separately if needed.
