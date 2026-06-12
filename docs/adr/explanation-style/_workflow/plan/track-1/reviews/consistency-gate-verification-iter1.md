<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: CR1, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Findings

<!-- No new findings. The CR1 fix verified clean with no fix-shifted
regressions; the count-bump did not introduce any stray "~50", plan/track
D1 mismatch, broken sub-count, or miscount of the 54 claim. -->

## Evidence base

#### Verify CR1: subset-naming-site inventory undercount (~50 → ~54; two missing closed-set flip sites)
- **Original issue**: the inventory claimed ~50 files, but the governance grep returns 54. Two genuine four-name closed-set enumerations — `commit-conventions.md:191-194` (literal hard-wraps across a line break) and `implementer-rules.md:1102-1105` (variant phrasing) — were missing from Track 1's roster, inventory, acceptance grep, and Plan-of-Work. Both escape the narrow `banned-section heading slugs` grep, so leaving them unflipped would read four-of-five after Phase C, the drift D1 forbids and `review-workflow-consistency`'s governance grep would fire on.
- **Fix applied**: bumped `~50`→`~54` across plan + track-1; added a roster bullet naming both sites; rewrote the inventory paragraph to cite the governance grep (54) with the 30/11 sub-counts and to name the two sites plus the three §-citing non-flip files; rewrote the acceptance check to use the governance grep and name the two sites; named both sites in Plan-of-Work step 4 and the D1 risks. Frozen-design anchors left verbatim.
- **Re-check**:
  - Search/trace performed: ran the governance grep, the two narrow sub-count greps, and a `comm`-based set-difference re-derivation; grep over plan + track-1 + track-2 for every `~50`/`~54` occurrence and every D1 mention (IDE not applicable — all references are Markdown/shell paths and unique literals, so grep+Read give full reference accuracy).
  - Code location: `.claude/workflow/commit-conventions.md:185-194`, `.claude/workflow/implementer-rules.md:1096-1105` (the two flip sites); `implementation-plan.md` D1 block (lines 71-77) + Component Map (lines 22, 50, 59, 69) + Constraints (121, 133) + checklist (140-141); `track-1.md` D1 (27-32), inventory (75), Plan-of-Work step 4 (86), acceptance (101), roster (121-130).
  - Current state — counts, all confirmed by direct execution:
    - Governance grep `grep -rln 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` returns **54** ✓ (matches the inventory, acceptance check, and D1 heading).
    - Narrow `banned-section heading slugs` returns **30** ✓; narrow `AI-tell subset of` returns **11** ✓ (both sub-counts intact, as the inventory and acceptance prose claim).
    - Set arithmetic re-derived: 54 = (30 ∪ 11 ∪ 13 remainder), the 30 and 11 sets have **zero overlap** (clean union), and the 13-file remainder enumerated by `comm` matches the track exactly. Of the 13: the two newly-named closed-set flip sites (`commit-conventions.md`, `implementer-rules.md`); the three §-citing non-flip files (`review-workflow-writing-style.md`, `design-mechanical-checks.py`, `design-document-rules.md`); and the eight canonical/infrastructure files (`house-style.md`, `house-conversation.md`, `conventions.md`, `house-style-write-reminder.sh`, `test_house_style_hook.py`, `test_dsc_ai_tell.py`, `ai-tells/SKILL.md`, `readability-feedback/SKILL.md`). The "54 = 30 ∪ 11 ∪ 13, two-of-13 named, three-of-13 non-flip" claim holds precisely.
    - The two flip sites are present at the cited lines and are genuine four-name closed-set enumerations: `commit-conventions.md` names all four slugs across a line break (so the narrow grep misses it); `implementer-rules.md` uses the variant phrasing "the four section slugs that make up the Tier-B AI-tell subset" naming all four. Both are now in Track 1's roster (`## Interfaces and Dependencies` bullet, line 129), inventory (line 75), acceptance check (line 101), Plan-of-Work step 4 (line 86), and D1 risks (track 30, plan 75).
    - The acceptance check (track-1:101) would now catch them: it keys on the governance grep (54) plus an Orientation-presence check on every closed-set enumeration including the two named sites, and explicitly calls out the old narrow `banned-section heading slugs` grep as insufficient because it returns 30 and silently misses the two line-wrapped / variant-phrased sites.
- **Regression check**: scanned for the four named regression classes across plan + track-1 + track-2 — all clean.
  - (a) Stray "~50": only **two** `~50` occurrences remain (plan:77, track-1:32), both the `design.md §"Subset sync across ~50 sites"` frozen-design anchor. Confirmed the design.md section heading is literally `## Subset sync across ~50 sites` (design.md:249), so the two anchors correctly match the frozen title — not stale counts. No other `~50`/`50-site`/`50 sites`/`50 files` anywhere. Clean.
  - (b) Plan-D1 vs track-1-D1 count mismatch: both D1 headings read "~54-site subset enumeration"; both alternatives, rationale, and risks use ~54 / 30 / 11 / ~40 / ~10 consistently; both name the two sites in the risks. The Component Map, Constraints, Non-Goal, and checklist scope all read ~54. No mismatch. Clean.
  - (c) Broken 30/11 sub-counts: both narrow greps still return 30 and 11 respectively, matching the prose. Clean.
  - (d) Miscount of the 54 claim: re-derived bottom-up (30 + 11 + 13 = 54, disjoint union confirmed by `comm`); the remainder enumeration in both files is exact. Clean.
  - Plan-of-Work / acceptance / roster mutual consistency: step 4 (flip ~54, hand-edit the three narrow-grep-missed sites), the acceptance check (governance grep 54 + Orientation-presence on every closed-set enumeration), and the roster (~54 in-scope, the two sites named with anchors) name the same site set and the same count. The §1.7 opt-out posture is intact: no `This plan is workflow-modifying:` marker and no `_workflow/staged-workflow/` subtree, so the absent staging marker is correct and not a finding. Clean.
- **Verdict**: VERIFIED
