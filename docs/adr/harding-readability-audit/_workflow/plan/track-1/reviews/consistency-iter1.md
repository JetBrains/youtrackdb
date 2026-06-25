<!-- MANIFEST
role: reviewer-plan
phase: 2
iter: 1
verdict: PASS
findings: 1
blockers: 0
index:
  - id: CR1
    sev: suggestion
    anchor: "### CR1 [suggestion]"
    loc: "design.md §\"The agent-side whole-doc guard\" / track-1.md ## Context and Orientation + ## Interfaces and Dependencies (D2 row); .claude/workflow/design-document-rules.md"
    cert: "Ref: design-document-rules.md cold-read-mechanics slicing statement"
    basis: current-state
    classification: design-decision
evidence_base: "12 Ref certificates + 1 file-existence sweep against live develop-state .claude/** files. PSI is N/A — prose-only Markdown change, all checks are exact literal-text matches. Staged-workflow tree absent at Phase 2, so every .claude/** read resolves to the live develop-state file the plan/design's current-state claims describe. 11 of 12 load-bearing current-state claims MATCH; 1 (D2's design-document-rules.md slicing-statement role) NOT FOUND — the only finding."
-->

## Findings

### CR1 [suggestion]
**Certificate**: Ref: design-document-rules.md cold-read-mechanics slicing statement
**Location**: design.md §"The agent-side whole-doc guard" ("`design-document-rules.md` (any cold-read-mechanics slicing statement, kept in sync by reference)"); track-1.md `## Context and Orientation` ("holds any cold-read-mechanics slicing statement that must stay in sync") and `## Interfaces and Dependencies` D2 row ("cold-read-mechanics slicing statement | Keep in sync by reference (D2)"). Code location: `.claude/workflow/design-document-rules.md` (whole file, 1040 lines).
**Issue**: The plan and design name `design-document-rules.md` as one of the six in-scope files whose role is to hold a "cold-read-mechanics slicing statement" that the change will "keep in sync by reference" (D2). The live `design-document-rules.md` carries no slicing / range-sliced / fan-out / partition / window statement at all. The Component-Map edge `DDR -.cross-references.-> ED` and the D2 distribution ("the other three files cross-reference the shared principle") therefore have no current anchor in this file — there is nothing in it today to point a cross-reference at, so the planned edit's nature (add a new slicing reference, or wire a `§`-cross-reference into an existing cold-read passage) is undetermined from the documents.
**Evidence**: `grep -niE "slic|sub-agent|auditor|fan.out|window|spawn|~200|line range|partition"` over `design-document-rules.md` returns only generic "cold-read sub-agent" prompt-template prose (lines 344-353, 471-487) and the `design-sync` `readability-auditor` block (484-487); none of it states a slice/window/range partition mechanic. The design's own wording hedges this with "**any** … that must stay in sync," signalling the authors were aware the statement may not pre-exist. By contrast `/readability-feedback` Procedure step 2 (line 33) DOES carry the proven `~200-line` / `## / # Part` / `Cap at ~6` rule the design cites as the port source — so the "keep the sibling in sync" obligation is real for readability-feedback but vacuous for design-document-rules.md as written.
**Proposed fix**: Reconcile the D2 distribution wording in design.md §"The agent-side whole-doc guard" and the track-1.md `## Context and Orientation` / `## Interfaces and Dependencies` D2 row with the live file: either (a) make D2 explicit that design-document-rules.md gains a NEW one-line cross-reference to the canonical slicing home (`edit-design` Step 4) under its `### Cold-read sub-agent prompt` section — not merely "keep in sync" an absent statement — or (b) drop design-document-rules.md from the six-file in-scope set if no slicing reference is intended there, reducing the Component Map to five files. The choice affects the track's file footprint and the "no drift across the sibling files" acceptance criterion, so it is the user's call.
**Classification**: design-decision
**Justification**: Multiple plausible fix renderings (add a new cross-reference vs. drop the file from scope) and the choice changes what the plan delivers (six-file vs five-file footprint, one acceptance-criterion clause) — "Multiple plausible fix renderings exist … escalate" per §Classification rules.

## Evidence base

PSI is **N/A** for this review: the branch edits only `.claude/**` workflow Markdown (a control-flow protocol described in prose), so every current-state claim is an exact literal-text match verified by `Read`/`Grep` against the live develop-state file. There are zero Java symbols, so no reference-accuracy caveat applies. The `_workflow/staged-workflow/` tree is absent at Phase 2 (confirmed: `ls` → ABSENT), so every `.claude/**` read resolves to the live develop-state file — exactly the files the plan/design's current-state claims describe. Phase ledger confirms `tier=full s17=workflow-modifying`, so all four artifacts (plan + track + design + live files) are in scope and the full consistency review runs.

### Ref: edit-design Step 4 "is range-sliced" auditor-spawn description (claim 1)
- **Document claim**: design.md §"Deterministic design-path slice partition" / track D1: today edit-design Step 4 says only that the auditor "is range-sliced: each slice gets its own spawn whose params file carries `target=design`, `target_path`, and the slice `range`", with NO partition rule / slice count / whole-doc floor.
- **Search performed**: `grep -n "is range-sliced"` + `Read` of `.claude/skills/edit-design/SKILL.md` lines 669-682 (§"Spawning the per-round auditor and second check").
- **Code location**: `.claude/skills/edit-design/SKILL.md:676-682`.
- **Actual signature/role**: "**The readability auditor** (`subagent_type: readability-auditor` …) is range-sliced: each slice gets its own spawn whose params file carries `target=design`, `target_path=<design_path>`, and the slice `range`. The auditor reads only `house-style.md`, its slice, and the standing anchors (the `## Overview` and `## Core Concepts` of `design.md`); its params file names **no** research-log path (S1)." No partition algorithm, no slice count, no `~200`-line window, no cap, no whole-doc floor anywhere in Step 4.
- **Verdict**: MATCHES
- **Detail**: Current-state claim confirmed verbatim. The "no partition rule / no count / no floor" gap is exactly as the design describes. (Target-state — the ~200-line partition Step 4 *will* state — correctly not asserted of the live file.)

### Ref: readability-feedback Procedure step 2 partition rule (claim 2)
- **Document claim**: design.md §"Deterministic design-path slice partition": today `/readability-feedback` Procedure step 2 reads "Split the doc into ~200-line ranges on `##` / `# Part` boundaries; give each companion file (`design-mechanics.md`) its own range. Cap at ~6 sub-agents."
- **Search performed**: `grep -n "Split the doc into\|Cap at ~6"` + `Read` of `.claude/skills/readability-feedback/SKILL.md`.
- **Code location**: `.claude/skills/readability-feedback/SKILL.md:33`.
- **Actual signature/role**: "2. **Partition.** Split the doc into ~200-line ranges on `##` / `# Part` boundaries; give each companion file (`design-mechanics.md`) its own range. Cap at ~6 sub-agents."
- **Verdict**: MATCHES
- **Detail**: Quoted byte-for-byte, including the companion-file `design-mechanics.md` clause the design says it ports minus the companion clause.

### Ref: edit-design Step 6 convergence claim + resume round-count glob (claim 3)
- **Document claim**: design.md §"Section-keyed settled-state" / §"File relocation": today edit-design Step 6 asserts the loop "moves monotonically toward dual-clean — typically one or two rounds", and holds a resume round-count glob that recovers the round by counting per-round params files.
- **Search performed**: `grep -n "monotonically"` + `Read` of `.claude/skills/edit-design/SKILL.md` lines 821-837.
- **Code location**: `.claude/skills/edit-design/SKILL.md:835-836` (convergence claim); `:821-826` (resume glob).
- **Actual signature/role**: ":835 'so the loop moves monotonically toward dual-clean — typically one or two rounds.'" Resume block at :821-826: "**Resume after a mid-loop context-clear.** … On a resume mid-loop, re-derive the round count from the latest per-round params files written under `_workflow/plan/` (each round writes one)."
- **Verdict**: MATCHES
- **Detail**: Both current-state claims confirmed. The glob reads `_workflow/plan/` today (relevant to claims 5 and 6 — the relocation target).

### Ref: create-plan Step 4b item 9 track-path analog + same readability-auditor agent (claim 4)
- **Document claim**: design.md §"Both paths get the convergence fix" / §"Track-path anchor stability": today create-plan Step 4b item 9 calls its loop "the track-path analog of the `edit-design phase1-creation` loop, parameterized to `target=tracks`" and spawns the SAME `readability-auditor` agent (one spawn per `track-N.md`); items 1-8 settle the plan Component Map and track skeletons before item 9.
- **Search performed**: `grep -n "track-path analog\|target=tracks"` + `Read` of `.claude/skills/create-plan/SKILL.md` lines 758-841.
- **Code location**: `.claude/skills/create-plan/SKILL.md:773-776` (analog), `:810-816` (per-file slice / same agent), `:769-776` (items 1-8 settle first).
- **Actual signature/role**: ":773 'This is the track-path analog of the `edit-design phase1-creation` loop (`edit-design/SKILL.md` § Workflow, § Step 6), parameterized to `target=tracks`.'" ":810-816 'The **`readability-auditor`** (`subagent_type: readability-auditor` …) … **Slice the track-path fan-out per track file: one `readability-auditor` spawn per `plan/track-N.md` (in track-number order)** …'" ":769-770 'Items 1-8 above settle the decisions, the track boundaries, the Decision Records, and the section homes; this item hands that settled shape to the `design-author` spawn …'"
- **Verdict**: MATCHES
- **Detail**: All three sub-claims confirmed. Note the live track path ALREADY states a per-file deterministic partition (`:812-821`), so D5's "the mechanism is identical, only two parameters differ" is consistent with the live track-path slicing being per-file (vs design-path per-window).

### Ref: edit-design Step 4 params home + phase4-creation comprehension output_path home (claim 5)
- **Document claim**: design.md §"File relocation to `_workflow/reviews/`": today edit-design Step 4 writes per-spawn params files "under `_workflow/plan/`", and the comprehension gate's `phase4-creation` `output_path` is also "under `_workflow/plan/`".
- **Search performed**: `grep -n "_workflow/plan/"` over `.claude/skills/edit-design/SKILL.md`.
- **Code location**: `.claude/skills/edit-design/SKILL.md:524` (params home), `:624` (phase4 output_path home).
- **Actual signature/role**: ":524 '… to a params file under `_workflow/plan/` (one file per spawn), and pass only that file's path in the spawn prompt.'" ":624 '- output_path: <abs path under `_workflow/plan/` for the cold-read output>'".
- **Verdict**: MATCHES
- **Detail**: Both current homes are `_workflow/plan/`, exactly as the relocation source describes. (Target: both move to `_workflow/reviews/` — not asserted of the live file.)

### Ref: conventions-execution.md §2.5 Third-scope review-file home (claim 6)
- **Document claim**: design.md §"File relocation"; D6: today §2.5's "Third-scope review-file home (Phase 0→1 gate)" defines `docs/adr/<dir-name>/_workflow/reviews/` and scopes it to "the Phase-0→1 gate's files".
- **Search performed**: `grep -n "Third-scope\|_workflow/reviews/"` + `Read` of `.claude/workflow/conventions-execution.md` lines 707-732.
- **Code location**: `.claude/workflow/conventions-execution.md:707` (heading), `:715` (directory), `:710-713` (scope).
- **Actual signature/role**: ":707 '### Third-scope review-file home (Phase 0→1 gate)'"; ":715 'docs/adr/<dir-name>/_workflow/reviews/'"; ":710-713 'The research-log adversarial gate writes its review files **before Step 4b** … The gate's files therefore live in a plan-scoped review directory under `_workflow/` …'". TOC row :17 summary: "Where the Phase-0→1 gate's review files live before any track directory exists".
- **Verdict**: MATCHES
- **Detail**: Directory and Phase-0→1-gate scoping confirmed. (Target: D6 generalizes the scope to "Phase-1 plan-scoped review scaffolding" — not asserted of the live file.)

### Ref: readability-auditor.md "Range-sliced fan-out" description + no guard + no slice_count/total_lines + read-scope (claim 7)
- **Document claim**: design.md §"The agent-side whole-doc guard" / track D2/D3: today "Range-sliced fan-out" is a description (not a hard requirement); the agent has NO whole-doc guard and NO `slice_count`/`total_lines` params; its S1 read-scope bars it from reading anything but house-style, its slice, and standing anchors.
- **Search performed**: full `Read` of `.claude/agents/readability-auditor.md` (109 lines).
- **Code location**: `.claude/agents/readability-auditor.md:3` (frontmatter description), `:25` (description body), `:75-83` (Inputs/params), `:28-29` + `:64-69` + `:71-73` (read-scope).
- **Actual signature/role**: Frontmatter :3 ends "… Range-sliced fan-out. Spawned by edit-design …" (a descriptive trailing sentence). Body :25 "You reuse the `readability-feedback` audit contract: a range-sliced fan-out where each slice is obligated to enumerate every finding in its range." Inputs :80-82 list exactly `target`, `target_path`, `range` — no `slice_count`, no `total_lines`. No whole-doc-guard text anywhere (`grep` for guard/whole-doc/collapse/wiring returns nothing). Read-scope :28-29 "Your tool allow-list is `Read` plus `Grep` … the only paths you read are `house-style.md`, your document slice, and the standing anchors"; :71-73 "House-style reads — Read only the cited `§ <heading>` section".
- **Verdict**: MATCHES
- **Detail**: All four sub-claims confirmed: "range-sliced" is a description, no guard, only 3 params, read-scope = house-style + slice + anchors. (Target: hard requirement + guard + 2 new params — not asserted of the live file.)

### Ref: Core Concepts standing-anchor conditional seeding rule (claim 8)
- **Document claim**: design.md §"The anchor-folded content hash": standing anchors on the design path are `## Overview` + `## Core Concepts`; Core Concepts is seeded only conditionally ("when the doc has Parts or introduces ≥3 new domain terms").
- **Search performed**: `grep -niE "Core Concepts|≥3 new domain|3 new domain|has Parts"` over `design-document-rules.md` and `edit-design/SKILL.md`.
- **Code location**: `.claude/workflow/design-document-rules.md:600-601` (conditional rule); `.claude/skills/edit-design/SKILL.md:222-223` (author seeding); `.claude/agents/readability-auditor.md:66` (anchors).
- **Actual signature/role**: design-document-rules.md :600-601 "Designs with no `# Part N` headings AND fewer than 3 new domain terms can skip Core Concepts." edit-design :222-223 "Core Concepts (when the doc will have Parts or ≥3 new domain terms)". readability-auditor.md :66 "**`target=design`** — the `## Overview` and `## Core Concepts` sections of `design.md`."
- **Verdict**: MATCHES
- **Detail**: The conditional ("Parts OR ≥3 new domain terms") matches design-document-rules.md's inverted form ("no Parts AND fewer than 3 terms → skip") and edit-design's positive form verbatim. Standing-anchor set (`Overview` + `Core Concepts`) confirmed in the agent file.

### Ref: conventions §1.7(k) criterion 2 — executable-procedure disqualifier naming the step-implementation loop (claim 9)
- **Document claim**: design.md §"Meta: tier and §1.7 routing" / track D7: today §1.7(k) criterion 2 disqualifies a plan from the prose-rule opt-out when a running phase reads its edited files as executable procedure, and the criterion explicitly names "the step-implementation orchestration loop".
- **Search performed**: `grep -n "1.7(k)\|step-implementation orchestration loop\|executable procedure"` + `Read` of `.claude/workflow/conventions.md` lines 1322-1376.
- **Code location**: `.claude/workflow/conventions.md:1362-1372` ("Opt-out criteria"), `:1368-1372` (criterion 2).
- **Actual signature/role**: ":1368-1372 '2. Every edited file's in-branch consumer is **judgment-layer** … Files a running phase reads as executable procedure (the implementer rulebook's gate sequence, **the step-implementation orchestration loop**, the migrate replay) stay staged even on an otherwise-qualifying plan.'"
- **Verdict**: MATCHES
- **Detail**: Criterion 2 disqualifies on the executable-procedure consumer test and names "the step-implementation orchestration loop" verbatim, exactly as D7 asserts. Confirms the branch's full-staging routing rationale.

### Ref: edit-design gate-A7 cache warm-up fixed delay (claim 10)
- **Document claim**: design.md §"Warm-up is severed from slicing": today edit-design describes a fixed delay between the first auditor spawn and the rest so later spawns hit a warm prefix.
- **Search performed**: `Read` of `.claude/skills/edit-design/SKILL.md` lines 529-547 (§"Fan-out cache warm-up (D13, the tunable cost lever)").
- **Code location**: `.claude/skills/edit-design/SKILL.md:529-547`.
- **Actual signature/role**: ":531-534 'spawn one auditor, wait a short fixed delay (the warm-up delay, default about a minute) for its cold prefix write to land and propagate, then spawn the rest concurrently against the now-warm prefix.'" ":538-539 'use whatever non-blocking fixed delay the harness offers between the first spawn and the rest; if no such delay mechanism is available, disable the warm-up and pay N cold prefixes.'" Labelled "gate A7" implicitly via "(gate A7)" at :537.
- **Verdict**: MATCHES
- **Detail**: The fixed-delay warm-up between the first spawn and the rest is confirmed, and the live text already frames it as a cost lever independent of slice count ("disable the warm-up and pay N cold prefixes") — consistent with the design's "warm-up is severed from slicing".

### Invariant: S1 (auditor reads no log/no settled-state), S4 (one prose owner per surface), I6 (running phase never reads half-modified workflow) (claim 11)
- **Document claim**: S1, S4, I6 are real, currently-defined invariants in the live workflow/agent files the design cites.
- **Code evidence**: S1 — `.claude/agents/readability-auditor.md:27-29` ("### You never read the research log (S1) — This is a hard invariant. Your tool allow-list is `Read` plus `Grep` — no PSI, no log path is passed to you …"). S4 — `.claude/agents/readability-auditor.md:58` ("You own the prose AI-tell axis on every surface where prose is judged. The comprehension reviewer runs it nowhere — that is the one-owner-per-surface invariant (S4)"); corroborated at `prompts/design-review.md:201-203` ("The one-owner-per-surface rule (S4) holds: every … and never neither") and `design-document-rules.md:484`. I6 — `.claude/workflow/conventions.md:1217-1239` ("### (g) The I6 invariant … Live workflow stays at develop state for the whole branch until promotion").
- **Mechanism**: S1 enforced by the auditor's `Read`,`Grep`-only allow-list and the no-log-path params contract. S4 enforced by the auditor owning the prose axis and the comprehension reviewer running it nowhere ("never neither"). I6 enforced by §1.7(g): promotion at Phase 4 is the only intra-branch authoring transition that moves staged `.claude/**` content live.
- **Verdict**: ENFORCED
- **Detail**: All three are real, currently-defined invariants at the cited homes. Note design.md §"Meta" cites I6 as "§1.7(g)" — the live §1.7(g) heading is literally "### (g) The I6 invariant", so the citation resolves correctly (no finding). S4's home is readability-auditor.md/design-review.md (the comprehension/de-warm rules), matching the design's citation. S2 (the sanctioned absorption/authoring log read) corroborated at edit-design Step 4 role table :507-511.

### Ref: design-document-rules.md cold-read-mechanics slicing statement (claim 12) — NOT FOUND
- **Document claim**: design.md §"The agent-side whole-doc guard" + track D2: `design-document-rules.md` carries a cold-read-mechanics slicing statement that the change will "keep in sync by reference".
- **Search performed**: `grep -niE "slic|sub-agent|auditor|fan.out|window|spawn|~200|line range|partition"` over `.claude/workflow/design-document-rules.md` (1040 lines); `Read` of the only candidate region, `### Cold-read sub-agent prompt` (lines 344-493).
- **Code location**: NOT FOUND. The candidate region (`:344-353` cold-read sub-agent prompt template; `:471-487` design-sync de-warmed cold-read + readability-auditor block) carries no slice/window/range/fan-out/partition mechanic — only generic "fresh sub-agent reads the doc without context" prose and the design-sync prose-owner (S4) wiring.
- **Actual signature/role**: ":347-352 'The cold-read half of the auto-review is implemented by the prompt at prompts/design-review.md … The prompt instructs a fresh sub-agent to read the design document without context, answer comprehension questions, and report structural findings.'" No slicing statement anywhere in the file.
- **Verdict**: NOT FOUND
- **Detail**: The design's wording is hedged ("**any** cold-read-mechanics slicing statement … that must stay in sync"), so the absence is not a flat contradiction, but the plan/design name this file as one of six in-scope files with a "keep in sync by reference" obligation that has no current anchor. → CR1 (suggestion / design-decision).

### File-existence sweep — Component Map's six files
- **Document claim**: implementation-plan.md `## Component Map` names six files and a control-flow protocol; each plays the described role.
- **Search performed**: `[ -f ]` test over all six paths.
- **Code location**: all six EXIST — `.claude/skills/edit-design/SKILL.md`, `.claude/agents/readability-auditor.md`, `.claude/workflow/conventions-execution.md`, `.claude/skills/create-plan/SKILL.md`, `.claude/workflow/design-document-rules.md`, `.claude/skills/readability-feedback/SKILL.md`.
- **Actual signature/role**: edit-design (design-path home, Step 4/6), readability-auditor.md (the cold auditor), conventions-execution.md §2.5 (review-file home), create-plan Step 4b item 9 (track-path home), design-document-rules.md (cold-read rules — see CR1 on the slicing-statement role), readability-feedback (the ~200-line partition source). Five of six roles MATCH the Component-Map description against live content (verified in the Ref certificates above); design-document-rules.md's described "slicing sync ref" role is the CR1 NOT-FOUND case.
- **Verdict**: PARTIAL
- **Detail**: All six paths exist and five play exactly the described roles; design-document-rules.md does not yet carry the slicing reference the Component Map's `DDR -.cross-references.-> ED` edge implies → CR1.
