<!--MANIFEST
dimension: workflow-writing-style
prefix: WS
findings: 0   severity: {Critical: 0, Recommended: 0, Minor: 0}
index: []
evidence_base:
  section: "## Evidence base"
  certs: 4
  matches: 4
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

No findings. The newly-authored prose in this track is house-style clean across
every writing-style axis, and the new `## Plain language` rule self-applies to
its own text without a violation.

The track is a rule-authoring plus enumeration-sync change. Most of the diff is
meaning-preserving cross-reference sync (flip "five" to "six", add one slug to a
list), which carries no new prose to assess. Four blocks hold genuinely new
prose, all of which pass:

- `house-style.md:78-96` — the new `## Plain language` section.
- `conventions.md:583-589` — the Tier-B code-comment restatement paragraph.
- `track-1.md` — SD5 and the three Episode blocks (Steps 1-3).
- `track-1/reviews/hook-safety-iter1.md` — the prior dimensional review file.

## Evidence base

#### C1: banned-vocabulary sweep — clean across all new prose
- **Check**: grep the Tier 1-4 banned-vocabulary lists (`house-style.md §
  Banned vocabulary`) over each new-prose block.
- **Result**: The only hits are inside the rule's own worked examples.
  `house-style.md:84` cites "utilize"/"demonstrate"/"regarding" as the words to
  replace; `:86` cites "knock out"/"kick off"; `:92` quotes `leverage → use`
  while defining the § Banned vocabulary boundary. Each is the rule naming a
  banned word to teach against it, the same literal-citation pattern as the
  unchanged Tier-2 list at `:110`. The `conventions.md` restatement, the
  `track-1.md` Episodes, and `hook-safety-iter1.md` carry zero hits.
- **Verdict**: CONFIRMED clean (no load-bearing banned-vocabulary use).

#### C2: em-dash discipline — at most one per paragraph, all new prose
- **Check**: `grep -c '—'` per blank-line-bounded paragraph over the new-prose
  blocks.
- **Result**: Zero em dashes in `house-style.md:78-96`, zero in the
  `conventions.md:583-589` restatement, zero in the `track-1.md` Episode blocks
  (Steps 1-3). The `track-1.md` SD5 heading uses one em dash in the
  bold-lead label, which matches the existing SD1-SD4 label convention and stays
  at one per paragraph.
- **Verdict**: CONFIRMED clean (no paragraph exceeds one em dash).

#### C3: BLUF lead — section and block openers state the conclusion first
- **Check**: read the first sentence of each new section / block against the §
  BLUF lead rule.
- **Result**: `## Plain language` opens "Write the general-English part of your
  prose in plain words." — the rule itself, no preamble. The `conventions.md`
  restatement opens "the `§ Plain language` floor is partial." — the conclusion
  first. Each Episode block opens with the bold `**What was done:**` /
  `**What was discovered:**` field shape the ExecPlan template fixes, which is
  the structured-field lead, not a background ramp.
- **Verdict**: CONFIRMED clean (every opener is conclusion-first).

#### C4: plain-language self-application — the new rule passes its own moves
- **Check**: apply the five `## Plain language` moves (common word, short
  one-idea sentences, no idioms or ambiguous phrasal verbs, expand a non-floor
  acronym on first use, explicit active grammar) to the newly-authored prose,
  per the branch's §1.7(k) self-application constraint.
- **Result**: The `## Plain language` section and the `conventions.md`
  restatement use common words throughout, keep sentences short with one idea
  each, name actors in active subject-verb-object order, and carry no idiom or
  unexpanded non-floor acronym. The Episode prose is registry-terse log text
  (the reader has the shared track vocabulary open), which the § Orientation
  register distinction permits, and it still reads in plain words. The
  word-choice scan flagged only the rule's own examples (see C1), not prose
  usage.
- **Verdict**: CONFIRMED clean (the rule self-applies without a violation).
