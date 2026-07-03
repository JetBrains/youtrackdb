<!-- MANIFEST
reviewer: reviewer-technical   track: "Track 1: Style-machinery rework"   iteration: 1
findings: 3   severity: {blocker: 0, should-fix: 3, suggestion: 0}
index:
  - {id: T1, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:151", anchor: "### T1 ", cert: P13, basis: "R1/A3 rationale states fixture resolves 'relative to __file__'; it resolves via REPO_ROOT=parents[3] like the corpus — wrong mechanism, correct conclusion, repeated 3x"}
  - {id: T2, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:143", anchor: "### T2 ", cert: P14, basis: "literal six-removed-rule-NAMES grep does not hit line 185 (removed rule named only by exemplar); stated method won't reproduce recorded snapshot; 185/188 mislabeled as § Plain language"}
  - {id: T3, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:215", anchor: "### T3 ", cert: P15, basis: "D10 BLUF add anchored only at review-workflow-writing-style.md § Key-rules summary bullet (28); enforcement criteria live in ### BLUF lead (78-80), unaddressed → reviewer won't enforce D10"}
evidence_base: {section: "## Evidence base", certs: 16, matches: 13}
cert_index:
  - {id: P13, verdict: WRONG,   anchor: "#### P13 "}
  - {id: P14, verdict: PARTIAL, anchor: "#### P14 "}
  - {id: P15, verdict: PARTIAL, anchor: "#### P15 "}
flags: [CONTRACT_OK]
-->

Reference-accuracy caveat: this track's in-scope surface is Markdown + Python under
`.claude/**` plus root `CLAUDE.md`; it names no Java production classes. mcp-steroid
was NOT reachable this session, and PSI `findClass` is Java-only regardless, so every
named reference below was verified as a workflow file path, a `§`-anchor, or a Python
identifier via grep + Read (the correct tool for these referents). No finding hinges
on a Java-symbol search, so the grep fallback carries no reference-accuracy risk here.

## Findings

### T1 [should-fix]
**Certificate**: P13 (Premise — R1/A3 staged-path corpus-dangling rationale)
**Location**: track-1.md `## Plan of Work` line 151 ("resolve their fixture relative to `__file__`"); repeated in `## Validation and Acceptance` line 168 and `## Idempotence and Recovery` line 199.
**Issue**: The track justifies why the `dsc-ai-tell` fixture assertions stay green while staged but the corpus assertions dangle with a false mechanism. It says the fixture assertions "resolve their fixture relative to `__file__`". In the actual code, `test_dsc_ai_tell.py:59` sets `REPO_ROOT = Path(__file__).resolve().parents[3]`, and both `FIXTURE` (line 61) and `SCRIPT` (line 60) resolve *through `REPO_ROOT`* — the exact same base the corpus (`CALIBRATION_ADRS`, lines 64-68) uses. Nothing resolves "relative to `__file__`" independently of `REPO_ROOT`. The real reason the split works: from the staged path `_workflow/staged-workflow/.claude/scripts/tests/`, `parents[3]` is `staged-workflow/`, and the `.claude/**` mirror (fixture + script) *is* present under `staged-workflow/` while `docs/adr/**` is *not* mirrored there — so `REPO_ROOT/.claude/...` resolves and `REPO_ROOT/docs/adr/...` does not. The conclusion (fixture-green-in-place, corpus-verified-at-Phase-4) is correct; the stated mechanism is wrong and could misdirect decomposition (e.g., an implementer refactoring `REPO_ROOT` for the staged run, believing the fixture path is `__file__`-anchored and therefore unaffected). Corollary the wrong framing hides: the test's `main()` runs *both* groups together (`fixture_findings` at line 314 plus `assert_calibration_adrs()` at line 321), so running the whole file while staged fails on the corpus group (line 290 `CALIBRATION ADR missing on disk`) — the Phase-B run must select the fixture assertions only, which the accurate mechanism makes obvious and the current wording does not.
**Proposed fix**: Reword lines 151/168/199 to: "Both the fixture and corpus paths resolve through `REPO_ROOT = parents[3]`; while staged that base is `staged-workflow/`, under which the `.claude/**` mirror (script + fixture) exists so the fixture assertions run green in place, while `docs/adr/**` is not mirrored so the corpus-calibration assertions cannot resolve and are verified at Phase-4 promotion against the live tree. Because `test_dsc_ai_tell.py` `main()` runs both groups, the Phase-B run selects the fixture assertions only." No approach change; accuracy only.

### T2 [should-fix]
**Certificate**: P14 (Premise — grep-derived drop-site snapshot for `review-workflow-writing-style.md`)
**Location**: track-1.md `## Plan of Work` line 143 and `## Interfaces and Dependencies` item 6 (line 215): "a case-insensitive grep of the six removed-rule names … hits lines 29, 34, 38, 71, 89, 185, 188, and 200".
**Issue**: A literal case-insensitive grep of the six removed-rule *names* (`negative parallelism`, `roundabout negation`, `closing phrase`, `hyphenat`, `curly`, `title case`/`sentence case`) over the develop-state file hits `{29, 34, 38, 71, 89, 188, 200}` — it does **not** hit line 185. Line 185 (`[Hard violations — "It's not X — it's Y" anti-pattern in load-bearing position, …]`, the `#### Critical` finding-format bucket) names the removed negative-parallelism rule *only by its exemplar phrase* `"It's not X — it's Y"`, never by the literal name, so a name-only grep skips it. The recorded snapshot is right to *include* 185 (it is a genuine drop-site — a Critical-severity example citing a removed rule, which R4's "the drop must reach every hit" logic requires retargeting), but the stated derivation method won't reproduce it. An implementer who follows the track's own instruction ("the definitive drop-site set is whatever a case-insensitive grep of the six removed-rule names returns") literally gets 7 lines, misses 185, and leaves a Critical-bucket example enforcing a removed rule — the exact R4 failure the track warns against. Secondary inaccuracy: lines 185/188 are labeled "§ Plain language Critical/Recommended criteria", but 185 is under `#### Critical` and 188 under `#### Recommended` — the output finding-format template's severity buckets — not the `§ Plain language` section (lines 30, 73-76, which is *not* a drop-site because it names no removed rule). The mislabel could send an implementer trusting the label to edit the wrong section.
**Proposed fix**: Broaden the recorded derivation to "a case-insensitive grep of the six removed-rule names **and their exemplar phrases** (`It's not X`, `In conclusion`, …)" so line 185 is reproducibly caught (lines 71 and 200 already carry both name and exemplar, so they survive either way; 185 is the sole name-free exemplar site). Correct the 185/188 labels to "the `#### Critical` / `#### Recommended` finding-format buckets". The set itself stays `{29, 34, 38, 71, 89, 185, 188, 200}`.

### T3 [should-fix]
**Certificate**: P15 (Premise — D10 BLUF-hardening acceptance site in `review-workflow-writing-style.md`)
**Location**: track-1.md `## Interfaces and Dependencies` item 6 (line 215): "add the D10 BLUF criteria (at the kept BLUF-lead rule, develop-state line 28)"; D10 Decision Log line 103 and Validation line 172 ("the `review-workflow-writing-style.md` BLUF criteria").
**Issue**: The track anchors D10's two new rules (self-contained plain-claim lead; body stands with the lead deleted) at review-workflow-writing-style.md **line 28** — the one-line `## Key rules` *summary* bullet ("**BLUF lead** — first sentence states the conclusion, not background."). The file's actual BLUF *enforcement criteria* live in the `### BLUF lead` section at **lines 78-80** ("Every section's first sentence should state the section's conclusion…" plus the skill/agent-body opener rule), which item 6 does not name. If only line 28 is edited, the Phase-C style reviewer's detailed criteria section still enforces only "state the conclusion" and will not catch an anaphor-opener body resolving into the lead (D10 rule 2) or a non-self-contained lead (D10 rule 1). This is the same "the add must reach the enforcement site" failure family as R4, inverted: an *addition* that lands in the summary but not the enforcer produces a reviewer that does not actually enforce D10.
**Proposed fix**: Add the two D10 rules to the `### BLUF lead` criteria section (develop-state lines 78-80), the enforcement site, in addition to the line-28 summary bullet. Update item 6 and the D10 Validation criterion to name lines 78-80 as the acceptance site.

## Evidence base

#### P1 — All 21 in-scope files plus the four supporting files named by the track exist at their stated live paths
- **Track claim**: `## Interfaces and Dependencies` lists 21 in-scope files; the track also names `absorption-check.md`, `fidelity-check.md`, `test_design_mechanical_checks_d11.py`, `conventions-execution.md`, and the frozen `design.md`.
- **Search performed**: `test -f` over all 25 paths (mcp-steroid unreachable; these are file-path referents, so `test -f` is exact).
- **Code location**: all 25 present (e.g., `.claude/output-styles/house-style.md`, `.claude/output-styles/house-conversation.md`, `.claude/scripts/design-mechanical-checks.py`, `.claude/scripts/tests/test_dsc_ai_tell.py`, `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md`, `.claude/scripts/tests/test_design_mechanical_checks_d11.py`, the five agent files, `CLAUDE.md`, `.claude/workflow/implementer-rules.md`, `.claude/skills/create-plan/SKILL.md`).
- **Actual behavior**: EXISTS for every path; `staged-workflow/` absent (correct — implementation not started, so reads resolve to live files).
- **Verdict**: CONFIRMED
- **Detail**: file-path referents; no Java-symbol reference-accuracy exposure.

#### P2 — Every named Python identifier resolves in the two script/test files
- **Track claim**: `NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`, `HYPHENATED_PAIR_CLUSTER_RE`, the Title-Case check, `section_has_tldr`, `SHAPE_EXEMPT_SECTION_NAMES`, `MANDATORY_OR_FORM_SUBHEADINGS`, `ANCHORED_REGRESSION_CASES`, `NEGATIVE_RANGES`, `REPO_ROOT = Path(__file__).resolve().parents[3]`.
- **Search performed**: grep over `design-mechanical-checks.py` and `test_dsc_ai_tell.py`.
- **Code location**: `NEGATIVE_PARALLELISM_RE` @ dmc.py:103; `NEGATIVE_PARALLELISM_TRAILING_RE` @ 127; `HYPHENATED_PAIR_CLUSTER_RE` @ 191; `_title_case_violation` @ 1734; `section_has_tldr` @ 708; `SHAPE_EXEMPT_SECTION_NAMES` @ 49; `MANDATORY_OR_FORM_SUBHEADINGS` @ 62 (compared lowercased @ 1335); `ANCHORED_REGRESSION_CASES` @ test:138; `NEGATIVE_RANGES` @ test:115; `REPO_ROOT` @ test:59.
- **Actual behavior**: all present; `SHAPE_EXEMPT_SECTION_NAMES` compares raw `section["title"]` (dmc.py:737) while `MANDATORY_OR_FORM_SUBHEADINGS` compares `sub.lower()` (dmc.py:1335), confirming the display-case-vs-lowercase distinction the folded T1/R2/A5 relies on.
- **Verdict**: CONFIRMED

#### P3 — §1.7 subsections (a)-(f) all exist in `conventions.md`
- **Track claim** (D12): the extension touches §1.7(a) mirror layout, (b) marker enumeration, (c)/(d)/(e)/(f) path lists.
- **Search performed**: grep `^### \([a-z]\)` in the §1.7 region.
- **Code location**: (a) @ 968, (b) @ 1005, (c) @ 1064, (d) @ 1108, (e) @ 1154, (f) @ 1207 (plus (g)-(l) @ 1231-1446).
- **Actual behavior**: all six named subsections present; §1.7(a):991 states "No other prefixes participate" over the four-prefix set, confirming the out-of-surface gap D12 addresses. §1.7(g) I6-invariant prose (1242) and (i) worked example (1279-1294) use illustrative single-file / non-exhaustive wording, not load-bearing prefix enumerations, so D12's omission of them from its edit list is sound.
- **Verdict**: CONFIRMED

#### P4 — `create-final-design.md` Step 4 runs `cp -r "$STAGED_DIR/.claude/." .claude/` and a four-prefix `git add` + divergence check
- **Track claim** (D12): the live promotion copies staged output-styles into the tree but `git add`s only four prefixes (so a staged `house-style.md` edit drops silently), and root `CLAUDE.md` is never `cp`'d.
- **Search performed**: grep Step 4 promotion block.
- **Code location**: divergence `git log … -- .claude/workflow .claude/skills .claude/agents .claude/scripts` @ 541; `cp -r "$STAGED_DIR/.claude/." .claude/` @ 547; `git add .claude/workflow .claude/skills .claude/agents .claude/scripts` @ 548; `git diff --cached --quiet || git commit` @ 549.
- **Actual behavior**: `cp -r` copies `.claude/output-styles/**` (it is under `.claude/`) but the four-prefix `git add` and divergence check both omit output-styles and root `CLAUDE.md`. D12's premise is exactly correct, including that root `CLAUDE.md` is never reached by the `cp -r`.
- **Verdict**: CONFIRMED

#### P5 — `implementer-rules.md` carries the four-prefix write-routing rule and the pre-commit `git diff --cached --name-only` gate
- **Track claim** (D12): both need `.claude/output-styles/**` and root `CLAUDE.md` added.
- **Search performed**: grep write-routing and gate lines.
- **Code location**: write-routing four-prefix map @ 257-295; pre-commit gate `git diff --cached --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/ .claude/scripts/` @ 412.
- **Actual behavior**: both are four-prefix only; neither watches output-styles or root `CLAUDE.md`. D12's premise correct.
- **Verdict**: CONFIRMED

#### P6 — `create-plan` Step 4b pins a bounded inline comprehension-gate return (A4)
- **Track claim** (A4): Step-4b pins a bounded inline-gate return that the D2 hybrid narrative-plus-findings output would contradict, so the consumer contract needs a matching edit.
- **Search performed**: grep + Read Step 4b comprehension-gate block.
- **Code location**: SKILL.md:951-955 — "The Step-4b gate omits the `output_path` file-write … deliberately: its return is the bounded comprehension verdict plus a … so the inline return stays small"; spawn @ 942-943; bounded by `iteration_budget` @ 963.
- **Actual behavior**: the bounded inline verdict return exists as claimed; D2's hybrid narrative would enlarge/contradict it, so A4's consumer-side edit is real.
- **Verdict**: CONFIRMED

#### P7 — The five-agent roster and model pins match the track (three Opus that change, two already Sonnet)
- **Track claim** (D3/D6): `readability-auditor`, `comprehension-review`, `design-author` are Opus (the three that change: two move to Sonnet, the author stays Opus with a pin note + voice mandate); `absorption-check`, `fidelity-check` are already Sonnet, unchanged.
- **Search performed**: grep `^model:` / `^name:` in the five agent files.
- **Code location**: readability-auditor model:opus; comprehension-review model:opus; design-author model:opus; absorption-check model:sonnet; fidelity-check model:sonnet.
- **Actual behavior**: exactly as claimed. The two reader agents are Opus today (target for the Sonnet move); the author is Opus (stays); the two coverage cross-checks are already Sonnet.
- **Verdict**: CONFIRMED

#### P8 — `test_design_mechanical_checks_d11.py` is a both-spellings precedent test
- **Track claim** (D4): the Summary rename follows the References→Decisions & invariants both-spellings precedent, pinned by tests modeled on `test_design_mechanical_checks_d11.py`.
- **Search performed**: grep the d11 test.
- **Code location**: test:5-6 (rename `### References` → `### Decisions & invariants`, backward-compatible both-spellings); fixtures `d11-footer-newname-pass.md` @ 30, `d11-footer-legacy-pass.md` @ 37.
- **Actual behavior**: the file pins both spellings passing the shape regex — a valid model for the TL;DR/Summary both-spellings acceptance.
- **Verdict**: CONFIRMED

#### P9 — `house-style.md` carries the six removed-rule sites and the §Voice/BLUF/Orientation/Navigability/Self-check sections the track edits
- **Track claim** (D1/D4/D5/D7/D10): remove six rules at their sites; swap §Voice-and-tone persona; rename §Navigability TL;DR shape; add §BLUF/§Orientation hardening; renumber §Self-check.
- **Search performed**: grep the rule names and section headers.
- **Code location**: Negative parallelism @ 100, Roundabout negation @ 101, Closing phrases @ 103, Hyphenated word-pair overuse @ 262, Curly quotes @ 273, Title Case headings forbidden @ 304; §BLUF lead @ 22, §Voice and tone @ 40, §Orientation @ 54, §Navigability @ 379, §Self-check @ 401 (items 405/406/408/409 cite the removed rules).
- **Actual behavior**: every removal site and target section exists; the §Title-Case carve-out (308) documents the `dsc-ai-tell` "3+ title-case words" behavior, consistent with `_title_case_violation`.
- **Verdict**: CONFIRMED

#### P10 — Every design.md §-anchor the Decision Log cites resolves
- **Track claim**: D1/D7→§"Removing the disguise-only style rules"; D5→§"The technical-writer voice"; D9→§"Transferring the internals-book voice rules"; D8→§"Dual-register design document"; D2/D6→§"Persona readers…"; D3→§"Reader-proxy model pins…"; D4→§"Renaming TL;DR to Summary"; D10→§"Hardening the section BLUF lead"; D12→§"Staging and promotion under §1.7" (superseded).
- **Search performed**: grep `^## ` anchors in `design.md`.
- **Code location**: @ 208, 300, 350, 420, 473, 539, 601, 661, 725 respectively.
- **Actual behavior**: all nine anchors present; the Class Design table (design.md:141) records `design-author` "Opus → Opus (pinned, never Fable)", consistent with D3/P7.
- **Verdict**: CONFIRMED

#### P11 — The D1 mirrored-consumer surfaces name the removed rules as claimed
- **Track claim** (D1): `ai-tells/SKILL.md` `description:` names three removals (negative parallelism, Title Case, closing phrases) while "adjective triads" stays; `design-document-rules.md` has a `dsc-ai-tell` catalogue row; `house-conversation.md` lists banned patterns; root `CLAUDE.md` §Writing Style has the negative-parallelism parenthetical.
- **Search performed**: grep the four files.
- **Code location**: ai-tells/SKILL.md:3 (negative parallelism, Title Case headings, adjective triads, closing phrases); design-document-rules.md:289 (`dsc-ai-tell` row naming "seven" patterns incl. the removed ones; the count/entries need updating, which item 12 covers); house-conversation.md:23 (negative parallelism, roundabout negation, closing connectives); CLAUDE.md:93 ("It's not X — it's Y" negative parallelism).
- **Actual behavior**: all four consumers present and naming the removed rules as claimed; no missing consumer detected.
- **Verdict**: CONFIRMED

#### P12 — §1.7(b) stable-prefix marker property supports D12's enumeration growth
- **Track claim** (D12): the stable-prefix marker property keeps develop-era markers matching while the staged definition adds prefixes, so the branch keeps its develop-state marker verbatim.
- **Search performed**: Read §1.7(b).
- **Code location**: conventions.md:1031-1042 — consumers match the stable prefix `This plan is workflow-modifying:`; "growing the canonical enumeration never deactivates the gate … load-bearing for the bootstrap that lets a workflow-modifying plan keep its develop-state marker verbatim while the staged definition adds prefixes."
- **Actual behavior**: the property exists and its own text names exactly the bootstrap D12 relies on. D12's rationale is accurate.
- **Verdict**: CONFIRMED

#### P13 — R1/A3 staged-path corpus-dangling rationale — stated mechanism is wrong
- **Track claim**: "The fixture-based fire/no-fire assertions resolve their fixture relative to `__file__` and stay green while staged; the corpus-calibration assertions cannot" (track-1.md:151, 168, 199).
- **Search performed**: Read `test_dsc_ai_tell.py` lines 55-110 (`REPO_ROOT`, `SCRIPT`, `FIXTURE`, `CALIBRATION_ADRS`, `main`, `assert_calibration_adrs`).
- **Code location**: test:59 `REPO_ROOT = Path(__file__).resolve().parents[3]`; test:60 `SCRIPT = REPO_ROOT/.claude/…`; test:61 `FIXTURE = REPO_ROOT/.claude/…`; test:64-68 `CALIBRATION_ADRS = REPO_ROOT/docs/adr/…`; both groups run from `main` (314 + 321); missing-ADR failure path @ 290.
- **Actual behavior**: FIXTURE and SCRIPT resolve through `REPO_ROOT` (= `parents[3]`), the same base as the corpus — not "relative to `__file__`" independently. While staged, `parents[3]` = `staged-workflow/`; `.claude/**` is mirrored there (fixture/script resolve → green) but `docs/adr/**` is not (corpus dangles). Conclusion correct; mechanism wrong.
- **Verdict**: WRONG
- **Detail**: produces T1.

#### P14 — Drop-site derivation method for `review-workflow-writing-style.md` does not reproduce the recorded snapshot
- **Track claim**: "a case-insensitive grep of the six removed-rule names … hits lines 29, 34, 38, 71, 89, 185, 188, and 200" (track-1.md:143, item 6:215).
- **Search performed**: case-insensitive grep of the six removed-rule names over the develop-state file; Read of lines 178-210 to classify 185/188.
- **Code location**: name-grep hits `{29, 34, 38, 71, 89, 188, 200}`; line 185 = `#### Critical` bucket (`"It's not X — it's Y" anti-pattern`), line 188 = `#### Recommended` bucket ("…Title Case headings…"); the `§ Plain language` section is at 30 and 73-76.
- **Actual behavior**: line 185 names the removed rule only by exemplar, so the name-grep misses it though 185 is a genuine drop-site; the recorded snapshot is right to include 185 but the stated method won't yield it. 185/188 are the finding-format Critical/Recommended buckets, not `§ Plain language` criteria.
- **Verdict**: PARTIAL
- **Detail**: produces T2. The set is correct; the derivation method and the 185/188 label are not.

#### P15 — D10 BLUF-hardening add is anchored at the summary bullet, not the enforcement criteria section
- **Track claim**: "add the D10 BLUF criteria (at the kept BLUF-lead rule, develop-state line 28)" (item 6:215); D10 acceptance names "the `review-workflow-writing-style.md` BLUF criteria" (line 103, 172).
- **Search performed**: grep `BLUF` over the file; Read the `### BLUF lead` section.
- **Code location**: line 28 = `## Key rules` summary bullet ("**BLUF lead** — first sentence states the conclusion, not background."); lines 78-80 = `### BLUF lead` enforcement criteria; line 119 process spot-check; 188/200/209 references.
- **Actual behavior**: the enforcement criteria the Phase-C reviewer applies live at 78-80; item 6 names only line 28. Editing only the summary leaves the enforcer without D10's two rules.
- **Verdict**: PARTIAL
- **Detail**: produces T3.

#### P16 — Branch is in §1.7(b) staging mode with no staged copies yet
- **Track claim**: `s17=staged`; reads resolve to live files because `staged-workflow/` is absent pre-implementation.
- **Search performed**: `cat` phase-ledger; `ls` staged-workflow.
- **Code location**: phase-ledger last line `[2026-07-03T09:20Z] phase=A`, earlier line carries `s17=staged`; `staged-workflow/` does not exist.
- **Actual behavior**: staging mode active; no staged mirror on disk, so every `.claude/**` read this review resolved to the live develop-state file, which is correct.
- **Verdict**: CONFIRMED
