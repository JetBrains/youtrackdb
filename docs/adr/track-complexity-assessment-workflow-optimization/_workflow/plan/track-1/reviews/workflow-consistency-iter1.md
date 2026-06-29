<!--MANIFEST
dimension: workflow-consistency
iteration: 1
verdict: CHANGES_REQUESTED
findings: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - { id: WC1, sev: should-fix, anchor: "### WC1", loc: "docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md:31-32,563,1052", cert: n/a, basis: judgment }
-->

## Findings

### WC1 [should-fix] — staged `create-plan/SKILL.md` cross-refs dangle on the heading + glossary terms Track 1 renamed

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 31-32, 563, 1052)
- **Axis:** glossary and term consistency (renamed-referent cross-reference within one track's staged tree)
- **Cost:** three dangling section/term references at Phase-4 promotion; the promoted live `create-plan/SKILL.md` would cite a `conventions.md` section heading and two glossary terms that no longer exist.

**Issue.** Step 3 of this track renamed, in the staged `conventions.md`:
- the section heading `### Per-tier artifact set` → `### Per-axis artifact set` (staged `conventions.md:230`, TOC row `:9`), and
- the two glossary rows `**Change tier**` and `**Tier gates**` → `**Complexity axes**` and `**Design gate**` (staged `conventions.md:84-85`).

Three cross-references in the staged `create-plan/SKILL.md` still point at the old names and were not re-keyed:
- line 31-32 — names the `conventions.md` glossary as "including the **change tier** and **tier gates** terms" **and** cites "the `§1.2` *Per-tier artifact set*";
- line 563 — `` (`conventions.md` `§1.2` *Per-tier artifact set*) ``;
- line 1052 — `` the `§1.2` *Per-tier artifact set* ``.

The referent resolves entirely inside Track 1's own staged tree: the renamed heading/terms live in the Step-3-edited staged `conventions.md`, and the citing prose is in the Step-2-edited staged `create-plan/SKILL.md`. Both files are Track-1-owned and promote together in the single Phase-4 commit, so this is an **intra-track** dangling reference, not a Track-2 forward obligation. The three lines are byte-identical to develop (verified by `diff` — only line numbers shifted from Step-2 insertions), confirming they were verbatim-copied while the rename in the same track silently invalidated them. The two sibling cross-refs into the same renamed heading *were* updated correctly — staged `plan-slim-rendering.md:197` reads `§Per-axis artifact set` — which isolates `create-plan/SKILL.md` as the miss. (The Step-3 episode logged a boundary call about residual `lite`/`full`/`minimal` vocabulary inside `conventions.md` itself, but did not cover these inbound cross-refs from `create-plan/SKILL.md`.)

**Suggestion.** In the staged `create-plan/SKILL.md`, re-key all three citations: `§1.2 *Per-tier artifact set*` → `§1.2 *Per-axis artifact set*` (lines 32, 563, 1052), and update the line-31 glossary-term list from "the **change tier** and **tier gates** terms" to the renamed terms (e.g. "the **complexity axes** and **design gate** terms") to match the staged `conventions.md` glossary rows.

## Evidence base
