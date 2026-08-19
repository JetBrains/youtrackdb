<!-- MANIFEST
findings: 9   severity: {blocker: 1, should-fix: 6, suggestion: 2}
index:
  - {id: A10, sev: blocker,    loc: "track-11.md:130 (acceptance bullet), :77 (item 4); GremlinStepWalker.java:619-641", anchor: "### A10 ", cert: V1, basis: "the union-suffix acceptance bullet still requires union(...).fold() and .tail(n) to translate and match native — unsatisfiable under item 4's own selectsPositionally constraints and the walker step 7 shipped; A1's criterion rewrite was never applied", supersedes: A1}
  - {id: A11, sev: should-fix, loc: "implementation-plan.md:370 (invariant); track-11.md:107 (item 8)", anchor: "### A11 ", cert: V2, basis: "item 8's bind makes a RECOGNIZED shape return different multisets on the two arms by design (native [] is the develop defect), and R15 fixed the tests while the invariant text stays absolute — amend it with a named bounded exception or descope item 8"}
  - {id: A12, sev: should-fix, loc: "implementation-plan.md:764-774 (Scope justification); track-11.md:110,:115,:94", anchor: "### A12 ", cert: C1, basis: "the delegated call is KEEP ONE TRACK, but both justification prongs fail — DR-S1 refutes the split-impossibility claim, item 9's drop is a recorded user requirement, item 10's absence grows this track's own duplication and its sweep now owns a correctness suspect — four conditions attach"}
  - {id: A13, sev: should-fix, loc: "track-11.md:74 (item 1), :88 (item 5)", anchor: "### A13 ", cert: AT1, basis: "T9's applied fix claims 'the mock cannot invert it', which is false — Mockito answers false for unstubbed booleans regardless of default-ness — and item 5 still says the method 'defaults to true', so the two items describe two different seams"}
  - {id: A14, sev: should-fix, loc: "track-11.md:84 (item 5), :129 (acceptance)", anchor: "### A14 ", cert: AT2, basis: "where(__.out().tail(1)) still stands as the combinator-swallow witness though the swallow's answer equals the correct answer on every graph; the positive-control rule tests fixture liveness, not witness discrimination", supersedes: A3}
  - {id: A15, sev: should-fix, loc: "track-11.md:128 (acceptance); UnionStepRecogniser.java:105-108", anchor: "### A15 ", cert: AT3, basis: "the 'witnessed by the explicit gate' criterion is satisfiable only by a white-box test with equals-equal ops that no item specifies — fresh per-recognition ops make the agreedShaping.equals gate decline first and every black-box decline is over-determined", supersedes: A4}
  - {id: A16, sev: should-fix, loc: "track-11.md:28 (DR-T2); GremlinToMatchStrategy.java:223-237,:296", anchor: "### A16 ", cert: AT4, basis: "DR-T2 still says the throw template breaks the all-or-nothing contract loudly out of apply(); the strategy's net catches RuntimeException and degrades it to a silent native decline — amended twice today without touching the refuted clause", supersedes: A5}
  - {id: A17, sev: suggestion, loc: "track-11.md items 3 and 5 (placeholder-form tail)", anchor: "### A17 ", cert: AT5, basis: "the placeholder form is still unreachable through the fluent API, no construction path is named, and the getLimitAsGValue decision is still delegated to the implementer of the branch's last track", supersedes: A8}
  - {id: A18, sev: suggestion, loc: "track-11.md item 7 (R14 resolution); origin at f2b1230db0", anchor: "### A18 ", cert: AT6, basis: "'no longer live only on one machine' is premature — the cherry-picked commits are local-only until the next user-approved push; push before Phase A closes or reword and make the push a decomposition precondition"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 5}
cert_index:
  - {id: C1,   verdict: WEAK,          anchor: "#### C1 "}
  - {id: V1,   verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2,   verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: AT1,  verdict: BREAKS,        anchor: "#### AT1 "}
  - {id: AT2,  verdict: BREAKS,        anchor: "#### AT2 "}
  - {id: AT3,  verdict: FRAGILE,       anchor: "#### AT3 "}
  - {id: AT4,  verdict: BREAKS,        anchor: "#### AT4 "}
  - {id: AT5,  verdict: FRAGILE,       anchor: "#### AT5 "}
  - {id: AT6,  verdict: BREAKS,        anchor: "#### AT6 "}
  - {id: AT7,  verdict: HOLDS,         anchor: "#### AT7 "}
  - {id: AT8,  verdict: HOLDS,         anchor: "#### AT8 "}
  - {id: AT9,  verdict: HOLDS,         anchor: "#### AT9 "}
  - {id: AT10, verdict: HOLDS,         anchor: "#### AT10 "}
  - {id: AT11, verdict: HOLDS,         anchor: "#### AT11 "}
flags: [CONTRACT_OK, GREP_NOT_PSI, FULL_RE_REVIEW]
-->

# Track 11 adversarial review — iteration 2

One blocker, six should-fix, two suggestions. The blocker is a leftover: A1 was closed in code by Track 9 step 7, but the acceptance bullet it targeted was never rewritten, so `## Validation and Acceptance` still requires `union(...).fold()` and `union(...).tail(n)` to translate and match native — the criterion the shipped walker now declines by design. An implementation faithful to item 4 fails the bullet; one faithful to the bullet re-ships the wrong answer A1 measured.

The scope call the orchestrator delegated comes back **keep one track**, against the justification rather than with it: "the alternative does not exist" is refuted by DR-S1, which split the last track of this branch once already, and the droppability clause fails on two of its three items — item 9's provenance records a user requirement, and item 10's sweep now owns a correctness-relevant suspect added to `## Surprises & Discoveries` today. The conclusion survives under four conditions listed in A12. Cross-track-episode reality otherwise held everywhere it was checked: every identifier item 4a tells the implementer to copy exists at HEAD, item 1's seam precedent is exactly as described, and all six javadoc-defect claims in `## Context and Orientation` are real.

Four of iteration 1's findings (A3, A4, A5, A8) were never applied and still stand; each is re-raised with its residue stated precisely.

## Reviewer notes

**The tree moved mid-review.** Reading began at `6d8962581e`; four commits landed while verification ran — `43907ff312` and `deb8e72ee9` (the item-7 harness, cherry-picked at user direction), `edcf10dfa6` (reverting the three files at tip), and `800e70aa1a` (amending item 7 and `## Surprises & Discoveries`). Every finding below is stated against `800e70aa1a`. The amendment resolves R14's securing question in plan text; A18 challenges one clause of it.

**Reference accuracy.** Grep plus end-to-end Reads of every returned site, not PSI. No `steroid_execute_code` attempt was spent: all three iteration-1 panels and both iteration-2 passes hit the 60 s cold-kotlinc timeout on this repository, and the marginal information from a sixth attempt is nil. Declaration reads and control-flow traces are reliable; "no other caller" negatives (item 1's `withListShapingOps` callers, the absence of a `FoldStep` registry entry) are bounded rather than established. One grep trap hit and recorded in AT9: two javadoc clauses the track quotes are line-wrapped, so a single-line grep reports them absent — verify multi-line claims by Read, not grep count. No measurement run was needed; iteration 1's measured evidence is carried only where the shape it measured is unchanged at HEAD, and each carry says so.

**Iteration-1 dispositions verified.** A1 closed in code; its unapplied criterion rewrite is A10. A2, A6 and A7 are absorbed correctly — item 7 targets the builders, item 6 is absolute with the R8-form ancestry check, the Scope line dropped "mirrored". A3 → A14, A4 → A15, A5 → A16, A8 → A17. A9's resolution looks right: this spawn ran on Fable 5, pinned by reading `design_gate=yes` off `design.md`'s existence — the fallback A9 proposed. The ledger still carries no `design_gate` field and D14 still lacks a fallback sentence; that is a workflow-file change no track on this branch owns, and it recurs on the next branch until someone owns it.

**Not re-litigated.** T9–T12 and R10–R20 as applied, with one exception: A13 challenges a sentence T9's fix wrote into item 1, on the ground that the sentence is false even though the declaration choice it argues for is right.

## Findings

### A10 [blocker]
**Certificate**: V1 — violation scenario, CONSTRUCTIBLE
**Target**: Acceptance criterion `track-11.md:130` ("A union **suffix** still folds once: `union(...).fold()` / `.unfold()` / `.reverse()` / `.tail(n)` translate and match native as multisets…"), against item 4 (`:77`, its own 4b/4c) and the walker Track 9 step 7 shipped
**Supersedes**: A1 — its part (2), the criterion rewrite, was never applied; parts (1) and (3) are done.
**Challenge**: The bullet encodes the pre-step-7 world and is unsatisfiable under the track's own constraints. Item 4 requires `tail` to answer `selectsPositionally` **true**, and `postUnionSuffixTranslatable` (`GremlinStepWalker.java:632-637`) declines any positional member not immediately followed by `count()` — so a faithful implementation declines bare `union(...).tail(n)`, and bare `union(...).fold()` declines under either 4c answer. The bullet then fails on two of its four spellings. The only way to pass it is to answer `selectsPositionally → false` for both, which re-ships A1's measured wrong multiset (`fold`'s one-element multiset compares by order-sensitive `List.equals` over a reordered fourteen-row concatenation). Phase B hits this as a hard fork: satisfy the gate or satisfy item 4, never both.
**Evidence**: `GremlinStepWalker.java:267-271` (allow-list), `:602-610` and `:619-641` (positional look-ahead requiring an immediate `count()`); `plan/track-9.md` `## Episodes` §Step 7 (both constraints, recorded as binding item 4); iteration 1's V1 measurement (multiset divergence on the fourteen-row shape).
**Proposed fix**: Rewrite `track-11.md:130` to cover only what item 4 admits: `union(...).unfold()` and `union(...).reverse()` translate and match native as multisets; bare `union(...).tail(n)` and bare `union(...).fold()` decline; `union(...).tail(n).count()` — and `union(...).fold().count()` if 4c picks `selectsPositionally → true` — translate with both arms equal. Move the "one list over the concatenation, not one per child" clause onto whatever fold spelling remains translatable, or restate it as the drain-mechanics unit test in item 5.

### A11 [should-fix]
**Certificate**: V2 — violation scenario, CONSTRUCTIBLE
**Target**: Plan invariant `implementation-plan.md:370` — "Translator-on and translator-off produce equal result multisets for every `RECOGNIZED` shape" — against item 8 (`track-11.md:107`)
**Challenge**: Item 8 violates this invariant by design and nothing reconciles the two texts. Once `HasStepRecogniser` binds labels, `g.V().out("knows").hasLabel("Person").as("a").select("a")` becomes RECOGNIZED and answers two vertices, while translator-off answers `[]` through `rebuildTraversal`'s label-dropping `else` branch — a measured pre-existing `develop` defect. The divergence is an improvement, which is exactly why it will not read as one: the invariant is the branch's headline correctness property, Phase C's conformance read checks it, and the equivalence drivers enforce it mechanically. R15 fixed the test guidance (hand-computed oracle, keep the spelling out of disagreement-fails drivers) and left the invariant text absolute.
**Evidence**: `implementation-plan.md:370-371`; `track-11.md:107` (R15 amendment); `plan/track-9.md:43` (the native `[]` measurement and the carry obligation); the probe's prototyped bind flipping the spellings to engaged=1 with row parity on the spellings that avoid the `else` branch.
**Proposed fix**: In the same commit that lands item 8, amend the invariant with a named, bounded exception — spellings whose native answer routes through `rebuildTraversal`'s label-dropping `else` branch are asserted against a hand-computed oracle, citing the defect's issue or `design-final.md` entry (R20 already owes it a destination). If item 8 is instead descoped under A12's conditions, the invariant stays absolute and this finding closes with it.

### A12 [should-fix]
**Certificate**: C1 — scope challenge, survival WEAK
**Target**: Scope and sizing — the plan's Track 11 Scope line and its over-bound justification (`implementation-plan.md:764-774`)
**Challenge**: The justification's two prongs both fail as written, and the delegated call is delivered here. **Prong one — "the alternative does not exist" — is refuted by the branch's own history**: DR-S1 split the then-last track on 2026-08-03, which is how this track exists; a Track 12 for items 8–10 is available by the identical move, and "no PR ahead of it" describes every final track including this one. **Prong two — "items 8, 9 and 10 are independently droppable… whose absence leaves the branch no worse than today" — fails on two of three items.** Item 9's own provenance records a user requirement ("the user's requirement was that this come out clean rather than partially done", `track-11.md:110`), so its drop is the user's call, not a Phase B descoping valve. Item 10 is mispriced twice: its sweep now owns a correctness-relevant question (today's `## Surprises & Discoveries` entry — whether Track 9 step 10's retirement of harness divergence (b) rested on a comparison that could not fail), and its absence leaves the branch *worse* than today because item 5's new test classes grow the very duplication it retires — the mechanism already observed when Track 9's Phase C added the sixth enum copy while the item sat in backlog (`:115`). And the valve names no destination: "the overflow is shed by descoping" sits four paragraphs from item 6's rule 3, "'deferred' with no owner is not a disposition" — a rule written for gate residue that descoped items evade, straight into the Phase-4 `_workflow/` deletion R20 warns about.
**Evidence**: `plan/track-9.md:89` (DR-S1); `track-11.md:110` (item 9 provenance), `:115` (the sixth copy), `:94` (rule 3), the 2026-08-04 Surprises entry; `implementation-plan.md:764-774` (the justification).
**Proposed fix**: Keep one track — a Track 12 buys a cleaner PR boundary but costs a full Phase A/B/C cycle for capability and hygiene work, which is the wrong trade this late — under four conditions, written into the Scope line in place of the current prongs: (1) decomposition orders item 10's shared-support extraction ahead of item 5, so the terminator tests consume the shared harness instead of copying it; (2) every descoped item takes a YouTrack issue or `### Non-Goals` amendment before track close — extend item 6's rule 3 to descoping explicitly; (3) an item 9 (or 8) drop is put to the user, citing the recorded requirement; (4) the justification stands on "descoping-with-destinations dominates a twelfth track's overhead", not on a split being impossible.

### A13 [should-fix]
**Certificate**: AT1 — assumption test, BREAKS
**Target**: Item 1 (`track-11.md:74`) — "With no default, both implementers must state an answer and the mock cannot invert it" — and item 5 (`:88`) — "`supportsListShaping()` defaults to `true`, so a Mockito mock… inverts it to `false`"
**Challenge**: The applied T9 fix carries a false mechanism claim, and the two items now disagree about the seam's shape. A Mockito mock answers `false` for an unstubbed boolean **regardless of default-ness** — `RETURNS_DEFAULTS` proxies default methods exactly like abstract ones unless `CALLS_REAL_METHODS` is opted into — so declaring `supportsListShaping()` non-default changes nothing about the mock-side hazard T4 described: a mock-based combinator-child decline assertion still passes without exercising the adapter's decline. What non-default actually buys is production-side and real — `WalkerContext` cannot compile without stating `true`, and a future third implementer cannot inherit an answer silently. Meanwhile item 5 still says the method "defaults to `true`", describing a seam item 1 no longer specifies. The risk is an implementer reading item 1's "the mock cannot invert it", concluding mock-based decline tests are structurally safe, and treating item 5's positive-control rule as belt they can drop.
**Evidence**: Mockito semantics (domain knowledge, not grep); `track-11.md:74` vs `:88` — the two sentences contradict on whether a default exists.
**Proposed fix**: Keep the non-default declaration. Rewrite item 1's sentence to claim its real benefit (a forgotten override is a compile error, not a silent `true`), state that mocks still answer `false` and the positive control remains the only mock-side defence, and delete item 5's "defaults to `true`" premise so the two items describe one seam.

### A14 [should-fix]
**Certificate**: AT2 — assumption test, BREAKS (carried measurement, shape unchanged)
**Target**: Item 5's decline list (`track-11.md:84`) and the acceptance bullet at `:129` — `g.V().where(__.out().tail(1))` as a combinator-swallow witness
**Supersedes**: A3, unapplied.
**Challenge**: The witness still cannot discriminate the defect it names. `tail(1)` of a stream is non-empty exactly when the stream is, so a swallow that turns the child into an existence filter produces the identical row set — iteration 1 measured both spellings returning the same rows natively, and the equivalence is semantic, not a fixture accident. The pre-flight gate's positive-control rule (`:88`) does not reach this: it proves the fixture is alive, while here the buggy answer *equals* the right answer on a live fixture. The companion `and(__.out().fold())` case discriminates (3-vs-2 on a chain with a leaf vertex); the `where` case reads as a second witness and is dead weight.
**Evidence**: iteration 1 AT2 (native `where(__.out())` ≡ `where(__.out().tail(1))`, both `{#22:0, #23:0}` on a three-vertex chain); item 5 and bullet `:129` unchanged at `800e70aa1a`.
**Proposed fix**: Make `g.V().where(__.out().fold())` the criterion's witness — native returns every vertex, a swallow returns only those with an out-edge — and keep `where(__.out().tail(1))` as coverage only, with a sentence saying it cannot witness the swallow.

### A15 [should-fix]
**Certificate**: AT3 — assumption test, FRAGILE
**Target**: Acceptance bullet `track-11.md:128` — the union-child decline is "witnessed by the explicit non-empty-`listShapingOps` gate rather than inferred from op reference identity"
**Supersedes**: A4, partially applied.
**Challenge**: Item 4 gained "checked before and independently of the `agreedShaping.equals` comparison", which fixes the production ordering, and no item specifies the only test that can *witness* it. The clone analysis requires fresh ops per recognition; fresh ops without value equality make `agreedShaping.equals(childResult.shaping())` (`UnionStepRecogniser.java:105-108`) decline first, so every black-box decline is over-determined — delete the new gate and the result assertions stay green. DR-T3 itself names the construction the witness needs: shapings carrying `equals`-equal ops (a shared instance or a value-equal test op), under which the equals gate passes and only the new gate can decline.
**Evidence**: `UnionStepRecogniser.java:105-108`, `:124`; DR-T3 (`track-11.md:29`, "record singletons… compare equal"); no item names a white-box witness at `800e70aa1a`.
**Proposed fix**: Name the unit test in item 5: drive `walkFork` with two children whose shapings carry the same op instance, assert the decline and (via the gate's own decline reason or ordering) that the list-shaping gate fired. Otherwise delete the witness clause and claim the gate by code order plus review.

### A16 [should-fix]
**Certificate**: AT4 — assumption test, BREAKS
**Target**: DR-T2 (`track-11.md:28`) — "copying `appendPostConcatOp` throws `UnsupportedOperationException` out of `TraversalStrategy.apply()`, breaking the all-or-nothing contract loudly"
**Supersedes**: A5, unapplied.
**Challenge**: Still false at HEAD, in a record amended twice today without touching the refuted clause. `GremlinToMatchStrategy.apply` re-throws only `ReservedAliasException` (`:223`) and routes every other `RuntimeException` to `declineOnThrow` (`:230-237`, `:296`), so the throw degrades to a silent native decline: nothing escapes, nothing is loud, and all-or-nothing is preserved rather than broken. DR-T4 classes the applied iteration-2 fixes as "text corrections whose correctness is a grep" — this clause greps false, and it is the kind an implementer verifies: a test asserting the escape can never pass.
**Evidence**: `GremlinToMatchStrategy.java:51`, `:110` (the net documented), `:223-237`, `:296` — all present at `800e70aa1a`.
**Proposed fix**: One sentence in DR-T2: reject the throw template on cost and diagnosability — an exception constructed on a path every Gremlin compilation crosses, and a decline diagnosed at the catch-all instead of at the decision point where `supportsListShaping()` names it.

### A17 [suggestion]
**Certificate**: AT5 — assumption test, FRAGILE (carried, surface unchanged)
**Target**: Item 3's open `getLimitAsGValue()` decision and item 5's "placeholder-form `tail`" test
**Supersedes**: A8, unapplied.
**Challenge**: Unchanged at HEAD. The fluent API compiles `tail(n)` to the concrete `TailGlobalStep` on both arms (iteration 1's probe), so item 5's placeholder test still has no stated way to build its subject, and the GValue pinning side effect that motivates item 3's open question lives only on that unnamed path. The choice is still delegated to the implementer of the branch's last track. Nothing in Track 9's landing touches the fork, so the iteration-1 evidence carries.
**Evidence**: iteration 1 AT5 (probe output; `TailGlobalStepContract` declares `getLimitAsGValue()` and `CONCRETE_STEPS` lists both classes); item 3 and item 5 text unchanged at `800e70aa1a`.
**Proposed fix**: Decide in decomposition: the clean branch (read `getLimitAsGValue()`, decline on `isVariable()` before touching `getLimit()`) costs one line and removes a decline-path side effect. Name the placeholder's construction path (GremlinLang / bytecode) or drop that test with a sentence saying why.

### A18 [suggestion]
**Certificate**: AT6 — assumption test, BREAKS until the next push
**Target**: Item 7's R14 resolution (`track-11.md`, "Resolved 2026-08-04… no longer live only on one machine")
**Challenge**: The clause is premature. `origin/gremlin-to-match-translator-design` sits at `f2b1230db0`, which predates the cherry-pick; `43907ff312` and `deb8e72ee9` are reachable only in the local branch history, and pushes on this branch wait for user approval. Until the next push, a local-disk loss still destroys the harness — the exposure R14 named, narrowed from "an unpushed side branch" to "an unpushed mainline", which is smaller but not zero. The mechanism (cherry-pick then revert, keeping reachability) is right; the tense is wrong.
**Evidence**: `git ls-remote --heads origin gremlin-to-match-translator-design` → `f2b1230db0`; local HEAD `800e70aa1a` with the two commits as ancestors.
**Proposed fix**: Push the branch (with user approval) before Phase A closes, or reword the resolution to "secured at the next branch push" and make that push a decomposition precondition.

## Evidence base

#### C1 Challenge: Scope — the over-bound justification and the delegated split-or-keep call
- **Chosen approach**: one ~35–45-file track; justification = a split "does not exist" for a last track, and items 8–10 are independently droppable.
- **Best rejected alternative**: Track 12 for items 8–10 via DR-S1's own mechanism, or descoping with named destinations.
- **Counterargument trace**: (1) DR-S1 split the then-last track on 2026-08-03 (`plan/track-9.md:89`) — the impossibility claim is refuted by this track's own origin. (2) Item 9's provenance records a user requirement (`track-11.md:110`), so its drop is not Phase B's to take. (3) Item 10's absence grows this track's own duplication through item 5's new test classes — the observed mechanism at `:115` — and its sweep now owns the vacuous-retirement suspect added to `## Surprises & Discoveries` on 2026-08-04. (4) The descoping valve names no destination while item 6's rule 3 (`:94`) defines one for everything else on the track.
- **Survival test**: WEAK — the one-track conclusion survives (a twelfth track's full phase cycle is the wrong spend for capability/hygiene work this late), the written grounds do not. Conditions in A12.

#### V1 Violation scenario: the union-suffix acceptance bullet is unsatisfiable under item 4's constraints
- **Invariant claim**: `track-11.md:130` — `union(...).fold()` / `.unfold()` / `.reverse()` / `.tail(n)` all "translate and match native as multisets".
- **Violation construction**: (1) Implement item 4 faithfully: `tail` answers `selectsPositionally → true` (mandatory, `:77`), `fold` answers `true` or stays off the allow-list (4c's two options). (2) Run bare `union(...).tail(n)`: `postUnionSuffixTranslatable` (`GremlinStepWalker.java:632-637`) sees a positional member with no immediate `count()` and returns false; the walk declines; the bullet's "translate" clause fails. Bare `union(...).fold()` fails the same way under either 4c answer. (3) The alternative implementation — both answer `false` — passes the reflective declaration test (it checks declaration, never value; R11) and translates, reproducing A1's measured multiset divergence on the fourteen-row shape, since `fold`'s one-element multiset compares by order-sensitive `List.equals`.
- **Violation point**: either the acceptance gate at track close or the plan's multiset-equality invariant; there is no third branch.
- **Feasibility**: CONSTRUCTIBLE — the walker code and the bullet text are both at HEAD; no unusual conditions.

#### V2 Violation scenario: item 8 against the multiset-equality invariant
- **Invariant claim**: `implementation-plan.md:370` — equal result multisets on both arms for every RECOGNIZED shape.
- **Violation construction**: (1) Item 8's `bindStepLabels`-or-decline call lands in `HasStepRecogniser.recognize`. (2) Run `g.V().out("knows").hasLabel("Person").as("a").select("a")` on both arms. (3) Translator-off routes through `rebuildTraversal`'s `else` branch, which drops the `HasStep`'s labels, and returns `[]` — measured, pre-existing on `develop` (`plan/track-9.md:43`). (4) Translator-on now binds the label, translates, and returns the oracle's two vertices (the probe's prototyped bind flipped the family to engaged=1, R15/C6 VALIDATED). (5) A RECOGNIZED shape returns different multisets on the two arms; any equivalence driver comparing arms fails, and Phase C's invariant conformance read flags it.
- **Feasibility**: CONSTRUCTIBLE — deliberately so; the divergence is the improvement item 8 exists to make, which is why the invariant text needs the exception rather than the code needing a change.

#### AT1 Assumption test: declaring supportsListShaping() non-default retires the mock-inversion hazard
- **Claim**: item 1 (`track-11.md:74`) — "With no default, both implementers must state an answer and the mock cannot invert it."
- **Stress scenario**: `Mockito.mock(RecognitionContext.class)` handed to a recogniser in a combinator-child decline test, `supportsListShaping()` unstubbed.
- **Code evidence**: Mockito's `RETURNS_DEFAULTS` answers `false` for any unstubbed boolean, and proxies default methods exactly like abstract ones (`CALLS_REAL_METHODS` is an explicit opt-in) — domain knowledge, not grep. So the mock answers `false` under both declaration shapes and the decline assertion passes without exercising the adapter. The production-side half of the claim holds: `WalkerContext` and `SubTraversalPredicateAdapter` must each state an answer, and a future implementer cannot inherit `true` silently — the `dropsRowsOnAbsentProperty` precedent's actual benefit.
- **Verdict**: BREAKS on the mock half, HOLDS on the implementer half. Item 5's positive control remains the only mock-side defence.

#### AT2 Assumption test: where(__.out().tail(1)) witnesses a combinator swallow
- **Claim**: item 5 / bullet `:129` — the case detects a recogniser wrongly claiming a `tail` inside a filter child.
- **Stress scenario**: the swallow occurs; the child degenerates to an existence filter.
- **Code evidence**: `tail(1)` emits exactly when its upstream is non-empty, so the existence reading and the correct reading return identical rows on every graph — iteration 1 measured both spellings at `{#22:0, #23:0}`. Carried: the item text and bullet are unchanged at `800e70aa1a`, and the equivalence is semantic rather than fixture-bound.
- **Verdict**: BREAKS. The positive-control rule (`:88`) tests fixture liveness, not witness discrimination; this witness cannot discriminate.

#### AT3 Assumption test: the union-child decline can be witnessed black-box as the explicit gate's work
- **Claim**: bullet `:128` — the decline is "witnessed by the explicit non-empty-`listShapingOps` gate rather than inferred from op reference identity".
- **Stress scenario**: implementer follows the clone analysis and allocates fresh ops per recognition; the new gate is deleted or ordered after the equals comparison.
- **Code evidence**: `UnionStepRecogniser.java:105-108` — `!agreedShaping.equals(childResult.shaping())` declines on the second child because `ResultShaping` is a record comparing `listShapingOps` element-wise and distinct instances without value equality are unequal. Every black-box result assertion stays green with the new gate absent. DR-T3 (`:29`) names the equal-comparing construction (record singletons) a white-box test needs.
- **Verdict**: FRAGILE — the gate is needed and the ordering is now specified, but the claimed witness exists only as a white-box unit test no item names.

#### AT4 Assumption test: a throwing append escapes TraversalStrategy.apply()
- **Claim**: DR-T2 (`:28`) — the throw template "throws `UnsupportedOperationException` out of `TraversalStrategy.apply()`, breaking the all-or-nothing contract loudly".
- **Stress scenario**: a recogniser raises `UnsupportedOperationException` during a sub-walk at HEAD.
- **Code evidence**: `GremlinToMatchStrategy.java:223` re-throws only `ReservedAliasException`; `:230-237` routes every other `RuntimeException` to `declineOnThrow` (`:296`), leaving the native step list untouched; `:51` and `:110` document the net. All present at `800e70aa1a`.
- **Verdict**: BREAKS. The throw degrades to a silent native decline; the design survives on cost and diagnosability, not the stated mechanism. Same result as iteration 1's AT3 — the clause was never corrected.

#### AT5 Assumption test: the TailGlobalStepPlaceholder form is reachable by an in-track test
- **Claim**: item 5 tests "placeholder-form `tail`"; item 3 defers the `getLimitAsGValue()` decision.
- **Stress scenario**: build `tail(n)` the way the package's tests build traversals.
- **Code evidence**: carried from iteration 1 (probe: `g.V().tail(1)` compiles to `TailGlobalStep` pre- and post-strategy on both arms; the contract's `CONCRETE_STEPS` covers both classes). Nothing in Track 9's landing touches the fork jar (DR-T4 records the same ground for not re-deriving step semantics), so the measurement carries.
- **Verdict**: FRAGILE — registration is right; the test's construction path and the GValue decision are still unnamed at `800e70aa1a`.

#### AT6 Assumption test: the cherry-picked harness commits are no longer on one machine
- **Claim**: item 7's resolution — reachable at `43907ff312` / `deb8e72ee9`, "no longer live only on one machine".
- **Stress scenario**: local disk loss before the next push.
- **Code evidence**: `git ls-remote --heads origin gremlin-to-match-translator-design` → `f2b1230db0`, which predates the cherry-pick; local HEAD `800e70aa1a` carries both commits as ancestors; pushes on this branch wait for user approval.
- **Verdict**: BREAKS until the next push, then HOLDS. One word of tense, or one push, closes it.

#### AT7 Assumption test: item 4a's template exists and its premises hold at HEAD
- **Claim**: the walker carries two in-loop fail-closed gates, neither reads `listShapingOps()`, and `capturedCardinalityClause` + `POST_CARDINALITY_RECOGNISERS` are the idiom to copy.
- **Code evidence**: gates at `GremlinStepWalker.java:442` (union carrier) and `:449` (cardinality); `capturedCardinalityClause` (`:534`) reads `skip()` / `limit()` / `returnDistinct()` only; `POST_CARDINALITY_RECOGNISERS` (`:569`) with a javadoc arguing each membership on "the row set, its order, or its multiplicity" — the argument shape 4a prescribes.
- **Verdict**: HOLDS on every particular. R10's fix describes real code.

#### AT8 Assumption test: item 1's seam premises hold at HEAD
- **Claim**: `dropsRowsOnAbsentProperty` is non-default on the interface, `false` on the adapter, read-and-declined-on; `withListShapingOps` exists at `ResultShaping.java:106` with only test callers.
- **Code evidence**: `RecognitionContext.java:357` (non-default), `WalkerContext.java:636`, `SubTraversalPredicateAdapter.java:472`, `RangeGlobalStepRecogniser.java:47-50,:210`; `ResultShaping.java:106`; grep finds `withListShapingOps` callers only in `YTDBMatchPlanStepTest` (six sites) — bounded, not PSI-established. `UnionStepRecogniser.java:124` calls `setResultShaping(agreedShaping)` as item 1's no-collision argument states.
- **Verdict**: HOLDS.

#### AT9 Assumption test: the six javadoc-defect claims in Context and Orientation are real
- **Claim**: the track opens `ListShapingOp`'s false "once per child plan" clause and thin `unfold` line, `UnionStepRecogniser`'s "not translated yet" comment, the seven-flags / "calls this once" wording, `BoundaryOutputType`'s opening, and relies on `MultiPlanMatchStep`'s single-stream behaviour and a seven-state lifecycle.
- **Code evidence**: `ListShapingOp.java:33-34` ("…and once per / child plan for a multi-plan boundary" — line-wrapped, invisible to single-line grep) and `:20-21`; `UnionStepRecogniser.java:28-29`; `RecognitionContext.java:341,:345`; `WalkerContext.java:126`; `MultiPlanMatchStep.java:36-37,:308,:337` (one `MultipleExecutionStream`); `AbstractMatchPlanStep` private `State` enum with all seven constants and `resetLifecycleForClone` at `:683`; `MultiPlanMatchStepTest:591-607` carries the clone-isolation idiom item 5 copies.
- **Verdict**: HOLDS. The one hazard is methodological: two of these clauses defeat single-line grep, so the item's implementer should locate them by Read.

#### AT10 Assumption test: item 6's corrected CI premise matches the pipeline at HEAD
- **Claim**: every platform leg passes `-Dmaven.test.failure.ignore=true`, so a green leg is not a pass and `embedded`'s numbers are read from annotations or reports.
- **Code evidence**: `maven-pipeline.yml:173` and `:185` (Linux via `matrix.mvn_opts`), `:334` (Windows), `:410` (macOS), `:507` (small-cache IT) — all carry the flag. This also confirms Track 9's Surprises entry citing ":200, no flag" is stale on `develop`'s current pipeline; item 6's corrected posture is the right one.
- **Verdict**: HOLDS (matches R13 as applied).

#### AT11 Assumption test: item 9's 18-site enumeration still reads on the tree Phase A decomposes against
- **Claim**: T12 re-verified 18 hand-built AST sites across seven files at `f2b1230db0`.
- **Code evidence**: `git log f2b1230db0..HEAD -- core embedded jmh-ldbc` shows only the three `jmh-ldbc` harness commits, netted out by the revert `edcf10dfa6` and touching no `core` file — no translator-package code moved since T12's measurement.
- **Verdict**: HOLDS. The re-enumerate-first instruction on items 9 and 10 stays right anyway; this only says the figure has not rotted in the past day.
