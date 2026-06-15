# Research Log — hidden-research-log (YTDB-1124)

## Initial request

Implement **YTDB-1124** — "Workflow: keep the research log opaque to the
user during Phase 0 (agent-internal artifact, not a conversation surface)".

Verbatim issue content (https://youtrack.jetbrains.com/issue/YTDB-1124,
Type: Feature, Priority: Normal):

> **Summary.** Codify that the research log is an agent-internal artifact
> during Phase 0. The user follows a normal research conversation; the
> agent maintains `research-log.md` silently as a byproduct and never
> surfaces its structure into the conversation: no D-numbers, section
> names, verbatim entries, or "I've logged this" narration. Findings reach
> the user as plain conversational prose, not as references or quotes from
> the log.
>
> **Problem.** `research.md` tells the agent to "append decisions,
> surprises, and open questions to the research log as they settle"
> (`research.md:138`, `:183`) but never says the log itself stays out of
> the conversation. Two current phrasings invite leakage:
> - The "Record decisions" rule (`research.md:183`) says "acknowledge it
>   clearly and append it to the log's `## Decision Log` with its `**Why:**`
>   and `**Alternatives rejected:**` fields." An agent reads this as license
>   to narrate the write and echo the log's field structure back at the user.
> - The Transition step (`research.md:151`) says the agent "confirms the
>   research log captures the key findings," which can turn into asking the
>   user to review or reference the log.
>
> The result is a research conversation peppered with "recorded as Decision
> D3", "per the research log", and quoted log fields. That is the agent's
> bookkeeping bleeding into the user's view. The log is the agent's durable
> memory across a `/clear`; it is not a document the user reads, quotes, or
> navigates during research.
>
> **Proposal.** Add a rule to `research.md` §Rules (and reflect it in the
> `create-plan` Phase 0 narration) stating the log is opaque to the user
> during Phase 0:
> - Maintain `research-log.md` silently. Do not narrate writes ("I've
>   logged this as D3"), name its sections, cite D-numbers, or quote its
>   entries to the user.
> - Surface findings, trade-offs, and decisions as plain conversational
>   prose. Acknowledging a decision the user makes stays (it is good
>   conversation), but in natural terms, not as a log reference.
> - The one place a structured recap reaches the user is the Phase-1
>   transition (`research.md:146`): even there it is a plain-language
>   summary of findings, not log quotes.
>
> Refine the "Record decisions" rule so "acknowledge it clearly" no longer
> reads as "echo the log structure". The acknowledgment is conversational;
> the logging is silent.
>
> **Scope.**
> - Edit `research.md` §Rules, §How it works, §Transition to Phase 1.
> - Check `create-plan` SKILL.md Phase 0 narration for the same leakage.
> - Read-scope discipline S2 (`research.md:112`) already governs where the
>   log is read for decision content; this adds the user-facing-opacity
>   rule it is missing.
> - Workflow-machinery change, so `conventions.md` §1.7 staging applies.

## Decision Log

<!-- append-only; one entry per settled decision -->

### D1: Use the §1.7(k) prose-rule self-application opt-out, not §1.7 staging
- 2026-06-15T03:49Z [ctx=safe]
- **Decision:** Edit `.claude/workflow/research.md` and
  `.claude/skills/create-plan/SKILL.md` **live**. Carry the §1.7(k) opt-out
  marker in the plan's `### Constraints` (the stable prefix `This plan uses
  the §1.7 prose-rule self-application opt-out:`), NOT the §1.7(b)
  workflow-modifying marker — the two are mutually exclusive. §1.7(l)
  re-points the Phase-3A review criteria onto the prose lenses.
- **Why:** The change moves no `_workflow/**` artifact schema (criterion 1
  clean), and editing live means this very session and the next
  `/create-plan` are held to the new opacity rule — self-application, which
  is the whole point of authoring a research-conversation rule on a branch
  that can exercise it. The issue Scope's "§1.7 staging applies" predates
  §1.7(k); the opt-out is the better-fitting mechanism for a prose-only
  change.
- **Alternatives rejected:** §1.7 staging (edit staged copies, promote at
  Phase 4). Conservative and what the issue anticipated, but it forfeits
  self-application — leaving the one branch that rewrites the rule as the
  single branch never checked against it — and stands up a staged subtree
  for a pure-prose change that gains nothing from isolation (no schema a
  running phase parses changes).
- **Resolves Open Question:** the `create-plan` SKILL.md classification —
  its Phase-0 narration edit is treated as **judgment-layer** (the edit is
  a conversational-narration reword plus a cross-reference, not a mechanical
  gate sequence), so it qualifies under §1.7(k) criterion (2) and does not
  force the plan to stage.
- **Consequences to honor:** (a) §1.7(k) mandatory stamp-advance — run
  `/migrate-workflow` after the branch's last live commit touching
  `.claude/{workflow,skills,agents}`, Suppress the drift gate on
  intervening sessions; (b) this is the first branch to exercise §1.7(l)
  end-to-end (its own note flags the bootstrap caveat).
- **Implemented in:** (track refs added during planning)

### D2: Tier = minimal; opt-out marker carried in a one-line ### Constraints added to the stub
- 2026-06-15T03:49Z [ctx=safe]
- **Decision:** User-confirmed tier **`minimal`** (Gate 1 no — no central
  HIGH-risk category; Gate 2 single — two small prose files, one logical
  change), matched categories **none** (the Phase-0→1 adversarial gate runs
  lens-free). The §1.7(k) opt-out marker is carried in a **one-line
  `### Constraints`** section added to the `minimal` stub plan, since the
  stub template enumerates no `### Constraints` natively.
- **Why:** Single-track prose change; minimal is the faithful tier. The
  marker must be readable by §1.7(l) (which reads the plan's
  `### Constraints`) to re-point the Phase-A reviews onto the prose lenses;
  adding one line to the stub is justified by the stub's own "carries
  exactly what the machinery reads" principle.
- **Alternatives rejected:** Bump to `lite` for a native `### Constraints`
  — rejected: `lite` implies multi-track and misrepresents a single-track
  change, and writes a fuller plan than this edit warrants.
- **Follow-up filed:** YTDB-1125 (Bug, dev-workflow, relates to YTDB-1124)
  — codify a native optional `### Constraints` marker home in the `minimal`
  stub template so future branches do not hand-roll it.
- **Implemented in:** (track refs added during planning)

### D3: SUPERSEDES D1 — use §1.7 staging, not the §1.7(k) opt-out (resolves gate blocker A1)
- 2026-06-15T03:49Z [ctx=safe]
- **Decision:** Use **§1.7 staging** with the §1.7(b) workflow-modifying
  marker. Edit BOTH `.claude/workflow/research.md` and
  `.claude/skills/create-plan/SKILL.md` under the staged subtree at
  `docs/adr/hidden-research-log/_workflow/staged-workflow/.claude/...`
  (copy-then-edit on first touch per §1.7(e)); live workflow stays at
  develop for the branch (I6, §1.7(g)); a single Phase-4 promotion commit
  copies staged → live after the §1.7(f) rebase-precedes-promotion. The
  marker sentence (`This plan is workflow-modifying: it edits
  .claude/workflow/**, .claude/skills/**, or .claude/agents/**.`) goes in a
  one-line `### Constraints` added to the minimal stub.
- **Why:** The Phase-0→1 adversarial gate (iter1) raised blocker A1: D1's
  opt-out classified `create-plan/SKILL.md` as judgment-layer via an
  edit-level reading, but §1.7(k) criterion (2) is file-level
  consumer-class ("consumer class, not author intent",
  `conventions.md:1231,1243`). `create-plan/SKILL.md` is the `/create-plan`
  orchestration procedure → execution-procedure file → must stage. A2: the
  two §1.7 markers are mutually exclusive per plan, so editing any
  execution-procedure file makes the opt-out unavailable, not just weaker.
  Verified against rule text — the gate is correct. User confirmed staging.
- **Alternatives rejected:** (a) §1.7(k) opt-out, research.md-only (drop the
  create-plan edit) — viable and keeps dogfooding, but narrows the issue
  below its "reflect it in the create-plan Phase 0 narration" scope;
  rejected in favor of honoring full scope. (b) Edit-level opt-out keeping
  both files — rule-prohibited (A1/A2).
- **Resolves Open Questions** (A3): staging-vs-opt-out → staging;
  `create-plan/SKILL.md` classification → execution-procedure, stages.
- **Cost accepted:** live `research.md` stays at develop for the branch, so
  this session is NOT held to the new opacity rule (no self-application) —
  the correct trade per §1.7's reasoning, since `create-plan/SKILL.md` is
  running machinery.
- **YTDB-1125 broadened:** the minimal-stub `### Constraints` gap (A4)
  applies to the §1.7(b) marker too, not only the §1.7(k) opt-out marker —
  both §1.7 markers live in `### Constraints` (`conventions.md:913-919`),
  which the minimal stub omits. Issue updated to cover both markers.
- **Implemented in:** (track refs added during planning)

## Surprises & Discoveries

<!-- append-only; codebase realities and assumptions surfaced during exploration -->

- 2026-06-15T03:49Z [ctx=safe] Scope confirmed by grep over
  `.claude/{workflow,skills,agents}`: the only Phase-0 surfaces that
  narrate the log toward the user are the two the issue names —
  `research.md` (§Transition "confirms the research log captures the key
  findings", `:151`; §Rules "Record decisions" "acknowledge it clearly and
  append it to the log's `## Decision Log` with its `**Why:**`...", `:183`)
  and `create-plan` SKILL.md Phase-0 narration. No hidden third surface. The
  other grep hits (`implementation-review.md`, `track-*review.md`,
  `design-review.md:261`, `migrate`/`review-workflow-pr` SKILLs) are
  review-finding bookkeeping or the design-review "load-bearing" definition,
  not research-conversation narration.

- 2026-06-15T03:49Z [ctx=safe] `create-plan` SKILL.md Phase-0 narration
  (Step 2 `:237`, Step 3 `:270-274`) is written as instruction *to the
  agent* about what to write *in* the log (the `## Initial request` seed,
  the append cadence, the `**Why:**`/`**Alternatives rejected:**` field
  shape) — it does not itself tell the agent to narrate the log *to the
  user*. So the leak risk there is lower than `research.md:183`; the
  SKILL.md edit is likely a short opacity cross-reference plus a reword of
  the Step-3 append bullet, not a structural rewrite.

- 2026-06-15T03:49Z [ctx=safe] The opacity rule must scope to the Phase-0
  research conversation only, and must explicitly preserve two existing
  user-facing recaps that are NOT leaks: (1) the Phase-1 transition
  plain-language findings summary (`research.md:146`, already carved out by
  the issue), and (2) the Step-4 adversarial-gate verdict recital and the
  tier proposal in `create-plan` SKILL.md, which surface findings/blockers,
  not log structure.

## Open Questions

<!-- append-only; unresolved questions carried toward planning -->

- 2026-06-15T03:49Z [ctx=safe] **Staging (§1.7) vs prose-rule opt-out
  (§1.7(k))?** The issue Scope says "§1.7 staging applies", but §1.7(k) is
  the newer opt-out for judgment-layer-prose-only branches — it edits
  workflow prose live (no staged subtree) so the branch is held to its own
  changed rules, and re-points the Phase-3A review criteria onto the prose
  lenses via §1.7(l). The opt-out is the conceptually better fit here (the
  branch rewrites the research-conversation rule and could dogfood it) but
  hinges on a classification sub-question (below). UNRESOLVED — user's call.

- 2026-06-15T03:49Z [ctx=safe] **Does `create-plan` SKILL.md count as an
  "execution-procedure file" under §1.7(k) criterion (2)?** The opt-out's
  two markers are mutually exclusive on one plan, and any edited file that
  fails criterion (2) forces the whole plan to stage. `create-plan`
  SKILL.md is read by the agent as the /create-plan orchestration procedure
  (file-level consumer-class test → execution procedure → must stage,
  killing the opt-out). But the *specific edit* is to Phase-0 narration
  prose, not a mechanical gate sequence (edit-level view → judgment-layer →
  opt-out allowed). This classification decides whether the opt-out is even
  available. UNRESOLVED — depends on the staging decision above.

- 2026-06-15T03:49Z [ctx=safe] If opt-out is chosen: §1.7(k) mandates a
  stamp-advance via `/migrate-workflow` after the branch's last live
  commit touching `.claude/{workflow,skills,agents}`, with Suppress on
  intervening drift-gate fires. §1.7(l) end-to-end is also untested — this
  would be the first branch to exercise it (the section's own note flags
  that). Process cost to weigh against staging.

## Baseline and re-validation

Workflow-modifying branch (edits `.claude/workflow/research.md` and
`.claude/skills/create-plan/SKILL.md`), so §1.7 staging applies and this
section anchors rebase-drift detection.

- Fork point from `develop`: `cbfcf7451782c89c106906d98575a4431aca594c`
  (branch HEAD == merge-base at session start, no branch commits yet).
- Baseline files to re-validate against on rebase: `.claude/workflow/research.md`,
  `.claude/skills/create-plan/SKILL.md` (and any other Phase-0-narration
  surface the audit turns up).

## Adversarial gate record

<!-- Phase 0→1 adversarial gate verdict headings; one per gate iteration -->

### Adversarial review of this log (2026-06-15T03:49Z) — NEEDS REVISION: 1 blocker, 3 should-fix
- Review file: `_workflow/reviews/research-log-adversarial-iter1.md`
- **A1 (blocker):** D1's opt-out classification of `create-plan/SKILL.md`
  as judgment-layer uses an edit-level / author-intent reading; §1.7(k)
  criterion (2) is file-level consumer-class ("consumer class, not author
  intent", `conventions.md:1231,1243`). `create-plan/SKILL.md` is read as
  the `/create-plan` orchestration procedure → execution-procedure file →
  must stage. Verified against rule text: correct.
- **A2 (should-fix):** The two §1.7 markers are mutually exclusive per plan
  (`conventions.md:1265-1269`), so a plan editing any execution-procedure
  file has no valid opt-out config — the opt-out is unavailable, not just
  weaker.
- **A3 (should-fix):** Two load-bearing Open Questions were recorded
  UNRESOLVED then "resolved" by self-assertion in D1; the gate cannot clear
  on a contested self-resolution.
- **A4 (should-fix):** D2 grows a `### Constraints` the minimal stub does
  not enumerate — sound and §1.7(l)-readable, but an undocumented hand-roll
  until YTDB-1125 lands; record the deviation explicitly.
- **Survivors (YES):** "first to exercise §1.7(l) end-to-end" holds; scope
  claim holds (no hidden third Phase-0 narration surface); §1.7(l) reads
  the marker from `### Constraints` in all three Phase-3A prompts.
- **Disposition:** D1 sent back to research for re-decision (blocker loop).

### Adversarial review of this log (2026-06-15T03:50Z) — PASS
- Review file: `_workflow/reviews/research-log-adversarial-iter2.md`
  (verdict-producer variant; findings: 0).
- **A1 → VERIFIED:** D3 supersedes D1, adopts the file-level
  consumer-class reading, classifies `create-plan/SKILL.md` as
  execution-procedure, switches to §1.7(b) staging.
- **A2 → VERIFIED:** D3 names the marker mutual-exclusion corollary; opt-out
  unavailable, moot under staging.
- **A3 → VERIFIED:** both load-bearing Open Questions resolved into the
  Decision Log with rule-grounded reasoning + user confirmation.
- **A4 → VERIFIED:** the minimal-stub `### Constraints` deviation recorded
  explicitly; YTDB-1125 broadened to both markers.
- **New-blocker hunt on D3 (staging for a minimal single-track change):**
  none. Staged paths match §1.7(a); Phase-4 minimal+workflow-modifying
  commit shape reconciled; `$WORKFLOW_SHA` resolves to the develop
  fork-point (I6 holds); scope claim intact.
- **Disposition:** gate CLEARED. Proceed to Step 4b plan derivation
  (minimal tier, no design).
