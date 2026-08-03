<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: S5, verdict: VERIFIED}
  - {id: S6, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

# Structural gate verification — iteration 2 (2026-08-03, 11-track plan)

Both fixes hold and the re-scan surfaces nothing: **PASS**, no new finding, no blocker. Dropping the
escape clause removed the last conditional from the handoff rule, so all eleven sites that state or
imply the publication point now read the same way with no branch, and `plan/track-11.md:26`'s flat
"not its post-item-2 one" is unconditionally true for the first time. The `embedded` re-install
criterion names both post-fix re-runs. Track 9's `~8–14` Scope figure survives the third measurement
pass for the same reason it survived the second — the pass amends one artifact file rather than
adding one — and every cost the three passes incur is stated at the gate that incurs it.

Artifacts read: `plan/track-9.md` and `plan/track-11.md` in full; `implementation-plan.md` D7, D9,
the Track 9 and Track 11 checklist entries (`:670-713`), `## Implementation state` and its table;
`structural-gate-verification-iter1.md` § Findings; `git diff HEAD` over both track files and the
plan; four residue greps across all three files.

**Reference-accuracy caveat.** No PSI query was run and none was needed — every check below is a
prose read, a word count, or a cross-reference resolution inside the `_workflow` tree. This review
reads no codebase, so `certs: 0`.

## Verification certificates

#### Verify S5: the escape clause was keyed to a narrower trigger than the rule it implements
- **Original issue**: item 4's re-run carried "if this item changes no production code, say so in the
  artifact and the item-3 measurement stands as published." The rule it implements keys on measured
  behaviour, not production code, and Track 9's own § Clarifications records that the `core`
  test-jar carries the Cucumber glue and the local feature files. An item-4 fix confined to either
  satisfied the clause word for word, moved the measured result, and republished item 3's number. In
  that branch `plan/track-11.md:26` named two different artifacts in one sentence.
- **Fix applied**: the user chose to drop the escape clause rather than re-key it. Item 4's re-run is
  now unconditional at both sites, with the reason spelled out; `plan/track-11.md:26` was left as
  written, because its flat reading is what the resolution makes true.
- **Re-check** — the two rewritten sites and the one deliberately untouched:
  - `plan/track-9.md:73` (Plan of Work item 4): "**Re-run both runners once more at the end of this
    item and publish that artifact as Track 11's baseline.** Fixing here moves the measured behaviour
    exactly as item 2 did, so the item-3 number stops being the handoff the moment a triage fix
    lands. The re-run is unconditional: production code is not the only thing that moves what these
    runners measure — a change to the Cucumber glue or a local feature file moves it too, and the
    `core` test-jar carries both — so no exemption is worth the risk of republishing a stale number."
    The trigger scope the finding contested is now named in the text itself and resolved by removing
    the branch rather than by widening it.
  - `plan/track-9.md:90` (handoff acceptance bullet): "The baseline handed to Track 11 is measured at
    the end of item 4, not after item 2 — both runners re-run, recorded, and named as the number
    Track 11 reads. The re-run is unconditional, so no artifact stamped item-3 is ever the handoff."
    The second sentence states the negative directly, so a reader does not have to derive it.
  - `plan/track-11.md:26` (Decision Log bullet 4), unchanged: "The baseline this track reads is Track
    9's last measurement, not its first and **not its post-item-2 one**." With no branch left in
    Track 9, its last measurement is always the item-4 one, so the exclusion holds in every case.
    The bullet's second half — "Track 9 re-runs both runners after its final fix and publishes that
    artifact explicitly for this purpose" — matches `plan/track-9.md:73` clause for clause.
  - Criteria met: the cross-track contradiction is gone in all branches, not only the default one,
    and the publication point reads identically across both files.
- **Conditional sweep**: grepped all three files for `item 3` / `item-3` / `post-item-2` / `escape` /
  `no production code` / `unconditional` / `unless` / `exempt` / `say so in the artifact` /
  `only where` / `if this item` / `stands as` / `waive` / `optional`. Every hit is either correct or
  unrelated: `implementation-plan.md:346` is `LIKE` case-insensitivity, `plan/track-11.md:48` is
  Track 11's own item 3, the four `optional` hits are the deferred Gremlin step, and
  `plan/track-9.md:83`'s "not optional" is a positive requirement on the install step. No site
  anywhere carries a conditional or names item 3 as the handoff.
- **Residual-reading check**: item 4's middle sentence ("the item-3 number stops being the handoff
  the moment a triage fix lands") is a rationale in the typical case, and read alone it could suggest
  the item-3 number survives when no triage fix lands. The sentence that follows closes that reading
  by fiat, the bolded imperative before it is unconditional, and `:90` states the negative flatly.
  No branch of the text produces an item-3-stamped handoff. Considered and not raised.
- **Flowchart agreement**: `plan/track-9.md:53-60` renders `Triage --> Handoff["item 4 re-measure:\n
  the baseline Track 11 reads"]` as a plain edge with no guard label. The Plan-of-Work text is now
  equally unguarded, so the two agree exactly — the asymmetry the finding recorded (flat flowchart
  against conditional prose) is what the fix removed. Six unique node ids, all labels quoted, `\n`
  line breaks as used throughout these files: still valid Mermaid.
- **Regression check**: checked the Decision Log rule at `:24` (unchanged and still the governing
  statement — "the number handed to Track 11 is item 4's"; "Item 3's measurement is the gate on item
  2, not the handoff"), item 3's title at `:72` (carries no publish clause), the three Track 11 sites
  at `:26`, `:69`, `:87`, and the three plan sites at `:642`, `:700`, `:718` (none names an item, all
  say "post-fix" or "publishes"). Clean.
- **Verdict**: VERIFIED

#### Verify S6: the `embedded` re-install criterion cited only the lesser of the two post-fix re-runs
- **Original issue**: the criterion's worked example named item 3's re-run, which S2 had demoted from
  the handoff. The unnamed case — item 4's — is the one Track 11 measures its whole no-regression
  claim against, so a run against the 2026-07-02 jar costs most there.
- **Fix applied**: the example names both.
- **Re-check**:
  - Location: `plan/track-9.md:83`, final sentence of the `embedded` acceptance criterion.
  - Current state: "The install step is not optional and is repeated before every `embedded`
    measurement, including item 3's gate re-run and item 4's published handoff re-run — see
    § Clarifications for why." The universal rule is unchanged, the two named cases match the two
    post-fix passes the Plan of Work now commits to, and item 1's own A/B is covered by "every".
  - Pointer resolution: "§ Clarifications" resolves to the `### Clarifications` heading at `:62` of
    the same file, whose `embedded` bullet at `:66` carries the reactor-exclusion, local-repository,
    and test-jar mechanism the criterion defers to.
  - Criteria met: the example no longer points away from the case that matters; no gate, scope, or
    ordering changed, which is what the `mechanical` classification promised.
- **Regression check**: the criterion is 88 words, up from 82 and well inside the cap. Checked
  `plan/track-11.md:61`, the sibling `embedded` clarification that points back at this file — it
  states the two commands, the repeat rule, and its own reason, and needs no parallel edit because it
  names no item. Clean.
- **Verdict**: VERIFIED

## Findings

## Evidence base

No certificates — plan-internal structural verification, no codebase read. Every claim above is a
direct read of `implementation-plan.md`, `plan/track-9.md`, `plan/track-11.md`, or
`structural-gate-verification-iter1.md`, cited inline by section and line. `certs: 0`.

Re-checked after the S5 and S6 fixes, recorded so the next pass does not re-litigate:

- **The third measurement pass does not move Track 9's Scope.** `~8–14` files holds. Track 9 now
  commits to three two-runner passes — item 1's baseline, item 3's gate on item 2, item 4's published
  handoff — but § Interfaces and Dependencies puts both runners in **one** artifact file ("one file
  carrying a labelled half per runner"), and items 3 and 4 amend that file rather than adding one.
  The plan's Scope line at `implementation-plan.md:684-686` names the same single "two-runner
  baseline artifact". This is the argument iterations 1 and 2 already accepted for the second pass;
  the third changes nothing about it. The under-floor justification for the `8` lower bound is
  DR-S1's — no shared file with Track 11, one-way coupling, the review-burden threshold — and none of
  the three clauses turns on how many times the runners execute. Track 11's `~14–20` is untouched.
- **The three passes are each costed at the gate that incurs them.** The `core` Cucumber gate is
  pinned to the targeted `./mvnw -pl core -o surefire:test@gremlin-feature-compliance-tests` at both
  `:82` and `:92`, with the 20 s figure and the ~31 min full-suite contrast stated at `:92`; the
  per-directory sweep is sized at "under 75 s including seven Maven starts" at `:43`. The `embedded`
  half is explicitly **unsized** at `:70` — "no measured A/B exists for it and the `core` numbers
  above do not transfer — so size it in the step that first runs it" — which is an honest deferral
  rather than a silent gap, and `:83` mandates the `-pl core -am install -DskipTests` prefix before
  each of its measurements. The ~31 min full suite belongs to item 4's process-compliance gate
  (`:89`, `:92`), not to any Cucumber pass, and `MatchStatementExecutionTest`'s ~32 min is booked at
  `:67` and `:88` against item 2's planner-side branch. Item 4 is the heaviest item and the file does
  not say so in one place, but every figure it carries is stated where it is spent. No under-statement
  found; not raised.
- **Bullet lengths after this session's accumulated edits.** In `plan/track-9.md` the two S5-touched
  blocks are item 4 at 158 words and the handoff acceptance bullet at 45; the S6-touched `embedded`
  criterion is 88. § Clarifications runs five bullets, longest 102; § Validation and Acceptance runs
  eleven, longest 104. In `plan/track-11.md` § Clarifications runs five bullets, longest 71, and
  § Validation and Acceptance ten, longest 112. Every block in all four sections is inside the
  200-word soft cap at the smallest-labeled-block unit, so the padding-based finding criterion is not
  even reached. The two blocks over 200 anywhere in either file are `plan/track-9.md:23` (DR-S1, 211)
  and `plan/track-11.md:70` (item 7, 207); both predate this session's fixes and neither was raised
  in iteration 1.
- **The dependency chain and the plan-file surfaces are unchanged.** Execution order 7 → 8 → 10 → 9 →
  11. `implementation-plan.md:690` gives Track 9 `**Depends on:** Track 10`, matching
  `plan/track-9.md:108`; `:713` gives Track 11 `**Depends on:** Tracks 7, 8, and 9`, matching
  `plan/track-11.md:104`. The narrative at `:718`, the eleven table rows with 9 and 11 `not started`,
  and the decision-conformance sentence at `:734` all still agree with the `[x]`/`[ ]` marks. These
  fixes moved no work across the track boundary, so nothing here needed an edit.
- **Standing Phase-4 deferrals, unchanged and not re-raised.** The frozen `design.md` class-diagram
  lag, the `MultiPlanMatchStep : "Track 6"` provenance, the boundary-step `reset()` / metrics-capture
  gaps, and the 7-line Component Map boundary bullet cleared by the 2026-08-01 pass.

**Overall: PASS.** S5 and S6 both VERIFIED, no new finding, no blocker. S1–S4 remain closed from
iteration 1.
