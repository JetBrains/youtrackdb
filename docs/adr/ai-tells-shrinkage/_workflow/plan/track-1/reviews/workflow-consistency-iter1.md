<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
dimension: workflow-consistency
target: "Track 1 (commits c24f222228..f69062032c) — shrink house-style to comprehension-serving rules; remove six concealment-only rules + four checker patterns; update every consumer in lockstep"
iteration: 1
verdict: CHANGES_REQUESTED
findings_total: 1
blockers: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WC1
    sev: should-fix
    anchor: "### WC1 [should-fix] code-review/SKILL.md:377 stale reviewer-roster description"
    loc: ".claude/skills/code-review/SKILL.md:377"
    cert: n/a
    basis: "live read of code-review/SKILL.md:377 against the rewritten review-workflow-writing-style.md:3 description and the removed house-style.md sections; broad lowercase-prose paraphrase grep across .claude/ + CLAUDE.md"
-->

## Findings

### WC1 [should-fix] code-review/SKILL.md:377 stale reviewer-roster description

- File: `.claude/skills/code-review/SKILL.md` (line 377), Axis: cross-file rule restatement, Cost: summary-vs-source drift — the dispatcher roster advertises removed capabilities for the `review-workflow-writing-style` agent; half-applied lockstep (the agent side was updated, the roster side was not). Issue: line 377 describes the agent as "house-style: **banned vocabulary, em-dash cap**, BLUF lead, soft section length cap with template-bound exemptions, repo-anchored voice." Both "banned vocabulary" and "em-dash cap" name rules this track removed from `house-style.md`. The referent — the agent's own `description` frontmatter at `.claude/agents/review-workflow-writing-style.md:3` — was rewritten in this same track (diff line 266) to "AI-tells, **banned sentence and analysis patterns**, BLUF lead, soft section cap with template-bound exemptions." The roster summary in `code-review/SKILL.md` was not updated in lockstep, so the dispatcher now misadvertises what the reviewer checks. This file is not in the track's changed-files list at all, which is why it slipped: it carries the rule names as bare lowercase prose ("banned vocabulary", "em-dash cap"), so neither the DR7 section-name acceptance grep (`Banned vocabulary|Em-dash discipline`, which matches the `## `/`§ ` slug forms) nor the slug sweep caught it. It is a documentation-accuracy drift, not a dispatch break — the `subagent_type` name is correct and the agent still runs — so it is should-fix rather than blocker. Suggestion: edit line 377 to mirror the agent's rewritten description, e.g. `**review-workflow-writing-style** — house-style: banned sentence and analysis patterns, BLUF lead, soft section length cap with template-bound exemptions, repo-anchored voice.`

## Evidence base

<!-- Evidence-trail-exempt dimension: no refutation or certificate phase to persist. -->
