<!-- workflow-sha: c99af024a0 -->
# Adversarial gate — research log (Phase 0→1), iteration 2

## Manifest

- role: reviewer-adversarial
- scope: research-log (Phase 0→1)
- target: docs/adr/ai-tells-shrinkage/_workflow/research-log.md
- matched_categories: Workflow machinery
- mode: verdict-producer (re-review of iteration-1 findings + new findings)
- verdict: PASS
- iter1_verdicts: { A1: VERIFIED, A2: VERIFIED, A3: VERIFIED, A4: VERIFIED, A5: VERIFIED, A6: VERIFIED }
- findings: 1
- counts: { blocker: 0, should-fix: 0, suggestion: 1 }
- index:
  - { id: A7, sev: suggestion, anchor: "### A7", target: "Gate iteration-1 resolution A1 (consumer-inventory count)", cert: "Assumption test: the cited '30 files (18 agents + 12 workflow)' figure bounds the inventory the acceptance grep must cover", basis: "research-log.md:187-189; live grep counts 40 files (20 agents + 20 workflow) carrying the verbatim backtick-slug enumeration" }
- evidence_base: 6 iteration-1 re-verifications (all VERIFIED against the revised Decision Log and live files) + 1 assumption test producing the single new suggestion.

## Iteration-1 verdicts

### A1 — VERIFIED
**Was:** should-fix — consumer inventory lived in a Surprise, not a gated decision, on a tier with no structural review.
**Resolution:** The revised log adds `### Gate iteration-1 resolutions` → A1, promoting the inventory to the **track acceptance contract** (`research-log.md:184-210`). It names the canonical source (`house-style.md` + `conventions.md §1.5` table at `:621` + rename-safety grep at `:626`), the replicated bootstrap-slug files, the checker/tests/fixture triple, the always-loaded skill description, the hook, the chat style, and seven named prose consumers. It makes the §1.5 rename-safety grep the operative mechanism ("for every hit, either edit it ... or record it as confirm-benign") and adds a manual paraphrase scan for the grep's blind spot.
**Basis (live re-check):** The grep mechanism is sound and the named consumers all exist (`design-document-rules.md`, `design-author.md`, `readability-auditor.md`, `review-workflow-writing-style.md`, `prompts/design-review.md`, `readability-feedback/SKILL.md`, `CLAUDE.md` — all confirmed present). `house-style-write-reminder.sh` exists at the cited path and is the only hook referencing the slugs (confirmed). Resolved as a gated contract; the residual count discrepancy is the new A7 below, a suggestion, not a re-open of A1.

### A2 — VERIFIED
**Was:** should-fix — the §1.7(k) opt-out leaned on calling executable `design-mechanical-checks.py` "judgment-layer".
**Resolution:** The staging decision (`research-log.md:155-160`) now leads with the no-invocation fact: "The opt-out's §1.7(k)-criterion-2 basis is the no-invocation fact, not a 'judgment-layer' reclassification of executable code ... no phase this branch runs executes `design-mechanical-checks.py` (`create-plan/SKILL.md` has zero references to it; `edit-design` Step 3 runs it but this minimal-tier branch authors no `design.md`)."
**Basis (live re-check):** Matches `conventions.md:1368-1372` criterion (2) — the test is executable-procedure *consumption by a running phase*, and a minimal-tier create-plan branch that authors no `design.md` invokes neither the create-plan path (zero refs, confirmed in the log's own Surprise at `:318`) nor the edit-design Step-3 path. The justification now rests on the correct basis.

### A3 — VERIFIED
**Was:** should-fix — removing `§ Banned vocabulary` / `§ Em-dash discipline` orphaned the §1.5 Tier-B six-section subset and the chat subset.
**Resolution:** New resolution A3 (`research-log.md:211-224`) states the post-shrink subset is **four sections** (`§ Orientation`, `§ Plain language`, `§ Banned sentence patterns`, `§ Banned analysis patterns`, the latter two minus removed bullets), to be updated at `conventions.md:621`, the replicated slug lines, and `house-conversation.md` in one commit. It adds the chat-only carrier: an inline "no sycophantic openers, no signposting" rule in `house-conversation.md`, since the bullets those guards pointed at leave the doc surface.
**Basis (live re-check):** The §1.5 table at `conventions.md:621` does enumerate exactly the six sections, and `house-conversation.md:19-30` mirrors them; dropping the two named sections yields the stated four. The inline chat-carrier resolves the dangling-pointer problem the doc-removed/chat-retained split (10:04) otherwise created.

### A4 — VERIFIED
**Was:** should-fix — the always-loaded `ai-tells/SKILL.md:3` description was not named as an edit target.
**Resolution:** New resolution A4 (`research-log.md:225-231`) adds it to the inventory with an explicit keep/drop call: drop the removed-tell names ("delve", "foster", "em dash overuse", "knowledge-cutoff disclaimers"), keep the surviving ones (negative parallelism "It's not X, it's Y", Title Case headings).
**Basis (live re-check):** `SKILL.md:3` is the always-loaded `description`; the keep/drop split matches the REMOVE set (negative parallelism and Title Case are KEPT, so retaining them in the description is correct). Literal-text fact, no symbol-audit risk.

### A5 — VERIFIED
**Was:** suggestion — the banned-vocab fold risked duplicating the Tier-2 examples and left the `:92` reconciliation subsection dangling.
**Resolution:** New resolution A5 (`research-log.md:232-238`) records the fold is a **move not a copy** (the Tier-2 examples at `:110` move into `§ Plain language`, since Tier 2 is deleted), and the `§ Plain language` reconciliation subsection at `:92` is **removed**, not reworded.
**Basis (live re-check):** `house-style.md:110` carries the two examples and `:92` is the reconciliation subsection whose sole purpose is dividing labour with the deleted section; move-and-remove is the correct disposition.

### A6 — VERIFIED
**Was:** suggestion — classifying curly quotes as concealment-only was contestable under the project's own keep-test.
**Resolution:** New resolution A6 (`research-log.md:239-248`) moves curly quotes from REMOVE to **KEEP**, on the tooling/ecosystem rationale (straight quotes keep grep and code-fence matching working), explicitly outside the concealment axis. The Final REMOVE-set entry at `:114-118` is edited to drop curly quotes and cross-reference the resolution. Knowledge-cutoff stays REMOVE.
**Basis (live re-check):** `house-style.md:348-349` states the rationale as ecosystem straight-quote consistency, which is the tooling-hygiene basis the keep-test retains. The Final-REMOVE-set entry and the 10:04 global-removes sentence both still need a same-commit consistency pass during implementation (the 10:04 entry at `:138-140` still lists "curly quotes" among global removes — see A7's sibling note), but the decision itself is now correct and gated.

## Findings

### A7 [suggestion]
**Certificate**: Assumption test — the cited "30 files (18 `.claude/agents/*.md` + 12 `.claude/workflow/**`)" figure bounds the inventory the acceptance grep must cover.
**Target**: Gate iteration-1 resolution A1 (`research-log.md:187-189`), the replicated-bootstrap-slug count.
**Challenge**: The A1 resolution is correct in design — it makes the §1.5 rename-safety grep the operative mechanism and treats every hit as an edit-or-confirm-benign obligation. But it pins a specific count ("the 'six AI-tell subset section slugs' enumeration is replicated verbatim in 30 files — 18 `.claude/agents/*.md` review agents + 12 `.claude/workflow/**` prompts/skills"). The live count is higher. A grep for the verbatim backtick-slug enumeration (`` `## Banned vocabulary` ``) returns 40 files (20 agents + 20 workflow); a grep for the "six AI-tell" phrasing returns 33. On a tier whose only safety net is this contract, an author who reads "30" as the target rather than running the grep could stop short, leaving phantom references in the ~10 uncounted files (for example `.claude/agents/review-workflow-writing-style.md`, `.claude/agents/dr-audit.md`, `.claude/workflow/implementer-rules.md`, `.claude/workflow/review-mode.md`, `.claude/workflow/workflow.md`).
**Stress scenario**: The track author treats the inventory as a 30-item checklist and edits 30 files; the remaining replicated copies keep `### Em-dash discipline` pointing at a removed heading. No structural review catches it (minimal tier), so it ships.
**Code evidence**: `grep -rlE '\`## Banned vocabulary\`' .claude/agents .claude/workflow` → 40 files (20 + 20). `grep -rlE 'six AI-tell|AI-tell subset section slugs|six Tier-B section'` → 33 files. Either count exceeds the cited 30. mcp-steroid unreachable, so this is a grep-derived count; the residual risk runs the other way (a paraphrase the grep misses would raise the count further, not lower it), which strengthens the finding rather than weakening it.
**Verdict**: FRAGILE — the contract mechanism (the grep) is sound and survives, but the cited count understates the blast radius and could anchor an author to a short list.
**Proposed fix**: Replace the "30 files (18 + 12)" figure with "the live grep output, currently ~40 files" or drop the numeric pin entirely, so the contract is unambiguously "every grep hit", not a fixed count. The exact number is implementation-time noise; what matters is that the inventory is defined by the grep, not by a frozen tally. This does not gate — the mechanism already covers the gap — but recording it keeps the contract honest about its own scope.
