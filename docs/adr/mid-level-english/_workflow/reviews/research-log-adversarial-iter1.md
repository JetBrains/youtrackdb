<!-- MANIFEST
findings: 4
severity: should-fix
evidence_base: 6 certificates (4 decision challenges, 1 assumption test, 1 open-question check); all reference checks via grep+Read (mcp-steroid unreachable this session); #1142 verified via git show --stat f74ef47e94
cert_index: C-A1, C-A2, C-A3, A-A1, OQ-resolved, C-A4
flags: workflow-machinery-lens; reference-accuracy-caveat (grep-only, no PSI)
index:
- id: A1
  sev: should-fix
  anchor: "### A1 "
  loc: research-log.md DR2/DR3 + Surprises (blast-radius enumeration)
  cert: A-A1
  basis: assumption
- id: A2
  sev: should-fix
  anchor: "### A2 "
  loc: research-log.md DR3 + DR5
  cert: C-A2
  basis: decision
- id: A3
  sev: suggestion
  anchor: "### A3 "
  loc: research-log.md DR4
  cert: C-A3
  basis: decision
- id: A4
  sev: suggestion
  anchor: "### A4 "
  loc: research-log.md DR5 (move a) + DR1
  cert: C-A4
  basis: decision
-->

## Findings

### A1 [should-fix]
**Certificate**: A-A1 (assumption test — blast-radius file inventory)
**Target**: Assumption behind DR2, DR3, and the "≈51 files" Surprises entry — the file set the flip touches is counted correctly.
**Challenge**: Two of the inventory's load-bearing numbers are wrong, and the error points the same way the precedent it cites already settled. The Surprises entry and DR3 both say "12 prompts in `.claude/workflow/prompts/`"; `ls .claude/workflow/prompts/*.md` returns 11, and #1142 (the four-to-five flip this branch mirrors) touched all 11. There is no 12th prompt. Separately, DR2 and DR3 list `CLAUDE.md` among the touched files, but `CLAUDE.md` was not in #1142's diff (`git show --stat f74ef47e94` has no `CLAUDE.md` row; its last edits are `d8c5c965bd`, `972abae8ed`, `a37db81832`, all before #1142). `CLAUDE.md:104` names the subset as a four-item parenthetical (banned vocabulary, banned sentence patterns, banned analysis patterns, em-dash discipline) and still omits Orientation today — the four-to-five flip deliberately left it as an illustrative, non-canonical list. A five-to-six flip that "follows the #1142 precedent" must decide the same way: either `CLAUDE.md:104` is a flip target (then it currently lags by one section, and this branch fixes a pre-existing gap while adding the sixth) or it is illustrative (then it is not in the blast radius at all and DR2/DR3 should drop it). The log asserts the precedent settles the opt-out but inherits an inventory the precedent contradicts.
**Evidence**: `ls .claude/workflow/prompts/*.md` = 11; `git show --stat f74ef47e94` = 57 files, 0 `CLAUDE.md`, 11 prompts, 18 agents, 6 skills; `CLAUDE.md:104` parenthetical lists four sections, `grep -c orientation CLAUDE.md` = 0; canonical subset block `house-conversation.md:21-27` lists five. Reference-accuracy caveat: counts are grep/`ls`-based (mcp-steroid unreachable); a path glob and a commit stat are textual facts grep reads reliably, so PSI would not change the verdict.
**Proposed fix**: Correct "12 prompts" to 11. Make the `CLAUDE.md:104` decision explicit in DR3: classify the parenthetical as canonical-flip (and note it currently lags at four, so the flip lands the missing Orientation plus the new section) or as illustrative (and drop `CLAUDE.md` from the DR2/DR3 touched-file list, keeping only the §1.5 cross-reference). Re-derive the "≈51" total from the corrected set rather than carrying the explanation-style as-built 55 by analogy.

### A2 [should-fix]
**Certificate**: C-A2 (decision challenge — DR3 reach + DR5 section content)
**Target**: DR3 (the rule joins the subset "with natural reach to ... `*.java`/`*.kt` code comments ... No carve-out") and DR5 (section content).
**Challenge**: The "no carve-out, natural code-comment reach" claim skips the restatement the Orientation precedent itself needed, so the decision survives but its rationale is incomplete. DR3 argues the new rule reaches code comments "the same reach `## Orientation` already has." But Orientation does not reach code comments uniformly — `conventions.md:574-581` restates it for the `*.java`/`*.kt` Tier-B surface because the literal test ("too terse to follow without opening the code") does not transfer to a reader who has the file open. The Tier-B restatement re-points Orientation to "out-of-file assumptions, not in-file terseness." A plain-language rule has the same transfer problem: moves like "expand a non-floor acronym on first use" and "prefer the common general-English word" read differently in a one-line `// why` comment than in a design paragraph, and "short sentences, one idea each" is near-meaningless for comment fragments. DR3's precedent is therefore evidence *for* a per-surface restatement, not against a carve-out. The decision to join the subset is right; the claim that it needs no Tier-B-specific text is the weak part.
**Evidence**: `conventions.md:567` Tier-B row lists the five slugs; `conventions.md:574-581` is the explicit Orientation restatement for code comments ("This bans out-of-file assumptions, not in-file terseness"); `house-style.md:56` says Orientation "applies to every prose surface" yet `:574` still restates it for Tier-B. Reference-accuracy caveat: these are anchor/line reads in named workflow files, verified by Read; no symbol resolution involved.
**Proposed fix**: Add to DR5 (or a new DR) the code-comment form of the plain-language rule, parallel to the `conventions.md:574-581` Orientation restatement: which moves apply at comment scale (common-word preference, acronym expansion, no idiom) and which do not (sentence-length / clause-nesting, which the comment surface cannot meaningfully carry). State whether the §1.5 Tier-B prose at `:574` gains a plain-language paragraph alongside the Orientation one.

### A3 [suggestion]
**Certificate**: C-A3 (decision challenge — DR4 opt-out for the two execution-procedure files)
**Target**: DR4 — the §1.7(k) opt-out covers `step-implementation.md` and `implementer-rules.md` because their edit is "a style citation, not a gate-sequence change."
**Challenge**: §1.7(k) criterion 2 explicitly excludes execution-procedure files ("the step-implementation orchestration loop ... stay staged even on an otherwise-qualifying plan", `conventions.md:1229-1237`), so a reviewer should test whether naming these two files in DR4 quietly violates the opt-out's own boundary. It does not. The criterion keys on *what part of the file* the edit touches: a gate-sequence or schema change must stage; a prose citation need not. The subset enumeration in both files is prose — `step-implementation.md:1038-1041` and `implementer-rules.md:1100-1105` list the slugs as a "section slugs to apply" sentence, not a parsed control structure. #1142 confirms by construction: it edited both files live under the same opt-out and shipped. The decision holds.
**Evidence**: `conventions.md:1223-1237` (two-criteria test, execution-procedure exclusion in criterion 2); `step-implementation.md:1038-1041` and `implementer-rules.md:1100-1105` (enumeration is a prose "slugs to apply" sentence); `git show --stat f74ef47e94` lists both files. Reference-accuracy caveat: the "is this prose or a parsed gate" judgment rests on reading the two enumeration blocks, done via Read; no caller graph needed.
**Proposed fix**: None required. Optionally tighten DR4 to cite the criterion-2 *consumer-class* test by line (`conventions.md:1229-1237`) rather than the general "style citation" phrasing, so the boundary the opt-out draws is visible in the rationale.

### A4 [suggestion]
**Certificate**: C-A4 (decision challenge — DR5 move (a) overlaps Banned vocabulary)
**Target**: DR5 move (a) ("prefer the common general-English word ... use/utilize, about/regarding, help/facilitate") and DR1 (plain-language, judgment-only).
**Challenge**: Move (a) governs word choice, which Banned vocabulary already governs, so the two rules can both fire on one word with no stated precedence. `leverage→use` is already a Tier-2 entry (`house-style.md:90`); DR5's example `use/utilize` sits in the same lexical space but `utilize` is in no tier. A reviewer reading two sections that both say "prefer the plainer word" gets no rule for which one owns a given word, or whether move (a) is meant to extend the AI-tell list toward general word-frequency (which DR1's "no measurement" stance and the Banned-vocabulary framing — "AI-anomalous frequency", `house-style.md:80` — would resist). The decision survives because the two have different scopes (Banned vocabulary targets AI tells; plain language targets reader proficiency) and DR5's boundary clause already separates general English from technical terms. But the section-to-section relationship is undocumented.
**Evidence**: `house-style.md:90` (Tier 2 `leverage (use "use")`); `house-style.md:80` (Banned vocabulary scoped to "AI-anomalous frequency"); `house-style.md:84,90,96` tiers contain no `utilize`/`facilitate`; DR5 "Reconciliation" addresses only `## Voice and tone`, not `## Banned vocabulary`. Reference-accuracy caveat: tier membership checked by reading the four tier lists; textual, no PSI dependence.
**Proposed fix**: Add one sentence to DR5 (or the new section) drawing the Banned-vocabulary boundary, parallel to the existing Voice-and-tone reconciliation: Banned vocabulary owns the closed AI-tell word list; plain language owns general-English word choice outside it, and never re-bans a word a tier already covers.

## Evidence base

#### C-A1 (folded into A-A1)
See A-A1 — the blast-radius challenge is an assumption test, not a decision challenge.

#### A-A1: Assumption test — the flip's file inventory is counted correctly
- **Claim**: DR2/DR3 and the Surprises "≈51 files" entry rest on an accurate touched-file set: "12 prompts", `CLAUDE.md` among the flip targets, modeled on the explanation-style as-built 55.
- **Stress scenario**: Re-count the directories the flip touches and re-check each named file against #1142, the precedent the log says settles the opt-out and the inventory shape.
- **Code evidence**: `ls .claude/workflow/prompts/*.md` = 11 (not 12); `git show --stat f74ef47e94` = 57 files / 0 `CLAUDE.md` / 11 prompts / 18 agents / 6 skills; `CLAUDE.md:104` lists four subset sections, `grep -c orientation CLAUDE.md` = 0 (Orientation never synced into it by the four-to-five flip); canonical subset block `house-conversation.md:21-27` = five sections; current agent count `ls .claude/agents/*.md` = 20 (DR3's "20 agents" is correct; #1142 touched 18 of them).
- **Verdict**: BREAKS on two specifics (prompt count, `CLAUDE.md` status); HOLDS on the agent count. The "20 agents" figure is right; "12 prompts" and the unqualified `CLAUDE.md` flip-target are wrong, and the `CLAUDE.md:104` four-item parenthetical shows the precedent treated that site as non-canonical — a fact the log's "follows #1142" framing has to reconcile.

#### C-A2: Decision challenge — DR3 "no carve-out, natural code-comment reach"
- **Chosen approach**: The new rule joins the subset with the same reach Orientation has and needs no code-comment carve-out (DR3).
- **Best rejected alternative**: Join the subset but author a Tier-B-specific restatement of which plain-language moves apply at comment scale, exactly as Orientation did.
- **Counterargument trace**:
  1. DR3 says the reach is "the same reach `## Orientation` already has" and infers no carve-out is needed.
  2. Orientation's reach is *not* uniform: `conventions.md:574-581` restates it for `*.java`/`*.kt` because the literal "open the code" test does not transfer to a file-open reader, re-pointing it to "out-of-file assumptions, not in-file terseness."
  3. Plain-language moves split the same way — common-word/acronym/idiom moves transfer to comments; sentence-length and clause-nesting moves largely do not — so the precedent argues for a restatement, producing a more complete rule than DR3's "no carve-out."
- **Codebase evidence**: `conventions.md:574-581` (the Orientation Tier-B restatement); `house-style.md:56` ("applies to every prose surface") coexisting with that restatement.
- **Survival test**: WEAK. Joining the subset is right; the "no special code-comment text needed" claim is under-argued and contradicted by how Orientation itself reaches that surface.

#### C-A3: Decision challenge — DR4 opt-out covers the two execution-procedure files
- **Chosen approach**: §1.7(k) opt-out covers `step-implementation.md` + `implementer-rules.md`; their edit is a style citation, not a gate-sequence change (DR4).
- **Best rejected alternative**: Hybrid — stage only those two execution-procedure files per §1.7(k) criterion 2, edit the rest live.
- **Counterargument trace**:
  1. §1.7(k) criterion 2 (`conventions.md:1229-1237`) forces execution-procedure files to stage "even on an otherwise-qualifying plan", and names the step-implementation loop and implementer rulebook explicitly.
  2. The criterion keys on the *part touched*: a gate-sequence/schema edit stages; a prose citation does not. The subset enumeration in both files (`step-implementation.md:1038-1041`, `implementer-rules.md:1100-1105`) is a "slugs to apply" prose sentence, parsed by no phase.
  3. #1142 edited both files live under the same opt-out and merged, so the live-edit path is demonstrated, not hypothesized.
- **Codebase evidence**: `conventions.md:1223-1237`; the two enumeration blocks; `git show --stat f74ef47e94`.
- **Survival test**: YES. The hybrid is the wrong call here because the edit is prose, not the gated part.

#### OQ-resolved: Open-question check — Q1-Q4 marked resolved
- **Claim**: The log's `## Open Questions` Q1-Q4 are resolved into the Decision Log (research-log.md:46) and none still blocks a load-bearing decision.
- **Check**: Q1→DR1, Q2→DR5, Q3→DR3, Q4→DR4 — each open question maps to a Decision Log entry that decides it, and the resolution note at `:46` states the mapping. No `## Open Questions` entry remains unresolved.
- **Verdict**: HOLDS. No open-question finding (the prompt's "verify Q1-Q4 resolved" check passes); per the gate's open-question rule an unresolved load-bearing question would be a should-fix, but none is unresolved.

#### C-A4: Decision challenge — DR5 move (a) vs Banned vocabulary
- **Chosen approach**: DR5 move (a) prefers the common general-English word (use/utilize, about/regarding, help/facilitate); DR1 keeps it judgment-only.
- **Best rejected alternative**: Document the precedence between move (a) and the existing Banned vocabulary tiers so one word is not governed by two rules with no owner.
- **Counterargument trace**:
  1. `leverage→use` is already Tier-2 (`house-style.md:90`); DR5's `use/utilize` lives in the same lexical space, `utilize` in no tier.
  2. Two sections both say "prefer the plainer word" with no stated precedence, and Banned vocabulary is scoped to AI tells ("AI-anomalous frequency", `house-style.md:80`), not general word-frequency, so their relationship needs a boundary.
  3. The decision survives on scope difference (AI tell vs reader proficiency) and DR5's existing general-vs-technical boundary clause, but the section-to-section relationship is undocumented.
- **Codebase evidence**: `house-style.md:80,84,90,96`; DR5 reconciles only with Voice-and-tone.
- **Survival test**: YES (suggestion). Holds, but adding the Banned-vocabulary boundary mirrors the reconciliation DR5 already wrote for Voice-and-tone and prevents a future "which rule owns this word" ambiguity.
