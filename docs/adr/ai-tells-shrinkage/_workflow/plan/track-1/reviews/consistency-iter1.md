<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
role: reviewer-plan
phase: 2
review_kind: consistency
tier: minimal
target: docs/adr/ai-tells-shrinkage/_workflow/plan/track-1.md
iter: 1
verdict: PASS
findings: 1
blockers: 0
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 "
    loc: "track-1.md § Context and Orientation (the always-loaded skill description bullet) + Plan of Work move 6"
    cert: "Ref: skills/ai-tells/SKILL.md body removed-tell references"
    basis: PARTIAL
    classification: mechanical
evidence_base:
  refs_checked: 15
  matches: 14
  partial: 1
  mismatches: 0
  not_found: 0
  flows_checked: 0
  invariants_checked: 0
  gaps_orphan_construct: "clean — every bootstrap-slug file is in the 47-file sweep or the named-consumer scan; no orphan consumer"
-->

## Findings

### CR1 [should-fix]
**Certificate**: Ref: `skills/ai-tells/SKILL.md` body removed-tell references
**Location**: `track-1.md` § Context and Orientation ("The always-loaded skill description" bullet, last sentence: "It hard-codes removed tells by name … alongside kept ones") and § Plan of Work move 6 ("`skills/ai-tells/SKILL.md`: rewrite the body and the line-3 `description` to drop the removed-tell names … while keeping the kept ones"). Code: `.claude/skills/ai-tells/SKILL.md`.
**Issue**: The track says the SKILL body references the removed tells by name, so move 6's body rewrite drops those names. The body names no removed tell verbatim — "delve", "foster", "em dash overuse", "knowledge-cutoff disclaimers" appear only in the line-3 `description` field (DR9 / the SKILL.md:3 claim, which is accurate). What the body actually carries is a pointer to a **removed section** by name (`§ Banned vocabulary`, line 23) and a pointer to a section that **survives but loses a subsection** (`§ Punctuation and typography`, line 21 — its `### Em-dash discipline` subsection is in the REMOVE set). An implementer following move 6 literally ("drop the removed-tell names from the body") finds none in the body and may leave the body's `§ Banned vocabulary` pointer un-repointed — the phantom-reference state DR7 exists to prevent, on the no-structural-review minimal tier.
**Evidence**: `grep -in 'delve|foster|em.dash overuse|knowledge-cutoff disclaimer'` over `.claude/skills/ai-tells/SKILL.md` matches line 3 only (the `description`). The body's catalogue-lookup list (lines 21-29) routes by section name: line 23 → `house-style.md § Banned vocabulary` (removed wholesale by DR2), line 21 → `house-style.md § Punctuation and typography` (kept, but loses `### Em-dash discipline`). Line-3 description content matches the track's named removed-tell strings exactly; the body does not.
**Proposed fix**: In move 6, change the `skills/ai-tells/SKILL.md` instruction so the body edit re-points its removed-section pointers (drop or re-point the `§ Banned vocabulary` catalogue-lookup line at SKILL.md:23; the `§ Punctuation and typography` line at SKILL.md:21 stays, since that section survives — only its em-dash subsection leaves). Keep the existing "line-3 `description`: drop the removed-tell names" clause unchanged, since that is where the named tells actually live. Optionally tighten the Context-and-Orientation gloss to say the body references removed *sections*, while the line-3 description hard-codes the removed *tell names*.
**Classification**: mechanical
**Justification**: Current-state claim (a §-Context-and-Orientation factual description of the file as it exists now; the pre-screen passes it as current-state per the carve-out that Context-and-Orientation claims are always current-state). One unambiguous correct rendering — the body's removed reference is the `§ Banned vocabulary` pointer at SKILL.md:23, not a tell name — and the fix updates only the description of the work, not the work's goal (move 6 still re-points every removed reference in the file).

## Evidence base

#### Ref: house-style.md line count
- **Document claim**: `track-1.md` § Context and Orientation: "`house-style.md` (492 lines) is the single declarative source".
- **Search performed**: `wc -l .claude/output-styles/house-style.md`.
- **Code location**: `.claude/output-styles/house-style.md`.
- **Actual signature/role**: 492 lines.
- **Verdict**: MATCHES

#### Ref: house-style.md planned-removal sections exist under the claimed names
- **Document claim**: DR2 / Validation: `house-style.md` contains `## Banned vocabulary` (Tier 1-4 incl. era-specific Tier 4), `### Em-dash discipline`, a knowledge-cutoff-disclaimer bullet AND a sycophantic-openers bullet under `## Banned sentence patterns`, and `### Signposting` + `### Copula avoidance` under `## Banned analysis patterns`.
- **Search performed**: full Read of `.claude/output-styles/house-style.md`.
- **Code location**: `## Banned vocabulary` (line 98) with `### Tier 1` (102), `### Tier 2` (108), `### Tier 3` (114), `### Tier 4 — era-specific (current as of May 2026)` (118); `### Em-dash discipline` (326, under `## Punctuation and typography` at 324); `## Banned sentence patterns` (136) with the **Sycophantic openers** bullet (142) and the **Knowledge-cutoff disclaimers** bullet (147); `### Signposting` (291) and `### Copula avoidance` (164) both under `## Banned analysis patterns` (149).
- **Actual signature/role**: every planned-removal target exists under the exact claimed heading name, including the era-specific Tier 4.
- **Verdict**: MATCHES

#### Ref: § Plain language "Reconciliation with § Banned vocabulary" subsection
- **Document claim**: DR6 / Invariant: `§ Plain language` holds a "Reconciliation with § Banned vocabulary" subsection that the track removes.
- **Search performed**: full Read of house-style.md.
- **Code location**: `.claude/output-styles/house-style.md:92`.
- **Actual signature/role**: bold sub-head `**Reconciliation with § Banned vocabulary.**` under `## Plain language`; its stated purpose is dividing labour with the section being deleted.
- **Verdict**: MATCHES

#### Ref: house-style.md:6 framing line and :20 four-consumer list
- **Document claim**: move 1: "the `house-style.md:6` framing 'Every rule below applies to every paragraph' and the `:20` four-consumer list stay".
- **Search performed**: `sed -n '6p;20p'`.
- **Code location**: house-style.md:6, house-style.md:20.
- **Actual signature/role**: line 6 ends "Every rule below applies to every paragraph you write."; line 20 is "Four readers consume these rules without restating them: the `ai-tells` skill …, the cold-read prompt in `prompts/design-review.md`, the `dsc-ai-tell` rule in `scripts/design-mechanical-checks.py`, and the default conversation style `house-conversation.md` …".
- **Verdict**: MATCHES

#### Ref: house-style.md Self-check items 1, 2, 4, 5, 6
- **Document claim**: move 1: item 1 = banned vocabulary, item 2 = em dashes, item 5 = copula/signposting names, item 4 = sycophantic-openers/knowledge-cutoff names, item 6 = curly quotes.
- **Search performed**: full Read of house-style.md § Self-check (lines 475-491).
- **Code location**: house-style.md:479 (item 1 Banned vocabulary), :480 (item 2 Em dashes), :482 (item 4 "Sycophantic openers, throat-clearing, closing phrases, trailing hedges, prompt-restating, knowledge-cutoff disclaimers"), :483 (item 5 analysis patterns incl. "copula avoidance ('serves as')" and "signposting ('let's dive in')"), :484 (item 6 "Curly quotes → straight quotes").
- **Actual signature/role**: each item carries the content move 1 attributes to it.
- **Verdict**: MATCHES

#### Ref: conventions.md §1.5 Tier-B table (:621) and rename-safety grep (:626)
- **Document claim**: § Context and Orientation: "The §1.5 Tier-B table sits at `conventions.md:621`; a rename-safety grep … sits at `conventions.md:626`", naming the six sections `§ Orientation`, `§ Plain language`, `§ Banned vocabulary`, `§ Banned sentence patterns`, `§ Banned analysis patterns`, `§ Em-dash discipline`.
- **Search performed**: `sed -n '621p;626p' .claude/workflow/conventions.md`.
- **Code location**: conventions.md:621 (Tier-B `*.java`,`*.kt` row), conventions.md:626 (the `grep -rnE '## Orientation|## Plain language|…|Em-dash discipline' .claude/ CLAUDE.md` rename-safety line).
- **Actual signature/role**: line 621 enumerates exactly the six named sections; line 626 is the rename-safety grep whose alternation includes the two slugs the track plans to drop.
- **Verdict**: MATCHES

#### Ref: agents/code-reviewer.md:20 canonical six-slug bootstrap line
- **Document claim**: § Context and Orientation: the canonical bootstrap-slug form "(from `agents/code-reviewer.md:20`): 'the six AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and `## Plain language`.'".
- **Search performed**: `sed -n '20p' .claude/agents/code-reviewer.md`.
- **Code location**: code-reviewer.md:20.
- **Actual signature/role**: line 20 carries the quoted sentence verbatim (ordering and slugs match).
- **Verdict**: MATCHES

#### Ref: bootstrap-slug enumeration file count (47)
- **Document claim**: DR7 / § Context and Orientation: a `grep` for `Em-dash discipline` across `.claude/agents`, `.claude/workflow`, `.claude/skills` finds the six-slug line in 47 files (~21 review agents, ~21 workflow prompts/rule files, ~5 skills).
- **Search performed**: `grep -rl 'Em-dash discipline' .claude/agents .claude/workflow .claude/skills | wc -l`, plus per-dir counts.
- **Code location**: 47 files total — `.claude/agents` 20, `.claude/workflow` 22, `.claude/skills` 5.
- **Actual signature/role**: total is exactly 47, matching the headline figure. The per-dir split (20/22/5) is within the track's "~21/~21/~5" approximation; the track explicitly flags the figure as a grep-derived "every hit" count, not a frozen list.
- **Verdict**: MATCHES
- **Detail**: Total matches exactly; the ~21/~21 agent/workflow approximations are 20/22 in fact, inside the track's own "~" tolerance — no finding.

#### Ref: design-mechanical-checks.py check_dsc_ai_tell — eleven patterns, removed = 1,3,5,6
- **Document claim**: § Context and Orientation: function `check_dsc_ai_tell`, docstring enumerates eleven patterns; patterns 1 (Tier-1 banned vocabulary), 3 (em-dash density), 5 (signposting openers), 6 (copula avoidance) map to removed rules. Paired with `tests/test_dsc_ai_tell.py` and fixture `tests/fixtures/dsc-ai-tell-fixture.md`.
- **Search performed**: `grep -n 'def check_dsc_ai_tell'`, Read of the docstring (lines 1837-1862), `find` for the test and fixture.
- **Code location**: `.claude/scripts/design-mechanical-checks.py:1830` (`def check_dsc_ai_tell`), docstring:1839 ("Eleven patterns fire"); `.claude/scripts/tests/test_dsc_ai_tell.py`; `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md`.
- **Actual signature/role**: docstring lists 11 numbered patterns; #1 "Tier-1 banned vocabulary scan", #3 "Em-dash density >1 per paragraph", #5 "Signposting openers", #6 "Copula avoidance" — the exact four the track removes. Test and byte-source fixture both exist at the located paths.
- **Verdict**: MATCHES

#### Ref: skills/ai-tells/SKILL.md:3 description hard-codes removed tells
- **Document claim**: DR9 / Invariant: line 3 is a `description` front-matter field hard-coding removed tells ("delve", "foster", "em dash overuse", "knowledge-cutoff disclaimers") alongside kept ones (negative parallelism "It's not X, it's Y", Title Case headings).
- **Search performed**: `sed -n '1,10p'` and `grep -in 'delve|foster|em.dash|knowledge.cutoff'` over the file.
- **Code location**: SKILL.md:3.
- **Actual signature/role**: line 3 `description:` reads "… vocabulary fingerprints (delve, tapestry, leverage, robust, multifaceted, navigate, foster), structural fingerprints (negative parallelism like \"It's not X, it's Y\", bullet-everything, Title Case headings, adjective triads), … punctuation fingerprints (em dash overuse, knowledge-cutoff disclaimers)". All four named removed tells and both named kept ones are present.
- **Verdict**: MATCHES

#### Ref: hooks/house-style-write-reminder.sh:262 Tier-B body lists six sections
- **Document claim**: § Context and Orientation ("The hook"): `hooks/house-style-write-reminder.sh` Tier-B body (line 262) lists the six section names.
- **Search performed**: `sed -n '258,266p'`.
- **Code location**: house-style-write-reminder.sh:262 (`tier_b_body='…'`).
- **Actual signature/role**: line 262 is the `tier_b_body` assignment naming "§ Orientation, § Plain language, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline".
- **Verdict**: MATCHES

#### Ref: house-conversation.md AI-tell-subset list (lines 23-28) and no-preamble rule (line 15)
- **Document claim**: § Context and Orientation ("The chat style") and DR3: the AI-tell-subset list names the six sections (lines 23-28); the Response-shape list carries an inline no-preamble rule (line 15).
- **Search performed**: full Read (`cat -n`) of house-conversation.md.
- **Code location**: house-conversation.md:23-28 (six bullets: `## Banned vocabulary` 23, `## Banned sentence patterns` 24, `## Banned analysis patterns` 25, `### Em-dash discipline` 26, `## Orientation` 27, `## Plain language` 28); line 15 ("No preamble, no postamble. Skip the 'Great question' / 'Sure, I can help' opener and the 'let me know if you need anything else' closer.").
- **Actual signature/role**: all six section bullets present at the claimed lines; line 15 is the inline no-preamble rule, which already names the sycophantic-opener guard ("Great question") — DR3's chat carrier exists.
- **Verdict**: MATCHES

#### Ref: named prose consumers exist
- **Document claim**: § Interfaces and Dependencies / § Context and Orientation: the named prose consumers `design-document-rules.md`, `agents/design-author.md`, `agents/readability-auditor.md`, `agents/review-workflow-writing-style.md`, `prompts/design-review.md`, `skills/readability-feedback/SKILL.md`, root `CLAUDE.md` all exist.
- **Search performed**: `test -f` over the seven resolved paths.
- **Code location**: `.claude/workflow/design-document-rules.md`, `.claude/agents/design-author.md`, `.claude/agents/readability-auditor.md`, `.claude/agents/review-workflow-writing-style.md`, `.claude/workflow/prompts/design-review.md`, `.claude/skills/readability-feedback/SKILL.md`, `CLAUDE.md`.
- **Actual signature/role**: all seven exist.
- **Verdict**: MATCHES

#### Ref: review-workflow-writing-style.md em-dash-cap (line 30), banned-vocab sweep (29, 70-71, 78), knowledge-cutoff lens (line 34)
- **Document claim**: move 6: in `review-workflow-writing-style.md` the em-dash-cap lens (line 30), the banned-vocabulary sweep (lines 29, 70-71, 78), and the knowledge-cutoff lens (line 34) name removed rules.
- **Search performed**: `sed -n '29p;30p;34p;70,71p;78p'`.
- **Code location**: review-workflow-writing-style.md:29 ("**Banned vocabulary** — apply the Tier 1-4 lists in `… § Banned vocabulary`"), :30 ("**Em-dash cap** — at most one em dash per paragraph"), :34 ("**No knowledge-cutoff disclaimers**"), :70 ("### Banned vocabulary sweep"), :71 ("Apply the Tier 1-4 banned-vocabulary lists in `… § Banned vocabulary`"), :78 ("It also never re-bans a `## Banned vocabulary` tier word").
- **Actual signature/role**: every cited line points at the described content; line 30 is the em-dash cap, line 34 the knowledge-cutoff lens, lines 29/70-71/78 the banned-vocabulary references.
- **Verdict**: MATCHES

#### Ref: skills/ai-tells/SKILL.md body references the removed tells
- **Document claim**: § Context and Orientation + move 6: the SKILL body references the removed tells (so move 6's "rewrite the body … to drop the removed-tell names" has a real target).
- **Search performed**: full Read of SKILL.md (lines 11-64); `grep -in 'delve|foster|em.dash overuse|knowledge-cutoff disclaimer'` over the whole file.
- **Code location**: `.claude/skills/ai-tells/SKILL.md` body lines 11-64; the catalogue-lookup list at lines 21-29.
- **Actual signature/role**: the body names no removed tell verbatim (the grep matches line 3, the `description`, only). The body's actual removed reference is a section pointer: line 23 routes to `house-style.md § Banned vocabulary` (removed wholesale) and line 21 to `house-style.md § Punctuation and typography` (kept, but loses `### Em-dash discipline`).
- **Verdict**: PARTIAL
- **Detail**: Move 6 has a real edit target (the `§ Banned vocabulary` pointer at SKILL.md:23 becomes a phantom reference after DR2's removal), but the target is a removed-*section* pointer, not the removed-*tell name* the track's wording points an implementer at. The named tells live only in the line-3 description. Drives CR1.

#### Gaps: orphan bootstrap-slug consumer not in the track inventory
- **Document claim**: DR7 acceptance contract — the §1.5 rename-safety grep (every `Em-dash discipline` hit) plus the named-prose-consumer paraphrase scan is the only safety net; the inventory must miss no real consumer.
- **Search performed**: `grep -rl 'Em-dash discipline' .claude/agents .claude/workflow .claude/skills` (the full 47-file set) cross-checked against (a) move 3's generic "every file carrying the verbatim six-slug line" sweep and (b) the named-consumer list. Also confirmed `readability-auditor.md` and `prompts/design-review.md` are named consumers though not in the bootstrap-line set.
- **Code location**: 47 bootstrap-line files; named consumers at the seven paths above.
- **Actual signature/role**: by construction the move-3 sweep is defined as every file carrying the six-slug line, so all 47 are covered; the named-consumer scan covers paraphrase references in files (`readability-auditor.md`, `prompts/design-review.md`) that do not carry the verbatim line. No bootstrap-line or paraphrase consumer falls outside both.
- **Verdict**: MATCHES
- **Detail**: No orphan consumer. The inventory (47-sweep + named-consumer scan) covers every construct that points at a removed section.
