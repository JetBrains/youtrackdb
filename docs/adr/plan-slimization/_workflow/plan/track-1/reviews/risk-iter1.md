<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 1, suggestion: 3}
index:
  - {id: R1, sev: should-fix, loc: "track-1.md §Plan of Work step 4 / conventions-execution.md §2.5 TOC row line 13", anchor: "### R1 ", cert: E1, basis: "third-scope gate reviewer reads §2.5 via TOC filter; row lacks planner/phase-1 today, so step 5 spawn before step 4 axes-extension strands the read"}
  - {id: R2, sev: suggestion, loc: "track-1.md §Plan of Work step 12 / design-mechanical-checks.py:666 + frozen design.md footer", anchor: "### R2 ", cert: E2, basis: "live-path D11 edit; semantic backward-compat for both footer spellings has no mechanical regression guard beyond the named acceptance check"}
  - {id: R3, sev: suggestion, loc: "track-1.md §Validation step / new fixture vs workflow-startup-precheck.sh closed-enum section_first_checkbox_token:1012", anchor: "### R3 ", cert: T1, basis: "minimal stub must be glyph-valid across 3 closed-enum section reads; fixture is the only proof the stub does not strand State 0"}
  - {id: R4, sev: suggestion, loc: "track-1.md §Plan of Work steps 5/8/9 (cold-read S3 + absorption) / design-review.md + edit-design freeze order", anchor: "### R4 ", cert: E3, basis: "absorption-completeness + fidelity are semantic checks with no mechanical backstop (D8 accepted residual); S3 reachability is documentation-only"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 6}
cert_index:
  - {id: E1, verdict: CONTRADICTED, anchor: "#### E1 "}
  - {id: E2, verdict: VALIDATED, anchor: "#### E2 "}
  - {id: E3, verdict: VALIDATED, anchor: "#### E3 "}
  - {id: A1, verdict: VALIDATED, anchor: "#### A1 "}
  - {id: A2, verdict: VALIDATED, anchor: "#### A2 "}
  - {id: T1, verdict: ACHIEVABLE, anchor: "#### T1 "}
  - {id: T2, verdict: ACHIEVABLE, anchor: "#### T2 "}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: E1 (Assumption — §2.5 is readable by the third-scope gate reviewer)
**Location**: `track-1.md` §Plan of Work step 4 ("§2.5 access wiring") and its ordering constraint; live anchor `conventions-execution.md` §2.5 TOC row (line 13) and the three used subsection annotations (lines 14, 16, 17).
**Issue**: The relocated adversarial gate (step 3/5) is spawned at the Phase 0→1 boundary as role `reviewer-adversarial`/`planner` at phase `1`, and per D17 it must read the `conventions-execution.md` §2.5 review-file schema to emit a manifest-plus-sections file. Every workflow file is read under the TOC protocol (`conventions.md §1.8`): a section is opened only when its annotation Roles intersect the reader's roles AND its Phases intersect the reader's phases. The live §2.5 TOC row and its `## Manifest-plus-sections file` / `## Verdict-producer manifest variant` / `## Coverage (S5)` subsections carry roles `orchestrator,decomposer,implementer,reviewer-dim-*,reviewer-plan,reviewer-technical,reviewer-risk,reviewer-adversarial` and phases `2,3A,3B,3C,4` — **neither `planner` nor phase `1` appears**. A Phase-1 gate reviewer therefore filters §2.5 out and never reads the schema it is required to write to. The track's step 4 is exactly the fix (extend the axes with `planner`/`1`), so this is not an unaddressed gap. The risk is a sequencing/omission hazard: the track's own ordering constraint ("steps 3-4 precede or accompany step 5") is the only thing preventing step 5 (gate spawn wiring) from landing while step 4 (axes extension) is skipped or deferred, which would produce a gate spawn that cannot self-document. Likelihood moderate (the two steps are in different files and easy to split across commits); impact contained (a TOC-filtered gate reviewer silently produces no schema-conformant file, surfacing only at the orchestrator's count-validation grep). Note that `reviewer-adversarial` is already on the row — the missing axis is **phase `1`** (and `planner` for the orchestrator-side read), not the role; the extension is narrow.
**Proposed fix**: Keep step 4 and step 5 in the same commit (or land step 4 first), and make the track-level acceptance assert the §2.5 TOC row and the three used subsections carry both `planner` and phase `1` after the staged edit — the existing acceptance bullet ("the staged third scope is reachable from the staged SKILL wiring … D17 output path") should be tightened to name the TOC-readability precondition explicitly, so a dry-run read catches a step-5-without-step-4 split.

### R2 [suggestion]
**Certificate**: E2 (Exposure — live-path `design-mechanical-checks.py` D11 edit)
**Location**: `track-1.md` §Plan of Work step 12; live `design-mechanical-checks.py:666` (`section_has_references`), `:672-674` (footer regexes), `:731-744` (`check_per_section_shape`); frozen `design.md` footers.
**Issue**: D11's footer rename (`References` → `Decisions & invariants`) and the new decision-cited-without-rationale check land on the **live** `design-mechanical-checks.py`, outside the §1.7 stageable prefix set (Constraints / D13). This is the only file in the track whose edit affects the running workflow mid-branch. The blast radius is the per-section shape check run by `/edit-design` Step 3 on every `design.md` mutation: if the edit accepts only the new footer spelling, this branch's own frozen `design.md` (old `### References` footer) would start failing its own mechanical check at the next `/edit-design` invocation or Phase-4 sync. The track and plan both call for a backward-compatible edit accepting both spellings, and the acceptance bullet ("passes against this branch's frozen `design.md` (old footer) and against a synthetic doc using the new footer") names the regression guard. Residual risk LOW: `section_has_references` already matches two forms (`### References` and `**References.**`), so adding a third (`### Decisions & invariants` / `**Decisions & invariants.**`) is an additive `re.search` alternation, and the existing `test_review_file_schema.py` / mechanical-check tests plus the named acceptance doc cover it. The new decision-cited-without-rationale check (analogous to `check_dsc_parenthetical_asides` at `:863`) is the part with no prior shape to extend — it is net-new logic on a live script and should ship with its own positive+negative test cases, not only the footer acceptance doc.
**Proposed fix**: At step 12, write the backward-compat edit as an alternation over all three footer spellings (verify both `section_has_references` at `:666` and the `check_dsc_parenthetical_asides` References-block toggle at `:924` recognize the new heading), and add a focused test for the new decision-without-rationale check covering a doc that cites a D-record with rationale (pass) and one without (flag). The existing frozen `design.md` is the natural old-footer fixture.

### R3 [suggestion]
**Certificate**: T1 (Testability — `minimal` stub parses through the unchanged precheck script)
**Location**: `track-1.md` §Plan of Work step 12 (fixture) and §Validation second bullet; live `workflow-startup-precheck.sh` `section_first_checkbox_token` (`:1012`) and the State 0/A/C/D walk (`:1447-1548`).
**Issue**: S1 forbids editing `workflow-startup-precheck.sh` and its existing tests, so the `minimal` stub template is the only artifact the resume machine sees for a `minimal` change, and the new fixture is the only proof the stub does not strand the state machine in State 0. The script reads three closed-enum sections: `## Plan Review` first top-level checkbox (`:1456`, State 0 if `[ ]`/absent), the `## Checklist` first `[ ]` track (`:1467-1531`, State A/C), and `## Final Artifacts` first top-level checkbox (`:1548`, State D/Done). `section_first_checkbox_token` is a **total closed-enum parse** that `parse_error`-exits on a malformed glyph (`:1043`, `:1091`), so a stub with a bare heading or an invalid checkbox glyph does not degrade gracefully — it aborts the precheck. CR1 already corrected the D1 spec (decision checkboxes under `## Plan Review`/`## Final Artifacts`, glyph-valid `## Checklist` with one entry), so the template shape is right; the residual risk is that the fixture must exercise **all three** reads and the full post-review transition sequence (Plan Review `[x]` → State A/C; track `[x]` + Final Artifacts → State D/Done), not just assert "a readable state" once. Testability is ACHIEVABLE — `test_workflow_startup_precheck.py` already exists as the pattern and the script is unchanged — but a fixture that only asserts the initial State 0 read would miss a stub that strands a later transition.
**Proposed fix**: Decompose the fixture (step 12) to assert each of the four documented transitions the acceptance bullet names (initial State 0; Plan Review flipped → State A/C; track flipped → still walks; Final Artifacts flipped → State D then Done), so a regression in any one section's glyph validity is caught. Keep it a new file under `.claude/scripts/tests/` (S1).

### R4 [suggestion]
**Certificate**: E3 (Exposure — write-time cold-read absorption/fidelity and the S3 freeze order)
**Location**: `track-1.md` §Plan of Work steps 5 (pre/post-presentation re-trigger), 8 (S3 gate on cold-read), 9 (absorption + full-tier fidelity); live `prompts/design-review.md` (cold-read target) and `edit-design/SKILL.md` Step 3.5/Step 4 ordering.
**Issue**: D8's absorption-completeness ("every load-bearing log decision in a track's scope appears in the carrier") and the full-tier seed↔track fidelity criterion are **semantic** checks performed by the retargeted cold-read sub-agent with no mechanical backstop — the plan records this as accepted residual risk (D8 Risks/Caveats; post-authoring divergence owned by the Track 2 propagation duty). S3 (no cold-read reaches an open log-adversarial gate entry, holding across the D15 batch loop-back) is enforced as a **documentation-only reachability property** over the staged `edit-design`/`create-plan` flow, not a runtime assertion. Both are correctly scoped as authoring-time discipline rather than code gates, and the track's acceptance bullets name the dry-run reachability check for S3. The risk worth recording: the cold-read's absorption judgment depends on the `Alternatives rejected` heuristic (a real fork named, judged not gamed) — a research log whose decision entries are thin or whose `Alternatives considered` are pro-forma would pass absorption while carrying no real challenge, and nothing downstream re-checks it. This is inherent to relocating the adversarial pass onto the log (D6) and shedding `design.md` in `lite`/`minimal`; it is the intended trade, not a track defect. Likelihood low (the adversarial gate runs first and is the real challenge); impact bounded to the no-design tiers where the log is the sole pre-code decision record.
**Proposed fix**: No structural change. At step 9, make the absorption criterion in `design-review.md` state explicitly that a `minimal`/`lite` carrier whose log decisions lack a genuine `Alternatives considered` fork is itself an absorption finding (not silently "absorbed"), so the cold-read does not rubber-stamp a thin log. This keeps the accepted residual risk visible at the one place it can be caught.

## Evidence base

#### E1 Exposure→Assumption: third-scope gate reviewer can read `conventions-execution.md` §2.5
- **Track claim**: Step 4 — "The review-file schema's TOC row and the subsections the third scope uses extend their phases with `1` and roles with `planner`." D17 — "Requires §2.5 annotation axes to gain `planner`/`1`." Signatures section — the gate "returns a thin §2.5 manifest."
- **Critical path trace** (TOC-read resolution, `conventions.md §1.8`):
  1. Gate spawned by `create-plan` Step 4 at Phase 0→1 as role `reviewer-adversarial`/`planner`, phase `1` (per D6/D14/D17 and the plan Integration Points).
  2. Reviewer must read `conventions-execution.md §2.5` to emit the manifest-plus-sections file (D17; `risk-review.md` Output Format cites §2.5 as the single source of truth).
  3. Read filters by TOC annotation: a section opens only if Roles ∩ reader-roles ≠ ∅ AND Phases ∩ reader-phases ≠ ∅.
  4. Live §2.5 TOC row (`conventions-execution.md:13`) and subsection annotations (`:14`, `:16`, `:17`, `:474`, `:483`, `:546`) carry phases `2,3A,3B,3C,4` and no `planner` role.
- **Evidence search**: Grep `'2\.5 Review-file|planner|reviewer-risk'` over `conventions-execution.md` (live; staged mirror absent — fallback per the spawn's staged-read note). Tool: Grep + Read. No Java symbol involved; no PSI caveat needed.
- **Code evidence**: `conventions-execution.md:13` — row Phases column = `2,3A,3B,3C,4` (no `1`); Roles column has `reviewer-adversarial` but **not** `planner`. Subsection `:474` and `:483` identical.
- **Verdict**: CONTRADICTED (for the *current live* state) — the schema is unreadable by a Phase-1 `planner`/adversarial reader today. The track's step 4 is the corrective edit; the finding is the sequencing hazard (step 5 must not precede step 4) and the acceptance gap (readability precondition not asserted).
- **Blast radius**: a gate spawn wired (step 5) without the axes extension (step 4) yields a reviewer that TOC-filters §2.5 out, cannot write a schema-conformant file, and surfaces only at the orchestrator's S4 count-validation grep over an absent/malformed file.
- **Existing safeguards**: the track's own ordering constraint ("steps 3-4 precede or accompany step 5"); the acceptance dry-run read. Both are prose discipline, not mechanical.
- **Residual risk**: MEDIUM (cross-file, splittable across commits; failure is silent until validation).

#### E2 Exposure: live-path `design-mechanical-checks.py` D11 edit (outside §1.7 staging)
- **Track claim**: Step 12 — "`design-mechanical-checks.py` accepts both footer spellings and gains the decision-cited-without-rationale check (D11), backward-compatible so the live frozen `design.md` keeps passing." Constraints/D13 — the script "sits outside the §1.7 stageable prefix set, so its D11 edit lands on the live path mid-branch."
- **Critical path trace**:
  1. `/edit-design` Step 3 runs mechanical checks on every `design.md` mutation (`edit-design/SKILL.md` §Workflow Step 3).
  2. `check_per_section_shape` (`design-mechanical-checks.py:684`) calls `section_has_references` (`:666`) per non-exempt section; today matches `### References` (`:672`) and `**References.**` (`:674`).
  3. A frozen `design.md` with the old `### References` footer must keep passing after the edit (this branch's design.md uses the old footer).
- **Evidence search**: Grep `'section_has_references|References|Decisions & invariants|def '` over the live script. Tool: Grep + Read.
- **Code evidence**: `:666-679` `section_has_references` two-form regex; `:863-952` `check_dsc_parenthetical_asides` with a References-block toggle at `:924` that must also recognize the renamed footer; `:731-744` the per-section-shape failure path.
- **Verdict**: VALIDATED — the edit is genuinely live-path, the backward-compat requirement is real and named in both Constraints and the acceptance bullet, and the change is an additive regex alternation plus one net-new check. Residual is the net-new decision-without-rationale check, which has no existing shape to inherit a test from.
- **Blast radius**: per-section shape check on every live `design.md` mutation; a one-spelling edit breaks this branch's own design.md at the next `/edit-design` or Phase-4 sync.
- **Existing safeguards**: `section_has_references` already multi-form; `test_review_file_schema.py` + mechanical-check tests; the named acceptance doc (old + new footer).
- **Residual risk**: LOW.

#### E3 Exposure: write-time cold-read absorption/fidelity + S3 freeze order
- **Track claim**: Steps 8-9 — Step 3.5 removed from `phase1-creation`; cold-read gated behind the log-adversarial gate clearing (S3); absorption-completeness (`Alternatives rejected` names a real fork, judged not gamed) and full-tier seed↔track fidelity at authoring time only.
- **Critical path trace**:
  1. `edit-design/SKILL.md` Step 3.5 (`:33`, `:159-161`) runs adversarial for `phase1-creation` only, before Step 4 cold-read.
  2. Track removes Step 3.5 (the gate relocates onto the research log per D6) and adds the S3 gate to the cold-read step.
  3. `design-review.md` (cold-read prompt) gains a second target (plan-at-start track sections) and the absorption/fidelity criteria.
- **Evidence search**: Grep `'Step 3\.5|phase1-creation|cold-read|adversarial'` over `edit-design/SKILL.md`; `'design\.md|track|Inputs|Verdict'` over `design-review.md`. Tool: Grep + Read.
- **Code evidence**: `edit-design/SKILL.md:159-161` (Step 3.5 kind-conditional); `design-review.md:41` (`design_path` — `design.md`), `:54` (`plan_dir` optional), `:141` (plan/track reads grep-only today). The retarget is feasible against the existing prompt shape.
- **Verdict**: VALIDATED — the relocation and retarget are mechanically sound against the live prompts; absorption/fidelity are semantic with no mechanical backstop (plan records this as accepted residual, D8); S3 is a documentation-only reachability property.
- **Residual risk**: LOW (no-design-tier-bounded; adversarial gate runs first as the real challenge).

#### A1 Assumption: `research-log.md` is replay-immune and S1 (script untouched) holds
- **Track claim**: D19 / step 1 — `research-log.md` joins the §1.6(f) "Explicitly NOT stamped" list; "no §1.6(h) walk enumerates the file"; S1 — `workflow-startup-precheck.sh` stays byte-identical.
- **Evidence search**: Grep the §1.6(h) walk and the precheck script's `ls` enumeration over artifact types. Tool: Grep + Read.
- **Code evidence**: `conventions.md:723-726` §1.6(h) walk enumerates exactly `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md` — `research-log.md` absent. `workflow-startup-precheck.sh:391-394`, `:488-491`, `:689-692` — the three `ls` walks enumerate the same four types, `research-log.md` absent from all. §1.6(f) `:671-679` currently lists Phase-4 finals + `design-mutations.md` as NOT-stamped (the exact precedent D19 mirrors).
- **Verdict**: VALIDATED — `research-log.md` is enumerated by no walk and rewritten/re-derived by no phase machinery, so the append-only replay-immunity rationale holds verbatim against the `design-mutations.md` precedent. Adding it to the prose §1.6(f) NOT-stamped list requires no script edit and no §1.6(h) change, so S1 is genuinely preserved.
- **Residual risk**: LOW. The existing branch log's harmless line-1 stamp (CR2-confirmed at `research-log.md:1`) is correctly characterized as harmless — the presence check fires only on enumerated types.

#### A2 Assumption: D4 risk-tagging HIGH-category list exists and is reusable at change level
- **Track claim**: Step 11 / D4 — the `risk-tagging.md` HIGH-risk category list is "also Gate 1's source, read at change level — one shared source of truth."
- **Evidence search**: Grep `'HIGH|concurrency|crash|public API|security|architecture|hot path|workflow machinery'` over `risk-tagging.md`. Tool: Grep + Read.
- **Code evidence**: `risk-tagging.md:38-39` enumerates "concurrency, durability, public API surface, security, load-bearing architecture, performance hot path"; `:96` §HIGH-risk triggers; `:161-163` + `:221` a "Workflow machinery" category. The list is decomposer/3A-annotated today; Gate 1 reads it at planner/Phase-1.
- **Verdict**: VALIDATED — the list exists and matches the D4 enumeration (concurrency, crash-safety, public API, security, architecture, perf hot path, workflow machinery). Reuse at change level is a read-axis extension analogous to E1's §2.5 case; step 11 adds the cross-reference note. Feasible.
- **Residual risk**: LOW (change-level centrality is a judgment call, ratified by the user at tier confirmation per D4 — a documented human gate, not a silent automation).

#### T1 Testability: `minimal` stub parses through the unchanged precheck script
- **Coverage target**: 85% line / 70% branch (workflow-machinery: the operative bar is the fixture exercising the documented transitions, not raw line %).
- **Difficulty assessment**: S1 forbids editing the script and its existing tests; the stub is the only artifact the resume machine sees and the fixture is the only proof it does not strand State 0. The script's section reads are total closed-enum parses that `parse_error`-exit on malformed glyphs (no graceful degradation).
- **Existing test infrastructure**: `.claude/scripts/tests/test_workflow_startup_precheck.py` (the pattern), `fixtures/` dir, the conformance fixture extracting the §1.6(h) block. A new fixture file is S1-compliant (additive, no existing-test edit).
- **Feasibility**: ACHIEVABLE — script unchanged, pattern exists, stub shape corrected by CR1. The residual is fixture completeness (must walk all four documented transitions, not just assert one readable state).
- **Code evidence**: `workflow-startup-precheck.sh:1012` `section_first_checkbox_token`; `:1043`/`:1091` parse_error exits; `:1447-1548` the State 0/A/C/D walk; `:991-1000` "FIRST top-level checkbox" semantics matching D1's stub spec.

#### T2 Testability: per-tier dry-run reachability of the staged authoring pipeline
- **Coverage target**: not line-coverage — the acceptance bullets are dry-run reads over the staged `create-plan`/`edit-design` flow (no dangling references; S3 no-open-gate-to-cold-read path; D14/D17 params at the spawn site).
- **Difficulty assessment**: the staged mirror does not exist yet (Phase B authors it); the dry-run runs at track-completion against the staged copies. Until then, references resolve against live files (validated above). The check is a manual walk, not a script.
- **Existing test infrastructure**: none mechanical for prose reachability; the cold-read and adversarial dry-run is human/sub-agent inspection per the acceptance bullets.
- **Feasibility**: ACHIEVABLE — the acceptance criteria are concrete (each tier walked end-to-end; every cited section/file/anchor exists in staged-or-live). E1's readability precondition should be folded into this walk (see R1).
