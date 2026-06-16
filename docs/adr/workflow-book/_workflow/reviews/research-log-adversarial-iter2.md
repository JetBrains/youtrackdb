<!-- review-manifest
verdict: pass
mode: verdict-producer
prior_findings: 5
prior_verdicts:
- id: A1, verdict: VERIFIED, basis: "D5 adds a 'Diverges from the base model' paragraph (model ships inline mermaid + zero assets), weighs the cheaper house-style all-ASCII baseline, names the bounded dense set (phase state machine, tier-gate tree, track/step/episode hierarchy), and re-weighs OQ2 diffability."
- id: A2, verdict: VERIFIED, basis: "D6 retitled '(a preference, not a verified-robustness claim)', Why softened to 'not a measured robustness result', new load-bearing install-prerequisite clause (d2/mmdc absent, only node/npx present; render-diagrams.sh checks+prints install; render path doesn't run this plan)."
- id: A3, verdict: VERIFIED, basis: "Surprise #4 rewritten to cite MAINTENANCE_PROMPT.md's git-log source-tree walk as the drift model and explicitly demote workflow-drift-check to a different stamp/merge-base mechanism; only the workflow-SHA value is reused as the baseline."
- id: A4, verdict: VERIFIED, basis: "D8 Why reframed — model splits into two working hand-driven entry points (cycle 1->2 added chapters this way, 'a working design, not a defect'); the 'removes a ceiling' framing is gone."
- id: A5, verdict: VERIFIED, basis: "D2 records the name choice over -press/-forge/-pipeline and closes the Maven sub-question; all four Open Questions now carry 'Resolved -> D#' pointers."
new_findings: 0
index: []
-->

# Adversarial Review — Research Log (Phase 0 → 1 gate), iter 2 (verdict-producer)

**Target:** `docs/adr/workflow-book/_workflow/research-log.md`
**Mode:** verdict-producer re-review of iter-1 findings A1–A5
**Verdict:** PASS — all five prior findings VERIFIED; no blocker remains, every should-fix is resolved; 0 new findings.

This iteration re-checks the five iter-1 findings against the revised on-disk log. The
iter-1 evidence base (d2 absent / node+npx present; the model book ships only inline
mermaid; `workflow-drift-check.md` is a `_workflow/**` stamp-fold gate, not a source-content
walk) still holds and grounds each verdict below. The revisions matched the iter-1 proposed
fixes; no revision introduced a new contradiction, scope creep, or weakened decision.

## Prior-finding verdicts

### A1 — VERIFIED
Iter-1 (should-fix): D5/D6 diagram rationale ignored the all-mermaid base model and the
cheaper all-ASCII option, and left the SVG set an open category.
**Basis:** D5 now carries an explicit "Diverges from the base model" paragraph stating the
chosen base ships inline mermaid in all 17 chapters with zero rendered assets and that D5
deliberately departs from the model here. The Why weighs the all-ASCII baseline (named as
already mixed into `branch-divergence-check.md` / `conventions-execution.md` and
house-style-preferred) and confines SVG to figures "ASCII genuinely cannot carry." The dense
set is now a named, bounded list — phase state machine, tier-gate decision tree,
track/step/episode hierarchy — fixed at TOC time. OQ2's diffability caveat is accepted in
exchange for portability and contained by keeping the SVG set small and the sidecar source
diffable. All four iter-1 asks are met.

### A2 — VERIFIED
Iter-1 (should-fix): D6's "robust in CI/containers" was asserted, not demonstrated, and the
environment contradicted it.
**Basis:** D6 is retitled "Render DSL is D2 (a preference, not a verified-robustness claim)";
the Why now reads "design preference, not a measured robustness result." A new load-bearing
"Install prerequisite" clause records that neither tool ships (d2 absent, mmdc absent, only
mermaid's node/npx present), requires `render-diagrams.sh` to check for `d2` and print the
install command on a miss, requires the brief to document the one-time install, and notes the
render path does not run during this builder-only plan — so the missing-binary risk is an
operator-doc requirement, not an execution blocker. Matches the iter-1 proposed fix.

### A3 — VERIFIED
Iter-1 (should-fix): Surprise #4 / D10 mis-cited `workflow-drift-check.md` (a `_workflow/**`
stamp-fold gate) as the drift model instead of the internals book's `MAINTENANCE_PROMPT.md`.
**Basis:** Surprise #4 is rewritten to state the book's drift model is
`MAINTENANCE_PROMPT.md`'s `git log BOOK_SHA..NEW_SHA --name-only -- <source paths>` triage,
explicitly demotes `workflow-drift-check.md` to "a different mechanism" (per-file
`workflow-sha:` stamps + `git merge-base` fold to `BASE_SHA`, routing to `/migrate-workflow`,
"none of that stamp/merge-base machinery transfers"), and keeps only the workflow-SHA value
as the baseline pin. D10's body (`git log <baseline>..HEAD -- .claude/workflow/** ...`) is now
consistent with the corrected Surprise #4. Exactly the iter-1 proposed reword.

### A4 — VERIFIED
Iter-1 (suggestion): D8/D10 overstated the model's "new-content ceiling"; the model added
content via working manual cycles.
**Basis:** D8's Why is reframed — the base model "splits production into two hand-driven entry
points" (lightweight `MAINTENANCE_PROMPT` refresh + heavyweight author-wave cycle), and "cycle
1 → cycle 2 in its README added new chapters this way, and it worked... a working design, not
a defect." The unification rationale is now "fold the two into one machinery," not "remove a
ceiling." The control-flow-divergence risk the iter-1 fix asked for landed in D10's new "Risk
to carry into the pipeline design" paragraph (from-scratch vs incremental branch must stay
explicit). Both the suggestion's reframe and risk-note are applied.

### A5 — VERIFIED
Iter-1 (suggestion): OQ3's name-alternative and Maven sub-questions were resolved in D2's body
but the Open Question wasn't explicitly closed.
**Basis:** D2's Alternatives-rejected now records choosing `workflow-book-builder` over
`-press`/`-forge`/`-pipeline` (plain-language, matches the `*-builder` convention) and that
"module" is taken as a directory, not a Maven module. All four Open Questions now carry
`Resolved → D#` pointers (OQ-scope → D1, OQ-diagram → D5/D6, OQ-machinery → D2, OQ-audience →
D4). The section is clean.

## New findings introduced by the revisions

None. The revisions are confined to rationale strengthening on D2, D5, D6, D8, D10 and
Surprise #4 plus the OQ resolution pointers. No decision was weakened, no scope was added, and
the revised Surprise #4 and D10 are mutually consistent (same pathspec, baseline-SHA pin, no
residual stamp-gate machinery). The gate clears.
