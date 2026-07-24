# Gate verification — Track 8 Step 2 review fixes, iteration 1

- **Verified range:** `fbbf661d3e..39153ddcf3` on branch `transactional-schema`
  (`1858811c1e` code+tests, `39153ddcf3` track-file record). HEAD at gate time: `39153ddcf3`.
- **Inputs:** `track-8/reviews/baseline-step2-iter1.md`, `track-8/reviews/crash-safety-step2-iter1.md`
  (read in full), the two commits' diffs, and HEAD source.
- **Mode:** read-only gate — no Maven, no file modification outside this report. Recorded
  verification numbers (test counts, coverage) are checked for internal coherence only, not
  re-run.

---

## 1. Decision criteria

Per finding: **VERIFIED** = the shipped change implements the approved remedy, the claimed test
genuinely pins it (would go red if the remedy were reverted), and no side effect was introduced;
**REJECTED** = the shipped change does not implement the remedy or is wrong; **STILL OPEN** =
untouched without an approved disposition; **MOOT** = overtaken by events. Fix-introduced
breakage → **RG** findings starting RG2. Every verdict is preceded by file:line-traced premises;
alternative hypotheses are checked in §5.

Diff footprint (scope baseline): `git diff --stat fbbf661d3e..39153ddcf3` — exactly three files:
`SchemaShared.java` (+52/−16, all inside `create`), `GenesisSchemaBootstrapTest.java` (+65),
`track-8.md` (+49). Nothing else.

---

## 2. Per-finding verification

### CQ15 — entry assert guarding the top-level-tx precondition → **VERIFIED**

**Premises (HEAD source):**

1. **Placement:** the assert is the FIRST executable statement of `create`
   (SchemaShared.java:1394-1397), preceded only by its hazard comment (:1388-1393). It runs
   **before** `lock.writeLock().lock()` (:1398), before `computeInTx` (:1402), before
   `newInternalInstance` and the `identity` assignment (:1415) — i.e. before any lock
   acquisition or mutation, as the remedy required. A failed assert therefore leaves the schema
   instance and the database byte-identical to the pre-call state (no lock leak either — no
   lock is held yet).
2. **Message quality:** "SchemaShared.create must run outside any active transaction: joining an
   outer transaction would persist a provisional schema record id into the storage configuration
   (the root's record id promotes only at the outer commit)" — names the mechanism (join →
   deferred promote → provisional rid stringified) and the consequence (persisted into storage
   config). The comment (:1388-1393) additionally names the live hazard by name: "the belt
   against the genesis restructure (Track 8 Step 3) accidentally swallowing schema.create into
   the phase-1 schema transaction". Matches the baseline review's counterexample gist exactly.
3. **Predicate correctness:** `session.getTransactionInternal()` never returns null — `currentTx`
   is initialized to `FrontendTransactionNoTx` (DatabaseSessionEmbedded.java:2493, :2525) whose
   `isActive()` returns false (FrontendTransactionNoTx.java:97-99);
   `FrontendTransactionImpl.isActive()` (FrontendTransactionImpl.java:1544-1548) is true for a
   begun-not-finished tx. So the assert fires iff a tx is genuinely active, and cannot NPE.
4. **Cannot misfire on legitimate paths:** the sole production caller is
   `SharedContext.create:199` ← `createMetadata` (DatabaseSessionEmbedded.java:598-603, no
   `executeInTx` wrapper, unlike `loadMetadata` :605-612) ← `internalCreate` (:572-586) on a
   freshly constructed session — an active tx is impossible by construction. Grep confirms no
   other production caller. Empirical corroboration: the recorded full-core run (17456 tests,
   every test-DB creation traverses the assert) was green.
5. **Test pin** (`createInsideActiveTransactionIsRejected`, GenesisSchemaBootstrapTest.java:137-160):
   begins a real tx, calls `create`, and pins all three demanded facets: (a) the assert fires
   (`assertNotNull(rejection)` — red if the assert is removed, since without it create would
   proceed and no `AssertionError` is thrown); (b) the message names the precondition
   (`contains("outside any active transaction")` — matches the shipped message); (c) no root
   allocated (`assertNull(virginSchema.getIdentity())` — this specifically pins the
   BEFORE-any-mutation placement: an assert placed after `newInternalInstance`/`identity`
   assignment would leave a non-null identity and go red). `getIdentity()` takes the schema read
   lock (SchemaShared.java:1460-1467); no lock is held at that point, no deadlock. The outer
   `finally { session.rollback(); }` restores session state. Javadoc (:130-136) honestly notes
   the `-ea` dependence (test builds run `-ea`, core/pom.xml argLine).

**Verdict: VERIFIED.** Assert-based (compiled out under `-da`) is exactly the approved remedy
shape and consistent with the codebase's precondition idiom (cf. `advanceVersion`'s lock assert,
SchemaShared.java:1452-1454).

### BG13 + CS51 — null-out of `identity` on create failure → **VERIFIED**

**Premises:**

1. **Mechanism:** `computeInTx` is wrapped in
   `catch (RuntimeException | Error creationFailure) { identity = null; throw creationFailure; }`
   (SchemaShared.java:1418-1425), inside the outer `try` after the write lock (:1398), so the
   null-out happens under the write lock (excluding concurrent `getIdentity()` readers, which
   take the read lock) and the rethrow still passes through the outer `finally` unlock
   (:1430-1432).
2. **Cannot swallow or alter the original failure:** the catch body is a field assignment (cannot
   throw) followed by `throw creationFailure` — Java precise-rethrow of the *same object*; type,
   stack trace, message, and suppressed list are untouched. Verified no wrapping, no logging, no
   conditional path.
3. **Catch exhaustiveness:** `computeInTx` is `<R, X extends Exception> R computeInTx(...) throws X`
   (DatabaseSessionEmbedded.java:5113-5116). At this call site the lambda throws no checked
   exception, so X infers `RuntimeException` — the compiler guarantees only unchecked
   throwables can propagate, and `RuntimeException | Error` covers them all. (No
   `@SneakyThrows`-style sneaky checked throws exist in core main — grep-verified.)
4. **All failure paths inside the wrapped scope:** (a) `begin()` throws before the lambda —
   `identity` was never assigned; nulling is a harmless no-op; (b) lambda throws before :1415 —
   same; (c) lambda throws after :1415 (e.g. `toStream`) — the dangling provisional rid is
   nulled ✓; (d) commit throws inside `finishTx` — `computeInTxInternal`'s finally rolls back
   and propagates (DatabaseSessionEmbedded.java:5303-5315) — nulled ✓. The one failure point
   OUTSIDE the wrap — `setSchemaRecordId` (:1428) throwing after a successful commit — leaves
   `identity` holding a **valid persistent** rid of a durable record, which is not the BG13/CS51
   defect (a *dead provisional* rid) and was explicitly dispositioned benign by the crash review
   (E2, §2.2: `load` overwrites the field; context condemned). The wrap boundary is therefore
   exactly right, and the catch comment's claim ("the failed transaction rolled the root back")
   is accurate for every path it covers.
5. **Test pin** (`failedCreateLeavesNoDanglingIdentity`, GenesisSchemaBootstrapTest.java:166-188):
   an anonymous `SchemaEmbedded` subclass overrides `toStream(DatabaseSessionEmbedded)` — the
   exact overload the lambda calls at :1416 (declared public, non-final, SchemaShared.java:1076;
   dynamic dispatch reaches the override) — to throw `IllegalStateException`. The injection
   point is *after* `identity = root.getIdentity()` (:1415), so the dangling-rid state genuinely
   arises before the catch runs; without the fix, `getIdentity()` would return the provisional
   `ChangeableRecordId` and `assertNull` goes red. The test also pins non-swallowing:
   `assertNotNull(failure)` + `assertEquals("injected create failure", failure.getMessage())`
   would catch a wrap or a substitute exception. Genuine pin.

**Verdict: VERIFIED.**

### CQ16 — containment-comment parity on the two schema tests → **VERIFIED**

GenesisSchemaBootstrapTest.java:40-42 (`virginDbSchemaTransactionSeedsCleanly`) and :69-71
(`createdRootPersistsAsBootstrapValidEmptySchema`) each carry: "create() repoints the test
database's schema record id — contained: the database is per-test and dropped afterwards, and
the session's in-memory schema never re-reads the configuration pointer within the test." This
mirrors (and slightly strengthens, with the no-config-re-read clause the review's safety argument
derived) the IM test's existing note (:116-117). Exactly what CQ16 asked. **VERIFIED.**

### CS50 — ARMED W6 silent-reopen window recorded in Surprises → **VERIFIED**

track-8.md:110-117: "the CS35-accepted W6 silent-reopen window is ARMED in-tree from commit
908a2374e6 until Step 3's genesis-completion marker lands: a crash in the K4 window (schema + IM
root shells and pointers durable, BEFORE the first `security.create` DDL self-commit) now reopens
with ZERO signal … Design-accepted trade (CS35, folded into §A1); Step 3's open-time marker check
closes it — `crash-safety-step2-iter1.md`'s K4/W6 row is the exact state the marker must refuse."

- **Exists** ✓, in `## Surprises & Discoveries` directly after the CS48 bullet.
- **Accurate** ✓ against the crash report's K4/W6 row (§2.1): window boundaries (after IM
  pointer durable / before first security DDL self-commit), zero-signal reopen, breadcrumb
  branch retained in code for legacy corpses — all match. (The K4 row's authenticated-open
  caveat is omitted, but so was it in CS50's own suggested wording; the bullet is a faithful
  summary of the suggested action.)
- **Mirrors CS48 format** ✓ (track-8.md:104-109): same "ARMED in-tree from commit X until Step
  N's fix lands" skeleton, same design-acknowledgment citation tail, same mid-track guidance
  intent; and it explicitly cites the crash report's K4/W6 row as required. **VERIFIED.**

### TQ14 — disposition recorded (defer byte-deserialization to Step 3 pin G.5 #2(b)) → **VERIFIED**

track-8.md:600-602 (Step 2 review-fix episode, item 5): "fresh-context byte-deserialization of
the virgin root is deferred to Step 3's reopen pin G.5 #2(b) — the same-session cache-served read
is a fidelity MATCH to production genesis, not a gap; no code." This is verbatim one of the two
closures the baseline review itself offered ("alternatively accept as covered by Step 3's pin
G.5 #2(b)", TQ14 tail), and pin G.5 #2(b) exists in the design drafts
(track-8-design-drafts.md:228-232: DB-level create → close → reopen). Coherence note: after Step
3's marker the *virgin* root is refused at open, so #2(b)'s populated-root reopen plus the W-state
refusal pins (#9) jointly retire the byte-level concern — the deferral is sound, not merely
recorded. **VERIFIED** (disposition-only finding; correctly no code shipped).

### TQ15 — disposition recorded (accepted; test 2 is the assert-independent net) → **VERIFIED**

track-8.md:602-606 (episode item 6): "accepted — pin G.5 #1's red signature is `-ea`-dependent by
nature … `createdRootPersistsAsBootstrapValidEmptySchema` is the assert-independent regression
net — do not delete it believing test 1 subsumes it." Matches the review's TQ15 ("accept as-is",
records the do-not-delete rationale). **VERIFIED** (disposition-only; correctly no code shipped).

---

## 3. Scope check — code diff

`git diff -w` plus word-level comparison of `1858811c1e`'s `SchemaShared.java` hunk confirms the
entire production change decomposes into:

1. the CQ15 assert + its comment (:1388-1397);
2. the BG13/CS51 try/catch + its comment (:1400-1401, :1418-1425), which forces the
   `var entity = …` → `EntityImpl entity; … entity = …` declaration split (`EntityImpl` already
   imported/used in the file) — a mechanical consequence of the remedy, not extra scope;
3. a pure re-wrap of the pre-existing lambda comment (word-for-word identical text, re-indented
   two columns deeper by the new nesting).

The test diff is exactly: one import (`DatabaseSessionEmbedded`, needed by the override), the two
CQ16 comments, the two new tests. `this.identity = entity.getIdentity()`, `setSchemaRecordId`,
`snapshot = null`, and the whole rest of the file are untouched. **No scope creep.**

## 4. Consistency check — track-file record (`39153ddcf3`)

- **Per-finding writeups vs shipped code:** episode items (1)-(6) (track-8.md:582-606) each match
  the artifact verified above — assert wording/placement (1), null-out semantics "identical to
  pre-create" (2 — accurate: null is the exact pre-create value), the two test names and their
  pinned facets, comment parity (3), CS50 bullet content (4), the two dispositions (5)(6). Key
  files list ✓ ("+2 tests, now 5" — file has exactly 5 `@Test`). Checkpoint entry (:59-61) counts
  "7 suggestions — all applied or dispositioned": BG13, CQ15, CQ16, TQ14, TQ15 + CS50, CS51 = 7 ✓.
- **Verification numbers internally coherent** (not re-run, per gate charter): Step 2's episode
  recorded core 17454; +2 new tests → 17456 ✓; coverage denominators grew consistently with the
  added non-assert lines (2117/2330 → 2126/2340 line; 969/1168 → 976/1176 branch). The IT-skip
  rationale is sound: the assert is a no-op on every valid path (and compiled out under `-da` —
  ITs run production shape), the null-out is reachable only when DB creation aborts entirely, and
  no persisted byte changed vs the state Step 2's full IT run verified. The same argument covers
  the un-re-run `tests`/`server` modules: production create always runs on a fresh session where
  an active tx is impossible (§2 CQ15 premise 4).
- **Step-3 inherited-obligations coherence:** the Step-3 spec block carries the CS47 proxy
  re-routing seam annotation (track-8.md:280-286), the TQ12/CN50 re-grep, and the CQ14
  name-snapshot rationale (:286-290). The new **CQ15 verify** obligation ("Step-3 reviewer must
  ALSO verify `schema.create`/`indexManager.create` stay pre-tx") is recorded in the episode
  (:590-592) with the claim "carried alongside the existing CS47/TQ12 items" — but the Step-3
  spec block's reviewer-obligations parenthetical itself was **not** amended to name CQ15; grep
  confirms CQ15 appears only in the episode. A Step-3 planner reading only the step spec would
  find CS47/TQ12/CQ14 but miss CQ15. Minor bookkeeping gap, not a code defect and not
  fix-introduced breakage — see the recommendation below. (Also noted: the belt asserts only
  `schema.create`; `indexManager.create` has the same theoretical hazard and is covered only by
  the carried obligation — consistent with the approved remedy's scope, worth the Step-3
  reviewer's attention.)

**Recommendation (non-blocking):** append "CQ15: verify `schema.create`/`indexManager.create`
stay pre-tx (the SchemaShared entry assert is the belt; IM has no assert)" to the Step-3
reviewer-obligations parenthetical at track-8.md:286-290, so the obligation lives where its
siblings do.

## 5. Alternative-hypothesis check (fix-introduced breakage scan)

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| A1 | The entry assert fires on some legitimate DB-creation path | caller enumeration (sole caller SharedContext.java:199 ← createMetadata :598-603, no tx wrapper; fresh session's `currentTx` = NoTx :2493/:2525) + recorded 17456-green corroboration | no such path — rejected |
| A2 | `getTransactionInternal()` NPEs or misreports on a fresh session | DatabaseSessionEmbedded.java:4368-4371; NoTx `isActive()` :97-99 | never null, false when inactive — rejected |
| A3 | The catch swallows/wraps/masks the original failure | catch body trace :1418-1425 (assignment + precise-rethrow of the same object) | impossible — rejected |
| A4 | A checked exception escapes the `RuntimeException \| Error` catch, leaving the dangler | computeInTx generics :5113-5116 — X infers RuntimeException at this site; no sneaky-throws in core main | compiler-excluded — rejected |
| A5 | Nulling `identity` races a concurrent `getIdentity()` reader | null-out under the write lock (:1398/:1423); readers take the read lock (:1460-1467) | excluded — rejected |
| A6 | Nulling breaks a create retry or later `load` on the same instance | retry reassigns at :1415; `load` reassigns from config (:1370 region); null = exact pre-create state | strictly restores pre-fix semantics — rejected |
| A7 | The test override of `toStream` diverges from the production seam (pins a different call) | lambda calls `toStream(session)` :1416 → single-arg overload :1076, public non-final, dynamic dispatch | same seam — rejected |
| A8 | The rejected-create test leaks a lock or tx state into teardown | assert fires before the lock (:1394 < :1398); test's finally rolls back its own tx | clean — rejected |
| A9 | The comment re-wrap altered the Step-2 comment's content | word-level comparison of old vs new comment text | identical text, wrap-only — rejected |

**No RG findings.** (RG numbering unopened; next would be RG2.)

## 6. Compact verdict block

| ID | Verdict | Evidence gist |
|---|---|---|
| CQ15 | VERIFIED | assert is create's first statement (SchemaShared.java:1394-1397), before lock :1398 and all mutation; message names the provisional-rid/storage-config brick; comment names the Step-3 hazard; test :137-160 pins assert firing + message (`contains("outside any active transaction")`) + no-root-allocated (`getIdentity()` null — pins the before-mutation placement); sole production caller tx-free by construction |
| BG13+CS51 | VERIFIED | `catch (RuntimeException \| Error)` + `identity = null` + precise-rethrow of the same object (:1418-1425), under the write lock; exhaustive at this site (X infers RuntimeException); covers every dangling-provisional path, and the uncovered post-commit path (`setSchemaRecordId` throw) leaves a *valid persistent* rid per the crash review's E2 disposition; test :166-188 injects a `toStream` throw at the real seam (:1416 → overridable :1076) after :1415, pins null identity + unaltered exception |
| CQ16 | VERIFIED | containment comments present on both schema tests (:40-42, :69-71), mirroring the IM test's note (:116-117) plus the no-config-re-read clause |
| CS50 | VERIFIED | Surprises bullet track-8.md:110-117 — ARMED from 908a2374e6 until Step 3's marker, K4 window described accurately, cites the crash report's K4/W6 row, mirrors the CS48 bullet's format (:104-109) |
| TQ14 | VERIFIED | disposition recorded (episode :600-602): deferred to Step 3 pin G.5 #2(b) (drafts:228-232) — the closure the review itself offered; no code, correctly |
| TQ15 | VERIFIED | disposition recorded (episode :602-606): accepted; test 2 named as the assert-independent net with the do-not-delete rationale; no code, correctly |
| RG | none | scope clean (remedies + forced declaration split + wrap-only comment reflow); no legitimate path trips the assert; catch cannot mask failures |

**Gate outcome: PASS** — all six findings VERIFIED, zero regressions. One non-blocking
bookkeeping recommendation: thread the CQ15 Step-3-reviewer obligation into the Step-3 spec
block's obligations parenthetical (track-8.md:286-290), where CS47/TQ12/CQ14 already live —
today it exists only in the review-fix episode.
