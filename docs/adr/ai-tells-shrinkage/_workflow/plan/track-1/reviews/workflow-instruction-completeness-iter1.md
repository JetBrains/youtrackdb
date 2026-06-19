<!--MANIFEST
review: workflow-instruction-completeness
iteration: 1
track: 1
range: e68f84f760f852aee1a53ff71fb1e46102642638..HEAD
findings: 2
high_water_mark: 2
evidence_base: 2
cert_index: 2
flags: []
index:
  - id: WI1
    sev: Recommended
    anchor: "#wi1-recommended-conventionsmd607-phantom-banned-vocabulary-section-name"
    loc: ".claude/workflow/conventions.md:607"
    cert: C1
    basis: judgment
  - id: WI2
    sev: Recommended
    anchor: "#wi2-recommended-code-reviewskillmd377-stale-reviewer-roster-description"
    loc: ".claude/skills/code-review/SKILL.md:377"
    cert: C2
    basis: judgment
-->

## Findings

### WI1 [Recommended] conventions.md:607 phantom "banned vocabulary" section name

- **File:** `.claude/workflow/conventions.md` (line 606-608)
- **Axis:** phase output → next-phase input (consumer-coverage completeness: a phantom cross-reference to a removed section)
- **Cost:** the §1.5 declarative-source listing still names a `house-style.md` section the shrink deleted, so a reader pointed here to learn what `house-style.md` contains is told it carries a `## Banned vocabulary` section that no longer exists.
- **Issue:** §1.5 opens with the Tier-A prose "That file is the single declarative source: BLUF lead, **banned vocabulary**, banned sentence patterns, banned analysis patterns, punctuation and typography, structural rules, and document-shape rules." Move 2 of the track updated the §1.5 *Tier-B table row* (`:621`), the count word (`:624`), and the rename-safety grep (`:626`), but left this Tier-A source-enumeration sentence (`:607`) naming "banned vocabulary". The section is gone from `house-style.md` (grep confirms its only `## `/`### ` headings are now Orientation, Plain language, Banned sentence patterns, Banned analysis patterns). The track's own focus calls out §1.5 as needing to "describe a complete, internally consistent procedure"; this is the one §1.5 pointer the consumer sweep missed.
- **Why DR7's oracle missed it:** the §1.5 rename-safety grep (extended with `-i 'sycophantic|signposting|copula|knowledge.cutoff'`) does not include the bare lexeme "banned vocabulary", so the prose phrase at `:607` is invisible to the completeness oracle. It is a paraphrase reference of exactly the kind DR7's *manual* scan was supposed to catch.
- **Suggestion:** drop "banned vocabulary, " from the `:607` listing so it reads "BLUF lead, banned sentence patterns, banned analysis patterns, punctuation and typography, structural rules, and document-shape rules" — matching the post-shrink `house-style.md` section set and the parallel edits already made to `commit-conventions.md`, `episode-format-reference.md`, `implementer-rules.md`, and `step-implementation.md`.

### WI2 [Recommended] code-review/SKILL.md:377 stale reviewer-roster description

- **File:** `.claude/skills/code-review/SKILL.md` (line 377)
- **Axis:** sub-agent handshake (the dispatch roster's description of a reviewer no longer matches the reviewer's own contract)
- **Cost:** the `/code-review` roster advertises `review-workflow-writing-style` as enforcing "banned vocabulary, em-dash cap" — two rules the track removed — so the orchestrator's roster view of what the writing-style reviewer covers is stale relative to the agent it dispatches.
- **Issue:** the roster entry reads "16. **review-workflow-writing-style** — house-style: **banned vocabulary, em-dash cap**, BLUF lead, soft section length cap with template-bound exemptions, repo-anchored voice." The track edited the agent's own frontmatter `description` (`agents/review-workflow-writing-style.md:3`) from "AI-tells, em-dash cap, ..." to "AI-tells, banned sentence and analysis patterns, ...", but `code-review/SKILL.md` is **not in the changed-files set** for this track, so its mirroring roster copy was never swept. Grep confirms `:377` is the only remaining live "em-dash cap" / "banned vocabulary" pair under `.claude/` outside the track file.
- **Why DR7's oracle missed it:** same gap as WI1 — the rename-safety grep matches the surviving slug names, not the bare lexemes "banned vocabulary" / "em-dash cap", and the file sits outside the named-consumer scan list in DR7, so neither the grep nor the manual scan covered it.
- **Suggestion:** update the `:377` roster entry to mirror the agent's new frontmatter, e.g. "house-style: banned sentence and analysis patterns, BLUF lead, soft section length cap with template-bound exemptions, repo-anchored voice." Consider widening the DR7 acceptance grep to include the bare lexemes `banned vocabulary` and `em.?dash` so a future shrink's oracle surfaces paraphrase references in roster/description prose.

## Evidence base

#### C1 — conventions.md:607 names a deleted section
- **Survives (one line):** `sed -n '604,611p' conventions.md` shows the Tier-A source listing still reads "banned vocabulary" at `:607`; `grep -nE '^## |^### ' house-style.md` confirms no `## Banned vocabulary` heading remains (survivors: Orientation, Plain language, Banned sentence patterns, Banned analysis patterns). The post-shrink §1.5 grep does not match the bare lexeme, so the reference is a confirmed phantom the oracle cannot see. Confirmed.

#### C2 — code-review/SKILL.md:377 unswept roster copy
- **Survives (one line):** `grep -c 'code-review/SKILL.md' /tmp/claude-code-track-1-files-1764.txt` returns 0 (file not in the track's changed set); `grep -rniE 'em.dash cap|em.dash overuse' .claude/ CLAUDE.md` returns only `code-review/SKILL.md:377`, while the agent's own frontmatter was updated to "banned sentence and analysis patterns" — confirming the roster description drifted from the reviewer's contract. Confirmed.
