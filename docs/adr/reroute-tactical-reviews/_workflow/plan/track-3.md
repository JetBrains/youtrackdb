<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Track 3: Dimensional reviewers emit file+manifest with IDs and an evidence trail

## Purpose / Big Picture
After this track, each of the 16 dimensional `review-*` agents writes a
file-plus-manifest (the `§2.5` schema) when handed an output path and returns
its current inline format unchanged otherwise. Each numbers its findings with
the dimension's canonical `<PREFIX>` from `review-iteration.md § Finding ID
prefixes` (only the integer `<n>` is per-fan-out). Where it runs a Phase-4
refutation phase it writes that reasoning to `## Evidence base`, and otherwise
carries an evidence-trail-exempt annotation with a closed-set reason. The 4
pure-standalone review agents carry the `exempt because…` annotation (D9).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track applies the `§2.5` contract to the tactical producers. The intent is
uniform (D6 path-conditional output, D5 reviewer-side ID continuation, D8
evidence trail), but the work splits into two edit templates because the two
agent families start from different inline formats. The 5 code and 5 test
dimensions emit un-numbered findings under `#### Critical`-style buckets; the 6
workflow dimensions already self-assign `<PREFIX><N>` IDs as bold bullets and
already cite the prefix table. The 4 standalone agents get a one-line exemption
(D9).

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-08T05:23Z [ctx=info] Review + decomposition complete (3 steps: 2 high, 1 medium; 0 failed)
- [x] 2026-06-08T05:54Z [ctx=safe] Step 1 complete (commit b0b9c93398e087a9955df62458b810910a40fbde)
- [x] 2026-06-08T06:09Z [ctx=info] Step 2 complete (commit 9c8e8e01641fd280a2b8e878a376753ddf613b41)
- [x] 2026-06-08T06:22Z [ctx=info] Step 3 complete (commit 87c0261f0a8e0c2492ba118cd7d39f8a377443b4)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

- 2026-06-08T06:09Z Step 2 discovered (ORCH-1): the canary's six-field manifest `cert`
  cross-link to a `#### C<n>` entry contradicts the evidence-trail-exempt clause's empty
  `## Evidence base` / `certs: 0`. Reconciled to "`cert` is `n/a`" in the two exempt code/test
  dimensions (CQ, TS). Step 3's four evidence-trail-exempt workflow dimensions (`consistency`,
  `context-budget`, `hook-safety`, `prompt-design`) must use the same "`cert` is `n/a`"
  phrasing, not the cross-link. See Episodes §Step 2.
- 2026-06-08T06:22Z Step 3 completed the agent-emit half of the feature: all 16 dimensional
  reviewers now write the §2.5 file+manifest with `### <PREFIX><n>` anchors on the path branch
  (inline format unchanged off-path). Track 4 routes on this fixed evidence-base map — cert-writing
  (10): bugs-concurrency, crash-safety, performance, security, test-behavior, test-completeness,
  test-concurrency, test-crash-safety, instruction-completeness, writing-style; evidence-trail-exempt
  with `certs: 0` (6): code-quality, test-structure, consistency, context-budget, hook-safety,
  prompt-design. See Episodes §Step 3.

## Decision Log
<!-- Continuous-log. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**DL1 — Phase A review disposition (Complex track: Technical + Risk +
Adversarial, iteration 1, 0 blockers).** The three reviews converged on the same
plan corrections; all were accepted and applied to the track file before
decomposition:
- *Two edit templates, not one uniform edit* (T2/R4/A1): the 6 workflow
  dimensions already self-assign IDs inline, so the "uniform across 16" framing
  and the "un-numbered `#### Critical` buckets" current-state claim were wrong.
  Corrected `## Purpose` and `## Context and Orientation`; decomposition carves
  the two families into separate steps.
- *`{findings_under_recheck}` is gate-check-only* (T4/R2/A2): "applied at the
  initial review too" described a mechanism that does not exist. Step 2 now
  starts the initial review at `<PREFIX>1`; any initial-review seed is Track 4
  dispatch scope.
- *Evidence-trail exemption bounded and relabeled* (T1/R3/A4): distinct from the
  `§2.5` S5 coverage exemption, with a closed-set reason and a non-exempt
  default; the 6 zero-refutation dimensions are exempt on structural grounds.
- *D5 prefix is table-bound* (A3): canonical `<PREFIX>` from `review-iteration.md`;
  the 10 code and test agents gain the citation.
- *Byte-for-byte means wrap, not rewrite* (R1) and *additive-only promotion*
  (R6): captured in Plan of Work step 1 and `## Idempotence and Recovery`.
- *Acceptance holes plugged* (A4): S4 round-trip on one agent per family
  including a workflow dimension, plus an evidence-trail coverage assertion.

**DL2 — `§2.5` does not define the `## Evidence base` body rendering** (T3/A5,
deferred). `§2.5` defines the `## Evidence base` anchor shape (`#### ` entries)
but not the survived-one-line / refuted-in-full roster rendering, which lives
only in design.md prose and the YTDB-1069 reference. Track 3 states the rendering
inline in each agent edit (step 3) rather than citing it. Whether the rendering
belongs in `§2.5` is a Phase 4 design-final reconciliation candidate, recorded
here and not changed in this track, since the schema is Track 2's staged
deliverable and out of Track 3 scope.

## Outcomes & Retrospective
<!-- Continuous-log. -->

- [x] Technical: PASS at iteration 2 (4 findings — 0 blocker, 2 should-fix, 2 suggestion; all accepted, applied as track-file corrections before decomposition)
- [x] Risk: PASS at iteration 2 (6 findings — 0 blocker, 4 should-fix, 2 suggestion; all accepted; R5 canary + R6 additive-only fold into decomposition and Idempotence)
- [x] Adversarial: PASS at iteration 2 (5 findings — 0 blocker, 4 should-fix, 1 suggestion; all accepted; A5 §2.5 evidence-body gap stated inline + recorded as DL2)

## Context and Orientation

The 16 dimensional agents under `.claude/agents/` are the tactical reviewers the
workflow fan-out spawns:

- Code dimensions (5): `review-bugs-concurrency`, `review-code-quality`,
  `review-crash-safety`, `review-performance`, `review-security`.
- Test dimensions (5): `review-test-behavior`, `review-test-completeness`,
  `review-test-concurrency`, `review-test-crash-safety`, `review-test-structure`.
- Workflow dimensions (6): `review-workflow-consistency`,
  `review-workflow-context-budget`, `review-workflow-hook-safety`,
  `review-workflow-instruction-completeness`, `review-workflow-prompt-design`,
  `review-workflow-writing-style`.

The 4 pure-standalone agents (`code-reviewer`, `pr-reviewer`,
`test-quality-reviewer`, `dr-audit`) are never in the workflow fan-out (D9).

The 16 dimensional agents start from two inline formats, not one. The 5 code and
5 test dimensions emit findings as un-numbered bullets under `#### Critical` /
`#### Likely Issues` / `#### Potential Concerns`-style buckets, and the
orchestrator mints merged `M<n>` IDs at synthesis. The 6 workflow dimensions
already self-assign their `<PREFIX><N>` IDs inline (e.g. `**WC<N>**` bold
bullets) and already cite `review-iteration.md § Finding ID prefixes`. The
`§2.5` file-output shape (a MANIFEST block, `## Findings` with `### <PREFIX><N>`
anchors, and `## Evidence base`) matches neither today, so the file branch is a
new output shape for all 16 while the inline branch stays each agent's current
format. This track moves merged-`M<n>` minting off the orchestrator to
per-dimension reviewer-side numbering and gates the file-plus-manifest output on
a supplied path.

The agents do not run a uniform structured protocol. About ten of the 16 carry a
premise / code-path-trace / Phase-4-refutation structure whose reasoning the D8
trail makes verifiable. The rest (`review-code-quality`, `review-test-structure`,
and the workflow dimensions `review-workflow-consistency`,
`review-workflow-context-budget`, `review-workflow-hook-safety`,
`review-workflow-prompt-design`) carry no refutation or certificate phase, so for
them the evidence trail has no source material and the evidence-trail exemption
applies on structural grounds rather than cost.

**Why path-conditional (D6):** the same `review-*` agents serve the standalone
`/code-review` and `/fix-ci-failure` skills, which read findings inline.
Unconditional file output would break them. The output path is a per-spawn
variable injected by the workflow at the dispatch sites (Track 4); the manifest
schema instruction lives in the agent definition, gated by the path's presence.
The no-path branch stays byte-for-byte each agent's current inline format (the
wrap-not-rewrite rule in Plan of Work step 1), so the file-branch ID and
manifest changes never leak into the standalone callers.

This track edits `.claude/agents/**`, so it stages only after Track 1's
three-prefix rule is in the staged mirror and the implementer reads it via
§1.7(d) reads-precedence.

## Plan of Work

1. **Path-conditional output (D6), per dimensional agent — wrap, never
   rewrite.** Add the gated instruction as a guard *around* the existing Output
   Format: if an output path is supplied, write the file-plus-manifest (the
   `§2.5` schema) and return only the thin manifest; otherwise the agent's
   current inline Output Format below is unchanged. Phrasing the edit as a
   leading guard that leaves the inline section verbatim is what keeps the
   no-path branch byte-for-byte today's format (R1); editing the single existing
   format block in place is the leak path. Cite `§2.5` for the file shape rather
   than restating it. In the file branch the agent follows `§2.5` heading
   discipline (MANIFEST block, `## Findings`, `### <PREFIX><N>` anchors,
   `## Evidence base`) and emits no `### Summary` or `### Findings` heading (the
   `### <PREFIX><N>` shape is reserved file-wide); the inline branch keeps
   whatever headings it has today.
2. **Reviewer-side ID numbering (D5), canonical prefix.** Each dimensional agent
   numbers findings with its canonical `<PREFIX>` from `review-iteration.md
   § Finding ID prefixes` (only the integer `<n>` is per-fan-out; the prefix is
   fixed, not chosen) and writes one `### <PREFIX><n> ` anchored body per finding
   in the file branch. The 6 workflow dimensions already cite that table; the 10
   code and test agents gain the same citation. Numbering is two-sided: at the
   initial review the agent starts at `<PREFIX>1`; at gate-check it reuses and
   continues from the `{findings_under_recheck}` IDs the orchestrator hands back
   (the existing gate-check hand-back). There is no initial-review
   high-water-mark hand-back today, and adding one is Track 4 dispatch-site
   scope, so the Track 3 agent edit only accepts a seed when present and starts
   at 1 when absent. Reserve the `### <ID> ` namespace for finding anchors;
   reasoning prose lives in `## Evidence base` or the finding body.
3. **Evidence trail (D8), only where a refutation phase exists.** Each
   dimensional agent that runs a Phase-4 refutation or certificate phase writes
   that reasoning to `## Evidence base` with this rendering: a survived claim
   compresses to one line, a refuted or non-passing claim appears in full (the
   YTDB-1069 roster rendering, stated here inline as the authoritative spec since
   `§2.5` defines the `## Evidence base` anchor shape but not this body
   rendering). A dimension with no refutation or certificate phase
   (`review-code-quality`, `review-test-structure`, `review-workflow-consistency`,
   `review-workflow-context-budget`, `review-workflow-hook-safety`,
   `review-workflow-prompt-design`) carries an evidence-trail-exempt annotation
   instead, with the reason drawn from a closed set of two: (a) no refutation or
   certificate phase to persist, or (b) the ~1.4K-token externalization cost does
   not pay for a dimension whose findings are inherently single-claim. This label
   is distinct from the `§2.5` S5 coverage `exempt because…` (which exempts a
   whole agent class from writing file+manifest at all): an evidence-trail-exempt
   agent still writes the MANIFEST and `## Findings`, writes an empty
   `## Evidence base` (manifest `evidence_base` with `certs: 0`), and is
   unaffected by the S4/S6 count grep, which counts `## Findings` anchors only.
4. **Standalone exemptions (D9).** Add the `exempt because: invoked standalone,
   output consumed by the user in the same turn, not accumulated in an
   orchestrator session` annotation to `code-reviewer`, `pr-reviewer`,
   `test-quality-reviewer`, `dr-audit`.

Invariants to preserve: S2 — the per-reviewer `id` prefix is the dimension's
canonical table-bound prefix, load-bearing twice (bucketing dimension proxy and
Review-mode override match), and is never renumbered. The no-path branch must
stay byte-for-byte today's inline format for that agent so the standalone
`/code-review` and `/fix-ci-failure` callers are untouched.

## Concrete Steps

1. **Canary: `review-bugs-concurrency.md` end-to-end (template A).** Wrap the
   path-gated `§2.5` file+manifest output as a leading guard around the existing
   inline Output Format (no-path branch left verbatim); number findings with the
   canonical `BC` prefix from `review-iteration.md § Finding ID prefixes`, adding
   the table citation; write the Phase-4 "Alternative Hypothesis Check" reasoning
   to `## Evidence base`. Validates the template-A wrap/manifest/evidence shape on
   one representative agent before steps 2-3 replicate it. — risk: high (override:
   canary — high blast radius if the wrap-gate or manifest shape is wrong, R1/R5;
   the step-level dimensional review validates the shape before replication)  [x] commit: b0b9c93398e087a9955df62458b810910a40fbde
2. **Template A across the remaining 9 code and test dimensional agents + the 4
   D9 standalone exemptions.** Apply the step-1 pattern (wrap-gate + canonical
   `<PREFIX>` numbering + `review-iteration.md § Finding ID prefixes` citation) to
   `review-code-quality`, `review-crash-safety`, `review-performance`,
   `review-security`, `review-test-behavior`, `review-test-completeness`,
   `review-test-concurrency`, `review-test-crash-safety`, `review-test-structure`;
   write `## Evidence base` where a refutation phase exists and an
   evidence-trail-exempt annotation for `review-code-quality` and
   `review-test-structure` (no refutation phase). In the same commit, add the D9
   `exempt because: invoked standalone…` annotation to `code-reviewer`,
   `pr-reviewer`, `test-quality-reviewer`, `dr-audit`. — risk: medium (workflow
   machinery — single-review-agent-spec behavioral change ×9, plus 4 prose-only
   D9 annotations; merged tag = medium)  [x] commit: 9c8e8e01641fd280a2b8e878a376753ddf613b41
3. **Template B: migrate the 6 workflow dimensional agents.**
   `review-workflow-consistency`, `review-workflow-context-budget`,
   `review-workflow-hook-safety`, `review-workflow-instruction-completeness`,
   `review-workflow-prompt-design`, `review-workflow-writing-style`: wrap the
   path-gated `§2.5` file output, and in the file branch migrate each agent's
   existing inline `**<PREFIX><N>**` bold bullets to `### <PREFIX><N>` anchors
   under a single `## Findings` (no `### Summary` / `### Findings` heading),
   preserving the documented numbering and the inline no-path branch verbatim;
   write `## Evidence base` for the two with a refutation phase
   (`instruction-completeness`, `writing-style`) and an evidence-trail-exempt
   annotation for the four without (`consistency`, `context-budget`,
   `hook-safety`, `prompt-design`). — risk: high (override: focal-risk migration
   distinct from template A — the bold-bullet → `### <PREFIX><n>` transform with
   byte-for-byte + S4-round-trip fragility flagged by A1/A4/R4; its own step-level
   dimensional review validates template B across all 6 at once)  [x] commit: 87c0261f0a8e0c2492ba118cd7d39f8a377443b4

Sequential: step 1 (canary) validates the shared wrap/manifest/evidence primitive
before step 2 replicates template A and step 3 applies template B. Steps 2 and 3
touch disjoint agent files and could run in either order after step 1; kept
sequential so the template-A lessons inform the template-B migration. Step counts:
1 high (canary, 1 file), 1 medium (step 2, 13 files), 1 high (step 3, 6 files);
20 files total, matching the ~20-file scope.

## Episodes
<!-- Continuous-log. -->

### Step 1 — commit b0b9c93398, 2026-06-08T05:54Z [ctx=safe]
**What was done:** Inserted a path-conditional `## Output routing` guard immediately
before the `## Output Format` heading of the staged `review-bugs-concurrency.md`
(copy-then-edit on first touch per §1.7(e): the live develop-state file was copied to
the staged mirror verbatim, then edited; the live tree stays untouched). With an
output path the guard directs the reviewer to write the §2.5 file-plus-manifest — all
six manifest `index` fields (`id`/`sev`/`anchor` mandatory, `loc`/`cert`/`basis`
downstream-consumed), each per-finding `cert` cross-linked to a `#### C<n>` evidence
entry, `## Findings` carrying one `### BC<n> [sev]` anchored body per finding, no
`### Summary` or `### Findings` heading, and only the manifest returned. Findings
number with the canonical `BC` prefix (two-sided, dormant until a dispatch site
supplies a hand-back). The Phase-4 "Alternative Hypothesis Check" reasoning writes to
`## Evidence base` via the YTDB-1069 roster rendering (survived claim → one line,
refuted → in full). With no path the inline Output Format below stays byte-for-byte
unchanged. The risk:high step-level review (`review-workflow-prompt-design`) returned
WP1 (should-fix: manifest-field population) and WP2 (suggestion: gate-check hand-back
dormancy); both applied in a `Review fix:` commit, gate-check PASS at iteration 2.

**What was discovered:** This canary is the complete template-A wrap that steps 2 and
3 replicate. Replication carries the six-field manifest bullet, the
dormant-until-supplied two-sided-numbering phrasing, and the
`prompts/dimensional-review-gate-check.md:reviewer-dim-step,reviewer-dim-track:3B,3C`
citation verbatim (its suffix applies to every dimensional reviewer, so it copies
unchanged), swapping `BC` for each dimension's canonical prefix from
`review-iteration.md § Finding ID prefixes`. The WP2 finding's proposed path
`dimensional-review-gate-check.md` did not resolve; the real path is the
`prompts/`-prefixed one (roles `reviewer-dim-step,reviewer-dim-track`, no
`orchestrator`), verified before citing. The live `implementer-rules.md` pre-commit
gate still greps two prefixes (`.claude/workflow/`, `.claude/skills/`); the
`.claude/agents/` third-prefix gate is staged from Track 1 and self-applied here via
§1.7(d) reads-precedence — the intended I6 state, made live by the Phase 4 promotion.

**Key files:**
- `…/staged-workflow/.claude/agents/review-bugs-concurrency.md` (new — staged)

### Step 2 — commit 9c8e8e0164, 2026-06-08T06:09Z [ctx=info]
**What was done:** Replicated the canary template-A wrap across the nine remaining
code and test dimensional agents and added the D9 standalone-exemption annotation to
the four pure-standalone agents, all in one commit through the staged mirror
(copy-then-edit on first touch per §1.7(e); live tree untouched). Each of the nine
gained a leading `## Output routing` guard before its inline Output Format, copied
from the canary with only the canonical `<PREFIX>` and the refutation/certificate
phase name substituted. The seven with a refutation/certificate phase write the trail
to `## Evidence base` (CS=Phase-5 Alternative Hypothesis Check, PF=Phase-4 Scale
Validation, SE=Phase-4 Reachability Check, TB=Phase-3 falsifiability analysis,
TC=Phase-4 alternative-hypothesis check, TX=Phase-3 test-race analysis, TY=Phase-3
recovery-path verification). The two without one — `review-code-quality` (CQ) and
`review-test-structure` (TS) — carry the evidence-trail-exempt clause with closed-set
reason "(a) no refutation or certificate phase to persist", write an empty
`## Evidence base`, and set the manifest `evidence_base` to `certs: 0`. The four
standalone agents (`code-reviewer`, `pr-reviewer`, `test-quality-reviewer`,
`dr-audit`) got the verbatim one-line `exempt because…` annotation only — no guard.
Medium step, so no step-level dimensional review fired; orchestrator verification of
the bulk edit found one should-fix (ORCH-1), reconciled in a `Review fix:` commit.

**What was discovered:** ORCH-1 — the canary's six-field manifest bullet cross-links
each finding's `cert` to a `#### C<n>` evidence-base entry, which contradicts the
evidence-trail-exempt clause's empty `## Evidence base` / `certs: 0`. For an exempt
dimension there are no `#### C<n>` entries, so `cert` has nothing to point to. Fixed
in CQ and TS to "the per-finding `cert` is `n/a`" for the exempt case; the seven
evidence-base-writing agents keep the cross-link (correct for them). Carry-forward to
step 3: its four evidence-trail-exempt workflow dimensions (`consistency`,
`context-budget`, `hook-safety`, `prompt-design`) must use the reconciled "cert is
n/a" phrasing, not the canary's cross-link, or the contradiction reproduces. Also:
the test dimensions use a lowercase `## Output format` heading; the guard's "use the
Output format below" pointer was matched to each agent's actual heading casing.

**Key files:**
- `…/staged-workflow/.claude/agents/review-code-quality.md` (new — staged, evidence-trail-exempt)
- `…/staged-workflow/.claude/agents/review-crash-safety.md`, `review-performance.md`, `review-security.md` (new — staged)
- `…/staged-workflow/.claude/agents/review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md`, `review-test-crash-safety.md` (new — staged)
- `…/staged-workflow/.claude/agents/review-test-structure.md` (new — staged, evidence-trail-exempt)
- `…/staged-workflow/.claude/agents/code-reviewer.md`, `pr-reviewer.md`, `test-quality-reviewer.md`, `dr-audit.md` (new — staged, D9 `exempt because…` annotation only)

### Step 3 — commit 87c0261f0a, 2026-06-08T06:22Z [ctx=info]
**What was done:** Migrated the six workflow dimensional agents (WC=consistency,
WB=context-budget, WH=hook-safety, WI=instruction-completeness, WP=prompt-design,
WS=writing-style) to template B in one commit through the staged mirror
(copy-then-edit first touch; live tree untouched). Each gained the leading
`## Output routing` guard before its `## Output format` heading. The guard's file
branch migrates the inline `**<PREFIX><N>**` bold bullets to `### <PREFIX><n>
[severity]` anchors under a single `## Findings` (no `### Summary` / `### Findings`),
preserving each agent's documented consecutive numbering, mapping the inline
Axis/Cost/Issue/Suggestion fields into the finding body, and carrying the native
Critical/Recommended/Minor severity into both the anchor line and the manifest `sev`
(§2.5 permits the producer's native scale). The no-path inline branch — bold bullets,
`#### Critical`/`#### Recommended`/`#### Minor` buckets, and the `Numbering:`
paragraph — stays byte-for-byte. WI and WS write `## Evidence base` from their
`## Process` verification (WI's complement/consumer/termination checks; WS's
section-length three-step decision and banned-vocab sweep); WC/WB/WH/WP are
evidence-trail-exempt (`cert` is `n/a`, empty `## Evidence base`, `certs: 0`) using
the step-2 ORCH-1 reconciled phrasing. The risk:high step-level review
(`review-workflow-prompt-design`) passed at iteration 1 — no blocker or should-fix.

**What was discovered:** With steps 1-3 complete, all 16 dimensional reviewers now
emit the §2.5 file+manifest+`### <PREFIX><n>` anchor shape on the path branch and
their current inline format on the no-path branch. The evidence-base-writing vs
evidence-trail-exempt map is now fixed for Track 4's routing: cert-writing =
bugs-concurrency, crash-safety, performance, security, test-behavior,
test-completeness, test-concurrency, test-crash-safety, instruction-completeness,
writing-style (10); exempt with `certs: 0` = code-quality, test-structure,
consistency, context-budget, hook-safety, prompt-design (6). The step-level review
confirmed the focal-risk byte-for-byte inline preservation held across all 6 — the
migration is a file-branch rendering instruction, not an inline rewrite. Two
suggestion-level findings were declined: WP1 (numbering-bullet redundancy — trimming
it would diverge from the canary's verbatim replication contract) and WP2
(anchor-line-tail title ambiguity — resolves via the cited §2.5 `### BC1 [blocker] …`
example, and a fix would force re-touching all 16 agents for consistency).

**Key files:**
- `…/staged-workflow/.claude/agents/review-workflow-instruction-completeness.md`, `review-workflow-writing-style.md` (new — staged, write `## Evidence base`)
- `…/staged-workflow/.claude/agents/review-workflow-consistency.md`, `review-workflow-context-budget.md`, `review-workflow-hook-safety.md`, `review-workflow-prompt-design.md` (new — staged, evidence-trail-exempt)

## Validation and Acceptance

- A dimensional agent handed an output path writes a file whose manifest the
  reviewer returns verbatim, with `## Findings` carrying `### <PREFIX><n> `
  anchored bodies, no stray `### ` heading, and `## Evidence base` carrying the
  refutation trail (or an empty `## Evidence base` when evidence-trail-exempt).
- The same agent with no output path returns inline in that agent's current
  format, byte-for-byte and with no manifest, so `/code-review` and
  `/fix-ci-failure` are untouched. The 6 workflow dimensions already carry inline
  IDs today, so "no ID prefix" is not part of their byte-for-byte baseline.
- Finding numbering is two-sided: an agent spawned with no high-water-mark starts
  at `<PREFIX>1`; an agent spawned with a gate-check high-water-mark of K
  continues at `<PREFIX>(K+1)`. The `<PREFIX>` is the dimension's canonical prefix
  from `review-iteration.md § Finding ID prefixes`; no agent renumbers a prior ID
  or chooses a non-canonical prefix (S2).
- For at least one agent per baseline family (one code, one test, and one
  workflow dimension), the path-branch file passes the S4 round-trip:
  `grep -cE '^### [A-Z]+[0-9]+ '` over the file equals the manifest `findings`
  count. The workflow-dimension case gates the bold-bullet to `### <PREFIX><n>`
  migration.
- Every dimensional agent carries either `## Evidence base` write instructions or
  an explicit evidence-trail-exempt annotation with a closed-set reason; the
  default is non-exempt, and an implementation that exempts every dimension does
  not pass.
- Each of the 4 standalone agents carries the `exempt because…` annotation (D9,
  contributes to S5).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3. -->

## Idempotence and Recovery

Agent edits are additive: the path branch is wrapped in front of the existing
inline Output Format, which stays verbatim (step 1). Never delete a live section
of an agent file to reconcile formats. `§1.7(e)` promotion is `cp -r` additive
and does not propagate deletions, so a deleted live section would survive into
the promoted file as stale content; a genuinely required deletion is a Phase 4
hand-edit, not a Track 3 staged edit.

Re-running a step is safe because each agent edit is an idempotent wrap:
re-applying the guard over an already-wrapped Output Format is a no-op once the
guard is present. The implementer verifies the no-path region against develop
(`git diff origin/develop -- <agent file>` shows only the added guard, with no
change inside the inline block).

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope (16 dimensional + 4 standalone agent files):**
- `.claude/agents/review-bugs-concurrency.md`, `review-code-quality.md`,
  `review-crash-safety.md`, `review-performance.md`, `review-security.md`
- `.claude/agents/review-test-behavior.md`, `review-test-completeness.md`,
  `review-test-concurrency.md`, `review-test-crash-safety.md`,
  `review-test-structure.md`
- `.claude/agents/review-workflow-consistency.md`, `review-workflow-context-budget.md`,
  `review-workflow-hook-safety.md`, `review-workflow-instruction-completeness.md`,
  `review-workflow-prompt-design.md`, `review-workflow-writing-style.md`
- `.claude/agents/code-reviewer.md`, `pr-reviewer.md`, `test-quality-reviewer.md`,
  `dr-audit.md` — `exempt because…` annotation only

**Out of scope:** the orchestrator-side routing, the `M<n>` synthesis removal,
and the path-injection dispatch sites (Track 4); the schema definition (Track 2);
the staging plumbing (Track 1). This track changes only what each agent emits.

**Inter-track dependencies:** depends on **Track 1** (agents must be stageable —
hard dependency, since these are `.claude/agents/**` edits) and **Track 2** (the
manifest schema the agents write). Downstream — **Track 4** consumes the
manifests and IDs these reviewers emit and removes the orchestrator-side `M<n>`
minting; the path-injection that switches on file output lives in Track 4's
dispatch-site edits.

## Base commit

dd3606f0b84f093b27dd1dd61e02ccf83d69a7e5
