<!-- MANIFEST
dimension: workflow-writing-style
target: Track 1 — the phase ledger, the new artifact model, the authoring surface
commit_range: 6c2e0b5f68b12599aacbcce8b608f5c1489a3159..HEAD
iteration: 1
high_water_mark: 0
findings: 2
evidence_base: { certs: 2 }
cert_index: [C1, C2]
index:
  - { id: WS1, sev: Recommended, anchor: "### WS1", loc: ".claude/workflow/conventions.md:360-373", cert: C1, basis: script }
  - { id: WS2, sev: Recommended, anchor: "### WS2", loc: ".claude/skills/create-plan/SKILL.md:144-154", cert: C2, basis: script }
flags: { evidence_trail_exempt: false, reason: "em-dash counts persisted as C1/C2" }
-->

## Findings

### WS1 [Recommended] Delta inserts a triple-clause em-dash cadence into the track-file shape paragraph

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md` (lines 360-373; delta lines 2374-2390)
- **Axis:** em-dash overuse
- **Cost:** the delta adds a fourth em dash to a single blank-line-bounded paragraph and creates the `X — Y — Z` triple-clause cadence the house style names "always a finding".

**Issue.** The `### Track file content` opening paragraph (staged `conventions.md:360-373`) is one blank-line-bounded paragraph. The live baseline already carried two em dashes ("Phase 1 alongside `implementation-plan.md` — one file per" and "The full file shape — ... — is defined"), which is verbatim already-live content and out of this track's scope. This delta, however, inserts a new clause between the existing closing em-dash pair: live `... track-level Mermaid diagram — is defined in` becomes staged `... track-level Mermaid diagram — 15 sections total — is defined in`. The inserted `— 15 sections total —` adds a new em dash (the paragraph now holds four) and, more sharply, produces the explicit `shape — … — 15 sections total — is defined` triple-clause cadence that `house-style.md § Em-dash discipline` flags unconditionally: "Never use the `X — Y — Z` triple-clause cadence."

**Suggestion.** Drop the em dashes around the inserted clause. Parenthesize it or fold it into the lead instead, e.g. change `together with any optional track-level Mermaid diagram — 15 sections total — is defined in` to `together with any optional track-level Mermaid diagram (15 sections total), is defined in`. Better still, since the live paragraph was already at two em dashes, convert one of the pre-existing dashes to a period while you are in the sentence: "The full file shape is defined in `conventions-execution.md §2.1` *Track file content*. It is the 12 OpenAI-style ExecPlan sections (…) plus the combined `## Invariants & Constraints` section (D9) and the workflow-specific `## Episodes` and `## Base commit` siblings — 15 sections total." That keeps one em dash for the count aside and removes the triple cadence.

### WS2 [Recommended] New Step 1c routing paragraph carries two em dashes in running prose

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 144-154; delta lines 1675-1685)
- **Axis:** em-dash overuse
- **Cost:** two em dashes in one blank-line-bounded running-prose paragraph exceeds the one-per-paragraph cap; this is fully delta-added prose, not a verbatim carry.

**Issue.** The "The routing signal is **tier-dependent** (D10)" paragraph (staged `SKILL.md:144-154`) is a single blank-line-bounded paragraph written entirely new by this delta (delta `>` lines 1675-1685). It is flowing prose, not a definition-list of `- **Term** —` bullets, so both of its em dashes are running-prose cadence rather than structural separators. The two dashes are at line 146 ("the **phase ledger** is the signal for `minimal` — when the ledger is present…") and line 152 ("read from the **ledger** `tier` field — never from a plan line…"). `house-style.md § Em-dash discipline` caps a paragraph at one em dash; this paragraph holds two.

**Suggestion.** Convert one of the two to a period. The second clause is the more natural sentence break: change `the tier is read from the **ledger** `tier` field — never from a plan line (the plan no longer carries a tier line; it moved to the ledger per D4) and never from a fresh read of the research log, which would be a third decision-content read site and break S2.` to `the tier is read from the **ledger** `tier` field, never from a plan line (the plan no longer carries a tier line; it moved to the ledger per D4) and never from a fresh read of the research log, which would be a third decision-content read site and break S2.` The comma reads identically and leaves the paragraph with its single permitted em dash at line 146.

## Evidence base

#### C1 [confirmed] — em-dash count, conventions.md:360-373 (WS1)

Section-length three-step is not the trigger; this is a direct em-dash-discipline finding. The staged paragraph at `conventions.md:360-373` is one blank-line-bounded unit. Em dashes after applying the delta: "plan artifact) —" (361), "The full file shape —" (362), "diagram — 15 sections total —" (371) = four. Live baseline at `conventions.md:312-322` had two ("— one file per", "The full file shape — … — is defined"). The delta-added clause `— 15 sections total —` introduces the third/fourth dash and the `X — Y — Z` triple-clause cadence. Confirmed against `house-style.md § Em-dash discipline` ("at most one em dash per paragraph"; "Never use the `X — Y — Z` triple-clause cadence").

#### C2 [confirmed] — em-dash count, SKILL.md:144-154 (WS2)

Direct em-dash-discipline finding. The staged paragraph at `SKILL.md:144-154` is one blank-line-bounded running-prose unit, delta-added in full (delta lines 1675-1685, all `>` adds). Em dashes: line 146 ("for `minimal` —") and line 152 ("`tier` field —") = two in running prose, neither a `- **Term** —` definition-list separator. Confirmed against `house-style.md § Em-dash discipline` (cap of one per paragraph).
