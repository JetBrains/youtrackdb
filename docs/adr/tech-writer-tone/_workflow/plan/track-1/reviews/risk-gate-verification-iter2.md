<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: R7, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:141", anchor: "### R7 ", cert: "#### Verify (new) R7", basis: "risk finding-ID tags R2/R3/R4/R5 (and T2) each label two distinct concerns within the track; ID-keyed decomposition/Phase-C lookups resolve ambiguously"}
verdicts:
  - {id: R1, verdict: VERIFIED}
  - {id: R2, verdict: VERIFIED}
  - {id: R3, verdict: VERIFIED}
  - {id: R4, verdict: VERIFIED}
  - {id: R5, verdict: VERIFIED}
  - {id: R6, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Risk gate-verification — Track 1 (iter2)

Every accepted risk finding's fix landed and is coherent against the cited source, so the gate passes. Verification surfaced one new should-fix: the track's finding-ID tags collide within the risk namespace (R2/R3/R4/R5 each label two distinct concerns), which can misdirect an ID-keyed lookup at decomposition or Phase C. It does not block — every fix's content is present and correct.

## Verification certificates

#### Verify R1: fixture-only run mode for the staged `dsc-ai-tell` test
- **Original issue**: The staged `test_dsc_ai_tell.py` cannot run its fixture assertions green because `main()` (line 307) folds `assert_fixture_*` (315-318) and `assert_calibration_adrs()` (321) into one `failures` list, and `assert_calibration_adrs()` appends a hard failure — not a skip — for each missing ADR (289-292). From the staged path `REPO_ROOT = parents[3]` resolves to `staged-workflow/`, where `docs/adr/**` is unmirrored, so `main()` always exits non-zero.
- **Fix applied**: `## Plan of Work` (line 151) adds a fixture-only-mode step — "treat a missing calibration ADR as a skip, or gate `assert_calibration_adrs()` behind a CLI flag / env var"; the `## Validation` bullet (168) and the Removal-completeness invariant (249) now say **fixture-only mode**; `## Idempotence and Recovery` (206) records that corpus-calibration assertions run at Phase-4 promotion.
- **Re-check**:
  - Location: `test_dsc_ai_tell.py:59-67` (paths) and `:288-328` (`assert_calibration_adrs` + `main`).
  - Current state: confirmed `FIXTURE`/`SCRIPT` (60-62) resolve through `REPO_ROOT` into `.claude/**` (mirrored under `staged-workflow/`, so resolvable while staged), while `CALIBRATION_ADRS` (64-67 = `persist-visible-count`, `index-gc`, `non-durable-wow`/`adr.md`) resolve into `docs/adr/**` (unmirrored, so unresolvable while staged). Both offered fixes neutralize the hard-fail — a skip inside 289-292, or a flag/env gate on the 321 call. The A10 no-surprise check (206) names the exact three ADRs, matching 65-67.
  - Criteria met: "green while staged" is now satisfiable; the corpus contract is preserved for Phase-4.
- **Regression check**: Checked the corpus assertions and the promotion path — clean. The mode is additive, stages and promotes like the rest, and does not weaken the corpus contract.
- **Verdict**: VERIFIED

#### Verify R2: new D4 shape tests avoid the `FROZEN_DESIGN` dangle
- **Original issue**: New D4 shape tests modeled on `test_design_mechanical_checks_d11.py` would inherit its `FROZEN_DESIGN = REPO_ROOT/docs/adr/plan-slimization/.../design.md`, which sits in the required-input loop that FATAL-returns-1 when a path is absent — dangling from the staged path where `docs/adr/**` is unmirrored.
- **Fix applied**: `## Plan of Work` (151), item 10 (226), and the Both-spellings invariant (250) direct the new shape tests to use only staged `tests/fixtures/` inputs with no `docs/adr/**` back-reference, rather than copying the `d11` frozen-design block verbatim.
- **Re-check**:
  - Location: `test_design_mechanical_checks_d11.py:69-70` (`FROZEN_DESIGN` path) and `:183-185` (`for path in (…, FROZEN_DESIGN): if not path.exists(): print("FATAL: …"); return 1`).
  - Current state: confirmed `FROZEN_DESIGN` is in the required-input FATAL loop. The fix correctly scopes the new tests to fixtures-only inputs, so no `docs/adr/**` path enters that loop.
  - Criteria met: the copied-block dangle is designed out.
- **Regression check**: item 10's "following the `d11` both-spellings precedent" names the test *approach*, constrained by the fixtures-only input rule in 151 — no contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R3: drop-site criterion is names **and** exemplar phrases, snapshot floor
- **Original issue**: A grep over the six removed-rule *names* does not reproduce snapshot line 185 (the exemplar-only Critical bucket, which names negative parallelism only through `"It's not X — it's Y"`), so a names-only sweep leaves a removed rule enforced at the reviewer's most severe bucket.
- **Fix applied**: `## Plan of Work` (143) and item 6 (222) make the drop-site criterion "names **and** their canonical exemplar phrases (`It's not X`, `In conclusion`, the curly-quote glyphs)", grep-derived and a superset of the develop-state floor `{29, 34, 38, 71, 89, 185, 188, 200}`; 185 is called out as the exemplar-only site.
- **Re-check**:
  - Location: `.claude/agents/review-workflow-writing-style.md`.
  - Current state: a names grep `negative[ -]parallelism` hits {29, 38, 71, 200} and **misses 185** (reproduces the original defect); the exemplar grep `it'?s not [a-z]` hits {29, 71, **185**}. The names+exemplar union reaches 185, and 185 is in the authoritative floor.
  - Criteria met: the drop sweep now reaches the exemplar-only site.
- **Regression check**: item 6 keeps the surgical-keep of kept-rule mentions and the "banned sentence patterns" category label — no over-removal. Clean.
- **Verdict**: VERIFIED

#### Verify R4: augmented-promotion runs inside the guarded block before the commit
- **Original issue**: The Phase-4 augmented-promotion recovery self-contradicted on ordering ("after the block" vs "before the commit"), which would stage the three out-of-surface files into a fresh index the promote commit had already passed, dropping them silently from the PR.
- **Fix applied**: `## Idempotence and Recovery` (188) now runs the augmented `cp`/`git add` **inside** the guarded block, immediately before the `:549` `git diff --cached --quiet || git commit`, with the "not after it" clause replacing the prior "after the standard block" phrasing; a `git commit --amend --no-edit` fallback is given (200); the `## Validation` bullet (176) now verifies the promote commit *contains* both path classes.
- **Re-check**:
  - Location: `create-final-design.md:539-551` — `cp -r "$STAGED_DIR/.claude/." .claude/` (547), `git add` four prefixes (548), `git diff --cached --quiet || git commit` (549), divergence check (541-546).
  - Current state: the augmented `cp "$STAGED_DIR/CLAUDE.md" ./CLAUDE.md` + `git add .claude/output-styles CLAUDE.md` are placed after 547 (so `output-styles` is already in the working tree) and before 549 (so both ride the promote commit). Root `CLAUDE.md` gets its own `cp` because the live `cp -r` does not reach it. The recovery now states a single ordering with no residual "after" phrasing, and extends the divergence check to the same two paths (200).
  - Criteria met: the silent-drop failure is designed out; the recovery is internally consistent.
- **Regression check**: the live `create-final-design.md` is unchanged this branch (the staged extension governs future branches), so the branch runs the augmented block by hand — matching 204. Clean.
- **Verdict**: VERIFIED

#### Verify R5: per-step commit-time guard on the out-of-surface files
- **Original issue**: No automated commit-time guard watches `.claude/output-styles/**` or root `CLAUDE.md` this branch; the sole guard was the track-level `git diff`.
- **Fix applied**: the Live-tree-isolation invariant (253) adds a **per-step guard** — every step touching one of the three out-of-surface files carries an inline copy-then-edit-into-mirror instruction and a per-commit assertion, `git diff --cached --name-only -- .claude/output-styles CLAUDE.md` must be empty before the commit.
- **Re-check**:
  - Location: `## Invariants & Constraints` → Live-tree-isolation (253).
  - Current state: the per-step guard text is present as described and shrinks the detection window from track-level to per-commit.
  - Criteria met: a manual per-commit guard now backs the three unwatched paths until promotion.
- **Regression check**: consistent with I6 and the track-level `git diff --name-only` acceptance (176). Clean.
- **Verdict**: VERIFIED

#### Verify R6: removal "one unit" atomicity acknowledged (realization owed at decomposition)
- **Original issue**: The removal unit (regex + fixture block + `PATTERN_SIGNATURES` + anchored/negative renumbering; both negative-parallelism regexes together) should be an explicit per-step atomicity constraint.
- **Disposition / fix applied**: ACCEPTED as a **decomposition-time** constraint; the intent is to be acknowledged in `## Plan of Work` and `## Invariants & Constraints`, with the per-step roster owed at decomposition.
- **Re-check**:
  - Location: `## Plan of Work` (139), `## Invariants & Constraints` Removal-completeness (249), `## Context and Orientation` (126).
  - Current state: the acknowledgment is present — 139 states "each of those deletes the regex … its assertions … and its fixture lines … in one change" and "Negative parallelism is two regex objects … its removal deletes both, plus each one's fixture line and assertion", with survivor renumbering; 249 names both `NEGATIVE_PARALLELISM_RE` and `NEGATIVE_PARALLELISM_TRAILING_RE` and requires no removed-pattern fixture/assertion to remain; 126 states "a regex, its assertion, and its fixture line form one unit".
  - Criteria met: the track-file-text acknowledgment owed at this gate is satisfied.
- **Regression check**: `PATTERN_SIGNATURES` (used in `test_dsc_ai_tell.py:333`, `len(PATTERN_SIGNATURES)`) is *not* enumerated in the acknowledgment, so it must be folded into the removal unit when `## Concrete Steps` realizes the per-step atomicity roster. This is expected under the disposition (realization owed at decomposition), not a track-file failure.
- **Verdict**: VERIFIED (acknowledgment present; per-step roster-realization correctly deferred to decomposition)

## Findings

### R7 [should-fix] Risk finding-ID tags collide within the track, so ID-keyed lookups resolve ambiguously
The track reuses a single risk finding-ID for two distinct concerns in several places, so a reader keying a fix or a check on a finding ID at decomposition or Phase C can resolve to the wrong edit. All content fixes are present and coherent — this is a traceability defect in the ID tags, not a missing fix, so it does not block the gate.

Colliding tags (all line numbers in `docs/adr/tech-writer-tone/_workflow/plan/track-1.md`):

- **R2** — line 141 tags the `SHAPE_EXEMPT_SECTION_NAMES` / `MANDATORY_OR_FORM_SUBHEADINGS` case-correctness concern (`T1/R2/A5`), while line 151 tags the `FROZEN_DESIGN` dangle (`(R2)`).
- **R3** — lines 139/151/168/224/225/249 tag the `dsc-ai-tell` fixture line-anchor renumbering (`ANCHORED_REGRESSION_CASES`, `NEGATIVE_RANGES`), while lines 143/222 tag the drop-site exemplar-185 concern (`T2/R3/A1`).
- **R4** — line 143 tags drop-site completeness ("reach every hit"), while lines 176/188 tag the augmented-promotion ordering / contains-check.
- **R5** — lines 143/220/229/252 tag "comprehension-gate narrative off the prose-AI-tell axis", while line 253 tags the per-step commit-time guard (`R5/A5`).
- **T2** (adjacent, same shape) — lines 139/249 tag the `NEGATIVE_PARALLELISM_TRAILING_RE` partial-delete, while lines 143/222 tag the exemplar-185 concern.

Related mismatch: the one-unit atomicity finding verified here as **R6** has no matching `R6` tag in the track — the `R6` number (143/170/252) labels the scoped, ownership-distinguishing S4 grep instead, and the atomicity intent rides the `T2`/`R3` prose at 139/249.

**Failure scenario**: at `## Concrete Steps` decomposition an implementer told to "realize the R6 atomicity constraint" greps the track for `R6`, lands on the S4-grep edits (143/170/252), and either mis-scopes or skips the regex+fixture+`PATTERN_SIGNATURES`+renumbering unit; symmetrically, a Phase-C check of "did R4 land" resolves to two different edits (drop-completeness vs promotion ordering) and can report a false pass.

**Suggested fix (decomposition-time)**: assign each distinct concern a unique risk ID (renumber the colliding second uses), or replace the bare ID tags with concern-naming phrases; keep cross-review composite tags (`T1/R2/A5`) only where the three review types genuinely flagged the *same* concern.

- **Verdict**: CONFIRMED
