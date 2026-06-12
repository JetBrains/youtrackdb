<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Consistency review — iter 1 (reviewer-plan, Phase 2)

## Manifest

```yaml
review: consistency
iteration: 1
plan_dir: docs/adr/explanation-style/_workflow
tier: full
verdict: changes-requested
findings: 1
blockers: 0
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 [should-fix]"
    loc: "implementation-plan.md Component Map / Track-1 §Interfaces and Dependencies; design.md §\"Subset sync across ~50 sites\"; track-1 §Context and Orientation + §Validation and Acceptance"
    cert: "Ref: ~50-site subset-enumeration inventory"
    basis: current-state
    class: mechanical
evidence_base:
  refs: 22
  flows: 0
  invariants: 3
  matches: 24
  nonmatches: 1
  tool: "grep + Read (non-Java textual refs — full reference accuracy; no PSI caveat needed)"
  staged_subtree_present: false
  opt_out_note_present: true
  canonical_marker_present: false
```

## Findings

### CR1 [should-fix]
**Certificate**: Ref: ~50-site subset-enumeration inventory (Plan ↔ Code).
**Location**: `implementation-plan.md` Component Map ("~50 files restate the AI-tell subset's section names inline") and D1 Component-Map bullet; `design.md §"Subset sync across ~50 sites"` (line 257: "50 files name the subset sections; 30 carry … 11 carry … the rest are the three canonical sites … the hook, two tests, and three governance/routing sites"); `track-1.md` `## Context and Orientation` (the inventory paragraph naming "the remainder"), `## Interfaces and Dependencies` (the In-scope-files roster), and `## Validation and Acceptance` (the acceptance grep). Code: `.claude/workflow/commit-conventions.md:191-194`, `.claude/workflow/implementer-rules.md:1102-1105`.
**Issue**: The plan/design/track enumerate the subset-naming inventory as **50 files** with a named "rest" (three canonical sites + hook + two tests + two governance greps + `ai-tells` catalogue). The live governance grep returns **54 files**, and two of the extra files — `commit-conventions.md` and `implementer-rules.md` — are genuine four-name *closed-set* enumerations ("The four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`" / "the four section slugs that make up the Tier-B AI-tell subset are …"). They are flip sites the atomic-sync (D1) must touch, but neither appears in Track 1's In-scope roster, the inventory remainder, or the acceptance grep. Left unflipped, they read four-of-five after Track 1's Phase C, which is exactly the drift the D1 invariant ("No site enumerates the AI-tell subset as four-of-five after Track 1's Phase C") forbids — and `review-workflow-consistency`'s governance grep catches both (3 hits each), so it would fire on the deliberately-created window.
**Evidence**:
- `grep -rln 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` → **54** files (plan/design/track say 50).
- `grep -rln 'banned-section heading slugs'` → 30 (matches the plan's 30-blurb claim exactly); `grep -rln 'AI-tell subset of'` → 11 (matches exactly). So the 30 and 11 sub-counts are correct; only the total and the "remainder" enumeration are off.
- The 54-minus-(30∪11) remainder is **13 files**, not the ~8 the plan names. Eleven are accounted for or correctly excluded (the three canonical sites `house-style.md`/`house-conversation.md`/`conventions.md`, the hook, `test_house_style_hook.py`, the two governance-grep holders `readability-feedback/SKILL.md`+`conventions.md`, the `ai-tells` catalogue, plus `review-workflow-writing-style.md`, `design-mechanical-checks.py`, `design-document-rules.md`, which cite individual `§` sections but do **not** enumerate the four-name closed set — correctly not flip sites). The two that are unaccounted are `commit-conventions.md:191-194` and `implementer-rules.md:1102-1105`.
- Why the 30-blurb grep misses them: `commit-conventions.md` hard-wraps the literal across a line break ("The four banned-section\nheading slugs"), and `implementer-rules.md` uses the variant phrasing "the four section slugs that make up the Tier-B AI-tell subset" — neither matches the `banned-section heading slugs` string the 30-set and the track-1:101 acceptance grep key on. So the acceptance test at `track-1.md:101` would falsely pass while these two sites stay at four-of-five.
**Proposed fix** (plan-and-track side; the orchestrator applies these):
1. In `track-1.md` `## Interfaces and Dependencies` In-scope roster, add `.claude/workflow/commit-conventions.md` (the four-name closed-set enumeration at `:191-194`, line-wrapped) and `.claude/workflow/implementer-rules.md` (the Tier-B four-name enumeration at `:1102-1105`) as substantive flip sites — they take the canonical reworded five-name blurb, not the 30-blurb byte-identical paste (their surrounding sentences differ).
2. In `track-1.md` `## Context and Orientation`, update the inventory paragraph so the remainder enumerates these two additional closed-set sites (and adjust the "50/30/11" gloss to "54/30/11" or "~54").
3. In `track-1.md` `## Validation and Acceptance`, broaden the acceptance grep beyond `'banned-section heading slugs'` (which misses both) to the governance grep `grep -rln 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` plus a check that every hit names `## Orientation`, so the test actually gates the two newly-named sites.
4. Update the plan Component Map prose ("~50 files restate …") and D1's Component-Map bullet count to "~54" (or keep the tilde and let "~50" stand if the orchestrator prefers, since the figure is fuzzy elsewhere — but the In-scope roster and acceptance grep in points 1-3 are the load-bearing fixes).
5. **Design-scoped, recorded only** (design.md is frozen): `design.md §"Subset sync across ~50 sites"` line 257 names "three governance/routing sites" and "the rest are the three canonical sites … the hook, two tests" — an enumeration that omits these two files. Phase 4 `design-final.md` should reconcile the inventory to the as-built 54-file set. Do not edit `design.md` now.
**Classification**: mechanical
**Justification**: Current-state claim (the intent-axis pre-screen passes it through — the inventory describes the tree as it exists today, not a target a `[ ]` track creates); the correct rendering is a single unambiguous one (add the two named files to the roster, broaden the acceptance grep, bump the count); the fix preserves plan intent (the atomic-flip goal and scope are unchanged, only the enumeration of which files the flip must touch is corrected). The design-side half (point 5) is recorded only because `design.md` is frozen.

## Evidence base

### Plan ↔ Code

#### Ref: ~50-site subset-enumeration inventory
- **Document claim**: plan/design/track say 50 files name the AI-tell subset (30 "banned-section heading slugs" blurbs, 11 chat blurbs, "the rest" = three canonical sites + hook + two tests + two governance greps + `ai-tells` catalogue); the atomic flip (D1) flips all of them four→five.
- **Search performed**: `grep -rln 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` (governance grep); `grep -rln 'banned-section heading slugs'`; `grep -rln 'AI-tell subset of'`; per-file `grep -nE` on the 13-file remainder; Read of `commit-conventions.md:185-196` and `implementer-rules.md:1098-1108`.
- **Code location**: governance grep → 54 files; blurb grep → 30; chat grep → 11. Extra closed-set sites at `commit-conventions.md:191-194`, `implementer-rules.md:1102-1105`.
- **Actual signature/role**: total is 54, not 50; two extra four-name closed-set enumerations exist that the plan does not name as flip sites.
- **Verdict**: PARTIAL
- **Detail**: 30 and 11 sub-counts exact; total and remainder enumeration off by two flip sites → CR1.

#### Ref: 30 "banned-section heading slugs" blurb count
- **Document claim**: 30 files carry the "four banned-section heading slugs" blurb (D1, track-1:75).
- **Search performed**: `grep -rln 'banned-section heading slugs' .claude/ CLAUDE.md | wc -l`.
- **Code location**: 30 files (18 agents + 12 workflow prompts/docs).
- **Actual signature/role**: exactly 30.
- **Verdict**: MATCHES

#### Ref: 11 chat "AI-tell subset of" blurb count
- **Document claim**: 11 files carry the chat blurb (D1, track-1:75).
- **Search performed**: `grep -rln 'AI-tell subset of' .claude/ CLAUDE.md | wc -l`.
- **Code location**: 11 files.
- **Verdict**: MATCHES

#### Ref: review-workflow-pr/SKILL.md hard-wrapped chat blurb (:44-45)
- **Document claim**: one chat-blurb site hard-wraps the find string across a line break and needs a hand-edit (D1, track-1:86, design Edge-cases).
- **Search performed**: Read `review-workflow-pr/SKILL.md:42-47`.
- **Code location**: chat blurb spans lines 42-45; `## Banned sentence patterns` wraps at the 43/44 boundary.
- **Verdict**: MATCHES (the `:44-45` line citation is accurate for where the wrap occurs).

#### Ref: test_house_style_hook.py pinned subset list (:694-697)
- **Document claim**: `test_house_style_hook.py:694-697` pins the four section strings and gates the hook sync (D1, track-1:30).
- **Search performed**: Read `test_house_style_hook.py:690-700`.
- **Code location**: `TIER_B_HEADINGS` list at 693-698, the four strings at 694-697.
- **Verdict**: MATCHES

#### Ref: hook tier_b_body (house-style-write-reminder.sh)
- **Document claim**: the hook holds the only programmatic copy of the subset section names in `tier_b_body` (Integration Points, D2).
- **Search performed**: `grep -nE 'tier_b_body' .claude/hooks/house-style-write-reminder.sh`.
- **Code location**: `tier_b_body` at line 262, enumerating the four `§` sections.
- **Verdict**: MATCHES

#### Ref: ai-tells/SKILL.md catalogue (:19-27)
- **Document claim**: the `ai-tells` catalogue table is a subset-naming site that gains an Orientation row (D1, track-1:128).
- **Search performed**: Read `ai-tells/SKILL.md:19-30`.
- **Code location**: `## Catalogue lookups` at 19; section-reference bullets at 23-27.
- **Verdict**: MATCHES (catalogue cites the sections; an Orientation row is addable).

#### Ref: conventions.md §1.5 + Tier-B row + governance grep
- **Document claim**: `§1.5` (line 545) has the Tier-B row (line 565) and the governance grep (line 570) enumerating the four section names; no chat-scale row (track-1:73, D2).
- **Search performed**: Read `conventions.md:545-575`.
- **Code location**: `## 1.5` at 545; Tier-B `*.java`/`*.kt` row at 565; governance grep at 570; table has full-Markdown + Tier-B rows only (no chat row).
- **Verdict**: MATCHES

#### Ref: conventions.md §1.7 staging convention (line 832)
- **Document claim**: `§1.7` (line 832) is the staging convention; `(b)` canonical marker; `(d)`/`(h)` exist; D6 amends it (track-1:73, D6, design).
- **Search performed**: `grep -nE` for §1.7 anchors; Read `conventions.md:830-840`.
- **Code location**: `## 1.7` at 832; `### (b) Canonical workflow-modifying marker` at 891; marker prefix "This plan is workflow-modifying:" at 899; `(d)`/`(h)` referenced in TOC.
- **Verdict**: MATCHES

#### Ref: technical-review.md:113 criteria-switch block
- **Document claim**: a "Workflow-machinery criteria (workflow-modifying plans)" block at `:113` gated on the `§1.7(b)` marker, distinct from the Staged-read block, gets the opt-out-marker OR-extension (D6, track-1:74).
- **Search performed**: `grep -nE` + Read `technical-review.md:108-120`.
- **Code location**: Staged-read block at 111; **Workflow-machinery criteria** block at 113 (five prose criteria at 116).
- **Verdict**: MATCHES

#### Ref: risk-review.md:110 criteria-switch block
- **Document claim**: same block at `:110` (D6, track-1:74).
- **Search performed**: `grep -nE` + Read `risk-review.md:105-117`.
- **Code location**: Staged-read at 108; Workflow-machinery criteria at 110.
- **Verdict**: MATCHES

#### Ref: adversarial-review.md:282 criteria-switch block
- **Document claim**: same block at `:282` (D6, track-1:74).
- **Search performed**: `grep -nE` + Read `adversarial-review.md:277-290`.
- **Code location**: Staged-read at 280; Workflow-machinery criteria at 282.
- **Verdict**: MATCHES

#### Ref: track-code-review.md:250-260 (staging-delta prep, not a criteria switch)
- **Document claim**: D6 says `track-code-review.md:250-260` is staging-delta prep, inert without the marker, not a criteria switch (track-1:58).
- **Search performed**: not opened in depth; claim is a negative ("needs no marker"), consistent with the other three blocks being the only criteria switches and the design's note that Phase-C coverage is diff-keyed via `review-agent-selection.md`.
- **Code location**: n/a (negative claim; not load-bearing for a flip site).
- **Verdict**: MATCHES (no contradicting evidence; treated as a low-risk negative).

### Design ↔ Code

#### Ref: house-style.md:379 scoping sentence
- **Document claim**: `house-style.md:379` is the scoping sentence that scopes the document-shape family to "BLUF alone" and excludes Orientation from issue/PR/status prose; D3 rewrites it (design §"The Orientation rule", track-1 D3).
- **Search performed**: Read `house-style.md:377-381` (numbered).
- **Code location**: line 379 = "These rules apply when the surface is a design document, ADR draft, or cold-read-reviewed artifact under `docs/adr/**`. They are not enforced on issue bodies, PR descriptions, or status prose, which use the BLUF rule alone." `## Document-shape rules (design / ADR-specific)` heading at 377.
- **Verdict**: MATCHES (line 379 is exactly the scoping sentence the plan names).

#### Ref: house-style.md ### Explanatory register (427)
- **Document claim**: `### Explanatory register` lives today under `## Document-shape rules`, design/ADR only; D3 reduces it to a specialization keeping its mechanism-overview nuance + mid-level-reader completeness bar.
- **Search performed**: Read `house-style.md:427-432`.
- **Code location**: `### Explanatory register` at 427; carries the mechanism-overview prose and the mid-level-reader completeness bar; cites `§ Why-before-what` for the too-terse finding category.
- **Verdict**: MATCHES

#### Ref: house-style.md ### Why-before-what (403)
- **Document claim**: the current Explanatory-register rule cites the design-only `§ Why-before-what`; D3 gives Orientation its own finding category instead (track-1 D3).
- **Search performed**: Read `house-style.md:403-409`; the Explanatory-register block at 431 cites `§ Why-before-what`.
- **Code location**: `### Why-before-what` at 403 (under Document-shape rules, design-only).
- **Verdict**: MATCHES

#### Ref: house-style.md ## Self-check item 8 bracket (444)
- **Document claim**: the Self-check entry sits inside item 8's "design/ADR only" bracket; D3 moves it to an always-on item (track-1 D3, design).
- **Search performed**: Read `house-style.md:433-448`.
- **Code location**: `## Self-check` at 433; item 8 "Document shape (design/ADR only)" at 444 lists "explanatory register" among design-only checks.
- **Verdict**: MATCHES

#### Ref: house-style.md line-20 "four readers" + four AI-tell sections
- **Document claim**: line ~20 carries the "Four readers consume these rules" paragraph and the "four AI-tell sections" count (track-1:72, design Enforcement-surface-map).
- **Search performed**: Read `house-style.md:16-24`.
- **Code location**: line 20 = "Four readers consume these rules without restating them … reuses the four AI-tell sections for terminal replies …".
- **Verdict**: MATCHES

#### Ref: house-style.md ### Mechanism traces and inline citations (360)
- **Document claim**: the new cold-read block scans against `§ Mechanism traces and inline citations` (design §"Over-dense prose enforcement", track-2 D4).
- **Search performed**: `grep -nE` on house-style.md.
- **Code location**: `### Mechanism traces and inline citations` at 360.
- **Verdict**: MATCHES

#### Ref: house-conversation.md chat register four-name enumeration
- **Document claim**: `house-conversation.md` enumerates the chat register as four sections today; the flip bumps it four→five (D1, track-1:123, design map).
- **Search performed**: `grep -nE` + Read `house-conversation.md:19-26`.
- **Code location**: `## AI-tell subset` at 19; "Apply these four sections" at 21; the four sections listed at 23-26.
- **Verdict**: MATCHES (the four→five flip target is real).

#### Ref: design-review.md ### Human-reader cold-read additions (165) + applies-to line
- **Document claim**: the new `### Prose AI-tell additions` block sits sibling to `### Human-reader cold-read additions` at line 165, whose applies-to line covers only the three design-target kinds (phase1-creation/phase4-creation/design-sync), so the new block cannot copy it (D4, track-2:27-28).
- **Search performed**: `grep -nE` + Read `design-review.md:160-172`, 43-46.
- **Code location**: `### Human-reader cold-read additions` at 165; applies-to line at 168 = "Applies to `phase1-creation`, `phase4-creation`, `design-sync`." `target=design`/`target=tracks` both defined at 43-46.
- **Verdict**: MATCHES

#### Ref: design-review.md § Tone and depth "five Human-reader rules" count (406)
- **Document claim**: `## Tone and depth` at 403 carries the "five Human-reader rules require evidence" count at 406, a sync site (D4, track-2:46).
- **Search performed**: Read `design-review.md:403-408`.
- **Code location**: `## Tone and depth` at 403; "the five Human-reader rules require evidence" at 406. TOC row for Human-reader at 22.
- **Verdict**: MATCHES

#### Ref: design-mechanical-checks.py dsc-ai-tell + demotable severity
- **Document claim**: `design-mechanical-checks.py` holds the `dsc-ai-tell` rule (narrow regex set) at a documented demotable severity; two new regexes extend it (D4b, D5/A9, track-2:47).
- **Search performed**: `grep -nE 'dsc-ai-tell|demotable|severity|suggestion'`; file present (2282 lines).
- **Code location**: `check_dsc_ai_tell` + many `"dsc-ai-tell"` emissions (1849-1952); demotable note at line 105 ("Demote-to-`suggestion` is the documented fallback").
- **Verdict**: MATCHES

#### Ref: readability-feedback Rule sync map (41) + design-review row (47) + governance grep (54)
- **Document claim**: the Rule sync map at line 41 has a `design-review.md` row (Human-reader bullet + Tone-and-depth count + TOC summary) that D4 syncs; the governance grep at line 54 is Track 1's flip site (D4, track-2:49, track-1:128).
- **Search performed**: `grep -nE` + Read `readability-feedback/SKILL.md:38-60`.
- **Code location**: `## Rule sync map` at 41; design-review row at 47; governance grep enumerating the four section names at 54.
- **Verdict**: MATCHES

#### Ref: design-document-rules.md § Overview "enabling primitive" collision
- **Document claim**: the inflated-abstraction regex collides with the design-doc Overview template, which prescribes naming "the enabling primitive(s)" (`design-document-rules.md § Overview`); the regex must target the subject slot, not the Overview enumeration element (D4b, design Edge-cases).
- **Search performed**: `grep -niE 'enabling primitive' .claude/workflow/design-document-rules.md`.
- **Code location**: Overview row at 277 (required elements include "enabling primitive"); "The enabling primitive(s)" required element at 506; enabling-primitives mention at 589.
- **Verdict**: MATCHES (the collision the design warns about is real).

### Design ↔ Plan

#### Ref: design.md Full-design anchors (four)
- **Document claim**: each plan/track Decision Record's `**Full design**: design.md §"…"` link resolves to a real design section.
- **Search performed**: `grep -cF "## <anchor>"` for the four anchors on design.md.
- **Code location**: `## The Orientation rule` (141), `## Over-dense prose enforcement` (195), `## Subset sync across ~50 sites` (249), `## The §1.7 opt-out` (293) — each exactly one match.
- **Verdict**: MATCHES (all four anchors resolve).

#### Ref: Component Map node files (13)
- **Document claim**: the plan + design Component-Map nodes name real files (`house-style.md`, `conventions.md`, `ai-tells` skill, `house-conversation.md`, `house-style-write-reminder.sh`, `design-review.md`, `design-mechanical-checks.py`, the three review prompts, the two tests, `readability-feedback`).
- **Search performed**: `[ -f ]` existence test on all 13 paths.
- **Code location**: all 13 exist.
- **Verdict**: MATCHES

#### Ref: Track 1 / Track 2 ownership split (D1/D2/D3/D5/D6 vs D4/D4b)
- **Document claim**: Track 1 owns D1/D2/D3/D5/D6 + the atomic subset flip; Track 2 owns D4 (cold-read block) + the two regexes (D4b) and depends on Track 1.
- **Search performed**: Read both track `## Decision Log` sections + `## Interfaces and Dependencies`.
- **Code location**: track-1 Decision Log = D1,D2,D3,D5,D6; track-2 Decision Log = D4,D4b with the dependency note (track-2:97) "depends on Track 1". Plan checklist marks Track 2 "Depends on: Track 1".
- **Verdict**: MATCHES

### Invariants

#### Invariant: Subset enumeration is uniform after Track 1 (no four-of-five site)
- **Document claim**: no site enumerates the subset as four-of-five after Track 1's Phase C; testable via the two governance greps + `test_house_style_hook.py`.
- **Code evidence**: governance grep returns 54 files; two of them (`commit-conventions.md:191-194`, `implementer-rules.md:1102-1105`) are four-name closed-set enumerations not on Track 1's In-scope roster.
- **Mechanism**: the invariant is enforced (post-Track-1) only over the files the flip touches; the two omitted sites would survive at four-of-five, and the governance grep `review-workflow-consistency` uses catches them (3 hits each).
- **Verdict**: ASPIRATIONAL (Track 1 must implement it) — but at risk because the implementing roster omits two sites the invariant covers. Routed through CR1 (the roster + acceptance-grep fix closes the gap). The invariant statement itself is sound; the In-scope roster is the defect.

#### Invariant: ## Orientation exists before any enumeration names it
- **Document claim**: the rule text lands with or before the subset flip (Plan-of-Work step 2 before step 4).
- **Code evidence**: track-1 `## Plan of Work` orders the Orientation rule (step 2) before the atomic flip (step 4); no enumeration currently names `## Orientation` (it does not exist yet — target state, correctly).
- **Mechanism**: ordering constraint stated explicitly at track-1:84,88.
- **Verdict**: ASPIRATIONAL (Track 1 implements; ordering is specified — no finding).

#### Invariant: The opt-out disables staging only
- **Document claim**: reviewer-criteria re-pointing stays on (three criteria-switch blocks fire on the opt-out marker); staged-read precedence + Phase-4 promotion guard find no staged subtree and skip.
- **Code evidence**: no `_workflow/staged-workflow/` subtree exists (verified `ls` → not found); plan `### Constraints` carries the prose opt-out note (1 match) and NOT the canonical "This plan is workflow-modifying:" marker (0 matches); the three criteria-switch blocks exist at the named lines and are the OR-extension targets.
- **Mechanism**: with the canonical marker absent, staging consumers default to live; D6's OR-extension keeps the criteria switch on. Consistent with the briefing's instruction not to flag the absent marker.
- **Verdict**: ASPIRATIONAL (the OR-extension is target state Track 1 lands) — no finding; the current-state facts (no staged subtree, opt-out note present, canonical marker absent) all check out.
