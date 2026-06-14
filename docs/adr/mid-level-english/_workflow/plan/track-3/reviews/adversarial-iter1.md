<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: A1, sev: suggestion, loc: ".claude/agents/dr-audit.md:22", anchor: "### A1 ", cert: C2, basis: "dr-audit blockquote slug list has no count word and a trailing clause; step 1's generic 'after Orientation' insert needs a per-file note so the slug lands before the period, not appended"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 1}
cert_index:
  - {id: C1, verdict: SURVIVES, anchor: "#### C1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: SURVIVES, anchor: "#### C3 "}
  - {id: C4, verdict: SURVIVES, anchor: "#### C4 "}
  - {id: C5, verdict: SURVIVES, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [suggestion]
**Certificate**: C2 (Assumption test — "after `## Orientation`, matching slug order" inserts cleanly in every enumerating agent)
**Target**: Plan of Work step 1 (the slug-insertion instruction) as applied to `dr-audit.md`
**Challenge**: Step 1 is one generic instruction for all 19 enumerating agents: "Add `## Plain language` after `## Orientation`, matching slug order. Flip any numeric 'five' to 'six'." It reads as if every site is the same shape. It is not. Eighteen agents carry the canonical prose preamble — a sentence ending "...the **five** AI-tell subset section slugs to apply are `## Banned vocabulary`, ..., and `## Orientation`." — where "after Orientation" means append the sixth slug at the end and flip "five"→"six". The 19th, `dr-audit.md:22`, is a different shape: a blockquote (`> **House style for chat-scale prose.**`) whose slug list reads "...`### Em-dash discipline`, and `## Orientation`. Structural rules (...) do not apply at chat scale." There is no "five" to flip (verified: `grep -c 'five' dr-audit.md` returns 0), and "after Orientation" is mid-sentence — the new slug must be inserted before the sentence-closing period and before the "Structural rules" clause, changing "..., and `## Orientation`." to "..., `## Orientation`, and `## Plain language`." A literal append or a "flip five→six" that does not exist would corrupt this one file. The track survives because its `## Context and Orientation` already calls out dr-audit as the odd file ("phrases the same five-slug list slightly differently (`:22`)") and step 1's count flip is already conditioned on "any numeric 'five'", which correctly no-ops on dr-audit. So the assumption holds — but only because the reader cross-references Context against the generic step; the step text alone, read in isolation, invites a wrong edit on dr-audit.
**Evidence**: `.claude/agents/dr-audit.md:22` — blockquote slug list, no count word, trailing "Structural rules (...)" clause. Contrast `.claude/agents/code-reviewer.md:20` — prose preamble, "the five AI-tell subset section slugs to apply are ... and `## Orientation`." `grep -c 'five' dr-audit.md` = 0; `grep -c 'AI-tell subset section slugs' dr-audit.md` = 0; both = 1 in code-reviewer.md.
**Proposed fix**: At Phase A decomposition, give dr-audit.md its own micro-step or an inline per-file note in step 1: insert `## Plain language` as the new last slug *before the closing period* of the blockquote sentence (no count word exists to flip), preserving the trailing "Structural rules (...) do not apply at chat scale." clause. No plan/track decision changes — this is a decomposition-precision note, not a re-scope.

## Evidence base

#### C1 Scope test — is the in-scope set (19 enumerating agents + `review-workflow-writing-style.md`) complete and correct?
- **Claim**: The track's in-scope set is exactly the 19 `.claude/agents/*.md` files that carry the AI-tell subset enumeration, plus `review-workflow-writing-style.md` for a content edit — 20 of 20 agent files, with no agent that names the subset left out and no listed file that should not be touched.
- **Stress scenario**: Look for an agent that names the AI-tell subset (so it should be in scope) but is absent from the list, or a listed agent that carries no enumeration (so it should not get a slug add).
- **Code evidence**:
  - `ls .claude/agents/*.md | wc -l` = 20. The plan touches exactly these 20 (19 + writing-style).
  - `grep -rln 'Banned analysis patterns' .claude/agents/` = 19 files — byte-identical to the track's enumerated list in `## Interfaces and Dependencies`.
  - `grep -rln 'house-style\|house style\|AI-tell' .claude/agents/` = all 20 files. Every agent file references house-style; the partition is clean (19 enumerate the subset; the 20th, review-workflow-writing-style.md, enforces it as lenses).
  - The "untouched agent" sweep (`for f in agents/*.md; do if not 'Banned analysis patterns' and not the writing-style file; then echo`) returned **nothing** — no agent file falls outside the 20-file scope. There is no agent that names the subset and is missing from the list, and no listed file lacks an enumeration.
  - `grep -c 'Em-dash discipline'` per agent = exactly 1 for all 20 — no agent carries a hidden *second* enumeration site the single-insert step would miss.
- **Verdict**: HOLDS. The footprint (20 files, ~20 figure approximate per the lite-tier derivation note) and the 19/1 split are complete and correct. The track's reconciliation rule (`grep -rln 'Banned analysis patterns' .claude/agents/` plus the writing-style file at Phase A) reproduces exactly this set.

#### C2 Assumption test — "after `## Orientation`, matching slug order" inserts cleanly in every enumerating agent
- **Claim**: Step 1's single generic instruction inserts the sixth slug correctly into all 19 enumerating agents.
- **Stress scenario**: Apply the literal instruction ("add after Orientation, flip five→six") to the one agent whose enumeration is not the canonical prose preamble.
- **Code evidence**: 18 agents match `grep 'AI-tell subset section slugs'` and carry "the five"; `dr-audit.md` matches neither (both greps = 0) — it is a blockquote (`:22`) with the slug list mid-sentence, no count word, followed by a "Structural rules (...)" clause. A literal "flip five→six" no-ops correctly (the step says "any numeric 'five'"), but "after Orientation" is ambiguous on this file: the slug must land before the sentence period, not appended after the trailing clause. The track's Context section pre-empts the error by flagging dr-audit explicitly, so the assumption holds via cross-reference, not via the step text alone.
- **Verdict**: FRAGILE (holds, but barely — the generic step text invites a wrong edit on dr-audit unless the reader honors the Context callout). Produces finding A1 as a decomposition-precision suggestion.

#### C3 Assumption test — cross-track-episode reality: Track 1's `## Plain language` section exists before Track 3 names it
- **Claim**: The track depends on Track 1; the `## Plain language` section must exist in `house-style.md` before these agents name or check it.
- **Stress scenario**: Track 3 derives over a Track-1 output that does not actually exist yet.
- **Code evidence**: `grep -n '^## Plain language\|^## Orientation' house-style.md` → `## Orientation` at line 54, `## Plain language` at line 78. The section exists, sits *after* `## Orientation` (the canonical order the slug list must mirror), and the full `## ` heading sweep confirms the ordering. Track 1's episode in the plan (`implementation-plan.md:116`) records authoring it. The dependency is satisfied.
- **Verdict**: HOLDS. The slug the 19 agents will name and the lens the writing-style agent will check both resolve to a real, correctly-placed section.

#### C4 Assumption test — cross-track-episode reality: the 19 agents currently carry the five-slug preamble in the claimed form
- **Claim**: The 19 agents carry the five-slug preamble (most with "five"; dr-audit as a blockquote with no count word); `review-workflow-writing-style.md` carries no enumeration but actively enforces house-style lenses, so it needs a content edit not a slug add.
- **Stress scenario**: The realized agent bodies differ from what the track assumes — e.g., already six slugs, or the writing-style agent already enumerates, or already carries a Plain-language lens.
- **Code evidence**: `grep 'the six' .claude/agents/` = 0 (no agent pre-flipped). 18 agents carry "the five" + "AI-tell subset section slugs"; dr-audit carries the five-slug blockquote with 0 "five". `review-workflow-writing-style.md`: `grep 'Banned analysis patterns'` = 0 (no enumeration), but its body actively enforces — line 28 "BLUF lead", line 29 "Banned vocabulary — apply the Tier 1-4 lists", line 30 "Em-dash cap", lines 69/74/78/84 the Banned-vocabulary sweep / Em-dash overuse / BLUF / section-cap checks. `grep -i 'plain language'` = 0 (no Plain-language lens yet). Every assumption the track states about the realized inputs is true on disk.
- **Verdict**: HOLDS. The five→six flip is unstarted, dr-audit's odd-shape claim is accurate, and the writing-style agent genuinely needs a lens (D3-1), not a slug add.

#### C5 Violation scenario — does any step violate a plan invariant?
- **Invariant claim**: (i) After all three tracks land, every site that names the subset lists exactly the six slugs in canonical order and every numeric count reads "six"; (ii) the new section governs general English only.
- **Violation construction attempt**:
  1. Start state: 19 agents at five slugs (18 with "five", dr-audit with none); writing-style agent with no lens.
  2. Action sequence: step 1 appends `## Plain language` after `## Orientation` in all 19 and flips "five"→"six" where present; step 2 adds a judgment-shaped Plain-language check to writing-style's enforce list + checks section.
  3. Intermediate state: 19 agents at six slugs in canonical order (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, `## Plain language` — matching the plan invariant's canonical list at `implementation-plan.md:101`); counts read "six" where a count word exists.
  4. Violation point: none reachable. The only way step 1 breaks invariant (i) is the dr-audit mid-sentence insert (covered by A1 as a precision note, not an order/count violation — the slug still lands last, in order). Invariant (ii) is untouched: the track adds a *slug name* and a *judgment lens*, never any text that bans/replaces/re-teaches a Java/database term; D3-1 explicitly keeps the new check "judgment-shaped (no count, no regex)", and the writing-style lens reports plain-language quality as a finding, not a score — it cannot re-ban a technical term.
- **Feasibility**: INFEASIBLE — no constructible action sequence in this track's two steps violates either invariant. The dr-audit edge is a phrasing-placement nuance, not an order or count or governs-general-English-only breach.
