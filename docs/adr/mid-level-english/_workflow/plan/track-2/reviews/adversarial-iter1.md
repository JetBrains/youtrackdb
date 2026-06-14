<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "track-2.md:35,44", anchor: "### A1 ", cert: "Assumption A-prompt-count", basis: "design-review.md has no five-slug preamble; only 10 of 11 prompts carry it, so 'each of the 11 prompts' overcounts the slug-add by one"}
  - {id: A2, sev: should-fix, loc: "track-2.md:44 / design-review.md:181,458", anchor: "### A2 ", cert: "Violation count-flip", basis: "design-review.md's two 'five' tokens are 'the five Human-reader rules' (a different set); the step-1 'flip any numeric five->six' would corrupt that count"}
  - {id: A3, sev: should-fix, loc: "track-2.md:46 / skills", anchor: "### A3 ", cert: "Assumption A-skill-count", basis: "only 4 of 6 skills carry a five-slug enumeration; ai-tells (catalogue) and readability-feedback (read-list/router) do not, so 'each of the 6 skills' overcounts the slug-add"}
  - {id: A4, sev: suggestion, loc: "track-2.md:24-27,45", anchor: "### A4 ", cert: "Challenge D2-1", basis: "D2-1 conflates two distinct design-review.md blocks; the sync-map router (readability-feedback:47) places density/terseness vs design-shape rules differently, and Plain language is neither cleanly"}
  - {id: A5, sev: suggestion, loc: "track-2.md:47 / readability-feedback:51-55", anchor: "### A5 ", cert: "Assumption A-verbatim-copy", basis: "'copy verbatim' underspecifies whether the trailing precision-caveat sentence travels into the fenced block, and the per-skill enumeration wrapping is non-uniform so the slug-add is per-site"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
flags: [CONTRACT_OK]
-->

# Adversarial track review — Track 2 (iteration 1)

Track-realization review under the narrowed Phase-3A scope (D9): scope/sizing,
cross-track-episode reality, and invariant violation. The opt-out (`§1.7(k)`)
re-points every reference to the workflow-prose lens, so each challenge resolves
named references as workflow paths / `§`-anchors via grep and Read; mcp-steroid
is unreachable, which is correct for a Markdown-only track, so the symbol-audit
caveat does not bite (no Java symbols are in scope).

**Verdict: PASS with three should-fix accuracy corrections and two
suggestions.** No blocker. Track 1's handed-off outputs all exist as the track
assumes (Challenge 2 clears clean). The three should-fix findings are
file-footprint miscounts in the track's prose — design-review.md carries no
five-slug preamble, and two of the six skills carry no five-slug enumeration —
that would mislead the decomposer into planning slug-adds at sites that have
nothing to add. The two suggestions sharpen the design-review.md content edit
(D2-1) and the grep-sync instruction.

## Findings

### A1 [should-fix]
**Certificate**: Assumption test A-prompt-count
**Target**: Scope / sizing — `## Context and Orientation` and Plan of Work step 1
**Challenge**: The track says "the 11 workflow prompts under
`.claude/workflow/prompts/` each carry a house-style preamble naming the five
subset slugs" (track-2.md:35) and "Add `## Plain language` to the five-slug
enumeration in each of the 11 prompts" (track-2.md:44). Only **10** of the 11
carry that preamble. `design-review.md` carries no
"five AI-tell subset section slugs to apply are …" sentence at all — it
references house-style by individual `§ <heading>` citations and a
`§ Reading rules` "fetch house-style on demand" model. The slug-add target is
10 prompts plus one structurally different edit in design-review.md, not "11
prompts each with a preamble."
**Evidence**: `grep -rln 'five AI-tell subset section slugs to apply are'
.claude/workflow/prompts/` returns exactly 10 files; design-review.md is absent.
`grep -n` for the preamble in design-review.md exits 1 (no match). The track's
own `## Context and Orientation` already half-acknowledges this ("`design-review.md`
is the exception in kind") but the same paragraph and Plan step 1 still group it
under "the 11 prompts each carry a preamble," and the in-scope set lists 11
prompts as one homogeneous slug-add class.
**Proposed fix**: Re-word track-2.md:35 and Plan step 1 to "10 prompts carry the
five-slug preamble; the 11th (design-review.md) carries no preamble and gets the
content edit (D2-1) instead." The grep-derived in-scope set stays 11 files, but
the decomposer should not plan a preamble slug-add for design-review.md.

### A2 [should-fix]
**Certificate**: Violation scenario — count-flip on the wrong "five"
**Target**: Invariant ("any numeric count reads six") / Plan of Work step 1
**Challenge**: Plan step 1 ends "Flip any numeric 'five' → 'six' in the same
sentence." design-review.md is in scope, and its only two "five" tokens are
**"the five Human-reader rules"** (line 181: "Reviewer tone for these five
rules"; line 458: "the five Human-reader rules require evidence"). Those name a
different five — Audience-fit, Glossary-introduction, Why-before-what,
Navigability, Explanatory register — and Plain language is **not** being added
to that set. A mechanical "flip any numeric five→six" applied to design-review.md
would corrupt the Human-reader-rule count to a wrong "six," breaking the
cross-reference between line 181/458 and the five bullets at
design-review.md:175-179.
**Evidence**: design-review.md:175-179 lists exactly five Human-reader rules;
lines 181 and 458 cite "five" against that set. No five-slug AI-tell count word
exists in the file to flip. The invariant "any numeric count reads six" is
scoped to the *subset* count, not every "five" in a file.
**Proposed fix**: Scope the count-flip in step 1 to the subset-enumeration
sentence only ("flip the count word in the five-slug preamble sentence"), and
add an explicit exclusion for design-review.md's "five Human-reader rules"
count, which must stay five. The decomposer's idempotence/acceptance line for
design-review.md should assert the Human-reader count is still five after the
edit.

### A3 [should-fix]
**Certificate**: Assumption test A-skill-count
**Target**: Scope / sizing — Plan of Work step 3
**Challenge**: Plan step 3 says "Add `## Plain language` to the subset
enumeration in each of the 6 skills." Only **4** of the 6 carry a five-slug
subset enumeration to extend (create-plan, execute-tracks, review-plan,
review-workflow-pr — the "House style for chat-scale prose" block). The other
two do not: `ai-tells/SKILL.md` uses a `## Catalogue lookups` fingerprint→section
map (six sections, two outside the subset — no five-slug enumeration), and
`readability-feedback/SKILL.md` has no preamble enumeration at all (its subset
touchpoints are the line-36 target-section router, the line-54 grep, and the
line-70 STEP-1 read-list, none of which is the five-slug list). For those two
skills the slug-add (step 3) has no enumeration target; ai-tells' only possible
edit is the open catalogue-row question, and readability-feedback's only edit is
the grep sync (step 4).
**Evidence**: `grep -rln '## Banned analysis patterns ... and ## Orientation'`
matches create-plan, execute-tracks, review-plan (review-workflow-pr matches via
the per-skill grep but not the exact string because its block wraps the line
differently). ai-tells/SKILL.md:19-28 is the catalogue map. readability-
feedback/SKILL.md:36/54/70 are router/grep/read-list, not a five-slug
enumeration. The track's `## Validation` already hedges ("or is confirmed not to
enumerate it"), but Plan step 3's "each of the 6 skills" contradicts that hedge.
**Proposed fix**: Re-word step 3 to "Add `## Plain language` to the four skills
that carry the five-slug enumeration (create-plan, execute-tracks, review-plan,
review-workflow-pr). ai-tells (catalogue) and readability-feedback (read-list)
carry no five-slug enumeration; ai-tells' catalogue-row question is the open
Phase-A item, readability-feedback's only edit is the step-4 grep sync." This
also drops the implied slug-add for readability-feedback, whose sole structural
edit is the grep.

### A4 [suggestion]
**Certificate**: Challenge D2-1 — which cold-read block gains the rule
**Target**: Decision D2-1 / Plan of Work step 2
**Challenge**: D2-1 and step 2 say to add the rule to "the cold-read Human-reader
rules / Prose AI-tell additions list" — but design-review.md has **two** distinct
named blocks with different applies-to sets and different routing, and the slash
conflates them. `### Human-reader cold-read additions` (design-review.md:169-184)
holds five design-doc-shape rules and runs on design kinds only.
`### Prose AI-tell additions` (design-review.md:186-217) holds the over-dense /
too-terse scan and runs on `target=design` **and** `target=tracks`. The
authoritative router is the readability-feedback sync map
(readability-feedback/SKILL.md:47): a rule "on prose density or terseness…
instead joins the `### Prose AI-tell additions` block." Plain language is a
lexical/syntactic clarity rule that fits neither cleanly — it is not a
design-doc-shape rule (Human-reader block) and not a density/terseness rule
(Prose AI-tell block), yet it must run on every prose surface including tracks.
D2-1 rationale cites the `## Orientation` precedent, but Orientation landed in
the Prose AI-tell block (design-review.md:207 already checks `§ Orientation`),
not the Human-reader block — so the precedent points at the Prose AI-tell block,
which contradicts the "Human-reader rules" half of D2-1's phrasing.
**Evidence**: design-review.md:169-184 (Human-reader, design-kind only),
:186-217 (Prose AI-tell, design+tracks), :207 (Orientation already in the
Prose AI-tell block), :458 (the two evidence exceptions name the two blocks
separately). readability-feedback/SKILL.md:47 is the routing rule.
**Survival test**: WEAK. D2-1's intent (a real check, not a slug-only add)
survives, but the target block is ambiguous and the Orientation precedent it
cites actually lands in the Prose AI-tell block. Decompose with the target
block named.
**Proposed fix**: Resolve at Phase A which block gains the rule and state it in
D2-1: most likely the `### Prose AI-tell additions` block (matches the
Orientation precedent and the `target=tracks` reach Plain language needs), with
a one-line `§ Plain language` lexical-scan bullet alongside the over-dense /
too-terse axes. Replace "Human-reader rules / Prose AI-tell additions list" with
the single chosen block name.

### A5 [suggestion]
**Certificate**: Assumption test A-verbatim-copy
**Target**: Plan of Work step 4 / Invariant ("the grep copy matches the live
helper")
**Challenge**: Step 4 says "copy the live `conventions.md` helper verbatim so
the two never diverge again." Two under-specifications surface. First, the live
helper at conventions.md:572 is inline-backticked prose followed by a precision-
caveat sentence ("`Orientation` and `Plain language` are common words, so the
scan matches them only in their `##` / `§` heading-pointer form…"); the
SKILL.md:54 copy is a fenced ` ```bash ` block with no caveat. A literal copy of
just the command leaves the anchored common-word terms in the SKILL.md block
unexplained, so a reader sees `## Orientation|## Plain language` and the four
bare names with no statement of why two are anchored and four are not. Second,
the per-skill enumeration prose (A3) is non-uniform in wrapping and lead-in, so
the slug-add in step 3 is a per-site manual edit, not one find-replace — worth
noting so the decomposer does not plan a single mechanical sweep.
**Evidence**: conventions.md:572 (inline command + trailing caveat sentence);
readability-feedback/SKILL.md:51-55 (fenced block, no caveat). The two skill
enumeration blocks (create-plan:23 vs review-workflow-pr:42-44) differ in
wrapping and lead-in sentence.
**Verdict**: FRAGILE. The invariant "the copy matches the live helper" holds for
the command line, but "verbatim" is ambiguous about the caveat sentence and
about exactly what "matches" means (command-only, or command + caveat).
**Proposed fix**: Specify in step 4 that the SKILL.md:54 block carries the
command **and** the trailing precision-caveat sentence (so the anchoring is
self-explaining), or add a one-line note in the SKILL.md prose pointing back to
conventions.md:572 for the anchoring rationale. State the acceptance as
"command line byte-identical to conventions.md:572 plus the caveat sentence."

## Evidence base

#### Assumption test A-prompt-count: every prompt carries the five-slug preamble — MATCHES (the gap, not the assumption)
- **Claim**: "the 11 workflow prompts … each carry a house-style preamble naming
  the five subset slugs" (track-2.md:35); slug-add applies "in each of the 11
  prompts" (track-2.md:44).
- **Stress scenario**: grep the exact preamble sentence across all 11 prompts;
  open design-review.md to confirm its house-style citation form.
- **Code evidence**: `grep -rln 'five AI-tell subset section slugs to apply are'
  .claude/workflow/prompts/` → 10 files (technical-review, consistency-review,
  structural-gate-verification, review-gate-verification, adversarial-review,
  risk-review, create-final-design, consistency-gate-verification,
  dimensional-review-gate-check, structural-review). design-review.md grep for
  the preamble exits 1. design-review.md:175-179 + :309-317 show the
  per-`§`-citation / fetch-on-demand model instead.
- **Verdict**: BREAKS — the assumption "all 11 carry the preamble" is false by
  one; the slug-add target is 10, plus design-review.md's content edit.

#### Violation scenario: mechanical "five→six" flip corrupts the Human-reader count
- **Invariant claim**: "any numeric count of the subset reads six" (track-2.md:49)
  via "Flip any numeric 'five' → 'six' in the same sentence" (track-2.md:44).
- **Violation construction**:
  1. Start state: design-review.md in scope; its only "five" tokens are at
     lines 181 ("for these five rules") and 458 ("the five Human-reader rules").
  2. Action sequence: decomposer applies step 1's "flip any numeric five→six" to
     design-review.md.
  3. Intermediate state: line 181/458 now read "six Human-reader rules."
  4. Violation point: design-review.md:175-179 still lists exactly five
     Human-reader rules (Audience-fit, Glossary-introduction, Why-before-what,
     Navigability, Explanatory register); Plain language is not added to that
     set.
  5. Observable consequence: the count word at :181/:458 contradicts the five
     bullets it references — a cross-file consistency-review finding and a broken
     "Reviewer tone for these N rules" pointer.
- **Feasibility**: CONSTRUCTIBLE — step 1's wording is "any numeric five," and
  design-review.md is named in scope; only a scoping note prevents the wrong
  flip.

#### Assumption test A-skill-count: every skill carries a five-slug enumeration — BREAKS
- **Claim**: "Add `## Plain language` to the subset enumeration in each of the 6
  skills" (track-2.md:46).
- **Stress scenario**: open each of the 6 skills and check for the five-slug
  enumeration.
- **Code evidence**: create-plan:23, execute-tracks:23, review-plan:31,
  review-workflow-pr:42-44 carry the "House style for chat-scale prose" five-slug
  block. ai-tells/SKILL.md:19-28 is a `## Catalogue lookups` fingerprint→section
  map (six sections incl. `§ Structural rules` + `§ Punctuation and typography`
  outside the subset), no five-slug enumeration. readability-feedback/SKILL.md
  has the line-36 router, line-54 grep, line-70 STEP-1 read-list — none is the
  five-slug enumeration.
- **Verdict**: BREAKS — only 4 of 6 skills have a five-slug enumeration to
  extend; "each of the 6" overcounts by two.

#### Challenge D2-1: which design-review.md block gains the rule
- **Chosen approach**: add `## Plain language` to "the cold-read Human-reader
  rules / Prose AI-tell additions list" (track-2.md:24-27, step 2).
- **Best rejected alternative**: name a single target block. The two named blocks
  differ: `### Human-reader cold-read additions` (design kinds only, five
  design-doc-shape rules) vs `### Prose AI-tell additions` (design + tracks,
  over-dense / too-terse).
- **Counterargument trace**:
  1. D2-1 cites the `## Orientation` precedent for adding a clarity rule to the
     cold-read.
  2. Orientation actually lives in the `### Prose AI-tell additions` block
     (design-review.md:207 checks `§ Orientation`), not the Human-reader block.
  3. So the precedent points at the Prose AI-tell block, contradicting the
     "Human-reader rules" half of D2-1's slash-joined phrasing.
- **Codebase evidence**: design-review.md:169-184 vs :186-217; :207;
  readability-feedback/SKILL.md:47 (the sync-map router for density/terseness vs
  design-shape rules).
- **Survival test**: WEAK — intent survives, target block is ambiguous and the
  cited precedent lands in the other block.

#### Assumption test A-verbatim-copy: "copy verbatim" fully specifies the grep sync — FRAGILE
- **Claim**: step 4 "copy the live `conventions.md` helper verbatim so the two
  never diverge again" (track-2.md:47); invariant "the …:54 grep copy matches
  the live `conventions.md §1.5` helper" (track-2.md:49).
- **Stress scenario**: compare the live helper's surrounding text to the
  SKILL.md fenced-block context.
- **Code evidence**: conventions.md:572 is inline command + a trailing
  precision-caveat sentence explaining why two names are `##`/`§`-anchored.
  readability-feedback/SKILL.md:51-55 is a fenced ` ```bash ` block with no
  caveat. "Verbatim" does not say whether the caveat sentence travels into the
  block or stays in the SKILL.md prose.
- **Verdict**: FRAGILE — the command line matches cleanly, but the caveat's
  destination is unspecified and "matches" is undefined (command-only vs
  command+caveat).

#### Cross-track-episode reality: Track 1's handed-off outputs all exist — MATCHES
- **Claim** (the track's dependency): the `## Plain language` section, the §1.5
  six-heading grep, and the §1.5 Tier-B restatement must exist before this track
  names the slug.
- **Code evidence**: house-style.md:78 (`## Plain language` section, boundary
  clause at :90, self-check 8a at :487). conventions.md:572 (the
  `grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|
  Banned vocabulary|Banned sentence patterns|Banned analysis patterns|
  Em-dash discipline'` six-heading helper with the two common-word names
  anchored). conventions.md:567 (the Tier-B table row lists all six). conventions.md:583-589
  (the parallel Plain-language Tier-B restatement). readability-feedback/SKILL.md:54
  still carries the stale four-bare-name `grep -rn` form — the real divergence
  this track syncs.
- **Verdict**: HOLDS — every Track-1 output the track depends on exists exactly
  as assumed; the SKILL.md:54 divergence is real and is the one cross-track
  correction in scope. Challenge 2 clears clean.

#### Idempotence / no-third-copy baseline — MATCHES
- **Claim**: no in-scope prompt/skill already lists `## Plain language`; the
  SKILL.md:54 grep is the only copy outside conventions.md.
- **Code evidence**: `grep -rln '## Plain language|Plain language'
  .claude/workflow/prompts/ .claude/skills/` returns nothing (clean baseline).
  `grep -rln 'Banned sentence patterns|Banned analysis patterns' .claude/`
  returns only readability-feedback/SKILL.md (`\|` form) and conventions.md
  (`-rnE` form) — exactly two copies, no third helper missed.
- **Verdict**: HOLDS — the slug-add starts from zero everywhere, and the grep
  sync targets the one stale copy with no third site to miss. The ~17-file
  footprint (11 prompts + 6 skills) matches the grep derivation exactly.
