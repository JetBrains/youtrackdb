<!-- MANIFEST
dimension: workflow-writing-style
iteration: 1
findings: 0   severity: {Critical: 0, Recommended: 0, Minor: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 5, matches: 5}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

## Evidence base

The track edits divide into mechanical slug flips (10 prompt preambles + 4
skill blockquotes) and four freshly authored prose units (the three content
edits plus the Episodes block). The slug flips change one count word
("five" to "six") and append `## Plain language` to a verbatim-copied
template sentence, so they carry no new prose risk and are out of scope as
already-reviewed text. The five certs below cover the in-scope authored
prose. All passed.

#### C1 ai-tells/SKILL.md catalogue row — PASS
Added line (diff:9): "Plain-language fingerprints (an uncommon word where a
common one fits, a long tangled sentence, an idiom or an ambiguous phrasal
verb) → `house-style.md § Plain language`. This covers general-English
clarity outside the closed AI-tell word list, so it stays distinct from
`§ Banned vocabulary`." Banned-vocabulary sweep clean. Zero em dashes.
Common words, two short sentences. Parallel to the sibling Orientation row
above it. Leads with what the row names (BLUF). No finding.

#### C2 readability-feedback/SKILL.md grep caveat + STEP-4 line — PASS
Caveat (diff:51): one sentence, two semicolon-joined clauses naming why the
two common-word names match only in heading-pointer form; plain words, no em
dash. STEP-4 line (diff:68): "A passage that is hard to read for uncommon
words, long sentences, or idioms is `CAUGHT by § Plain language`, not a
GAP." — parallel to the existing Orientation classification sentence, plain
words, no em dash. The trailing "not a GAP" reads as a positive
classification target, not negative parallelism. No finding.

#### C3 design-review.md cold-read lens (Hard-to-read axis) — PASS
Added bullet block (live lines 211-217): "**Hard-to-read** — check against
... § Plain language. Flag a sentence that uses an uncommon word where a
common one fits, a long tangled sentence the reader must read twice, or an
idiom or ambiguous phrasal verb. This axis is about word choice and sentence
shape, so it applies even to prose that is the right length and
well-motivated. Report it as a finding; plain-language quality stays a
judgment call, with no score." Exactly one em dash (the `- **Hard-to-read**
—` lead-in, parallel to sibling `- **Over-dense** —` / `- **Too-terse** —`
bullets); within the per-paragraph cap. Plain words, short sentences. No
finding.

#### C4 design-review.md second-exception + summary/axis-count edits — PASS
Tone-and-depth "second exception" line (live line 465): the edit inserted
"or the hard-to-read one" and "or § Plain language" into a verbatim template
sentence. Em-dash count per paragraph is 1 (the pre-existing `require
evidence too —` dash, unchanged); within cap. The "both axes" to "three
axes" lead-in and the `<!-- summary= -->` / `:23` TOC summary edits are
single-word/short-phrase parallel additions, no new sentence structure. No
finding.

#### C5 track-2.md Episodes prose (Step 1 + Step 2 blocks) — PASS
Both `**What was done:**` / `**What was discovered:**` paragraphs lead with
the action (BLUF), use common words ("Added", "changed", "left as they
were", "confirmed"), and carry zero em dashes per paragraph. Step 2's "What
was done" sentence is long but each clause names one concrete edit and is
followable; the `## Episodes` structured-field block is length-exempt per
house-style.md § Structural rules "Section length cap exception" anyway. No
idioms or ambiguous phrasal verbs. Banned-vocabulary sweep clean. No
finding.
