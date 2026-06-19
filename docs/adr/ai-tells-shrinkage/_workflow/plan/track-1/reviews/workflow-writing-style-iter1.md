<!--MANIFEST
dimension: workflow-writing-style
target: "Track 1 (commits c24f222228, f69062032c) — shrink house-style to comprehension-serving rules; remove six concealment-only rules and update consumers in lockstep"
iteration: 1
verdict: PASS
findings_total: 0
blockers: 0
evidence_base:
  certs: 0
cert_index: []
flags: []
index: []
-->

## Findings

No findings. The track's added and modified prose complies with the four surviving AI-tell-subset rules (`§ Orientation`, `§ Plain language`, `§ Banned sentence patterns`, `§ Banned analysis patterns`), the BLUF lead, and the soft section cap. The review applied the post-shrink rule set as the agent definition now states it; the removed axes (em-dash count, banned vocabulary, signposting, sycophantic openers, copula avoidance) were deliberately not scanned per the self-application note in the dispatch.

Verified:

- **Rewritten `§ Plain language` first move (`house-style.md`, diff line 468).** "When a vague adjective stands in for a specific quality, name the quality: `robust` → 'tolerant of <X>' naming the X; `comprehensive` → 'covers X, Y, Z'." Clean, concrete, leads with the claim, and supplies two worked examples. The folded precision intent reads as one well-formed clause; no padding, no synonym cycling. The block stays well under the 200-word soft cap.
- **`ai-tells/SKILL.md:3` description rewrite.** Leads with what the skill does ("Reviews any draft for AI writing tells and produces a clean rewrite"), then names three fingerprint families with concrete in-line examples. BLUF-led, no banned sentence or analysis pattern. The literal "negative parallelism like 'It's not X, it's Y'" is a named example of a tell, used as a meta-reference, not the description itself committing the pattern.
- **`design-document-rules.md:287` `dsc-ai-tell` row rewrite.** The cell is a single long table sentence enumerating the seven surviving patterns. As a table row the length cap does not bite, and the prose is a straight subtractive mirror of the pre-existing eleven-pattern form with no new padding pattern introduced.
- **`house-conversation.md` no-preamble rule and subset list (diff lines 429, 436-446).** The expanded no-preamble bullet ("Skip the sycophantic opener …, the signposting opener …, and the … closer") is a clean parallel list. The four-section subset list and the `## Plain language` line ("prefer the precise or common word …") carry no banned pattern.
- **`readability-auditor.md` added ownership paragraph (line 60).** "Your axis is judgment, not the mechanical count" was weighed against the surviving negative-parallelism rule. It is a genuine plain contrast carrying distinct information in each half (your scope is judgment; the count is the checker's), with no emphatic `not just` / `not merely` intensifier and an immediate positive elaboration in the same paragraph. It falls on the allowed side of the trailing-negation carve-out (`§ Banned sentence patterns` distinguishes a genuine plain contrast from the depth-performing shape), so it is not a finding. The paragraph is concrete and within the section cap.
- **`CLAUDE.md` § Writing Style edits (diff lines 1644, 1653).** Both edits name the tells as a literal example list inside a meta-description of the LLM register; "'It's not X — it's Y' negative parallelism" is a quoted example, not authored negative parallelism. No finding.
- **Bootstrap-slug six→four sweep (~46 files), the hook body, and the `house-style.md` Self-check renumber.** Mechanical slug-list and count-word edits; the prose around each edit is unchanged and carries no new style content to review.
- **Track-file Decision Records and Episodes (`track-1.md`).** DR and episode prose is dense but information-bearing and BLUF-led per the DR/episode templates. No negative parallelism, throat-clearing, closing phrase, trailing hedge, or prompt-restating in the added prose; no non-exempt unit over the soft cap carries a padding pattern.

## Evidence base

<!-- Clean pass: no surviving or refuted findings to persist. The per-surface
verification above is the cert material — each line records the surviving-style
check (banned-sentence-pattern sweep result or section-length three-step
decision) that confirms the surface is clean against the post-shrink rule set. -->
