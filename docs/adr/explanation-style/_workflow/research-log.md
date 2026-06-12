# Research Log — explanation-style (YTDB-1084 + YTDB-1106)

## Initial request

Implement YTDB-1084 and YTDB-1106 as one batched pass.

YTDB-1084 — "Design cold-read doesn't enforce the prose AI-tell house-style
subset." Add an enforcer for the existing § Banned analysis patterns and
§ Structural sentence rules that today fall between the design cold-read
(comprehension/doc-shape only) and the `dsc-ai-tell` mechanical check (narrow
regex). Two combining moves: a judgment-layer `### Prose AI-tell additions`
block in `design-review.md`, and regex-detectable extensions to `dsc-ai-tell`
(inflated-abstraction labels, the "X, not Y" faux-symmetry variant).

YTDB-1106 — "Add an Orientation rule to house-style: terse-but-context-free
prose is a defect." The mirror failure: prose too terse to follow without
opening the code. Add a top-level `## Orientation` section to `house-style.md`
and wire it into the always-on AI-tell subset (the canonical four-name subset
becomes five). Judgment-layer only.

Both issues are Show-stopper and tagged `dev-workflow`. Their comments direct a
single batched pass: same files (`house-style.md`, `design-review.md`, the
AI-tell subset wiring), the same ~11 sync sites, one reviewer block in
`design-review.md` scanning both directions (too dense / too terse).

## Baseline and re-validation

Workflow-modifying branch: it edits `.claude/workflow/**`, `.claude/skills/**`,
plus `.claude/output-styles/**` and `.claude/scripts/**` (the last two are
**outside** the §1.7 staging convention's covered prefixes — open question OQ1).

- Fork point / branch tip / develop tip all at `26f990ed82` ("Complexity-Adaptive
  Workflow Tiering" #1140). No commits on the branch yet.
- The plan-slimization merge YTDB-1106 told us to reconcile against has already
  landed on develop (`26f990ed82`); the branch sits on top of it, so the
  in-flight-conflict risk the issue was filed to avoid is resolved. Plan against
  the post-merge state.
- Sync-site inventory (the ~11 sites both issues must keep consistent) to be
  enumerated and pinned during research; re-validate after any rebase onto
  develop.

## Decision Log

<!-- append-only; one entry per settled decision, each with **Why:** and
**Alternatives rejected:** -->

### [2026-06-12] [ctx=safe] D1 — Faithful full sync of the subset enumeration (OQ2 = A)

The four-name AI-tell subset becomes five at **every** site that enumerates it
as a closed set (~50 files: agent blurbs, prompt blurbs, chat-scale-prose
blurbs, the 4 workflow-doc enumerations, the 3 canonical sites, the hook, and
the 2 tests).

Exact inventory and the canonical reworded blurb are pinned in S1.

**Why:** Matches the project's "the canonical subset must move together" sync
discipline. The drift risk that made the issue defer is gone — plan-slimization
merged at `26f990ed82` and this branch sits on top with nothing in flight, so a
large diff carries low rebase-conflict cost.
**Atomic-sync constraint (A4 — planner must inherit this):** the ~50-site
enumeration sync lands as **one commit** (or, at minimum, every blurb plus the
canonical sites land inside a single track with the four-vs-five window closed
before that track's Phase C). Under live-edit (D5), any split that commits the
canonical sites at five while blurbs still read four leaves a window where the
branch's own `review-workflow-consistency` (which reads cross-file, beyond the
diff) flags the inconsistency — the same four-of-five state the option-C
rejection names. The edits are one-line prose changes, so one atomic commit is
feasible.
**Alternatives rejected:** (B) centralize-then-add — fixes the duplication
root-cause but is scope expansion beyond the two issues, deferred to a possible
follow-up. The real reason the ~50 inline enumerations exist (A8) is **per-spawn
self-containedness**: a sub-agent reads its prompt blurb and knows the
applicable sections without opening another file; a pointer-only blurb either
adds a file read to every spawn (a real per-spawn context-budget cost across the
~30 agent/prompt files, cf. the YTDB-1094 boot-cost work) or drops the inline
names and weakens compliance. A B follow-up must start from that fork, not just
"out of scope." (C) issue-literal ~10 sites — leaves ~40 blurbs at four-of-five,
which `review-workflow-consistency` and the governance grep flag as drift.

### [2026-06-12] [ctx=safe] D2 — Orientation joins both subset tiers (OQ3 = yes)

The new `## Orientation` rule joins the always-on AI-tell subset on **both**
surfaces the subset governs: chat-scale prose **and** `*.java` / `*.kt` code
comments (Javadoc rationale).

**Evidence (corrected per A5).** `conventions.md §1.5` has **no chat-scale
table row**; its two tiers are full-style Markdown (+ PR/commit/YouTrack) and
the `*.java`/`*.kt` AI-tell subset. The **chat** subset lives in the per-file
blurbs and `house-conversation.md`, not in §1.5. The **code-comment** subset is
the §1.5 Tier-B table row plus `house-style-write-reminder.sh`'s `tier_b_body`
(its Tier A is Markdown-full, Tier B the four names). So Orientation joins the
chat carriers (the 11 chat blurbs + `house-conversation.md`) and the
code-comment carriers (§1.5 Tier-B row + the hook).
**Why:** The issue scopes it to "chat and every prose surface." A Javadoc
reader, though, has the code open by definition, so YTDB-1106's literal
criterion ("too terse to follow without opening the code") does not transfer
verbatim. The design **commits to a code-comment-scale restatement** of the
orientation criterion for the Tier-B row and `tier_b_body`: rationale comments
must not assume context **outside the file** — distant call-site behavior, issue
history, reviewer-thread knowledge — and must gloss the project-specific entity
the rationale turns on. Without that restatement the rule's test would be
incoherent on the code-comment surface.
**Not enumeration drift.** A deliberate tier difference recorded once in the
§1.5 table is a documented scope split, not the four-vs-five enumeration drift
D1 forbids (D1 binds sites enumerating the *same* subset). Leaving Javadoc out
would be a defensible scope choice, not a D1 violation; we include it because
CLAUDE.md's "Comment non-obvious code" already points the same way for rationale
comments.
**Alternatives rejected:** chat-only (the orientation floor is just as real for
rationale comments that assume out-of-file context; CLAUDE.md already asks for
context there).

### [2026-06-12] [ctx=safe] D3 — Generalize § Explanatory register into ## Orientation (OQ4 = generalize)

`## Orientation` becomes the single always-on statement of the
terse-but-context-free-is-a-defect principle. The existing
`### Explanatory register` (today under `## Document-shape rules`, design/ADR
only) is reduced to a design-doc-specific specialization that cross-links up to
`## Orientation`, keeping only its design-specific nuance (mechanism-overview
sections, the mid-level-reader completeness bar) rather than restating the
general rule.

**Why:** Avoid two near-duplicate statements of the same principle. One general
rule + one specialization that points at it is maintainable; two parallel
statements drift.
**Reconciliation set (A6 — the design must name all three, or generalizing
leaves the file self-contradictory):**
1. **Rewrite `house-style.md:379`.** Today it scopes the document-shape family:
   "[these rules] are not enforced on issue bodies, PR descriptions, or status
   prose, which use the BLUF rule alone." Once `## Orientation` is top-level and
   always-on, those surfaces are governed by BLUF **plus** Orientation **plus**
   the always-on tells — so line 379 must carve Orientation out of the
   document-shape exclusion or it reads as stale on its face.
2. **Give `## Orientation` its own finding category.** The current
   `### Explanatory register` calls too-terse prose "a finding under
   § Why-before-what" — a document-shape rule. The generalized always-on rule
   cannot cite a design-only section for a finding on a non-`docs/adr/**`
   surface; it needs its own severity/finding framing.
3. **Move the Self-check entry.** Item 8 lists explanatory register under
   "Document shape (design/ADR only)"; Orientation needs an entry in an
   always-on self-check item instead (it is item 5's family — analysis
   patterns — or a new always-on item, decided at design time).
**Alternatives rejected:** leave both (duplication the issue explicitly flags);
cross-link only without generalizing (still two full statements).

### [2026-06-12] [ctx=safe] D4 — New reviewer block runs for design AND tracks (OQ5 = both)

The new `### Prose AI-tell additions` block in `design-review.md` runs for both
cold-read targets — `target=design` (phase1-creation / phase4-creation /
design-sync) and `target=tracks` (the Step-4b track cold-read) — at creation and
review.

**Why (scoped per A7).** Track prose carries the same over-dense / too-terse
failure as design prose at **creation time**, so scanning only `design.md`
leaves the plan-at-start track sections unchecked. The claim is bounded to that
creation-time surface: the `target=tracks` cold-read runs **once**, at Step 4b
before the plan commit, so it does **not** cover the YTDB-1106 exemplar surface
(live `## Decision Log` entries, episodes, review findings) that accrues during
Phase 3 — nothing re-runs a cold-read there. The Phase-3 surface is enforced by
the **always-on blurb wiring** (D1/D2) on the writers, not by this reviewer
block. So D4 buys creation-time track-prose coverage; D1/D2 buy the rest.
**Applies-to asymmetry (the design must spell this out).** The sibling
`### Human-reader cold-read additions` applies only to the three design-target
mutation kinds (phase1-creation / phase4-creation / design-sync), so the new
block **cannot copy its applies-to line** — it needs its own statement covering
`target=design` (those three kinds) **and** `target=tracks`. Sync sites for the
block: the new TOC row, the § Tone-and-depth "five Human-reader rules" count
(line 406), and the `readability-feedback` Rule sync map's design-review row
(which today lists only the Human-reader bullets).
**Alternatives rejected:** design-only (leaves creation-time track prose
unchecked for the same tells).

### [2026-06-12] [ctx=safe] D5 — No staging; live-edit all surfaces, no workflow-modifying marker (OQ1)

The plan does **not** stage: every edit (`.claude/workflow/**`,
`.claude/skills/**`, `.claude/agents/**`, `.claude/output-styles/**`,
`.claude/scripts/**`) lands on live paths and self-applies during the branch.
The legitimacy comes from **D6** (a §1.7 opt-out amendment this branch carries),
not from the current §1.7 — which, as written, **binds** this branch to stage.

**Why live-edit (substance).** This change alters prose rules, prompt text, one
judgment-layer reviewer block, and one contained regex — it changes **no
`_workflow/**` artifact schema** (no track-file sections, resume-state fields,
or drift-gate format), so the destabilize-the-branch's-own-machinery hazard
§1.7 guards against does not exist (assumption-test C8: HOLDS). The largest
surfaces (`house-style.md`, `house-conversation.md`,
`design-mechanical-checks.py`) sit outside §1.7's covered prefixes already
(§1.7(a): "No other prefixes participate"), so staging only the
workflow/skills/agents blurbs buys neither clean isolation nor clean
self-application. Self-application is the **goal** for a style-rule change: the
branch's own `design.md`, track files, and chat are held to the new rules during
the branch.
**Why not the current §1.7 (A1 correction).** The earlier claim that "§1.7(b)
documents marker-omission as a sanctioned path" was **false**. §1.7(b) calls
omission *forfeiture* and assigns a planner duty to add the marker plus a
reviewer duty to verify it; §1.7(h) binds every workflow-modifying branch opened
after the convention landed to the full convention. So omission today is a
convention violation, not an opt-out. The fix is to **make** the opt-out real
(D6), not to assert one that does not exist.
**Drift handling (A2 — stamp-advance, not habituated Suppress).** Committing
live `.claude/workflow|skills|agents` edits advances HEAD past the artifacts'
stamp base, so the startup drift gate fires every subsequent session. Picking
**Suppress** each time habituates the user and would mask a *real* develop-side
drift if develop is ever rebased in mid-branch. The principled resolution is
**stamp-advance**: after the last workflow-editing commit, run
`/migrate-workflow`; the per-commit replay is a no-op (the commits change prose,
not artifact schema), so the run reduces to advancing every artifact's line-1
stamp to HEAD (`/migrate-workflow` §4.8). The gate then goes silent for
subsequent sessions and **re-arms** for any later genuine drift. Until that
run, Suppress is the interim answer, and the D6 opt-out clause records the
stamp-advance duty so it is not lost. This is folded into D6.
**New-regex severity (A9).** The two `dsc-ai-tell` additions (inflated-abstraction
labels; the "X, not Y" faux-symmetry, which risks firing on legitimate
contrastive "A, not B" prose) ship at the **demotable** severity discipline the
rule already documents ("Demote-to-`suggestion` is the documented fallback"),
calibrated against the false-positive count observed on the branch's own Phase-4
`design-final.md` authoring (where the live regexes self-apply through
`edit-design`; a blocker-severity false positive there would exit 1 and block
the loop).
**Alternatives rejected:** full staging (defers all self-application to
post-merge, so the rule-adding branch is the one branch never checked against
its rules; odd split given output-styles/scripts are live regardless); hybrid
stage-covered-only (neither clean isolation nor clean self-application);
user-waiver-only without amending §1.7 (leaves the convention saying the branch
forfeited the mechanism — a one-off exception rather than a reusable rule; the
user chose D6's amendment over this).

### [2026-06-12] [ctx=safe] D6 — Amend §1.7 with a prose-rule self-application opt-out (A1 resolution)

This branch adds an explicit opt-out to `conventions.md §1.7` so live-editing is
genuinely sanctioned. The marker's **two** roles must be separated (A10): the
§1.7(b) workflow-modifying marker switches on **both** the staging mechanism
(implementer write-routing, the pre-commit gate, staged-delta prep, the Phase-4
promotion guard) **and** the reviewer-criteria re-pointing (the
"Workflow-machinery criteria" blocks at `technical-review.md:113`,
`risk-review.md:110`, `adversarial-review.md:282`, and the staging-aware path in
`track-code-review.md:250-260`, which make Phase-3A/Phase-C reviews verify prose
references as paths/anchors and supersede the Java lenses with the five prose
criteria). The opt-out must disable the **first** and keep the **second**.

**Chosen marker shape (A10 shape ii — the lower-surface one).** The opt-out plan
carries a **distinct opt-out marker** in `### Constraints`, **not** the
workflow-modifying marker. With the workflow-modifying marker absent, every
staging-mechanism consumer already defaults to live (write live, no staged
delta, promotion guard finds no `.claude/` staged subtree and skips) — so the
staging half needs **no** consumer edits and there is **no bootstrap deadlock**
(shape i's rider would route its own bootstrap commit into staging via the
unamended `implementer-rules.md:256-300`). The only rewiring is the
reviewer-criteria switch: extend the four criteria-switch blocks above to fire
on the **workflow-modifying marker OR the opt-out marker**, so prose-criteria
reviews stay on for this all-prose branch. Those four files are review prompts —
judgment-layer, inside the opt-out's own class (below).

**Opt-out criteria (A12-tightened — consumer class, not intent).** A plan may
take the opt-out when both hold: (1) it changes no `_workflow/**` artifact
*schema* (track-file section set, resume-state field, drift-gate format, stamp
format); **and** (2) every edited file's in-branch consumer is
**judgment-layer** — style rules, review criteria, prompt blurbs, reviewer
blocks. Execution-procedure files (`implementer-rules.md` write-routing/gates,
`step-implementation.md`'s procedure, phase state machines, resume/gate
protocols) stay staged, or need a per-file justification naming which of the
branch's own remaining phases consume the edit and why mixed-version execution
is safe. Intent alone is always claimable; consumer class is checkable. This
branch's edits are all judgment-layer (the four criteria-switch extensions are
review prompts), so it qualifies.

**Drift handling (A2).** The opt-out clause records a **mandatory** stamp-advance
duty: after the last workflow-editing commit, run `/migrate-workflow` (a no-op
replay over prose-only commits that reduces to advancing every artifact stamp to
HEAD, §4.8), re-arming the drift gate for real develop drift. Suppress is the
interim answer until then.

**Landing order (A11).** The §1.7 amendment **and** the four criteria-switch
extensions land in the branch's **first** workflow-editing commit (the
conventions track ordered first), so no reviewer ever reads unamended §1.7
against this plan. Until it lands, the `### Constraints` opt-out note is written
**self-justifying** — citing D6 and naming the in-flight amendment — so a
reviewer reading unamended §1.7 (or the Phase-2 consistency review resolving the
opt-out-clause reference) sees an acknowledged deviation, not a phantom
reference. Same window class as D1's atomic-sync constraint (A4).

**Why:** Turns the A1 contradiction into a reusable rule and fits the branch
theme. Under live-edit it self-applies once that first commit lands.
**Implemented in:** the conventions track, ordered first (§1.5 sync + §1.7
opt-out + the four criteria-switch extensions land together).
**Alternatives rejected:** shape (i) keep-marker-plus-rider (needs a bootstrap
fix and edits to execution-procedure files — `implementer-rules.md`,
`step-implementation.md` — which criterion (2) says must stay staged, so it is
self-defeating here); user-waiver-only (one-off, not reusable); leaving §1.7
unchanged and omitting the marker (the A1 violation, and it silently disables the
reviewer-criteria switch).

## Surprises & Discoveries

- [2026-06-12] [ctx=safe] S1 — Blast radius is ~5× the issue's estimate, hand-maintained,
  and the count bump is **not** a literal "four → five." Pinned grep output (run
  on the branch tip, excluding `docs/adr/`):
  - `grep -rln 'Banned analysis patterns' .claude/ CLAUDE.md` = **50 files** —
    the universe that enumerates the subset section names.
  - `grep -rln 'banned-section heading slugs' .claude/ CLAUDE.md` = **30 files** —
    the `.claude/agents/*.md` + `.claude/workflow/prompts/*.md` +
    workflow-doc blurb that reads "the four banned-section heading slugs to
    apply are …".
  - `grep -rln 'follows the AI-tell subset of' .claude/ CLAUDE.md` = **11 files** —
    the chat-scale-prose blurb in skills + workflow docs.
  - Remaining sites in the 50: the 3 canonical (`house-style.md` line-20
    count + self-check, `house-conversation.md` "four sections" + bullets,
    `conventions.md §1.5` table row + "four Tier-B" + governance grep), the
    hook (`house-style-write-reminder.sh` `tier_b_body`), 2 tests
    (`test_house_style_hook.py:694-697` pins the section strings;
    `test_dsc_ai_tell.py`).

  Three sites the first roster missed (A3), all in-scope:
  1. `readability-feedback/SKILL.md` — the `## Rule sync map` (line 41) is the
     codified add-a-rule procedure, and its governance grep (line 54) plus the
     **twin** at `conventions.md:570` search for the four names; both must gain
     `Orientation` or every future rename/sync audit silently skips the fifth
     section.
  2. `ai-tells/SKILL.md` — the `## Catalogue lookups` routing table (lines
     19-27), walked during Pass 1, needs an Orientation row.
  3. (folded into #1) the second governance grep at `conventions.md:570`.

  **The count bump is semantic, not numeric.** The 30-site blurb says "the
  **four banned-section** heading slugs"; `## Orientation` is a positive
  **floor**, not a ban, so "five banned-section slugs" would be false. The
  blurb must be **reworded** once, canonically, and pasted byte-identically, or
  ~30 hand edits produce ~30 slightly different sentences — exactly what
  `review-workflow-consistency` flags. **Canonical replacement (record once,
  copy bytes not intent):** *"the five AI-tell subset section slugs to apply
  are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned
  analysis patterns`, `### Em-dash discipline`, and `## Orientation`."* The
  11-site chat blurb does not say "banned-section," so it needs no rewording —
  just append a fifth list item and bump the "four → five" count in
  `house-conversation.md`. The append literal (A13 — copy bytes, not the glitch):

  ```
  , and `## Orientation`
  ```

  No generator emits any of this — `workflow-reindex.py` only rebuilds TOC and
  stamps; only `house-style-write-reminder.sh` holds blurb text (the one
  `tier_b_body` Java/Kotlin string). So the sync is ~50 hand edits.

- [2026-06-12] [ctx=safe] S2 — The issues' line/section citations are stale.
  Both issues were written before #1140 (Complexity-Adaptive Workflow Tiering)
  merged. `design-review.md` has since been restructured: it now has two
  targets (`target=design` / `target=tracks`), a `### Track-scoped cold-read
  (Step 4b)` section, and the Human-reader additions block at lines 165-180
  (not `:167-170`). The intent maps cleanly (add a `### Prose AI-tell
  additions` block sibling to `### Human-reader cold-read additions`, sync the
  TOC row + the § Tone-and-depth "five Human-reader rules" count at line 406),
  but the exact anchors differ. This is why the issue said "reconcile against
  the active branch before implementing."

- [2026-06-12] [ctx=safe] S3 — The over-dense and too-terse surfaces are the
  same review-agent output. YTDB-1106's exemplar is about decision-log
  *findings* being too terse; the agent/prompt blurbs govern exactly that
  emitted prose. So the Orientation rule's target surface and the agent-blurb
  sync set overlap heavily — both halves of the change touch the same files.

## Open Questions

All five questions raised during research were resolved into Decision Log
entries before the Phase 0→1 gate; none remain open. Resolution map: OQ1 → D5,
OQ2 → D1, OQ3 → D2, OQ4 → D3, OQ5 → D4. Entries kept below for provenance.

- [2026-06-12] **RESOLVED → D5.** OQ1 — Staging coverage gap. The §1.7 staging convention covers
  only `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`. This
  change also edits `.claude/output-styles/**` (`house-style.md`,
  `house-conversation.md`) and `.claude/scripts/**`
  (`design-mechanical-checks.py`, `tests/test_house_style_hook.py`). Decide how
  those out-of-staging edits are handled on a workflow-modifying branch.

- [2026-06-12] **RESOLVED → D1.** OQ2 — Sync strategy for the ~50-site subset enumeration (the
  central planning fork). Three paths:
  (A) **Faithful full sync** — every blurb becomes five; ~50 hand edits.
      Matches the issue's "must move together" intent. Low rebase-conflict risk
      now that plan-slimization has merged and no other branch is in flight.
  (B) **Centralize then add** — replace the ~50 duplicated enumerations with a
      pointer to one canonical list, then add Orientation only at the canonical
      sites. Fixes the root-cause duplication this change exposed; future subset
      changes become one-line. Scope expansion beyond the two issues, needs its
      own rationale, touches a similar file count to (A) but leaves a durable
      win.
  (C) **Issue-literal** — update only the ~10 sites the issue named; leave ~40
      blurbs listing four-of-five. Smallest diff but violates the project's
      sync discipline; the `review-workflow-consistency` agent and the
      `grep -rn` sync command would flag the drift.

- [2026-06-12] **RESOLVED → D2.** OQ3 — Does Orientation join the **Java/Kotlin code-comment**
  subset too, or chat/Markdown-prose only? conventions.md §1.5 uses the
  four-name subset in two tiers: chat-scale prose and `*.java`/`*.kt` code
  comments (the Tier-B table row + the `house-style-write-reminder.sh` hook).
  The issue says "chat and every prose surface"; whether Javadoc rationale is
  in scope is unstated.

- [2026-06-12] **RESOLVED → D3.** OQ4 — § Explanatory register overlap. The new top-level
  `## Orientation` rule near-duplicates the existing
  `### Explanatory register` (house-style.md:427-431), which already says
  "too terse: a finding" but is scoped to design/ADR mechanism sections. The
  issue leaves open whether to generalize, cross-link, or leave both.

- [2026-06-12] **RESOLVED → D4.** OQ5 — Scope of the new `### Prose AI-tell additions` reviewer
  block in design-review.md. Does it run only for the `design.md` cold-read
  (`target=design`: phase1-creation / phase4-creation / design-sync), or also
  for the `target=tracks` Step-4b cold-read that assesses track prose?

## Adversarial gate record

<!-- the Phase 0→1 adversarial gate appends one verdict heading per iteration -->

### Adversarial review of this log (2026-06-12) — NEEDS REVISION: 1 blocker, 5 should-fix, 3 suggestion

Iteration 1. Review file: `reviews/research-log-adversarial-iter1.md`. Blocker
A1 vs D5 (the "§1.7(b) sanctions marker omission" justification is false — re-decide
the legitimacy mechanism). Should-fix A2–A6 strengthen/correct D5/D1/D2/D3 rationale
and record the atomic-sync and code-comment-restatement constraints. Suggestions
A7–A9. Loops on A1.

### Adversarial review of this log (2026-06-12) — NEEDS REVISION: 0 blocker, 3 should-fix, 1 suggestion

Iteration 2 (verdict-producer). Review file:
`reviews/research-log-adversarial-iter2.md`. All nine iteration-1 findings
**VERIFIED** against the files (counts 50/30/11 reproduced; §1.7, migrate-workflow,
§1.5, house-style.md, design-review.md re-checked). No blocker. Three new
should-fix on D6's opt-out mechanics (A10 marker drives staging **and**
reviewer-criteria, so the opt-out must split them; A11 amendment landing order
unpinned; A12 criterion (2) too broad — admits execution-procedure edits) plus
one suggestion (A13 backtick collision in S1's append literal). D6 revised to
adopt the lower-surface marker shape (distinct opt-out marker + four
criteria-switch extensions), tightened criterion (2) to consumer class, pinned
the conventions track first, and fixed the append literal. Loops on the should-fix.
