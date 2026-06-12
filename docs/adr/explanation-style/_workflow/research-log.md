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

**Why:** Matches the project's "the canonical subset must move together" sync
discipline. The drift risk that made the issue defer is gone — plan-slimization
merged at `26f990ed82` and this branch sits on top with nothing in flight, so a
large diff carries low rebase-conflict cost.
**Alternatives rejected:** (B) centralize-then-add — fixes the duplication
root-cause but is scope expansion beyond the two issues, deferred to a possible
follow-up; (C) issue-literal ~10 sites — leaves ~40 blurbs at four-of-five,
which `review-workflow-consistency` and the `grep -rn` sync command flag as
drift.

### [2026-06-12] [ctx=safe] D2 — Orientation joins both subset tiers (OQ3 = yes)

The new `## Orientation` rule joins the always-on AI-tell subset for **both**
tiers conventions.md §1.5 defines it on: chat-scale prose **and** `*.java` /
`*.kt` code comments (Javadoc rationale).

**Why:** The issue scopes it to "chat and every prose surface." Javadoc
rationale is prose a reader must follow without leaving it; the orientation
floor applies there too. Joining only chat would leave the code-comment tier
enumerating four while chat enumerates five — the exact inconsistency D1 is
avoiding.
**Alternatives rejected:** chat-only (leaves the Tier-B code-comment row and the
`house-style-write-reminder.sh` hook at four, inconsistent with chat).

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
**Alternatives rejected:** leave both (duplication the issue explicitly flags);
cross-link only without generalizing (still two full statements).

### [2026-06-12] [ctx=safe] D4 — New reviewer block runs for design AND tracks (OQ5 = both)

The new `### Prose AI-tell additions` block in `design-review.md` runs for both
cold-read targets — `target=design` (phase1-creation / phase4-creation /
design-sync) and `target=tracks` (the Step-4b track cold-read) — at creation and
review.

**Why:** Track prose carries the same over-dense / too-terse failure as design
prose, and the YTDB-1106 exemplar surface (terse decision-log findings) lives in
the track files. Scanning only `design.md` would leave the track-prose surface
unenforced.
**Alternatives rejected:** design-only (leaves track creation/review prose
unchecked for the same tells).

### [2026-06-12] [ctx=safe] D5 — No staging; live-edit all surfaces, no workflow-modifying marker (OQ1)

The plan does **not** declare itself workflow-modifying: the `### Constraints`
marker is omitted, the §1.7 implementer staging gate stays inactive, and every
edit (`.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`,
`.claude/output-styles/**`, `.claude/scripts/**`) lands on live paths and
self-applies during the branch.

**Why:** This change alters prose rules, prompt text, one judgment-layer
reviewer block, and one contained regex — it changes **no `_workflow/**`
artifact schema** (no track-file sections, resume-state fields, or drift-gate
format), so the destabilize-the-branch's-own-machinery hazard §1.7 guards
against does not exist. The largest surfaces (`house-style.md`,
`house-conversation.md`, `design-mechanical-checks.py`) sit outside §1.7's
covered prefixes already, so staging only the workflow/skills/agents blurbs
would be a split that buys neither isolation nor self-application.
Self-application is the goal for a style-rule change: the branch's own
`design.md`, track files, and chat are held to the new rules during the branch.
§1.7(b) documents marker-omission as a sanctioned path.
**Cost (accepted):** committing live `.claude/workflow|skills|agents` edits
trips the startup drift gate each subsequent session (the branch flags its own
authoring). It is a false positive — the commits change workflow *prose*, not
the `_workflow/**` artifact *schema*, so no migration is needed. Resolution:
**Suppress** each session; safe because the branch sits on current develop with
nothing in flight, so there is no real develop drift to mask. Re-evaluate if a
mid-branch rebase of develop ever happens.
**Alternatives rejected:** full staging (defers all self-application to
post-merge, so the rule-adding branch is the one branch never checked against
its rules; odd split given output-styles/scripts are live regardless); hybrid
stage-covered-only (neither clean isolation nor clean self-application).

## Surprises & Discoveries

- [2026-06-12] [ctx=safe] S1 — Blast radius is ~5× the issue's estimate, and
  it is hand-maintained. YTDB-1106 lists ~10 sync sites. The actual count of
  files that enumerate the four-name AI-tell subset as a closed set is ~50:
  ~19 `.claude/agents/*.md` (the line-20 "four banned-section heading slugs"
  blurb), ~10 `.claude/workflow/prompts/*.md` (same blurb), ~10 chat-scale-prose
  blurbs in skills + workflow docs (`create-plan`, `execute-tracks`,
  `review-plan`, `review-workflow-pr`, `design-decision-escalation`,
  `inline-replanning`, `mid-phase-handoff`, `review-iteration`, `review-mode`,
  `workflow.md`), 4 workflow-doc enumerations (`step-implementation`,
  `implementer-rules`, `commit-conventions`, `episode-format-reference`), the 3
  canonical sites (`house-style.md` line-20 count + self-check,
  `house-conversation.md`, `conventions.md §1.5`), the hook
  (`house-style-write-reminder.sh`), and 2 tests (`test_house_style_hook.py`
  pins the section-name list; `test_dsc_ai_tell.py`). No generator emits these
  blurbs — `workflow-reindex.py` only rebuilds TOC/stamps. So a faithful
  "subset becomes five" is ~50 hand edits, not ~10. The issue itself
  under-counted because the enumeration is duplicated ~50× — the same
  duplication this change is forced to confront.

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
