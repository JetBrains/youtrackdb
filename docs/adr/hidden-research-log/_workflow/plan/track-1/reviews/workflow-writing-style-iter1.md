<!-- MANIFEST
findings: 3   severity: {Critical: 0, Recommended: 3, Minor: 0}
index:
  - {id: WS1, sev: Recommended, loc: "docs/adr/hidden-research-log/_workflow/plan/track-1.md:30", anchor: "### WS1 ", cert: C1, basis: "D1 Rationale paragraph uses the banned X — Y — Z triple-clause em-dash cadence ('orchestration procedure — an execution-procedure file — so it must stage'); two em dashes in one paragraph, always a finding per house-style § Em-dash discipline"}
  - {id: WS2, sev: Recommended, loc: "docs/adr/hidden-research-log/_workflow/plan/track-1.md:31", anchor: "### WS2 ", cert: C2, basis: "D1 Risks/Caveats paragraph carries two prose em dashes (session — live research.md; one-line section — tracked by YTDB-1125), exceeding the one-per-paragraph cap"}
  - {id: WS3, sev: Recommended, loc: "docs/adr/hidden-research-log/_workflow/plan/track-1.md:136", anchor: "### WS3 ", cert: C3, basis: "Concrete Steps step-description prose adds three authored em dashes beyond the two template-bound — risk:/— size: field separators, putting four+ em dashes in the description and overusing the X — Y — Z cadence"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [Recommended] D1 Rationale uses the banned `X — Y — Z` triple-clause em-dash cadence

- **File:** `docs/adr/hidden-research-log/_workflow/plan/track-1.md` (line 30)
- **Axis:** em-dash overuse
- **Cost:** banned triple-clause cadence plus two em dashes in one paragraph, in branch-authored ExecPlan Decision-Record prose

The D1 `**Rationale**` paragraph reads: *"`create-plan/SKILL.md` is read by the agent as the `/create-plan` orchestration procedure — an execution-procedure file — so it must stage"*. The two em dashes bracket an apposition, producing the `X — Y — Z` triple-clause cadence that house-style.md § Em-dash discipline bans outright ("Never use the `X — Y — Z` triple-clause cadence"), independent of the per-paragraph count. Track files are `*.md` and carry the full house-style tier per `conventions.md §1.5`; there is no ExecPlan/Decision-Record carve-out from the punctuation rules.

- **Suggestion:** Replace the bracketing dashes with a relative clause or a parenthetical, leaving at most one em dash. For example: *"`create-plan/SKILL.md` is read by the agent as the `/create-plan` orchestration procedure (an execution-procedure file), so it must stage"*.

### WS2 [Recommended] D1 Risks/Caveats carries two em dashes in one paragraph

- **File:** `docs/adr/hidden-research-log/_workflow/plan/track-1.md` (line 31)
- **Axis:** em-dash overuse
- **Cost:** two prose em dashes in a single blank-line-bounded paragraph, exceeding the one-per-paragraph cap

The D1 `**Risks/Caveats**` paragraph uses two em dashes as prose punctuation: *"No self-application this session — live `research.md` stays at develop…"* and *"the workflow-modifying marker is hand-rolled into a one-line section — tracked by YTDB-1125"*. House-style § Em-dash discipline caps em dashes at one per paragraph; the mechanical check counts per blank-line-bounded paragraph, and this paragraph holds two.

- **Suggestion:** Convert one of the two to a period or a colon. For example, turn the first into a sentence boundary: *"No self-application this session. Live `research.md` stays at develop until the Phase-4 promotion, so this `/create-plan` run is not held to the new rule…"*, and keep the second em dash as the paragraph's single dash.

### WS3 [Recommended] Concrete Steps description overuses em dashes beyond the template field separators

- **File:** `docs/adr/hidden-research-log/_workflow/plan/track-1.md` (line 136)
- **Axis:** em-dash overuse
- **Cost:** three authored prose em dashes on top of the two template-bound `— risk:` / `— size:` field separators, putting the step well past the one-per-paragraph cap

The `## Concrete Steps` line carries five em dashes. Two are the template-bound step-field separators the `conventions-execution.md:150-151` step template prescribes (`<description> — `risk:` — size: …`) and are not a finding. The other three are authored prose punctuation inside the step *description*: *"…across both staged Phase-0 surfaces — the five `research.md` edits…"*, *"…changes agent-observable behavior; bounded — drives no gate…"*, and *"…(a) no mergeable low/medium work fits — the two staged files are the entire single-track change"*. The description prose itself overuses em dashes and re-runs the `X — Y — Z` cadence. The template exemption covers the field separators only, not the prose between them.

- **Suggestion:** Rewrite the description's three prose dashes as colons, periods, or parentheticals, leaving the two template separators intact. For example, open with *"Apply the research-log opacity rule across both staged Phase-0 surfaces: the five `research.md` edits …"* (colon), and recast the risk clause as *"workflow machinery: multi-file prose that changes agent-observable behavior; bounded (drives no gate, runs nothing automatically)"*.

## Evidence base

#### C1 D1 Rationale triple-clause cadence — CONFIRMED

Banned-pattern check against house-style.md § Em-dash discipline. `grep -oE '—'` on track-1.md:30 returns two em dashes; both bracket the apposition "an execution-procedure file", forming the explicitly banned `X — Y — Z` triple-clause cadence. Track-file `*.md` surface carries the full house-style tier (`conventions.md §1.5` table, "All `*.md` files … Full house-style"), with no ExecPlan/Decision-Record carve-out from § Em-dash discipline. Confirmed.

#### C2 D1 Risks/Caveats two-per-paragraph — CONFIRMED

Em-dash count check. `grep -oE '—'` on track-1.md:31 returns two em dashes, both authored prose punctuation in one blank-line-bounded paragraph (the D1 block spans lines 28-32, with the two dashes on line 31). House-style § Em-dash discipline caps at one per paragraph and counts per blank-line-bounded paragraph outside fenced code. Confirmed over cap.

#### C3 Concrete Steps prose dashes beyond template separators — CONFIRMED

Em-dash count check plus template-exemption test. `grep -oE '—'` on track-1.md:136 returns five em dashes. Cross-checked against the `## Concrete Steps` step template at `conventions-execution.md:150-151` (`<description> — `risk: low` — size: …`), which prescribes exactly two em dashes as field separators; those two are exempt. The remaining three sit inside the authored step description as prose punctuation and re-run the banned `X — Y — Z` cadence. Confirmed: three authored prose em dashes over the one-per-paragraph cap.
