<!-- MANIFEST
findings: 9   verdicts: {VERIFIED: 9, REJECTED: 0, STILL_OPEN: 0, MOOT: 0, REGRESSION: 0}
scope: "git show b54384e08a — Track 7 Step 5 review-fix commit (freeze-id retention deque, cp2 mechanism test, batched suggestions); verdict-only gate, iter 1"
residuals: 3 (supplier-record migration under concurrent mixed-mode operator freezes — accepted family, follow-up-eligible; two comment nits)
flags: [READ_ONLY, NO_MAVEN_RUN, NO_PRODUCT_CODE_MODIFIED, WORKING_TREE_DIRTY_OUTSIDE_TARGET]
-->

# Gate verification — Track 7 Step 5 review-fix commit `b54384e08a` (iter 1)

**Artifact:** commit `b54384e08a` ("Close freezer bookkeeping leak and pin the gate mechanisms"),
branch `transactional-schema`. `git diff b54384e08a HEAD -- core/` is **empty** (HEAD `68a1078e2f`
adds docs only), so HEAD's committed product/test code equals the artifact. **Caution applied:**
the working tree carries uncommitted BG8-Option-A work (comment-only additions in
`OperationsFreezer.java` and one new 72-line test in `FreezerGateTest.java`) — all citations below
were taken from or re-checked against the committed blobs (`git show b54384e08a:<path>`), not the
dirty working tree. Line numbers cite the committed state.

**Method:** read-only; no Maven run (test execution owned by another thread). Verdicts derived
independently from the code, the diff, and the original review reports
(`{concurrency,crash-safety,baseline}-step5-iter1.md`); red-proof claims cross-checked against the
episode record (commit `68a1078e2f`, plan/track-7.md Episodes §"Step 5 review-fix iteration 1")
and re-derived analytically where possible. Each finding gets numbered premises, traces, a
counterexample or justification, and an alternative-hypothesis check.

Files examined (committed state): `AbstractStorage.java` (freeze/release :5793-5879, probe
:2545-2546, pin/clear :2549-2592, cp2 :3294-3296, helpers :6672-6710), `OperationsFreezer.java`
(whole file), `AtomicOperationsManager.java` (:128-165, :297-305), `FreezerGateTest.java` (whole
file), `ScalableRWLock.java` (:685-765), `MetadataDefault.java` (:50, :78-104), `DbTestBase.java`
(@Before/@After lifecycle).

---

## F1 (CN42 = CS29 = BG7) — freeze-id retention deque — **VERIFIED**

**Claim:** `AbstractStorage` retains real freeze ids in a `ConcurrentLinkedDeque`; `freeze()`
pushes the id returned by `freezeWriteOperations`, `release()` pops and releases by id; `-1` only
as guarded fallback; javadoc updated; test-only accessor; regression test proven red.

**Premises:**
1. `AbstractStorage:312` — `private final ConcurrentLinkedDeque<Long> operatorFreezeIds`, field
   javadoc (:302-311) states the push/pop-by-real-id protocol, the sentinel-as-fallback, and the
   LIFO-soundness argument ("every retained id resolves to the same OPERATOR kind").
2. `freeze()` (:5810-5822): both arms capture `freezeId` from `freezeWriteOperations(OPERATOR, …)`
   and `operatorFreezeIds.push(freezeId)` immediately after registration, **before** the
   index-freeze loop and `doSynch()` — the only statements after registration that can throw. So
   an aborted `freeze()` still leaves its id retained, and a subsequent `release()` pops it and
   fully cleans the freezer record (strictly better than pre-fix, which stranded the record).
3. `release()` (:5866-5872): `poll()` (= `pollFirst`; `push` = `addFirst` ⇒ genuine LIFO), passes
   the real id, falls back to `-1` only on `null` (empty deque).
4. `OperationsFreezer:38-47` — set javadoc rewritten to the release-by-real-id expectation with
   the sentinel as guarded fallback; `:54-56` — `registeredOperatorFreezeIdCount()`,
   production-unused (grep: only the `AtomicOperationsManager:303` delegate and
   `FreezerGateTest:586/:597` reference it; `public` is required — the test lives in another
   package).
5. Regression test `repeatedFreezeReleaseCyclesDoNotLeakOperatorFreezeIds`
   (`FreezerGateTest:581-600`): 16 alternating throw/park-mode `freeze()`/`release()` cycles,
   set-size pinned to baseline. **Red-proof re-derived analytically:** pre-fix, each cycle adds
   one id (`OperationsFreezer:191`) that the `-1` release path never removes (the `remove(id)`
   sits in the `else`, :259) and the `requests == 0` sweep purges only `freezeParametersIdMap`
   (:291-297) ⇒ 16 cycles strand exactly 16 ids ⇒ `assertEquals(baseline, …)` fails — matching
   the episode's recorded "16 cycles stranded 16 ids".
6. The reviewers' fix-caution was honored: **no** sweep-on-`op==0` exists (grep: the set is
   touched only at :47/:191/:259) — the racy shape CN42 warned against (wiping a live id between
   `op++` at :190 and `add(id)` at :191, permanently disarming the gate) was not used.

**Probe 1 — LIFO pairing soundness under concurrent freeze()/release() cycles (id-keyed removal
semantics):**
- *Counter movement:* every id in the storage deque came from an OPERATOR registration
  (`freeze()` is the only pusher; ids are globally unique via `freezeIdGen`), so any popped id
  resolves `operatorFreezeIds.remove(id) == true` → OPERATOR → exactly one `freezeRequests` +
  one `operatorFreezeRequests` CAS-floor decrement (:274, :282) — identical regardless of WHICH
  paired id was popped. The fixer's javadoc claim is correct for the counters.
- *Can releasing the wrong id remove a live registration's protection?* The gate's and the park
  admission's only inputs are the two counters — unaffected by id identity. The retained-record
  SET stays an exact mirror: with k engaged freezes, k pushes and j ≤ k polls leave k−j records;
  each poll removes a distinct deque element atomically, so each real id is released **at most
  once** — no double id-keyed removal, hence no TRANSIENT misclassification of a genuine
  operator release and no upward `op` leak. A double `release()` polls an empty deque → `-1` →
  the guarded fallback (CAS-floor + log when floored; counter theft when another freeze is
  engaged — the pre-existing accepted risk-6 family, byte-identical to pre-fix where EVERY
  release was `-1`).
- *Deque-empty-while-engaged (fallback correctness):* reachable only through a release without a
  matching completed `freeze()` (the push precedes `freeze()`'s return and any post-registration
  throw, premise 2), i.e. exactly the caller-bug shape the fallback exists for. Behavior: `-1` →
  OPERATOR decrement, floored-and-logged when no freeze is engaged, theft otherwise — no new
  disarm shape versus pre-fix.
- **Residual found (not a rejection):** `releaseOperations` also removes the id-keyed
  **throw-supplier record** (`freezeParametersIdMap.remove(id)`, :262-264). Under two CONCURRENT
  storage-level operator freezes of MIXED modes released out of LIFO order, the popped id can
  belong to the other freeze, migrating the supplier removal: a park-mode freeze's release can
  strip a still-engaged throw-mode freeze's supplier (data writes then PARK instead of throwing
  until the second release), or retain a released throw-mode freeze's supplier (data writes
  throw under a park-mode freeze — which is exactly the PRE-FIX behavior of every `-1` release,
  recorded by the crash review as pre-existing residue (b)). Writes remain blocked in all cases
  (`freezeRequests > 0`); counters, gate arming, and admission are untouched; the shape requires
  two overlapping admin freeze() calls with different modes. Same identity-less-pairing family
  the design accepted (risk 6 / CS29's own counterexample note). The field javadoc's soundness
  argument mentions only counters — one sentence acknowledging the supplier-record dimension
  would complete it. **Suggestion-weight follow-up; does not defeat the fix.** (Minor comment
  nit, same site: `AbstractStorage:5868-5870` says the fallback's floor guard "then logs the
  underflow" — true only when no other freeze is engaged; in the theft case nothing is logged.)

**Alternative hypothesis** (*the deque itself leaks*): each cycle pushes one and polls one; an
aborted freeze's un-polled id is consumed by the next `release()` — bounded by engaged freezes.
Rejected.

---

## F2 (CN43) — checkpoint-2 mechanism test — **VERIFIED**

**Claim:** new test `writeLockAbortFiresWhileQueuedBehindFreezerParkedReader` builds the missing
ingredient (freezer-parked data commit holding `stateLock.readLock`), requires the abort out of
the acquisition with cp2 stack attribution, pin balance, and reads flowing under the engaged
freeze; proven red against a plain-lock cp2 revert.

**Premises:**
1. Test at `FreezerGateTest:244-393`. Ingredient 1 (:260-274): a pure-data commit behind a
   manager-level TRANSIENT quiesce — the data branch takes `stateLock.readLock().lock()` around
   `applyCommitOperations` (`AbstractStorage:2569-2576`) and the freezer entry
   (`startTxCommit:2802` → `startOperation`) parks INSIDE it, so the parked thread holds the
   read lock. Verified against the committed commit() body.
2. Ingredient 2 (:277-295): armed schema commit driven past its entry probe (no freeze exists at
   probe time) and blocked on the held committed-schema write lock; released into cp2 with a
   500 ms bias sleep; the operator freeze then engages at manager level (:326).
3. Cp2 is `commitSchemaCarry:3294-3296`: `if (!stateLock.exclusiveLockWithAbort(…)) throw
   operatorFreezeGateException();` — the exception is **constructed inside `commitSchemaCarry`**.
   `exclusiveLockWithAbort` (`ScalableRWLock:685-765`) polls the predicate in phase 1 before each
   1 ms `tryWriteLock` attempt (:695-697) and per phase-2 drain iteration (:742-749), releasing
   the write bit and returning `false` on abort — so the abort genuinely fires "out of the
   acquisition" while queued, and the freed bit lets the read prober (:349-368) proceed while
   both freezes are still engaged. Reads never enter the freezer — the outage property is pinned
   directly.

**Probe — does the stack attribution genuinely discriminate cp2 from cp3?** Construction-site
stacks of the three candidate throw sites:
- **Cp1 (probe):** built in `commit()` (:2546) — NO `commitSchemaCarry` frame ⇒ the
  `anyMatch(commitSchemaCarry)` assert (:336-338) fails. (Also structurally excluded: the probe
  ran before the freeze existed.)
- **Cp2:** built in `commitSchemaCarry` (:3296) — `commitSchemaCarry` frame present, no
  `OperationsFreezer` frame ⇒ both asserts pass.
- **Cp3/4 (in-freezer):** built by the threaded supplier `this::operatorFreezeGateException`
  invoked from `OperationsFreezer.startOperation` (:126/:144), whose call chain runs UNDER
  `commitSchemaCarry` → `applyCommitOperations` → `startTxCommit` — so the stack contains BOTH a
  `commitSchemaCarry` frame AND an `OperationsFreezer` frame ⇒ the `noneMatch(className
  endsWith "OperationsFreezer")` assert (:339-341) fails. `OperationsFreezer` is the only class
  in `core/src/main` with that suffix (grep), and the supplier is a method reference (no
  synthetic freezer-suffixed lambda classes).
Conjunction: only cp2 satisfies both. **Discrimination genuine.**

**Red-proof (analytic):** with cp2 reverted to a plain `lock()`, phase-2's reader drain cannot
complete while the parked data commit holds the read lock (it unparks only when the freezes
release — which the test does only in its `finally`, AFTER `done.await(15 s)`), so the committer
hangs and the bounded await fails — matching the episode's recorded red run. Can the plain-lock
regression instead pass via cp3/4? Only after the freezes release (post-`finally`), by which time
`done.await` has already failed. No green path. **Test would catch the regression.**

**Flake check (alternative hypothesis):** the acknowledged benign trajectory — freeze engaging
before the committer reaches the acquisition — aborts at phase 1's entry poll: same throw site
(:3296), same frames, same assertions (the test comment :316-321 says exactly this). The only
path to a cp3/4-attributed flake would require the data writer's observed park (:275-277) to be a
transient pre-`readLock` wait AND the committer to drain past cp2 in that window — the
`awaitThreadParked` looseness is the reviewers' accepted TQ10 family (suggestion-weight,
explicitly carried as-reviewed in the episode), and the data writer's path from spawn to freezer
park is short and lock-free. Accepted residual, not a defect of this fix.

Pin balance: `pinsAfter` set on the worker after the caught throw, asserted `0` (:342-343) —
latch-ordered. Cleanup: freezes released in `finally` (:377-381); daemon workers joined-or-failed
by the `@After` (:58-66). **Verdict: VERIFIED.**

---

## F3 (CS31 = CQ7) — dead `startTxCommit` overload removed — **VERIFIED**

`git grep startTxCommit b54384e08a -- core/ server/`: exactly one definition — the two-arg
`private void startTxCommit(AtomicOperation, boolean)` (`AbstractStorage:6672`) — and exactly one
caller (:2802, `startTxCommit(atomicOperation, schemaContext != null)`); the remaining hits are
javadoc prose (:2764, :3261). The single-arg overload is gone; being `private`, no external
caller was possible. **Zero callers remain; deletion complete.**

---

## F4 (CN45 = CQ8) — `requireNonNull` on armed entrants — **VERIFIED**

`OperationsFreezer.startOperation(boolean, Supplier)` (:105-112): first statement of the method
body is `if (schemaArmed) { Objects.requireNonNull(schemaGate, "a schema-armed freezer entrant
must supply the gate-exception factory"); }` — (a) **placement**: at the API boundary, BEFORE the
`operationDepth` read and the `operationsCount.increment()`, so a violation leaves the freezer's
accounting untouched and fires deterministically (not only under an active freeze, which was the
filed defect); (b) **message**: names the contract (armed entrant ⇒ factory), not a bare NPE;
(c) the site comment states the rationale (the NPE-inside-the-freezer diagnosis point). Unarmed
callers (`startOperation():73`, `startToApplyOperations(op):129`) pass `(false, null)` and skip
the guard; the sole armed producer (`AbstractStorage.startTxCommit:6673-6675`) threads
`this::operatorFreezeGateException` whenever `schemaArmed` — never null when armed. Behavior
unchanged for all correct callers. **VERIFIED.**

---

## F5 (TQ9) — timeouts + surrogate-thread activation — **VERIFIED**

1. `@Test(timeout = 60_000)` present on the three main-thread gate tests
   (`probeThrowsOnPreEngagedOperatorFreeze:116`, `doubleReleaseFloorsCountersAndGateStillArms:536`,
   `throwModeFreezeKeepsLegacySupplierForDataWrites:607`) and on the new leak test (:581). Each
   body opens with `session.activateOnCurrentThread()` plus a comment naming why (JUnit 4's
   `FailOnTimeout` runs a timed body on a surrogate thread).
2. **Does the surrogate thread invalidate what the tests pin?** No, on all four axes checked:
   (a) gate/counter/leak state is storage-global — thread-agnostic; (b) the pin count
   (`MetadataDefault.immutableCount:50`) is a plain session-confined int — the pin-taking commit
   and the `pinCount` read run on the SAME surrogate thread within one body; (c) session thread
   affinity is re-established at body top and at every hand-off (`:122/:127/:130/:146`, pooled
   sessions activated at acquire on the same thread); (d) teardown is safe: in
   BlockJUnit4ClassRunner's statement nesting, `withBefores`/`withAfters` wrap OUTSIDE
   `withPotentialTimeout`, so `@Before`/`@After` run on the main JUnit thread — whose
   thread-local session binding from `@Before` is untouched by the surrogate's activation — and
   `DbTestBase`'s `@After` additionally calls `session.activateOnCurrentThread()` before
   `close()` (:180-182). The legacy-supplier wording, the double-release floor + re-arm, the
   probe placement, and the leak pin are all asserted on observations produced on the same
   surrogate thread or on global atomics.
3. One scope note: on an actual total-gate regression the timeout produces the promised NAMED
   failure first; a freeze left engaged by the abandoned surrogate could still wedge LATER tests
   — the fix's claim ("fails by name instead of hanging the fork") holds for the diagnostic that
   matters (the first, named failure identifies the regression). Not a defect of this change.

**VERIFIED.**

---

## F6 (CS30) — stray-node documentation at checkpoint 4 — **VERIFIED**

`OperationsFreezer:135-143` (committed): the park-decision checkpoint comment now states (a) the
throw "deliberately leaves this entrant's just-enqueued node linked", (b) the consequence — "the
next cut unparks a thread that never parked on it (a benign stray permit — every park site
re-checks in a loop) and retains the node only until that cut", and (c) the explicit warning:
"Do NOT 'fix' that residue by moving this gate above the enqueue — the enqueue-before-recheck
ordering is exactly what closes the race." All three elements CS30 demanded, at the throw site,
technically accurate (the freezer's own park sits in a re-checking loop; cuts are the only
node-removal sites; the V1 rationale is cross-referenced to the method javadoc). **VERIFIED.**

---

## F7 (CQ9) — `cutAndUnparkWaiters()` extraction — **VERIFIED**

1. **Identical semantics:** the diff shows both original sites were the character-identical
   four-line detach-walk-unpark loop (`cutWaitingList()` → walk `node.next` → `unpark(node.item)`);
   the helper (:309-315) reproduces it verbatim. Both call sites (`freezeOperations:216`,
   `releaseOperations:299`) invoke it under the same conditions as before (OPERATOR arm after
   both increments; release's `requests == 0` transition). No behavioral delta.
2. **V1 ordering comments survived:** the arm site retains the full "V1 arm ordering, part 2 …
   strictly AFTER both increments" + Q-B4 herd + single-cutter block (:200-215) — the
   load-bearing WHEN. The release site's WHEN is carried by the guarding `if (requests == 0)`
   (:291) plus the `releaseOperations` javadoc, exactly as pre-fix (that site never had an inline
   cut comment, so nothing was lost). *Nit:* the helper's javadoc says the WHEN "is … documented
   at the call sites" — for the release side it is expressed by the guard/method javadoc rather
   than a call-site comment; the helper javadoc itself names both WHENs, so no information is
   missing. Comment-polish nit only.

**VERIFIED.**

---

## F8 (CN44) — probe-placement stack attribution — **VERIFIED**

`probeThrowsOnPreEngagedOperatorFreeze` (:141-144): asserts the caught exception's stack has NO
`commitSchemaCarry` frame and NO `OperationsFreezer`-suffixed class frame, with a comment
correctly explaining why the zero pin-delta assert alone cannot pin placement. Discrimination
check against the probe-deletion regression: with the probe (:2545-2546) deleted and the freeze
pre-engaged, the schema commit proceeds to `commitSchemaCarry`; cp2's first predicate poll aborts
(⇒ `commitSchemaCarry` frame) — or, were the acquisition somehow passed, cp3's loop-top gate
throws (⇒ both frames). Either way the `noneMatch` fails — **red on regression**. False-positive
check: the legitimate probe throw is constructed in `commit()` after `isOperatorFreezeActive()`
returned — no freezer or `commitSchemaCarry` frame on its stack — **green on healthy code**.
**VERIFIED.**

---

## F9 (TQ11) — direct pin-balance assert on the loop-top throw path — **VERIFIED**

`parkedSchemaCommitWakesAndThrowsWhenOperatorFreezeArrives`: `pinsAfterThrow` captured on the
worker immediately after the caught throw, BEFORE `pooled.close()` (:404, :420), and asserted
`Integer.valueOf(0)` on the main thread (:441-445) after the `pooledClosed.await` — publication
is latch-ordered on an `AtomicReference` (double-safe). The comment names precisely what changed:
previously the balance was covered only indirectly by the recycle inside `close()`, whose failure
would have read as a latch timeout. Order of assertions is sound (the gate-exception asserts
precede the pin assert, so an unexpectedly successful commit fails on `assertNotNull` first).
This closes path-(c)-symmetry on the five-path pin matrix for the in-freezer (cp3) throw.
**VERIFIED.**

---

## Cross-cutting checks

- **No overlooked regression surface:** the commit touches exactly the four files in the stat;
  the only production-semantics changes are the id retention/release-by-id (F1),
  `requireNonNull` (F4, throws only on a contract violation no current caller can commit), the
  dead-code deletion (F3), and the comment/javadoc updates (F6/F7 + release-path javadoc).
  V1/V8 orderings, checkpoint placement, CAS-floor guards, and the cut protocol are untouched
  (diff-verified). The `-1` explicit-OPERATOR mapping is retained for the fallback, so the
  double-release test's sentinel shape (`FreezerGateTest:551`) still exercises the operator
  floor.
- **Episode consistency:** the recorded verification (FreezerGateTest 10/10, liveness green,
  full unit + integration suites green, coverage gate PASSED 88.5 %/83.1 %) and both red-proofs
  match the episode record in `plan/track-7.md`; BG8 correctly recorded as deferred (its
  in-progress Option-A work sits uncommitted in the working tree, outside this gate's target).

## Residuals for the orchestrator (no verdict impact)

1. **[suggestion] Supplier-record migration under concurrent mixed-mode operator freezes** (F1
   probe): LIFO id-swap can migrate the `freezeParametersIdMap` removal between two overlapping
   storage-level operator freezes of different modes, flipping data-write failure mode
   (throw↔park) for the overlap tail. No counter/gate/admission effect; writes stay blocked;
   pre-fix had the mirror wrongness on the same exotic shape. Candidate one-line javadoc
   addition on `AbstractStorage.operatorFreezeIds`; could fold into the BG8-family follow-up.
2. **[nit] `AbstractStorage:5868-5870`** — "CAS-floor guard then logs the underflow" holds only
   when no other freeze is engaged (the theft shape is silent, as pre-fix).
3. **[nit] `cutAndUnparkWaiters` javadoc** — "documented at the call sites" overstates the
   release side (WHEN carried by the `requests == 0` guard + method javadoc, not a call-site
   comment).

---

## Verdict block

```
F1 (CN42=CS29=BG7)  VERIFIED   deque retain/release-by-id sound; LIFO counter-neutral; no sweep race; red-proof re-derived (16 ids); residual: supplier-record migration (suggestion)
F2 (CN43)           VERIFIED   parked-reader ingredient real; cp2-vs-cp1/cp3 stack attribution genuinely discriminating; plain-lock revert analytically red
F3 (CS31=CQ7)       VERIFIED   single-arg startTxCommit gone; zero callers (grep at artifact)
F4 (CN45=CQ8)       VERIFIED   requireNonNull at method entry, pre-accounting, contract-naming message
F5 (TQ9)            VERIFIED   timeouts on 3 main-thread tests (+leak test); surrogate-thread activation invalidates nothing pinned; @After main-thread-safe
F6 (CS30)           VERIFIED   stray-node residue + benign-permit + do-not-reorder warning at the cp4 throw site
F7 (CQ9)            VERIFIED   helper verbatim-identical to both sites; arm-site V1 WHEN comment intact; release WHEN carried by guard (nit only)
F8 (CN44)           VERIFIED   probe attribution red on probe deletion, green on healthy probe
F9 (TQ11)           VERIFIED   direct worker-side pin assert, latch-ordered, correctly sequenced
REGRESSIONS: none found.  BLOCKERS: none.  Gate: PASS (9/9 VERIFIED).
```
