<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
<!-- MANIFEST
review_type: workflow-consistency
scope: track-level
target: track-1
iteration: 1
findings: 2
evidence_base: { certs: 0 }
cert_index: []
flags: [PATH_COLLISION_AVOIDED]
index:
  - { id: WC1, sev: should-fix, anchor: "WC1", loc: "edit-design/SKILL.md:418-422; research.md:54-75; create-plan/SKILL.md:413-414; conventions-execution.md:661-662; adversarial-review.md:103-185", cert: n/a, basis: script+judgment }
  - { id: WC2, sev: should-fix, anchor: "WC2", loc: "conventions.md:226-257", cert: n/a, basis: judgment }
-->

# Workflow consistency review — Track 1 (track-level, iteration 1)

Note: the orchestrator routed this review to `consistency-iter1.md`, but that
path already holds the **Phase-2 autonomous plan review** (reviewer-plan,
`CR`-prefixed findings, a different scope and audit trail). To avoid clobbering
that artifact, this track-level dimensional review is written to
`consistency-track-iter1.md` instead. Flagged as `PATH_COLLISION_AVOIDED` in the
manifest; the synthesizing orchestrator should confirm the intended filename.

All findings below are scoped to the Track 1 review-target delta (the 11 staged
prose changes) plus the live `.claude/scripts/**` Python; the verbatim-copied,
already-live remainder of each staged whole-file add is out of scope. mcp-steroid
is irrelevant here (no Java); every reference was resolved by grep/Read over the
staged mirror and the live tree, honoring §1.7 staged-read precedence (the staged
copy is the branch's intended new behavior).

## Findings

### WC1 [should-fix] S3-gate verdict carrier has no producer and no slot in the research-log structure

- **Axis**: cross-file rule restatement / phantom reference (a consumer reads a carrier no producer is told to write)
- **Cost**: the S3 freeze-order gate's load-bearing input is a research-log section that nothing writes and the log schema does not define; the documented S3 reachability check ("no path reaches cold-read with an open log-adversarial entry") rests on a section that will not exist at runtime
- **Files**:
  - `…/staged-workflow/.claude/skills/edit-design/SKILL.md` (lines 418-422) — consumer
  - `…/staged-workflow/.claude/workflow/research.md` (lines 49-75) — log-structure authority
  - `…/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 413-414) — gate orchestrator
  - `…/staged-workflow/.claude/workflow/conventions-execution.md` (lines 661-662) — §2.5 lifecycle clause
  - `…/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (lines 103-185) — the gate prompt
- **Issue**: The staged `edit-design` S3 gate (Step 4) reads the research log's
  `### Adversarial review of this log …` section as "the gate's durable verdict carrier" and decides
  freeze order from a heading encoding `PASS` / `NEEDS REVISION` (lines 418-422). That section is the
  **Referent**, and it resolves nowhere:
  - `research.md` §The research log (lines 56-75) is the canonical authority on the log's shape and
    enumerates exactly **five** sections — `## Initial request`, `## Decision Log`,
    `## Surprises & Discoveries`, `## Open Questions`, `## Baseline and re-validation`. None is an
    adversarial-review / gate-verdict section; no `### Adversarial review of this log` heading is
    defined anywhere in the file.
  - `create-plan` (lines 413-414) and `conventions-execution.md` §Third-scope review-file home
    (lines 661-662) call the carrier "the research log's own gate records" but never name the
    `### Adversarial review of this log` heading and never instruct anyone to write it. The gate's
    actual documented outputs (`adversarial-review.md` §Output Format, D17) are `### A<N>` findings in
    the *ephemeral* `output_path` review file under `_workflow/reviews/` plus a thin manifest —
    explicitly **not** the carrier (`edit-design` line 421: those review files "are ephemeral and not
    the carrier").
  - So the durable carrier the S3 gate depends on has no defined producer (no file tells the gate or
    `create-plan` to append a verdict heading to the log) and no home in the log's five-section
    structure. The implementer flagged this in `## Surprises & Discoveries` (Step 4) as a "split /
    under-specified heading-match contract"; verified independently and found sharper than an unpinned
    lexical match — the section the consumer reads is never written and is not part of the log schema.
- **Suggestion**: Pin the contract on the producer side. Either (a) add a research-log section to
  `research.md` §The research log (e.g. a sixth `## Adversarial gate record` section, or a fixed
  sub-heading under `## Decision Log`) carrying the per-iteration
  `### Adversarial review of this log (<ISO>) — <PASS | NEEDS REVISION[: counts]>` heading the consumer
  matches, and instruct `create-plan` Step 4 (and the D15 batch step) to append that verdict heading at
  each gate-clear / re-challenge; or (b) repoint the `edit-design` S3 read at the carrier that *is*
  written and committed — the `_workflow/reviews/research-log-adversarial-iter<N>.md` manifest verdict —
  and drop the log-section read. Whichever carrier wins, define the exact heading shape once and have the
  consumer (`edit-design`), the orchestrator (`create-plan`), the §2.5 lifecycle clause, and the
  log-structure authority (`research.md`) reference that single definition, including the "match the
  latest dated entry when looped" rule for multi-iteration gates.

### WC2 [should-fix] conventions.md §1.2 Plan-file-content template is stale against the new per-tier plan shape (D18 / D1 / per-tier matrix)

- **Axis**: threshold-and-table sync / cross-file rule restatement (a canonical template drifted from the operative templates on the same branch)
- **Cost**: the §1.2 fenced block is the documented "required sections and their order" for `implementation-plan.md`; it now contradicts the create-plan templates, the new §Per-tier artifact set matrix, and the Change-tier / Aggregator-plan glossary entries, so a planner treating §1.2 as the authority would emit a plan missing the D18 tier line and carrying a `## Design Document` link in tiers that have no design
- **File**: `…/staged-workflow/.claude/workflow/conventions.md` (lines 226-257)
- **Issue**: The §1.2 *Plan file content* schematic (a fenced `markdown` block titled "Required sections
  of the top-level plan file and their order") still shows the develop-baseline plan shape, which three
  same-branch additions now contradict (the **Referents**):
  1. **No `**Change tier:**` line.** D18, the new Change-tier (line 82) and Aggregator-plan (line 85)
     glossary entries, the create-plan full-aggregator template (delta lines 596-606), and the `minimal`
     stub template all require a `**Change tier:** <…>` line directly under `## High-level plan` in
     **every** tier. The §1.2 template omits it.
  2. **Unconditional `## Design Document` link** (lines 229-230). The new §Per-tier artifact set matrix
     (lines 198-222) and the create-plan full-aggregator template (delta line 614, "omit the two lines
     above in `lite`") establish that `design.md` exists in `full` only, so these lines must be omitted
     in `lite`/`minimal`.
  3. **Unconditional `## Final Artifacts` line** (line 256) showing `design-final.md, adr.md`. The
     §Per-tier matrix and the create-plan template (delta line 626) make this tier-keyed: `adr.md` only
     in `lite`, a PR-description summary in `minimal`.
  This is the item the implementer flagged in `## Surprises & Discoveries` (Step 2); verified
  independently. It is a genuine cross-file inconsistency, not a glossary aside — §1.2 is the canonical
  plan-shape template a structural review checks a plan against.
- **Suggestion**: Bring the §1.2 fenced template into line with the create-plan operative templates: add
  the `**Change tier:** <full | lite | minimal> — matched categories: <…>` line under `## High-level
  plan`; mark the `## Design Document` block `full`-only (an HTML comment mirroring the create-plan
  template's "omit in lite/minimal — no design.md"); and make the `## Final Artifacts` line tier-keyed,
  or add a one-line pointer from the §1.2 template prose to §Per-tier artifact set so the two stay in
  sync.

## Evidence base

<!-- This dimension is evidence-trail-exempt under the closed-set reason
"(a) no refutation or certificate phase to persist": the agent runs no
Phase-4-style refutation or certificate phase whose reasoning could be
externalized, so no #### C<n> entries are written and evidence_base.certs = 0. -->
