# Voice Exemplar — A Concrete, Checkable Voice Spec

`BOOK_BRIEF.md` states the voice rules in the abstract ("concrete before abstract",
"earn every name", "no bullet-point fact dumps"). This document is the concrete
companion: the observable **fingerprint traits** an exemplar chapter exhibits, most with an
illustrative quote you can pattern-match against, and the **anti-pattern catalog** of LLM
tells that a draft must be scrubbed of. It does not restate the brief's rules — it makes them
checkable.

**When to use this document:**

- Before writing a new chapter — read Part A so the target cadence is in your ear.
- During a copy-edit or a dedicated voice pass — check a draft against Parts A and B.
- During a blind A/B voice test — the residual-tell list in Part B is the highest-value
  thing to catch, because those tells survive after the worked examples are already right.

The three **exemplar chapters** below are the voice models. When a passage feels off, open
one of them and read the parallel passage aloud.

| Exemplar | Why it is a model |
|---|---|
| `docs/yql-internals-book/chapters/08-cardinality-selectivity-fanout.md` | Named numeric scenario (Alice, Berlin) walked with real arithmetic before any abstraction is defined. |
| `docs/yql-internals-book/chapters/13-hash-joins.md` | Long-setup / short-punchline rhythm; each variant introduced by the problem, not the class name. |
| `docs/yql-internals-book/chapters/16-reading-explain.md` | Warm second-person teaching; honest about illustrative-vs-live output; opener states the reader's exact gap. |

The book is validated against three **beta-reader personas**. Read the matching file when
you want to predict how each will react to a draft:

| Persona | File | Reads for |
|---|---|---|
| Target reader (Java/Spring engineer, new to graph DBs) | `beta-feedback/beta-reader-1-target-reader.md` (cycle-2 companion: `beta-feedback/beta-reader-1-cycle2.md`) | Whether an undefined term or a rushed section blocks a motivated non-expert. |
| Skeptical veteran (has shipped planner join-ordering) | `beta-feedback/beta-reader-2-veteran.md` (cycle-2 companion: `beta-feedback/beta-reader-2-cycle2.md`) | Whether depth is real or a surface tour dressed up as depth. |
| Time-constrained practitioner (dip-in, 30-minute budget) | `beta-feedback/beta-reader-3-timeconstrained.md` | Whether TOC/opener navigation gets a reader with one symptom to the right chapter fast. |

`beta-feedback/SYNTHESIS.md` is not a persona: it is the prioritized revision table that
synthesizes all three readers' feedback at the end of a beta cycle.

---

## Part A — Voice fingerprint (A1–A8)

Each trait is a concrete manifestation of a `BOOK_BRIEF.md` rule. Match a draft against
the quote where one is given, not just the description.

**A1 — Long clause-piling setup, resolved by a short punchline.** A sentence (or two)
that piles up qualifications, then a 3–8 word sentence that lands the point.

> "That model is correct. It is also, in the wrong query, catastrophically slow." (Ch 13)

**A2 — Short paragraphs, one idea each; single-sentence paragraphs for emphasis.**
Paragraphs run 3–6 sentences. The rare one-sentence paragraph is a deliberate beat, not an
accident — the A1 quote above is also a standalone paragraph doing emphasis work.

**A3 — A named, numeric, concrete scenario before any abstraction.** The section opens
with real names and real numbers, walks the arithmetic, and only then defines the term.
(Brief rule 2.)

> "If there are 5 people named Alice and 80,000 people living in Berlin, starting from
> `me` is obviously better … But the planner does not know those numbers; it has never
> read a single record." (Ch 8, *before* the words *cardinality* / *selectivity* are
> defined)

**A4 — Openers name what the reader already knows, then the gap; titles name the
problem.** The first paragraph bridges from the prior chapter and states the missing
capability. Section titles name the problem, not the class. (Brief rules 4, 5.)

> "What you still lack is a way to go from 'this query is slow' to 'this is the plan the
> planner chose, and here is why it is wrong.' That is what this chapter gives you." (Ch 16)

Section-title example: **"A question the planner must answer before touching a single
record"** (Ch 8) — never "`estimateRootEntries()`".

**A5 — Closings point forward and name the next chapter's open question.** The last
paragraph sets up a specific unresolved tension the next chapter resolves. (Brief rule 5.)

> "…there is one case where the obvious rule — 'pick the alias with the smallest
> estimate' — would choose an alias the traversal engine can never legally start from.
> Chapter 9 … turns to that edge case." (Ch 8, *What comes next*)

**A6 — Inline citations are sparse and load-bearing; the bulk live in a footer.** At most
one `file:line` per paragraph, and only when the exact line is part of the argument.
Decorative citations move to *Further reading*. (Brief rule 6.)

> "Cardinality is computed by `MatchExecutionPlanner.estimateRootEntries()`
> (`core/.../sql/executor/match/MatchExecutionPlanner.java:5192`)…" (Ch 8 — one citation,
> because the method *is* the subject of the sentence)

**A7 — Diagrams teach one idea the prose leans on.** Mermaid diagrams are used for
decision trees, data-flow, and structure — never to restate a paragraph. Captioned
`**Figure N.K — caption.**` below the fence. (Brief rules 7, conventions.)

**A8 — Warm, second-person, honest about limits; zero marketing.** The voice is
"you"-addressed and teacherly, and it admits when something is approximate. No comparative
or promotional language.

> "The output below is illustrative — the exact text depends on database state at plan
> time and may differ from what a live run produces." (Ch 16 — honest about its own
> example)

---

## Part B — Anti-pattern catalog (B1–B8)

These are the LLM tells found in the first Chapter 18 draft. A draft must be scrubbed of
all of them.

- **B1 — Semicolon-chained triple-clause sentences with no punchline.** Three clauses
  glued by semicolons that never resolve into a short landing sentence (violates A1).
- **B2 — Citation pile-ups.** Multiple `file:line` coordinates crammed into one paragraph
  (violates A6).
- **B3 — Abstraction with no worked number.** A section that argues entirely in the
  abstract, with no named scenario or arithmetic (violates A3).
- **B4 — Rhetorical parallelism instead of a real list.** "every X…; every Y…; every Z…"
  used where a genuine bullet list or prose belongs (violates brief rule 8).
- **B5 — Abstract-first section openings.** "Here is the awkward truth the two chapters
  never reconciled…" opening cold, before any concrete anchor (violates A3/A4).
- **B6 — Marketing / appeal-to-authority / reader-flattery.** "industry consensus", "the
  outlier", "you are no longer a reader… you are equipped to be one of the people who
  improves it" (violates A8).
- **B7 — Uniform dense paragraph blocks.** Every paragraph the same 4–7 dense sentences,
  sometimes packing multiple ideas into one (violates A2).
- **B8 — Templated scaffolding that tells instead of shows.** The same frame repeated each
  subsection ("The team's direction is…", "A contributor reads…") without ever showing the
  concrete artifact (e.g. an actual EXPLAIN fragment).

### Highest-value residual tells (catch these in the final voice pass)

These four survive *after* the worked examples are fixed. They hide in framing sentences
and abstract prose, they read as fluent, and they are the last thing a blind A/B voice
test flags. Treat them as the top priority of any final pass.

- **RT1 — The semicolon-tricolon.** "every X…; every Y…; every Z…". A rigid three-beat
  parallel with matching clause shapes. Fix: vary the sentence structures and end on a
  short punchline (this is the sharpest form of B1/B4).
- **RT2 — Stacked balanced aphorisms.** Two or more slogans in a row, each a tidy balanced
  sentence, none grounded in a number. Fix: keep at most one, and anchor it to a concrete
  quantity from the surrounding example.
- **RT3 — Manufactured chiasmus / antithesis.** "invisible today and load-bearing
  tomorrow", "X today and Y tomorrow" — an engineered mirror-image or before/after
  contrast. Fix: flatten to a plain statement ("does no harm yet, and that is exactly why
  it is easy to miss").
- **RT4 — The repeated-subject aphorism frame.** "A planner whose cost turns on X is a
  planner with a gap to close." The subject noun repeated to bookend a maxim. Fix: state
  the finding once, plainly, without the rhetorical loop. *(Note: RT4 also masked a false
  technical claim — see the citation and near-miss notes in `PRODUCTION_CHECKLIST.md`; a
  clever frame is not evidence.)*

---

## Per-chapter citation convention

Citation style is not uniform across the book. New or edited content **must match the host
chapter's convention**:

- **Chapter 18** (*Open Problems: A Contributor's Map*, Part VIII) cites code by **durable
  class and method names only — no `file:line` coordinates.** It is a forward-looking map
  of an optimisation backlog; its targets move as work ships, so line numbers would drift
  immediately and mislead. Cite `MatchExecutionPlanner.updateScheduleStartingAt`, not a
  line range.
- **Every other chapter** uses `ClassName.java:NNN` coordinates, kept fresh by the
  line-number sweep described in `MAINTENANCE_PROMPT.md` (Phase 2 sweep, whole-refresh
  Rule 1). These are load-bearing per A6 and are re-verified against the live tree every
  refresh cycle.

A citation in the wrong style for its chapter is a defect even when the target is correct.
