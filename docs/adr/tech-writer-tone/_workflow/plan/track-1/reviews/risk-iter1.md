<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 5, suggestion: 2}
index:
  - {id: R1, sev: should-fix, loc: ".claude/scripts/tests/test_dsc_ai_tell.py:59", anchor: "### R1 ", cert: C1, basis: "staged Python tests resolve REPO_ROOT via parents[3]; under staged-workflow the docs/adr calibration + frozen-design inputs dangle, so the suite fails on missing-input, not logic — blocks the track's green-suite acceptance"}
  - {id: R2, sev: should-fix, loc: ".claude/scripts/design-mechanical-checks.py:49", anchor: "### R2 ", cert: C2, basis: "track item 7 adds lowercase 'summary' to SHAPE_EXEMPT_SECTION_NAMES, but is_shape_exempt matches title raw/case-sensitively; a Part-level ## Summary would not be exempted -> spurious per-section-shape blocker"}
  - {id: R3, sev: should-fix, loc: ".claude/scripts/tests/dsc-ai-tell-fixture.md:39", anchor: "### R3 ", cert: C3, basis: "shared fixture; deleting the 3 removed patterns' positive blocks renumbers surviving line-anchored assertions (ANCHORED 79/130, NEGATIVE_RANGES 115-126) -> build-time failure on survivors if not renumbered"}
  - {id: R4, sev: should-fix, loc: ".claude/agents/review-workflow-writing-style.md:29", anchor: "### R4 ", cert: C4, basis: "removed rules recur at multiple sites within one consumer file (writing-style reviewer: 5 hits at 28/29/34/71/188); a partial edit leaves the Phase-C reviewer enforcing a rule house-style no longer bans -> rule contradiction"}
  - {id: R5, sev: should-fix, loc: ".claude/agents/comprehension-review.md:34", anchor: "### R5 ", cert: C5, basis: "the D2/D6 hybrid narrative added to the comprehension gate is an unguarded channel for prose-AI-tell judgment (a stumble report is a prose-quality verdict), duplicating the auditor's sole-owned S4 axis; the grep acceptance guards only the static ownership claim"}
  - {id: R6, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:205", anchor: "### R6 ", cert: C6, basis: "S4 acceptance 'grep shows exactly one owner' is ambiguous: 'prose AI-tell axis' appears 4x across 2 files (owner + disclaimer) and review-workflow-writing-style also enforces the axis on its own surface; the grep must be scoped + ownership-distinguishing"}
  - {id: R7, sev: suggestion, loc: ".claude/skills/ai-tells/SKILL.md:3", anchor: "### R7 ", cert: C7, basis: "D1 rationale is design-doc-scoped, but ai-tells is a general user-facing de-AI skill whose trigger use case wants negative-parallelism/Title-Case/closing-phrase detection; trimming its catalogue changes user-facing behavior beyond the stated scope"}
evidence_base: {section: "## Evidence base", certs: 10, matches: 7}
cert_index:
  - {id: C1, verdict: INFEASIBLE-FROM-STAGED, anchor: "#### C1 "}
  - {id: C2, verdict: CONTRADICTED, anchor: "#### C2 "}
  - {id: C3, verdict: DIFFICULT, anchor: "#### C3 "}
  - {id: C4, verdict: UNVALIDATED, anchor: "#### C4 "}
  - {id: C5, verdict: RESIDUAL-MEDIUM, anchor: "#### C5 "}
  - {id: C6, verdict: DIFFICULT, anchor: "#### C6 "}
  - {id: C7, verdict: RESIDUAL-MEDIUM, anchor: "#### C7 "}
  - {id: C8, verdict: VALIDATED, anchor: "#### C8 "}
  - {id: C9, verdict: VALIDATED, anchor: "#### C9 "}
  - {id: C10, verdict: RESIDUAL-LOW, anchor: "#### C10 "}
flags: [CONTRACT_OK]
-->

# Risk review — Track 1 (Style-machinery rework), iteration 1

Single-track, workflow-modifying (`s17=staged`), Markdown + Python under `.claude/**` plus `CLAUDE.md`. No Java in scope. Per branch context, named references were verified as workflow file paths, `§`-anchors, and Python symbols via grep + Read against the live tree; the staged subtree does not exist yet, so every `.claude/**` read resolved to the live file per §1.7(d). mcp-steroid was reachable but not used for symbol audits: `findClass` is Java-only and returns null for the Python identifiers and Markdown anchors here, so a null result would not be a finding. Reference-accuracy caveat: the findings below rest on grep + Read over the live tree, not PSI find-usages, because the in-scope symbols are Python and Markdown, which PSI does not index for reference search here.

The five prose criteria (rule coherence and non-contradiction, instruction completeness, prompt-design soundness, context-budget impact, breakage of dependent prompts or agents) supersede the Java-oriented WAL/crash/migration/hot-caller criteria for all workflow prose in this track.

## Findings

### R1 [should-fix]
**Certificate**: C1 (Testability — staged Python test suite REPO_ROOT back-references).
**Location**: Track acceptance "The `dsc-ai-tell` test suite … is green after the three regex removals" (`track-1.md:147`) and the Removal-completeness invariant (`track-1.md:202`); source `test_dsc_ai_tell.py:59` and `test_design_mechanical_checks_d11.py:61`.
**Issue**: Both Python test suites compute `REPO_ROOT = Path(__file__).resolve().parents[3]`, then reach back out to `REPO_ROOT/docs/adr/**` for the three calibration ADRs (`test_dsc_ai_tell.py:64-68`) and the frozen-design seed (`test_design_mechanical_checks_d11.py:69-70`). Under §1.7 staging the tests are copied to `_workflow/staged-workflow/.claude/scripts/tests/`, where `parents[3]` resolves to the *staged-workflow* directory, not the repo root. `SCRIPT` and `FIXTURE` still resolve correctly (they mirror under the staged root), but `REPO_ROOT/docs/adr/...` dangles, so `assert_calibration_adrs` reports "CALIBRATION ADR missing on disk" and the D11 runner prints "FATAL: required input missing" — the staged suite fails on missing-input, not on any logic error. Running the *live* suite instead is a no-op: it exercises the unedited live script and validates nothing about the removals. So the track's primary acceptance criterion is not mechanically runnable from the location where the edits actually live, and §1.7 gives no verification recipe for staged scripts. Likelihood high (the implementer will hit it the first time they try to prove the suite green); impact is a blocked acceptance gate with no documented path.
**Proposed fix**: In decomposition, add an explicit staged-test verification recipe: either run the staged suite with `REPO_ROOT` overridden to the real repo root (env var or a small `--repo-root` shim), or run from a cwd/PYTHONPATH that keeps `parents[3]` at the true root while pointing `SCRIPT`/`FIXTURE` at the staged copies. Whichever is chosen, make the new D4 shape tests self-contained (fixtures co-located under the staged `tests/fixtures/`, no `docs/adr/**` back-reference) so they run green from the staged location without a shim.

### R2 [should-fix]
**Certificate**: C2 (Assumption — SHAPE_EXEMPT_SECTION_NAMES case-sensitivity).
**Location**: `track-1.md:178` in-scope item 7 — "`SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"`"; source `design-mechanical-checks.py:49-55` and `:735-737`.
**Issue**: The two sets use different comparison semantics. `MANDATORY_OR_FORM_SUBHEADINGS` is matched via `sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS` (`:1335`) and holds lowercase keys, so adding `"summary"` (lowercase) is correct there. `SHAPE_EXEMPT_SECTION_NAMES` is matched raw and case-sensitively by `is_shape_exempt`: `return section["title"] in SHAPE_EXEMPT_SECTION_NAMES` (`:737`), and its existing keys are heading-case (`"Overview"`, `"TL;DR"`). A Part-level `## Summary` heading (the rename target of the current `## TL;DR` Part-level section, whose exemption is the reason `"TL;DR"` sits in this set — see the `:54` comment) has title `"Summary"`, which lowercase `"summary"` will not match. The section then loses its exemption and is subjected to the per-section shape check (`check_per_section_shape`), producing a spurious `per-section-shape` blocker on a well-formed Part-level Summary. The track's literal instruction ("gain `"summary"`") is wrong for this one set.
**Proposed fix**: Add heading-case `"Summary"` to `SHAPE_EXEMPT_SECTION_NAMES` and lowercase `"summary"` to `MANDATORY_OR_FORM_SUBHEADINGS`; correct item 7's wording to name the two spellings distinctly. Add a shape test feeding a `# Part` design with a `## Summary` Part-level section and assert zero per-section-shape findings on it.

### R3 [should-fix]
**Certificate**: C3 (Testability — shared line-anchored fixture renumbers survivors on deletion).
**Location**: `track-1.md:124` and `:179` ("the regex, test, and fixture are one unit"); source `dsc-ai-tell-fixture.md` and the line-anchored assertion tables in `test_dsc_ai_tell.py:113-148`.
**Issue**: The "one unit = regex + assertion + fixture line" framing understates the coupling. `dsc-ai-tell-fixture.md` is a single shared file serving all seven patterns, and `test_dsc_ai_tell.py` pins the *surviving* patterns by absolute line number: `H1_TITLE_CASE_LINE = 1`, `OVERVIEW_INFLATED_LABEL_LINE = 24`, `ANCHORED_REGRESSION_CASES` at lines 130 (fragmented-header) and 79 (inflated-abstraction — a surviving pattern), and `NEGATIVE_RANGES` 115-126 (concrete-mechanism negative — guards the surviving inflated-abstraction rule). Removing the three deleted patterns' positive blocks (negative parallelism `:39-44`, Title Case Demo `:46-51`, hyphenated cluster `:60-65`, trailing negative parallelism `:67-75`) shifts every line below downward, so the surviving anchored/range constants point at the wrong lines and the suite fails at build time on the *survivors*, not on the removed rules. A "partial deletion" that removes blocks but forgets to recompute the survivor constants is the exact failure the acceptance criterion "no assertion or fixture line still referencing a deleted regex" does not catch. Note also that "negative parallelism" is two regexes (`NEGATIVE_PARALLELISM_RE` + `NEGATIVE_PARALLELISM_TRAILING_RE`) with two `PATTERN_SIGNATURES` entries, the line-69 anchored case, and the 99-113 negative range — all four must go together.
**Proposed fix**: In decomposition, treat the fixture edit as a renumber-the-survivors operation, not a per-pattern excision. Either (a) recompute `H1_TITLE_CASE_LINE`/`OVERVIEW_INFLATED_LABEL_LINE`/`ANCHORED_REGRESSION_CASES`/`NEGATIVE_RANGES` after deleting the blocks, or (b) leave the removed patterns' fixture text in place (inert once the regex and `PATTERN_SIGNATURES`/`ANCHORED`/`NEGATIVE` entries for them are gone) to keep the survivors' line numbers stable, and delete only the test-side entries. Add the two negative-parallelism regexes and their two signature entries plus the line-69 anchor and 99-113 range to the removal checklist explicitly.

### R4 [should-fix]
**Certificate**: C4 (Assumption — removal completeness is per-file multi-site, not per-consumer).
**Location**: `track-1.md:124` ("propagate to the mirrored consumers"); Removal-completeness invariant `:202`; sources `review-workflow-writing-style.md` (5 removed-pattern hits: `:29`, `:34`, `:71`, `:188`, plus the `:209` severity referent), `ai-tells/SKILL.md` (`:3` description + `:24` catalogue), `house-conversation.md`, `design-document-rules.md`, `CLAUDE.md`.
**Issue**: The design's D1 table enumerates consumer *files* per removed rule but not the multiple *sites within one file*. `review-workflow-writing-style.md` names the removed rules at line 29 (banned-pattern list), line 34 ("No Title Case headings"), line 71 (inline examples "It's not X — it's Y" / "In conclusion"), and line 188 (report-template "Title Case headings"). A partial edit that fixes the top-of-file criteria list but misses the inline example at `:71` or the template at `:188` leaves the Phase-C style reviewer flagging text that `house-style.md` no longer bans — a rule contradiction (the coherence/non-contradiction prose criterion). The track's grep-based acceptance covers the `dsc` surfaces and the `ai-tells` description but has no blanket completeness gate over the prose consumers.
**Proposed fix**: Add a removal-completeness invariant that greps each prose consumer file (`house-style.md`, `house-conversation.md`, `review-workflow-writing-style.md`, `ai-tells/SKILL.md`, `design-document-rules.md`, `CLAUDE.md`) for every removed pattern name plus "Title Case" and asserts zero residual enforcement mentions (allowing only intentional survivors, e.g. adjective triads). Decompose the `review-workflow-writing-style.md` edit as a single step that touches all of its sites at once.

### R5 [should-fix]
**Certificate**: C5 (Exposure — comprehension-gate hybrid narrative is an unguarded S4 duplication channel).
**Location**: `track-1.md:128` and `:175` (comprehension-review gains "hybrid narrative-plus-findings output"); D2 `:43-46`, D6 `:71-76`; source `comprehension-review.md:37-41`, `readability-auditor.md:59-70`.
**Issue**: S4 today is clean: the auditor owns the prose AI-tell axis, the comprehension gate "runs it nowhere" (`comprehension-review.md:39`). D2/D6 add a *reading-experience narrative* to the comprehension gate. A narrative stumble report — "I had to re-read this dense sentence" — is functionally a prose-AI-tell (over-dense / hard-to-read) judgment, the auditor's sole-owned axis. The invariant is preserved statically in the agent files, but the runtime hybrid narrative is a new, unguarded channel through which the comprehension gate can re-report prose-quality findings, duplicating the axis. The S4 acceptance grep checks the static ownership *claim*; it cannot catch axis leakage in the runtime narrative. This is exactly the "risk that the persona recast silently duplicates the prose AI-tell axis owner" the review was asked to weigh.
**Proposed fix**: In the comprehension-review recast, scope the narrative explicitly to comprehension / mental-model stumbles ("could I follow the argument, build the model in the time budget") and forbid prose-quality verdicts (word choice, sentence shape, AI-tells, density) with a one-line boundary example; route any density/word-choice stumble to the auditor's axis rather than reporting it in the narrative. Note in the design that the S4 grep does not cover the narrative channel, so the boundary is enforced by the agent contract, not the acceptance test.

### R6 [suggestion]
**Certificate**: C6 (Testability — S4 grep acceptance under-specified).
**Location**: `track-1.md:149` and `:205` ("grep over the staged agent definitions shows exactly one owner … no other agent claims it").
**Issue**: The acceptance is ambiguous as a mechanical check. "prose AI-tell axis" appears four times across the two reader agents: the auditor claims ownership (`readability-auditor.md:3`, `:68`) and the comprehension gate mentions it to disclaim (`comprehension-review.md:3`, `:39`). A raw count returns 4, not 1. Even grepping "own" near the phrase matches both the auditor's claim and the comprehension gate's line 39 ("the cold readability auditor owns it … you run it nowhere"). Separately, `review-workflow-writing-style.md` also enforces the banned-pattern axis on its own Phase-C surface (`:29`, `:71`), so a grep over all of `.claude/agents/` over-counts. The intent ("exactly one owner") is clear, but the check as written either false-fails (counts disclaimers) or, if loosened, false-passes.
**Proposed fix**: Pin the exact grep in the invariant: scope it to the two dual-clean readers only, match the ownership assertion (e.g. `^Owns the prose AI-tell axis` / "You own the prose AI-tell axis"), and separately assert the comprehension gate still carries "run it nowhere". State the expected match count.

### R7 [suggestion]
**Certificate**: C7 (Exposure — ai-tells is a general user-facing de-AI skill; D1 removal exceeds its stated scope).
**Location**: `track-1.md:150`, `:182` (edit the `ai-tells/SKILL.md` description + body catalogue); D1 `:38-39`; source `ai-tells/SKILL.md:3`, `:24`.
**Issue**: D1's rationale is scoped to durable design documents ("the review cost lives in the durable documents"). The `ai-tells` skill is a general, user-facing de-AI tool that triggers on "humanize this / does this sound like ChatGPT / remove the AI smell" over arbitrary drafts, not workflow design docs. Negative parallelism, Title Case headings, and closing phrases are genuine AI tells that a user invoking this skill legitimately wants caught. Removing them from its catalogue (not just the context-budget trim of the always-loaded description) changes the behavior of a dependent skill beyond the change's design-doc scope — the "breakage of dependent prompts or agents" prose criterion. This may be intended given the user's broad thesis, but the design argues only the design-doc scope, so the general-skill scope is an unstated inference.
**Proposed fix**: Make the ai-tells scope an explicit decision. If removing the rules from the general skill is intended, state it in D1 (the skill's de-AI catalogue is deliberately narrowed too). If not, keep ai-tells out of scope for the catalogue-body edit and take only the context-budget trim of the always-loaded description, or leave the skill whole.

## Evidence base

#### C1 Testability: staged Python test suite REPO_ROOT back-references
- **Coverage target**: 85% line / 70% branch (the track's green-suite acceptance is the operative gate here, not a coverage percentage).
- **Difficulty assessment**: The tests locate the repo root by `Path(__file__).resolve().parents[3]` (`test_dsc_ai_tell.py:59`, `test_design_mechanical_checks_d11.py:61`) and then read repo-relative inputs outside the `.claude/` mirror: `CALIBRATION_ADRS = REPO_ROOT/docs/adr/{persist-visible-count,index-gc,non-durable-wow}/adr.md` (`test_dsc_ai_tell.py:64-68`) and `FROZEN_DESIGN = REPO_ROOT/docs/adr/plan-slimization/_workflow/design.md` (`test_design_mechanical_checks_d11.py:69-70`). Under §1.7(e) the tests are copied to `_workflow/staged-workflow/.claude/scripts/tests/`; from there `parents[3]` = the `staged-workflow` dir. `SCRIPT`/`FIXTURE` mirror under that root and resolve, but `REPO_ROOT/docs/adr/...` does not exist under `staged-workflow`, so `assert_calibration_adrs` (`:289-291`) yields "missing on disk" and the D11 `main` (`:183-186`) prints FATAL. The live suite tests the unedited live script (no-op for the removals). §1.7 (conventions.md lines 940-1339) covers write-routing, reads-precedence, promotion, and drift, but prescribes no execution/verification path for staged scripts.
- **Existing test infrastructure**: `test_dsc_ai_tell.py` (shell-out runner), `test_design_mechanical_checks_d11.py` (both-spellings precedent), `dsc-ai-tell-fixture.md`.
- **Feasibility**: DIFFICULT — achievable only with a documented staged-run recipe (REPO_ROOT override or cwd/PYTHONPATH shim) or self-contained new fixtures.
- **Detail**: The runner's own `assert_calibration_adrs` docstring (`:284-286`) confirms removal cannot introduce a false positive on the calibration ADRs, so the *logic* stays green; the failure is purely path resolution from the staged location.

#### C2 Assumption: SHAPE_EXEMPT_SECTION_NAMES case-sensitivity
- **Track claim**: item 7 (`track-1.md:178`) — "`SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"`".
- **Evidence search**: grep + Read over `design-mechanical-checks.py` (live tree). `SHAPE_EXEMPT_SECTION_NAMES` at `:49-55` holds heading-case keys (`"Overview"`, `"Core Concepts"`, `"TL;DR"` with comment "When used as a Part-level TL;DR section under a `# Part N` heading"). `is_shape_exempt` at `:735-737` does `return section["title"] in SHAPE_EXEMPT_SECTION_NAMES` — raw, case-sensitive. `MANDATORY_OR_FORM_SUBHEADINGS` at `:62-73` holds lowercase keys, matched at `:1335` via `sub.lower()`.
- **Code evidence**: `design-mechanical-checks.py:737` (raw match) vs `:1335` (`.lower()` match).
- **Verdict**: CONTRADICTED — a lowercase `"summary"` will not exempt a `## Summary` Part-level heading (title `"Summary"`), producing a spurious per-section-shape blocker.
- **Detail**: The correct spelling is heading-case `"Summary"` for `SHAPE_EXEMPT_SECTION_NAMES` and lowercase `"summary"` for `MANDATORY_OR_FORM_SUBHEADINGS`.

#### C3 Testability: shared line-anchored fixture renumbers survivors on deletion
- **Coverage target**: 85% line / 70% branch (green-suite acceptance is the operative gate).
- **Difficulty assessment**: `dsc-ai-tell-fixture.md` is one shared file for all seven patterns. `test_dsc_ai_tell.py` anchors surviving patterns by absolute line: `H1_TITLE_CASE_LINE=1` (`:113`), `OVERVIEW_INFLATED_LABEL_LINE=24` (`:114`), `NEGATIVE_RANGES` 90-97/99-113/115-126 (`:115-119`), `ANCHORED_REGRESSION_CASES` 130/69/79 (`:138-148`). Removing the three deleted patterns' positive blocks (`:39-44`, `:46-51`, `:60-65`, `:67-75`) shifts every subsequent line, invalidating the surviving anchors (inflated-abstraction at 79, fragmented-header at 130, concrete-mechanism negative 115-126). "Negative parallelism" is two regexes (`:103`, `:127`) with two signature entries (`:76-77`, `:90-91`), the line-69 anchor, and the 99-113 range.
- **Existing test infrastructure**: `test_dsc_ai_tell.py` assertion tables (`:75-148`); fixture positive/negative blocks.
- **Feasibility**: DIFFICULT — feasible with a renumber pass or by leaving inert fixture text; a naive per-pattern excision fails the suite on survivors.
- **Detail**: The acceptance line "no assertion or fixture line still referencing a deleted regex" does not detect a stale *survivor* line number.

#### C4 Assumption: removal completeness is per-file multi-site
- **Track claim**: `track-1.md:124` — remove each rule "at its `house-style.md` site, then propagate to the mirrored consumers"; Removal-completeness invariant `:202`.
- **Evidence search**: grep -icE over removed-pattern names per consumer (live tree): `house-style.md` 13, `review-workflow-writing-style.md` 5, `ai-tells/SKILL.md` 2, `house-conversation.md` 1, `design-document-rules.md` 1, `CLAUDE.md` 1. Site-level Read of `review-workflow-writing-style.md`: `:29` banned list, `:34` "No Title Case headings", `:71` inline examples, `:188` template "Title Case headings", `:209` severity referent.
- **Code evidence**: `review-workflow-writing-style.md:29`, `:34`, `:71`, `:188`.
- **Verdict**: UNVALIDATED — the design's D1 table enumerates consumer files but not the multiple within-file sites; the track's grep acceptance covers only the `dsc` surfaces and the `ai-tells` description.
- **Detail**: A partial edit leaves the Phase-C reviewer enforcing a deleted house-style rule (coherence/non-contradiction failure).

#### C5 Exposure: comprehension-gate hybrid narrative duplicates the S4 axis
- **Track claim**: comprehension-review gains "hybrid narrative-plus-findings output" (`track-1.md:128`, `:175`; D2, D6).
- **Critical path trace**:
  1. Entry: comprehension gate runs once after the dual-clean loop converges (`comprehension-review.md:23`).
  2. Today it runs no prose AI-tell axis (`comprehension-review.md:37-39`) — S4 has exactly one owner, the auditor (`readability-auditor.md:68`).
  3. D2/D6 add a reading-experience narrative to the gate's output.
  4. A narrative stumble ("I re-read this dense sentence") is a prose-density judgment = the auditor's over-dense/hard-to-read axis.
- **Blast radius**: The narrative can re-report prose-quality findings the auditor owns, breaking the "runs it nowhere" half of S4 at runtime while the static ownership claim still reads as one-owner.
- **Existing safeguards**: The S4 acceptance grep (static), the persona-governs-stance-not-checklist rule in D6, the auditor's explicit sole-ownership text. None inspects the runtime narrative content.
- **Residual risk**: MEDIUM — the hybrid narrative is a new, unguarded channel; the mitigation is a contract-level boundary in the recast, not a testable grep.

#### C6 Testability: S4 grep acceptance under-specified
- **Coverage target**: the S4 invariant's grep assertion (`track-1.md:205`).
- **Difficulty assessment**: "prose AI-tell axis" appears at `readability-auditor.md:3`, `:68` (ownership) and `comprehension-review.md:3`, `:39` (disclaimer). A raw count = 4. "own" near the phrase matches both files (comprehension line 39 attributes ownership to the auditor). `review-workflow-writing-style.md:29`, `:71` also enforce the banned-pattern axis on the Phase-C surface, so an all-agents grep over-counts.
- **Existing test infrastructure**: none dedicated; the invariant is a manual grep.
- **Feasibility**: DIFFICULT as written; ACHIEVABLE once the grep is pinned (scoped to the two readers, matching the ownership assertion, with an expected count).
- **Detail**: Without a pinned grep the check false-fails on the disclaimer or false-passes if loosened.

#### C7 Exposure: ai-tells general-skill blast radius
- **Track claim**: edit `ai-tells/SKILL.md` description + body catalogue to drop three removed rules (`track-1.md:150`, `:182`; D1).
- **Critical path trace**:
  1. Entry: `ai-tells` triggers on user requests to humanize / de-AI / "remove the AI smell" over arbitrary drafts (`ai-tells/SKILL.md:3`).
  2. Its catalogue maps to `house-style.md` sections (`:24`) and currently catches negative parallelism, Title Case, closing phrases (`:3`).
  3. D1 removes those names from the description and catalogue.
- **Blast radius**: A user invoking the skill on any non-design-doc draft no longer gets these tells caught — a user-facing behavior change beyond the design-doc scope of D1's rationale.
- **Existing safeguards**: none; the skill is the terminal consumer.
- **Residual risk**: MEDIUM — likely benign if intended, but unstated; worth an explicit scope decision.

#### C8 Assumption: backward-compat for legacy committed designs
- **Track claim**: "Removal is not a backfill: legacy committed designs under `docs/adr/**` … are not re-reviewed" (D1 `:39`); both `TL;DR` spellings stay accepted (D4).
- **Evidence search**: Read of `test_dsc_ai_tell.py:278-304` (calibration contract), `section_has_tldr` (`:708-716`), D11 both-spellings handling (`section_has_references` `:719-732`).
- **Code evidence**: removing a regex can only lower a document's finding count, and the calibration ADRs are pinned at zero (`test_dsc_ai_tell.py:284-286`); `section_has_tldr` keeps `**TL;DR.**`/`### TL;DR` while gaining `### Summary`, so legacy `**TL;DR.**` designs still pass; the D10/D9 rules land on prose/judgment surfaces, not new `dsc` regexes, so no new mechanical check newly fails a legacy design.
- **Verdict**: VALIDATED — the backward-compat stance is safe; no finding.
- **Detail**: Contingent on keeping both spellings accepted (the D4 acceptance already pins this).

#### C9 Assumption: MANDATORY_OR_FORM_SUBHEADINGS must gain "summary"
- **Track claim**: D4 (`track-1.md:59`, `:204`) — omitting `"summary"` false-positives the same-shape sibling check on every well-formed `### Summary` design.
- **Evidence search**: Read of `check_same_shape_siblings` (`:1310-1369`); custom-subhead filter at `:1332-1336`; `_jaccard` at `:1301-1307`.
- **Code evidence**: `:1335` filters `sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS`; a shared `### Summary` H3 in every section would otherwise inflate the Jaccard overlap and trip the 3+-sibling consolidation finding.
- **Verdict**: VALIDATED — the design correctly identified this coupling; the lowercase `"summary"` addition to this set is correct (contrast C2 for the sibling set).
- **Detail**: No finding on the set membership itself; the atomicity constraint ("add in the same change as the shape regex", `:126`) is sound.

#### C10 Exposure: ai-tells always-loaded description context-budget
- **Track claim**: the `description:` is loaded every session, so three of the six removals keep costing context until it is edited (D1 `:39`).
- **Critical path trace**: `ai-tells/SKILL.md:3` frontmatter appears in every session's skill catalogue; it names negative parallelism, Title Case headings, closing phrases (and adjective triads).
- **Blast radius**: editing the description is a small, one-time always-loaded context saving; no runtime behavior beyond the skill-trigger surface.
- **Existing safeguards**: n/a.
- **Residual risk**: LOW — the context-budget angle is a benign, correctly-identified win; the behavioral concern is captured separately in C7.
