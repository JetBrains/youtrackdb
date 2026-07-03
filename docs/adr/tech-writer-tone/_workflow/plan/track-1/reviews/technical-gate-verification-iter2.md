<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: T4, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/plan/track-1.md:224, anchor: "### T4 ", cert: C1, basis: "Interfaces item 8 change-list omits the fixture-only-mode edit that three sites reference as 'item 8'; non-blocking, decomposition will formalize"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

## Verification certificates

#### Verify T1: `dsc-ai-tell` corpus-reach mechanism and fixture-only-mode need
- **Original issue**: Track claimed the fixture assertions "resolve relative to `__file__`" and omitted that `test_dsc_ai_tell.py` `main()` runs both fixture and corpus groups, so a fixture-only run mode is needed for "green while staged."
- **Fix applied**: `## Plan of Work` (line 151) now states both the fixture paths (`SCRIPT`, `FIXTURE`) and the corpus paths (`CALIBRATION_ADRS`) resolve *through* `REPO_ROOT` (`parents[3]`) — "the same base, not one relative to `__file__`" — that `parents[3]` from the staged path is `staged-workflow/`, that `main()` runs both groups, that `assert_calibration_adrs()` appends a hard failure (not a skip) per missing ADR, and adds a fixture-only-mode step. `## Idempotence and Recovery` (line 206) restates the corpus resolution through `REPO_ROOT` with no "relative to `__file__`" claim.
- **Re-check**:
  - Location: track lines 151, 168, 206, 249; source `.claude/scripts/tests/test_dsc_ai_tell.py:59-68,307-321`.
  - Current state — verified against source (live file byte-identical to `origin/develop`):
    - `REPO_ROOT = Path(__file__).resolve().parents[3]` (line 59); for the live test `parents[3]` is the repo root, for the staged path `_workflow/staged-workflow/.claude/scripts/tests/` it is `staged-workflow/`. Track's claim accurate.
    - `SCRIPT` (60), `FIXTURE` (61-62) and `CALIBRATION_ADRS` (64-68) all derive from `REPO_ROOT` — the same base. Track's "resolve *through* `REPO_ROOT`, not relative to `__file__`" accurate; `.claude/**` is mirrored under `staged-workflow/`, `docs/adr/**` is not, so the fixture paths resolve and the corpus paths do not while staged.
    - `main()` (307-321) runs the fixture group (`assert_fixture_positive/anchored/negative`, `assert_overview_inflated_label_skipped`) AND `assert_calibration_adrs()`; the latter appends `"CALIBRATION ADR missing on disk"` per non-existent ADR (289-291), and any `failures` makes `main()` return 1 (322-328). Track's "runs both groups … hard failure, not a skip … always exits non-zero" accurate; the fixture-only-mode need is real.
  - Criteria met: mechanism text is factually accurate against the cited source lines; no residual "relative to `__file__`" claim survives (the only `__file__` occurrence, line 151, is the corrected negation).
- **Regression check**: Cross-read the four sites that describe the corpus/fixture split (151, 168, 206, 249) — mutually consistent (REPO_ROOT/parents[3] → staged-workflow/, fixture-only for Phase B, corpus at Phase 4). One minor completeness gap surfaced: `## Interfaces` item 8 (line 224) does not list the fixture-only-mode edit that lines 168/206/249 reference as "item 8" (raised as T4, non-blocking).
- **Verdict**: VERIFIED

#### Verify T2: names+exemplar drop-site grep and 185/188 labels
- **Original issue**: Name-only drop-site grep misses `review-workflow-writing-style.md:185` (exemplar-only), and lines 185/188 were mislabeled "§ Plain language".
- **Fix applied**: `## Plan of Work` (line 143) and `## Interfaces` item 6 (line 222) now say the grep matches the six removed-rule names **and their exemplar phrases**, treat the snapshot `{29, 34, 38, 71, 89, 185, 188, 200}` as a grep-derived floor/superset, and relabel 185/188 as the `#### Critical` / `#### Recommended` finding-format buckets (185 = the exemplar-only Critical site).
- **Re-check**:
  - Location: track lines 143, 222; source `.claude/agents/review-workflow-writing-style.md` (byte-identical to `origin/develop`).
  - Current state — snapshot maps verified line by line: 29 (§ Project-context banned-patterns bullet, names + exemplar), 34 (§ Project-context "No Title Case headings"), 38 (§ Tooling "negative-parallelism markers"), 71 (banned-patterns sweep formulaic-phrasings), 89 (§ Heading style "Sentence case"), 185 (body under `#### Critical` at 184 — names negative parallelism ONLY via `"It's not X — it's Y"`, never by rule name, so a names-only grep skips it; a names+exemplar grep catches it), 188 (body under `#### Recommended` at 187 — names "Title Case headings"), 200 (`**WS<N>**` template axis token + "negative parallelism" / "closing-phrase filler" Cost examples).
  - Criteria met: a names+exemplar grep reproduces the `{…185…}` set; labels now match the live headings (185 under `#### Critical`, 188 under `#### Recommended`). No residual "§ Plain language" mislabel of 185/188 anywhere in the track (the lone "Plain language" hit, line 82, is an unrelated D7 kept-rule rationale).
- **Regression check**: item 6's collateral claims verified — the surviving § Heading style rule after removing the Title Case line (89) is "One H1 per file" (source line 90); the axis token "banned sentence patterns" is a kept category label; retargeting only the Cost examples (not the axis list) is correct. Clean.
- **Verdict**: VERIFIED

#### Verify T3: D10 BLUF anchored at the `### BLUF lead` enforcement section
- **Original issue**: The D10 BLUF add was anchored only at the § Project-context summary bullet (line 28); the enforcement criteria live at `### BLUF lead` (78-80), so an add reaching only the summary produces a reviewer that does not enforce D10.
- **Fix applied**: Four sites now name `### BLUF lead` (78-80) in addition to the summary bullet (28): item 6 (222), the D10 Decision Log record (103), `## Plan of Work` (145), and the D10 Validation bullet (173).
- **Re-check**:
  - Location: track lines 103, 145, 173, 222; source `.claude/agents/review-workflow-writing-style.md`.
  - Current state: source has `### BLUF lead` at line 78 with criteria at 78-80 (78 the heading, 79 the conclusion-first rule, 80 the skill/agent-opening rule); the § Project-context "BLUF lead" bullet is at line 28. All four track sites cite "78-80" as the enforcement-criteria section and "28" as the summary bullet.
  - Criteria met: `### BLUF lead` exists at 78 with criteria 78-80 as claimed; all four sites now name both anchors.
- **Regression check**: The four sites are mutually consistent on the 78-80 / 28 split; no contradiction with the two out-of-scope D10 house-style.md acceptance sites also listed in the D10 record. Clean.
- **Verdict**: VERIFIED

## Findings

### T4 [suggestion] Interfaces item 8 change-list omits the fixture-only-mode edit it is referenced as
The T1 fix references "item 8" as the fixture-only-mode change at three sites — `## Validation` (line 168, "run in **fixture-only mode** (item 8 — a missing calibration ADR skips rather than fails)"), `## Idempotence and Recovery` (line 206, "run the fixture-only mode (item 8) during Phase B"), and `## Invariants & Constraints` (line 249, "the fixture-based assertions run green in place in fixture-only mode (item 8)"). Throughout this track "item N" resolves to the `## Interfaces and Dependencies` list, and item 8 is `test_dsc_ai_tell.py`. But item 8's change-list (line 224) enumerates only the assertion removals, the `ANCHORED_REGRESSION_CASES`/`NEGATIVE_RANGES` renumber (R3), and the corpus-at-Phase-4 note (R1/A3); it does not name the behavior-bearing fixture-only-mode addition (skip missing calibration ADRs, or gate `assert_calibration_adrs()` behind a flag/env var). An implementer reading item 8 alone for "what changes in `test_dsc_ai_tell.py`" would miss the R1 fix. The change is fully specified in `## Plan of Work` (151) and listed as an acceptance criterion, so the risk of it being dropped is low and Phase-A decomposition formalizes concrete steps regardless — hence suggestion, not blocker. Suggested fix: append to item 8 a clause naming the fixture-only-mode edit (e.g. "add a fixture-only run mode — treat a missing calibration ADR as a skip, or gate `assert_calibration_adrs()` behind a CLI flag/env var (R1)").

## Evidence base

#### C1 T4 interface-completeness cross-check — MATCHES
Grep for `item 8` in `track-1.md` returns lines 168, 206, 249 (each treating item 8 as the fixture-only mode) plus item 8's own definition at 224. Read of line 224 confirms the fixture-only-mode edit is absent from its change-list, while `## Plan of Work` line 151 fully specifies it. Non-blocking: the acceptance criterion (line 168) and Plan of Work (151) carry the change; only the terse Interfaces enumeration is incomplete.
