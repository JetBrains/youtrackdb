<!-- MANIFEST
findings: 4
severity: { blocker: 0, should-fix: 2, suggestion: 2 }
index:
  - id: WI1
    sev: should-fix
    loc: workflow-book-builder/PIPELINE.md:54
    anchor: "#wi1-should-fix-technical-reviewer-wave-has-no-blocker-resolution-gate-or-re-verification-loop"
    cert: C1
    basis: judgment
  - id: WI2
    sev: should-fix
    loc: workflow-book-builder/prompts/author.md:29
    anchor: "#wi2-should-fix-author-flag-for-the-producer-output-has-no-consuming-step"
    cert: C2
    basis: judgment
  - id: WI3
    sev: suggestion
    loc: workflow-book-builder/PIPELINE.md:64
    anchor: "#wi3-suggestion-empty-baseline-beta-read-set-per-persona-is-undefined"
    cert: C3
    basis: judgment
  - id: WI4
    sev: suggestion
    loc: workflow-book-builder/PIPELINE.md:65
    anchor: "#wi4-suggestion-five-or-more-touched-chapters-gate-has-no-definition-of-touched"
    cert: C4
    basis: judgment
evidence_base: present
cert_index: [C1, C2, C3, C4]
flags: []
-->

## Findings

### WI1 [should-fix] Technical-reviewer wave has no blocker-resolution gate or re-verification loop

Location: `workflow-book-builder/PIPELINE.md:54` (Step 4 — technical-reviewer wave), against `prompts/technical-reviewer.md:22` and `BOOK_BRIEF.md:75`.

Axis: loop termination / gate resume path.

Issue: the technical-reviewer prompt defines a **blocker** as "a claim is wrong, a cited file or section does not exist, or a diagram contradicts the source. The chapter cannot ship until this is fixed" (`technical-reviewer.md:22`), and `BOOK_BRIEF.md:75` states the load-bearing invariant "A chapter is done only when it has cleared all four roles." PIPELINE Step 4 discharges this with one clause: "Apply blockers and important fixes in a short revision pass." Three things are left undefined:

1. **No re-verification.** After the producer applies a fix to a blocker, nothing re-runs the reviewer (or any check) on the patched chapter. A fix that is itself wrong, or that the producer mis-applies, ships unverified. There is no "re-review the chapters that had blockers" step.
2. **No gate before the next wave.** Step 5 (copy-editor) and Step 6 (beta-reader) run on the touched chapters unconditionally. Nothing blocks a chapter with an unresolved blocker from flowing into copy-edit and beta-read. The "cannot ship until fixed" condition is stated in the role prompt but enforced nowhere in the pipeline.
3. **No iteration cap or exit condition.** The author → technical-reviewer → fix sequence is implicitly a loop (a fix can introduce a new factual error a re-review would catch), but the pipeline runs it exactly once with no max-iteration cap and no terminal "all chapters clean" assertion. The wave sequence Step 3 → 8 has no defined exit gate; it simply falls through to Step 8 (bump the baseline) regardless of the verdict the reviewers returned.

Proposed fix: add an explicit gate at the end of Step 4. After the revision pass, re-run the technical reviewer over only the chapters that carried a blocker; a chapter does not advance to Step 5 until its technical-reviewer verdict is "clean." Cap the apply/re-review loop (e.g. two iterations) and define the third-failure path — escalate the unresolved blocker to the operator rather than silently bumping the baseline in Step 8. State the one-line invariant the brief already implies: Step 8 runs only when every touched chapter's technical-reviewer verdict is clean.

### WI2 [should-fix] Author "flag it for the producer" output has no consuming step

Location: `workflow-book-builder/prompts/author.md:29` and `:31` (the two "flag / note it for the producer" hand-offs) and `:35` (the deliverable's closing list of "any concept you had to reference that no chapter yet owns"), against `PIPELINE.md:42-50` (Steps 2 and 3).

Axis: phase output → next-phase input.

Issue: the author prompt produces a second output beyond the chapter file. Two instructions route material to "the producer": "if no chapter owns it yet, flag it for the producer rather than defining it out of order" (`author.md:29`), "If your chapter reveals that an earlier chapter is wrong or incomplete, note it for the producer" (`author.md:31`), and the deliverable closes by "listing the source files you verified and any concept you had to reference that no chapter yet owns" (`author.md:35`). Nothing in PIPELINE consumes this. The TOC edit that would create a chapter home for a flagged-but-homeless concept happens in Step 2, which runs **before** the author wave in Step 3; an author who discovers mid-Step-3 that a concept has no chapter home emits a flag that no later step reads, and the run proceeds to technical review with the gap unaddressed. This is an orphan output: the author is told to raise the case, but the pipeline defines no step where the producer acts on it (no loop back to Step 2's TOC edit, no "reconcile author flags before Step 4").

Proposed fix: add a short reconciliation step between Step 3 and Step 4 (or as a sub-step at the end of Step 3): the producer collects the author flags, and for each homeless-concept flag either assigns it to an existing chapter, opens a new-or-restructure TOC edit (re-entering Step 2 for that chapter), or records it as out of scope for this run. Name where the cross-chapter "earlier chapter is wrong" notes are tracked so they are not lost.

### WI3 [suggestion] Empty-baseline beta-read set per persona is undefined

Location: `workflow-book-builder/PIPELINE.md:64` (Step 6, empty-baseline arm) against `prompts/beta-reader.md:19`.

Axis: empty-input / degenerate case.

Issue: PIPELINE Step 6 empty-baseline arm reads "run all three personas over the whole book in order" (`PIPELINE.md:64`), but the beta-reader prompt instructs each spawned reader to "Read the chapters in **your set**, in order, in one pass" (`beta-reader.md:19`). On the empty baseline the "set" handed to each of the three personas is not pinned: each persona reads the whole book (three full passes), or the book is partitioned across the three. The persona definitions imply the whole-book reading (the skeptical veteran "push[es] on every place the book asks you to do extra work"), but the prompt's "your set" language and the parallel-spawn framing leave a producer free to partition, which would defeat the in-order cover-to-cover read the empty-baseline arm intends. A producer hitting this ambiguity guesses.

Proposed fix: in the Step 6 empty-baseline arm, state that each persona's set is the whole book (three full in-order passes, not a partition), or amend the beta-reader prompt to say the empty-baseline set is always the entire book and the evolution set is the touched chapters in context.

### WI4 [suggestion] "Five or more touched chapters" gate has no definition of "touched"

Location: `workflow-book-builder/PIPELINE.md:65` (Step 6 evolution arm) and `:106` (Table P.1 beta-read row).

Axis: conditional branch coverage (fuzzy-criterion tie-breaker).

Issue: the Step 6 evolution gate fires when "five or more chapters were touched" (`PIPELINE.md:65`), and the run classifies chapters into four bands in Step 2 — clean, sweep, rewrite, new-or-restructure (`PIPELINE.md:37-40`). "Touched" is used elsewhere to mean authored-or-swept (Step 4: "Only touched chapters (authored or swept) are reviewed"), which would fold sweep chapters into the count, but the beta gate never says whether a sweep-only chapter counts toward its five. A run with four rewrites and two sweeps sits exactly on the borderline: it is six touched-by-the-Step-4-sense chapters but four substantive ones. The gate has no tie-breaker for which band-set the count draws from.

Proposed fix: pin the count to a named band-set, e.g. "five or more chapters in the rewrite or new-or-restructure bands" (substantive changes, matching the copy-edit gate's "an author substantially rewrote" scope), or state explicitly that sweep chapters count. One sentence closes it.

## Evidence base

#### C1

`technical-reviewer.md:22` defines blocker as ship-blocking ("The chapter cannot ship until this is fixed") and the deliverable (`:34`) ends with a per-chapter verdict ("clean, or N blockers and M fixes"). `BOOK_BRIEF.md:75`: "A chapter is done only when it has cleared all four roles." PIPELINE Step 4 (`:54`) is the only place the blocker verdict is acted on, with one clause — "Apply blockers and important fixes in a short revision pass" — and no following re-verification, no gate guarding Steps 5/6, and no iteration cap. `grep` for "gate", "re-review", "cannot ship", "verdict", "iterate" across PIPELINE.md returns no enforcing step: the only "verdict" hit is in the reviewer prompt's output, never read back; the only "(gated)" hit is Step 6's beta gate, which is unrelated to blockers. The wave sequence Step 3 → Step 8 falls through to "bump the baseline" regardless of the reviewer verdict. The invariant the brief states is therefore declared but unenforced — a procedural completeness gap. Confirmed finding.

#### C2

`author.md:29` and `:31` route two flag types to "the producer"; `:35` adds the closing list of homeless concepts to the deliverable. PIPELINE Steps 2 and 3 (`:33-50`) place the TOC edit (the only step that creates a chapter home) strictly before the author wave, and `grep` for "flag", "producer", "note it for", "reference it" across PIPELINE.md returns matches only inside the START prompt's whole-run rules (Removed-features and New-features rules at `:82`/`:83`, which cover drift-detected concepts, not author-raised ones) — none consume the author's per-chapter flags. The author output is produced and never read: an orphan output with no downstream step. Confirmed finding.

#### C3

`PIPELINE.md:64` ("run all three personas over the whole book in order") versus `beta-reader.md:19` ("Read the chapters in your set, in order, in one pass"). The two phrasings are consistent only if "your set" on an empty baseline is the whole book; the prompt never says so, and the per-persona spawn framing (`beta-reader.md:1`, three personas spawned in parallel) admits a partition reading. A real ambiguity the producer must resolve by guessing; low operational cost (degenerate-case clarity, not a strand), so a suggestion. Confirmed.

#### C4

`PIPELINE.md:65` gates the evolution beta wave on "five or more chapters were touched"; Step 2 (`:37-40`) defines four bands and Step 4 (`:54`) uses "touched (authored or swept)" for a different scope. The beta gate does not state which band-set its count draws from, so a mixed run on the four-to-six boundary has no deterministic answer. Borderline-case tie-breaker missing; the run still proceeds either way, so the cost is inconsistent behavior across runs, not a strand. Suggestion. Confirmed.
