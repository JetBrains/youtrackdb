<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: .claude/skills/execute-tracks/SKILL.md:109, anchor: "### T1 ", cert: I1, basis: "removed-agent roster reference lives outside the track's five-mirror-site grep guard; dangles after the split"}
  - {id: T2, sev: should-fix, loc: .claude/workflow/risk-tagging.md:68, anchor: "### T2 ", cert: P3, basis: "in-scope file's risk-level table names review-bugs-concurrency but Plan-of-Work step 1 enumerates only the tag rule, not this roster re-key"}
  - {id: T3, sev: should-fix, loc: .claude/workflow/conventions-execution.md:530, anchor: "### T3 ", cert: I2, basis: "in-scope file reads the tier (Tier-driven review selection pointer) but Plan step 6 lists it only for roster references, omitting the tier re-key"}
  - {id: T4, sev: suggestion, loc: docs/adr/.../plan/track-2.md:276, anchor: "### T4 ", cert: P2, basis: "track prose cites the short anchor form; live anchor has a longer title"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 12}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: PARTIAL, anchor: "#### P2 "}
  - {id: P3, verdict: PARTIAL, anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: P8, verdict: CONFIRMED, anchor: "#### P8 "}
  - {id: P9, verdict: CONFIRMED, anchor: "#### P9 "}
  - {id: I1, verdict: CALLERS AT RISK, anchor: "#### I1 "}
  - {id: I2, verdict: MISMATCHES, anchor: "#### I2 "}
  - {id: I3, verdict: MATCHES, anchor: "#### I3 "}
flags: [CONTRACT_OK]
-->

# Technical review — Track 2 (iteration 1)

Track 2 is workflow-modifying prose (s17=workflow-modifying, ledger-confirmed).
No Java production classes are in scope, so the five workflow-prose criteria
supersede the Java `findClass`/WAL/crash criteria: every named reference is
verified as a workflow file path or `§`-anchor via grep + Read. mcp-steroid PSI
is not applicable to this track (zero Java symbols), so no reference-accuracy
caveat is needed — grep over Markdown is the authoritative tool for prose-path
existence and the count of reference sites is exact, not a polymorphic-dispatch
estimate.

The track's mechanics are sound: every in-scope file exists, the seven HIGH
triggers and the tier-read seams the track re-keys are present as described, the
agent files to remove and the `TX` template exist, the prefix tables carry the
rows the track edits, and Track 1's staged `--append-ledger --reconciled-tag`
flag→key map makes the D5 reconciliation write realizable. The findings are all
**scope-completeness** gaps: the track's roster-split and tier re-key reach
files the track's in-scope list and its own grep-guard acceptance criterion do
not name, so dangling references to removed agents and a stale tier read can
survive Track 2.

## Findings

### T1 [should-fix]
**Certificate**: Integration I1 (removed-agent roster references outside the five mirror sites)
**Location**: track-2.md "No dangling roster references" acceptance (lines 420-423, 556-558) + Interfaces in-scope list (lines 436-474); real sites `.claude/skills/execute-tracks/SKILL.md:109`, `.claude/agents/review-workflow-instruction-completeness.md:24`, `.claude/agents/review-workflow-consistency.md:63`
**Issue**: The track's only dangling-reference guard is scoped to "the five
selection mirror sites" (`code-review/SKILL.md`, `review-agent-selection.md`,
`track-code-review.md`, `step-implementation.md`, `fix-ci-failure/SKILL.md`). A
live grep for the three removed agents (`review-bugs-concurrency`,
`review-test-behavior`, `review-test-completeness`) across `.claude/` returns
references in files **outside** those five sites and outside the track's
in-scope list:
- `execute-tracks/SKILL.md:109` — "the step-level baseline
  `review-bugs-concurrency`, subject to the baseline-skip override" — a
  load-bearing prose description of step-level dispatch. After the split this
  names a deleted agent.
- `review-workflow-instruction-completeness.md:24` — "This is the procedural
  analogue of `review-test-completeness`" — names a merged-away agent in a
  descriptive analogy.
- `review-workflow-consistency.md:63` — "A skill dispatches sub-agents by name
  (e.g., `review-bugs-concurrency`)" — uses a removed agent as the worked
  example in the consistency reviewer's own checklist. Doubly ironic: the agent
  whose job is to catch dangling roster references would itself carry one.

The design.md confirms the planner's mental model is exactly five mirror sites
(design.md:567-569) and does not anticipate these three; this is a genuine
completeness gap, not a deliberate omission. None of the three files appears in
the track's in-scope list (lines 436-474), so the track as written will leave
them dangling, and its grep-guard acceptance criterion (scoped to the five
sites) will report clean.
**Proposed fix**: Either (a) widen the in-scope list and the "No dangling roster
references" acceptance grep to cover the whole `.claude/**` reference surface
(`grep -rE 'review-bugs-concurrency|review-test-behavior|review-test-completeness'`
returning zero outside the staged-removed agent files), adding
`execute-tracks/SKILL.md` and the two workflow-reviewer agent files to scope; or
(b) if those three are intentionally deferred, state so explicitly in the track
and downgrade the dangling references to a known-residual note so a later track
owns them. Option (a) is preferred — the `execute-tracks/SKILL.md:109` reference
is load-bearing step-level-dispatch prose, not a soft analogy.

### T2 [should-fix]
**Certificate**: Premise P3 (risk-tagging.md roster reference vs Plan-of-Work coverage)
**Location**: track-2.md Plan of Work step (1) (lines 316-326) + in-scope list (line 437); real site `.claude/workflow/risk-tagging.md:68`
**Issue**: `risk-tagging.md` is in scope, but the Plan-of-Work step that owns it
(step 1) describes only the track-granularity tag-computation rule (D9). The
file also carries a roster reference at line 68 — the risk-level table's
"Step-level review (sub-step 4)" column reads "`review-bugs-concurrency`
(subordinate to the workflow/docs-only baseline-skip override) + triggered
conditional + step-level workflow reviewers". After the D7 split this names a
removed agent and must re-key to the step-level burial role D3 assigns
(`review-bugs` always + `review-concurrency` when concurrency is present). The
edit is in an in-scope file, but no Plan-of-Work step enumerates it, so a step
decomposition keyed off the Plan of Work could miss it.
**Proposed fix**: Add the `risk-tagging.md:68` roster re-key to either Plan step
(1) (extend it: "and re-key the §"Risk tagging" / risk-level table's step-level
review column off `review-bugs-concurrency` onto the split roster") or step (4)
(the roster-adaptation step). Cross-reference D3 so the burial-role wording stays
consistent with `review-agent-selection.md`.

### T3 [should-fix]
**Certificate**: Integration I2 (conventions-execution.md tier read vs Plan step-6 scope)
**Location**: track-2.md Plan of Work step (6) (lines 373-378) + in-scope list (lines 457-458); real site `.claude/workflow/conventions-execution.md:530-532`
**Issue**: Plan step (6) lists `conventions-execution.md` for "review-file /
roster references and any per-track-tag track-file references". But the file also
carries a **tier read** at lines 530-532: the "Two-tier dimensional code review"
bullet's sibling "**Tier-driven review selection** (which Phase-3A pre-execution
reviews to run, keyed off the confirmed tier rather than step count): covered in
`track-review.md:orchestrator:3A` §Tier-driven review selection." Track 2's D6
re-keys the Phase-A panel off the whole-change tier onto the per-track complexity
tag, so this pointer's "keyed off the confirmed tier rather than step count"
description goes stale the moment `track-review.md`'s panel re-keys. The Plan of
Work's enumeration for this file omits the tier re-key, so the gap mirrors T2:
in-scope file, un-enumerated edit. (The roster reference at line 528 is covered
by step 6's "roster references"; only the tier read is unaddressed.)
**Proposed fix**: Extend Plan step (6)'s `conventions-execution.md` clause to add
"and re-key the §2.4 'Tier-driven review selection' pointer's tier-vs-step-count
description onto the per-track complexity tag". This also implicates the
`§Tier-driven review selection` anchor the pointer targets (see T4).

### T4 [suggestion]
**Certificate**: Premise P2 (track-review.md §-anchor name)
**Location**: track-2.md Context and Orientation (line 276) + the pointer at conventions-execution.md:532; real anchor `.claude/workflow/track-review.md:620`
**Issue**: Track prose cites the section as `track-review.md §"Tier-driven
review selection"` (line 276), and conventions-execution.md:532 points at
`§Tier-driven review selection`. The live heading is the longer
`### Tier-driven review selection and which reviews to run`
(track-review.md:620). The short form is an unambiguous prefix today, so no
link is broken yet — but D6 rewrites this section's logic (tier → complexity
tag), and if the rename touches the heading title, both the track prose and the
conventions-execution.md pointer become stale anchors. Worth pinning the exact
title now so the re-key keeps the anchor and its two referrers in sync.
**Proposed fix**: When step (2) re-keys the section, either keep the heading
title byte-stable, or update the track prose (line 276) and the
conventions-execution.md:532 pointer to the new exact title in the same edit. A
low-cost forward note in the track's Plan of Work step (2) suffices.

## Evidence base

#### P1: risk-tagging.md contains exactly the seven HIGH triggers the track names
- **Track claim**: "the seven `risk-tagging.md` HIGH triggers (`Concurrency`, `Crash-safety / Durability`, `Public API`, `Security`, `Architecture / cross-component coordination`, `Performance hot path`, `Workflow machinery`) exist already; this track runs them at track granularity" (track-2.md:282-285, D9).
- **Search performed**: grep `^### ` over `.claude/workflow/risk-tagging.md` §"HIGH-risk triggers"; Read lines 97-207.
- **Code location**: `.claude/workflow/risk-tagging.md:102-180` (the seven `###` sub-headings) + §"Gate 1 reuse (change-level)":182-206.
- **Actual behavior**: Exactly seven HIGH sub-headings: Concurrency (102), Crash-safety / Durability (114), Public API (126), Security (135), Architecture / cross-component coordination (145), Performance hot path (152), Workflow machinery (159). §Gate 1 reuse (184-192) already states the same seven categories are reused at the change level: "The same list drives two decisions from one source of truth: the per-step tagging above ... and the change-level Gate 1." D9's "run the same seven triggers at track granularity" is a third granularity of an established reuse pattern, fully supported.
- **Verdict**: CONFIRMED
- **Detail**: The per-step risk tag is computed from these triggers (§HIGH-risk triggers is `decomposer:3A`), confirming the track's premise that the tag mechanism already exists at step granularity.

#### P2: track-review.md §"Tier-driven review selection" reads the whole-change tier today
- **Track claim**: "The Phase-A panel in `track-review.md` §"Tier-driven review selection" today reads the **whole-change tier** as its intensity knob: `minimal` → Technical only; `lite`/`full` → Technical always + Risk track-characteristic-gated + Adversarial narrowed." (track-2.md:276-280).
- **Search performed**: grep `Tier-driven|tier|minimal|lite|full` over track-review.md; Read lines 620-664.
- **Code location**: `.claude/workflow/track-review.md:620-664`.
- **Actual behavior**: line 623-626: "The **confirmed tier** (D9), not step count, selects the Phase-3A panel ... Read the tier ledger-first: the phase ledger's `tier` field (last value wins)". Table at 639-643: `minimal` → Technical only; `lite`/`full` → Technical + Risk-gated + Adversarial narrowed. Exact match for the track's description of the seam D6 re-keys.
- **Verdict**: PARTIAL
- **Detail**: The mechanism is exactly as described, so the re-key target is real. The PARTIAL is solely the anchor-name discrepancy: live heading is `### Tier-driven review selection and which reviews to run` (line 620); the track cites the prefix `§"Tier-driven review selection"`. Unambiguous today, but a rename hazard for D6's rewrite → finding T4.

#### P3: risk-tagging.md carries a step-level roster reference (review-bugs-concurrency)
- **Track claim**: implicit — Plan step (1) scopes risk-tagging.md to the tag rule only; the track does not flag the file's roster reference.
- **Search performed**: grep `review-bugs-concurrency` over risk-tagging.md; Read lines 66-70.
- **Code location**: `.claude/workflow/risk-tagging.md:68`.
- **Actual behavior**: The risk-level table's "Step-level review (sub-step 4)" column for `high` reads: "Step-level dimensional review: `review-bugs-concurrency` (subordinate to the workflow/docs-only baseline-skip override) + triggered conditional + step-level workflow reviewers (`hook-safety`, `prompt-design`)". This names an agent the track removes.
- **Verdict**: PARTIAL
- **Detail**: File is in scope but the Plan-of-Work step that owns it (step 1) describes only the D9 tag rule, not this roster re-key → finding T2.

#### P4: The five selection mirror sites exist and carry category-driven selection
- **Track claim**: "Reviewer selection today ... is purely category-driven ... mirrored across five sites: `code-review/SKILL.md`, `review-agent-selection.md`, `track-code-review.md`, `step-implementation.md`, and `fix-ci-failure/SKILL.md`" (track-2.md:263-267).
- **Search performed**: grep `category|select|review-bugs-concurrency|review-test` over each of the five files; Read code-review/SKILL.md §Step 5 (188-216) and review-agent-selection.md §Step-level routing (94-179).
- **Code location**: `code-review/SKILL.md:188-216` (the category→agent table); `review-agent-selection.md:94-179`; `track-code-review.md:534-544`; `step-implementation.md:431-449`; `fix-ci-failure/SKILL.md:170-204`.
- **Actual behavior**: All five exist and carry the binary category-driven selection ("category present → launch its agent"). code-review/SKILL.md:190-216 enumerates the agents and their launch conditions; review-agent-selection.md §"Step-level vs track-level routing" carries the localized-versus-buried rule (99-103), the single-step-high override (115-126), and the "Workflow-review group" narrowing (139-166). track-code-review.md:543-544 names the four baselines; step-implementation.md:433-449 narrows the step baseline to `review-bugs-concurrency`; fix-ci-failure/SKILL.md:170-204 mirrors the selection and roster.
- **Verdict**: CONFIRMED
- **Detail**: The five mirror sites and their selection logic are exactly as the track describes. The step-level routing rule, single-step-high override, and Workflow-review-group narrowing all exist as D3 assumes.

#### P5: The three agent files to remove exist, and review-test-concurrency (TX) is present as the template
- **Track claim**: "three new agent files ... three removed (`review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`)" and "the test side already split concurrency out as `review-test-concurrency` (prefix `TX`) ... that existing split is the template the production split mirrors" (track-2.md:300-303, 258-261).
- **Search performed**: `ls` over `.claude/agents/`; grep `TX|prefix|finding` over review-test-concurrency.md.
- **Code location**: `.claude/agents/review-bugs-concurrency.md`, `review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md` all present; new files `review-bugs.md` / `review-concurrency.md` / `review-test-quality.md` absent (correctly — Track 2 has not implemented).
- **Actual behavior**: The three removal targets exist. review-test-concurrency.md uses the `TX` prefix (lines 217-226: "Number findings with the canonical `TX` prefix"), confirming it as the production-split template.
- **Verdict**: CONFIRMED
- **Detail**: Per the prompt's planned-class rule, the three new agent files being absent is correct (this track creates them); not a NOT-FOUND blocker.

#### P6: review-iteration.md §"Finding ID prefixes" carries the BC / TB / TC / TX rows
- **Track claim**: "the canonical owner table `review-iteration.md` §"Finding ID prefixes" ... retire the `BC` row, add the two new ... rows, keep `TB`/`TC`" (track-2.md:362-364, 453-455).
- **Search performed**: grep `BC|TB|TC|TX|prefix` over review-iteration.md; Read lines 42-71.
- **Code location**: `.claude/workflow/review-iteration.md:48-71` (the prefix table).
- **Actual behavior**: Table rows: `BC` Bugs & concurrency (56), `TB` Test behavior (60), `TC` Test completeness (61), `TX` Test concurrency (63). All four rows the track edits exist. The table is the canonical owner the track names.
- **Verdict**: CONFIRMED
- **Detail**: `BC` is retireable, `TB`/`TC` are keepable verbatim, `TX` is unchanged. Two new rows can be inserted as the track plans.

#### P7: finding-synthesis-recipe.md references the finding-prefix family
- **Track claim**: "Owned by `review-iteration.md` §"Finding ID prefixes" and referenced by `finding-synthesis-recipe.md`" (track-2.md:507-509, 362).
- **Search performed**: grep `BC|TB|TC|TX|prefix` over finding-synthesis-recipe.md.
- **Code location**: `.claude/workflow/finding-synthesis-recipe.md:188-189, 200-201, 459-505`.
- **Actual behavior**: The recipe uses `BC3`, `TX1`, `TS5`, `CQ9` as worked examples throughout (`### BC3 `, `### TX1 ` anchors), and names `review-bugs-concurrency` / `review-test-concurrency` in its routing table (188-189). It references the prefix family operationally.
- **Verdict**: CONFIRMED
- **Detail**: The track's claim is accurate; finding-synthesis-recipe.md is correctly in scope (line 452) and the `BC`-prefix worked examples must update with the split.

#### P8: create-final-design.md carrier table reads the tier today
- **Track claim**: "The Phase-4 carrier table in `create-final-design.md` is keyed off the tier today (full → design-final + adr; lite → adr; minimal → PR-summary)" (track-2.md:285-288) + D8b re-derives it from the axes.
- **Search performed**: grep `tier|full|lite|minimal|design-final|adr|carrier|verdict` over create-final-design.md; Read lines 87-106.
- **Code location**: `.claude/workflow/prompts/create-final-design.md:89-99`.
- **Actual behavior**: line 89: "Which artifacts to produce is keyed off the confirmed tier (D16). Read the confirmed tier ledger-first". Table 97-99: `full` → design-final.md + adr.md; `lite` → adr.md only; `minimal` → neither (PR-description verdict fold). Exact match for the track's description of the seam D8b re-derives.
- **Verdict**: CONFIRMED
- **Detail**: Confirms create-final-design.md is one of the four live tier-readers Track 1's handoff left for Track 2 to promote.

#### P9: design-review.md carries a tier=full fidelity gate today
- **Track claim**: "`design-review.md` (a `tier=full` fidelity gate) read the tier today" + Plan step (6) re-keys "its `tier=full` fidelity gate ... to read `design_gate=yes`" (technical-review spawn key-checks; track-2.md:376-378, 473-474).
- **Search performed**: grep `tier=full|tier|fidelity|design_gate` over design-review.md; Read lines 63-87.
- **Code location**: `.claude/workflow/prompts/design-review.md:67-71`.
- **Actual behavior**: line 67-71: "`tier` (optional) — `full` / `lite` / `minimal`. Supplied with `target=tracks` so the reviewer knows whether the full-tier fidelity criterion (Step 4b) ... applies ... the seed↔track fidelity" — a design-presence proxy via the `full` tier. The Plan's re-key to `design_gate=yes` is coherent: `design_gate=yes` is the direct design-presence signal Track 1 adds, replacing the `tier=full` proxy.
- **Verdict**: CONFIRMED
- **Detail**: Confirms design-review.md is one of the four live tier-readers. The re-key from a `tier=full` proxy to the direct `design_gate=yes` field is semantically sound (both mean "a design.md exists").

#### I1: removed-agent roster references outside the five mirror sites
- **Plan claim**: "No reference to a removed agent (`review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`) survives across the five selection mirror sites" (track-2.md:420-423).
- **Actual entry point**: `grep -rlE 'review-bugs-concurrency|review-test-behavior|review-test-completeness' .claude/` returns 15 files. Excluding the three agent files being removed and the five named mirror sites and the in-scope re-keyed files (risk-tagging, finding-synthesis-recipe, conventions-execution, code-review-protocol), the residual sites are: `execute-tracks/SKILL.md:109`, `review-workflow-consistency.md:63`, `review-workflow-instruction-completeness.md:24`.
- **Caller analysis**: execute-tracks/SKILL.md:109 — "the step-level baseline `review-bugs-concurrency`, subject to the baseline-skip override" — load-bearing prose in the orchestrator skill that drives Phase B. review-workflow-consistency.md:63 — example in the consistency reviewer's checklist. review-workflow-instruction-completeness.md:24 — descriptive analogy. (Grep over Markdown is exact for prose-path references; no polymorphic-dispatch miss risk.)
- **Breaking change risk**: After the split lands, all three name a deleted agent. The track's grep-guard acceptance is scoped to the five mirror sites and will not catch them. The consistency review (the track's named backstop) might catch the consistency/instruction-completeness agent files, but the design (design.md:567-569) and the track both model only five sites, so coverage is not guaranteed.
- **Verdict**: CALLERS AT RISK
- **Detail**: Produces finding T1.

#### I2: conventions-execution.md tier read vs Plan step-6 scope
- **Plan claim**: Plan step (6) re-keys conventions-execution.md for "review-file / roster references and any per-track-tag track-file references" (track-2.md:373-374, 457-458).
- **Actual entry point**: `.claude/workflow/conventions-execution.md:530-532` — the §2.4 "**Tier-driven review selection** (which Phase-3A pre-execution reviews to run, keyed off the confirmed tier rather than step count)" pointer to track-review.md.
- **Caller analysis**: This is a load-on-demand pointer read by `orchestrator:3A,3B,3C`. Its "keyed off the confirmed tier rather than step count" description is exactly what D6 invalidates (panel re-keys to the per-track complexity tag).
- **Breaking change risk**: After D6 re-keys the panel, this pointer's description is stale. Plan step (6) enumerates only "roster references" for this file, not the tier read, so the tier re-key is un-scoped (the roster ref at line 528 *is* covered).
- **Verdict**: MISMATCHES
- **Detail**: Produces finding T3. Distinct from the line-528 roster reference, which step-6 "roster references" already covers.

#### I3: Track 1's --append-ledger --reconciled-tag flag→key map makes D5 realizable
- **Plan claim**: "Write the reconciled tag (`max(step tags)`) to Track 1's per-track ledger field at the A→C boundary via `--append-ledger`" + "the reconciliation mechanism appends the per-track reconciled tag through Track 1's `--append-ledger` field at the A→C boundary" (track-2.md:336-338, 510-512).
- **Actual entry point**: staged `_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:209` (`--reconciled-tag` arg parse), :1738 (bare-token validation), :1768 (emit on the ledger line).
- **Caller analysis**: The live A→C append already runs `--append-ledger --ctx <level> --phase C --track <N> --substate ...` (track-review.md:1051-1052 + Phase A Completion recovery branch). Adding `--reconciled-tag <tag>` to that same call is supported by the staged script. The script comment (precheck:1763-1764) confirms "reconciled_tag rides on the same line as its `track=` token so the track-scoped reader resolves it" — and the emit order (track= at :1759, reconciled_tag= at :1768) puts both on one line. The prior-episodes handoff (flag→key map `--reconciled-tag`→`reconciled_tag`, emitted on the same ledger line as `track=`) holds against the staged script.
- **Breaking change risk**: None. The write contract is realizable as Track 1 froze it.
- **Verdict**: MATCHES
- **Detail**: No finding. D5's reconciled-tag write is implementable against Track 1's staged schema; the A→C boundary already carries `--track <N>`, so the same-line pairing requirement is satisfied by construction.
