<!-- MANIFEST
findings: 3   severity: {Recommended: 2, Minor: 1}
index:
  - {id: WS1, sev: Recommended, loc: "review-agent-selection.md:117-118", anchor: "### WS1 ", cert: C1, basis: "negative parallelism in the load-bearing read-first override paragraph"}
  - {id: WS2, sev: Recommended, loc: "code-review-protocol.md:60-65", anchor: "### WS2 ", cert: C2, basis: "negative parallelism plus a two-sentence skip-is-sound restatement"}
  - {id: WS3, sev: Minor, loc: "review-agent-selection.md:125-128", anchor: "### WS3 ", cert: C3, basis: "nested conditional defers subject-verb past a stacked lead clause"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [Recommended]
Axis: banned sentence patterns. In the new "Single-step-high override (read
first)" paragraph the override sentence uses the `not X — Y` negative-parallelism
shape:

> In that case the step-level selection is **not** narrowed — it runs the
> **full track-pass-equivalent selection**: every baseline and every workflow
> reviewer the Phase C track pass would run for that diff.

`house-style.md` § Banned sentence patterns cuts the "It's not X — it's Y"
contrastive negation and asks for a positive statement. The cost is sharper here
than usual because this is the "read first" rule an orchestrator reaches at
dispatch — the em-dash negation makes it parse the negative before the positive
content. Rewrite positively while keeping the override signal:

> In that case the step-level selection runs the **full track-pass-equivalent
> selection** instead of narrowing: every baseline and every workflow reviewer
> the Phase C track pass would run for that diff.

### WS2 [Recommended]
Axis: banned sentence patterns. The rewritten `## Single-step tracks` section in
`code-review-protocol.md` carries two issues in one passage. First, negative
parallelism (`X, not Y`):

> The skip is licensed by that full selection, not by step-level review as such

Second, an adjacent restatement — two consecutive sentences both assert the skip
is sound:

> …widens a sole-step-of-its-track high step to the full track-pass-equivalent
> selection (…), precisely so this skip stays sound. The skip is then sound
> because this section is a review-selection rule the orchestrator re-reads…

The "stays sound" / "The skip is then sound" pair is restatement per § Elegant
variation (a sentence adding no information beyond the previous one's claim). The
parallel `track-code-review.md` §Single-Step Track edit states the same mechanism
once and cleanly ("The skip rests on that full selection: …"); mirror that
register. Suggested rewrite:

> **Single-step tracks skip the code review portion of Phase C only when
> the single step is `risk: high`** — i.e., the full track-pass-equivalent
> selection already ran against the identical diff at the step. The skip rests
> on that full selection: `review-agent-selection.md` §Step-level vs track-level
> routing widens a sole-step-of-its-track high step to the full
> track-pass-equivalent selection (every baseline and every workflow reviewer the
> track pass would run). This section is a review-selection rule the orchestrator
> re-reads at the start of each Phase C, so once the full selection already ran at
> the step, re-running the track pass would select the same reviewers against the
> same diff and add nothing.

### WS3 [Minor]
Axis: plain language. The Baseline-group lead clause nests the exception inside a
conditional that defers the subject-verb until after a stacked clause:

> Unless the high step is the sole step of its track (then the full selection
> runs at the step per the single-step-high override above), at a high step of a
> **multi-step** track only `review-bugs-concurrency` runs;

A reader holds the `Unless … (then …)` conditional and the parenthetical before
reaching "only `review-bugs-concurrency` runs". § Plain language asks for one idea
per sentence over a stacked subordinate clause. The lead-clause-first ordering is
deliberate (the override paragraph says each narrowing "carries a lead clause
pointing back here so a dispatch reader sees the exception before the rule"), so
keep the order but split the clause: "**Sole-step-of-its-track exception:** the
full selection runs at the step per the single-step-high override above.
Otherwise, at a high step of a **multi-step** track only `review-bugs-concurrency`
runs;". Minor because the meaning is recoverable in one careful pass.

## Evidence base

#### C1 [CONFIRMED] WS1 negative parallelism
Banned sentence patterns sweep, § Banned sentence patterns "Negative parallelism"
("It's not X — it's Y"). The text "is **not** narrowed — it runs the **full
track-pass-equivalent selection**" is the inverted `not X — Y` form; the rule
calls for a positive restatement. Confirmed against the live diff line.

#### C2 [CONFIRMED] WS2 negative parallelism + restatement
Two checks. (1) Banned sentence patterns sweep: "licensed by that full selection,
not by step-level review as such" is the `X, not Y` contrastive-negation form.
(2) § Elegant variation / paragraph-adds-no-information: "stays sound" followed by
"The skip is then sound because" is two consecutive sound-assertions; the sibling
`track-code-review.md` edit proves the mechanism states cleanly in one sentence.
Both confirmed against the live diff lines.

#### C3 [CONFIRMED] WS3 stacked lead clause
§ Plain language "keep sentences short, one idea each". The `Unless … (then …), at
a high step of a multi-step track only … runs` sentence holds a conditional plus a
parenthetical before the subject-verb. Judgment call: reads harder than the
split-clause rewrite, recoverable in one careful pass, so Minor not Recommended.
Confirmed against the live diff line.
