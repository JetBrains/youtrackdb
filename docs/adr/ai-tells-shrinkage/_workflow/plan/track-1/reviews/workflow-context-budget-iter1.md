<!--MANIFEST
dimension: workflow-context-budget
target: "Track 1 (e68f84f760..HEAD) — remove six concealment-only rules from house-style.md, shrink check_dsc_ai_tell from eleven to seven patterns, update every consumer in lockstep including always-loaded surfaces (ai-tells/SKILL.md:3, root CLAUDE.md, conventions.md §1.5, the 47 bootstrap-slug lines)"
iteration: 1
verdict: PASS
findings_total: 0
blockers: 0
evidence_base:
  certs: 0
flags: []
cert_index: []
index: []
-->

## Findings

No findings. Every axis a context-budget review covers is either a net reduction or neutral, and the deterministic `workflow-reindex.py --check` half ran clean.

Verified:

- **Always-loaded surface is a net reduction, no self-contradiction.** The change removes rule text from each always-loaded surface and leaves no stale lead-count or removed-section pointer. `ai-tells/SKILL.md:3` description: −221 bytes, names no removed tell (grep for `delve|tapestry|foster|em dash|knowledge.cutoff|leverage|robust|multifaceted` returns RC=1). `house-conversation.md`: −80 bytes, the AI-tell-subset list reads "Apply these four sections" over a four-bullet list (line 21) — count word matches list. `conventions.md §1.5`: −136 bytes, Tier-B row and "four Tier-B section names" prose both shrunk in lockstep. Root `CLAUDE.md`: +16 bytes, a semantically-neutral swap of the example AI-tell phrasing and the §Structural-rules clause; far under any threshold. A live grep for `six AI-tell|six Tier-B|six section|Banned vocabulary|Em-dash discipline` across all four files returns RC=1 (no surviving "six" lead, no phantom removed-section reference).
- **Bootstrap-slug line shrank, replicated 47×.** Each single-line copy drops from 483 to 434 chars (−49 chars/copy); the wrapped multi-line copies and the Tier-A category descriptors dropped the same two slugs. This is a per-turn saving on every file that loads, paid 47 times.
- **Load-on-demand discipline intact.** The largest single reduction, `house-style.md` at −5342 bytes, stays in the load-on-demand output-style file (body, loaded only when an authoring session reads it). No removed content was pushed onto an always-loaded surface; no always-loaded file grew with inline rules. No structural drift.
- **Instant per-operation consumption reduced.** `check_dsc_ai_tell` lost four patterns and ~120 lines of regex/scan code (Tier-1 vocab table, em-dash density scan, signposting, copula), so each `edit-design` Step-3 invocation pulls a smaller checker into context; the fixture and test shrank in lockstep. No new orchestrator-side read, sub-agent dispatch, inlined recipe, or multi-phase content reuse was introduced anywhere in the diff.
- **Deterministic schema gate clean.** `python3 .claude/scripts/workflow-reindex.py --check` exits 0 with zero stdout findings and empty stderr (full-repo walk, used because the changed workflow-machinery `.md` set exceeds 25 files). No §1.8 TOC, annotation, cross-file-ref, in-file-stamp, or bootstrap-block finding on any changed file.

## Evidence base

<!-- Evidence-trail-exempt dimension: (a) no refutation or certificate phase to persist. -->
