# Production Checklist — Authoring and Editing Chapter Content

This is the executable process for producing a new chapter **and** for any substantive
edit to an existing one (anything beyond a mechanical line-number sweep). Follow it in
order. It references `VOICE_EXEMPLAR.md` for the voice rubric and the beta personas; keep
this file for the process.

---

## Rule 0 — Every code claim is verified against the live source by a fresh reviewer

**This rule is first because it caught two false claims in a prior cycle, and both had
already passed a tone gate.**

Every code claim — the class name, the method name, the line number, **and the behavioural
assertion** — is independently verified against the **live source** by a **fresh reviewer**
before the content is accepted. The author's own assertions are never trusted. A passing
tone gate does **not** imply technical correctness: prose can be fluent, on-brief,
and completely wrong about what the engine does.

Two near-misses motivate the rule; both read beautifully and both were false:

1. **The IC10/IC11 claim.** A draft asserted that the LDBC benchmark queries IC10 and IC11
   exemplify the greedy planner's wrong-root pathology. They cannot: one has a single valid
   anchor (so there is no root *choice* to get wrong), and the other traverses a
   non-invertible `WHILE` edge (so the alternative order the argument needed does not
   exist). The claim named a real benchmark, which made it *sound* authoritative — and it
   was wrong.
2. **The "spelling-sensitivity" claim.** A draft asserted that the planner produces a
   different plan depending on which of two identical edge spellings you type. It
   contradicted the engine's actual edge-invertibility semantics: plain `out`/`in` MATCH
   edges **are** invertible via reverse traversal (`bidirectionalMethods` includes `out`
   and `in`; `SQLMethodCall.executeReverse` maps `out↔in`); only `WHILE` / `maxDepth` /
   `optional` edges are non-invertible. Rewriting the edge the "other way round" produces
   the *same* bad plan, so no asymmetry exists.

Both passed a tone gate. Both were caught only by source-level technical review. That is
why Rule 0 exists.

---

## The pipeline

1. **Confirm the voice fingerprint.** Read `VOICE_EXEMPLAR.md` Part A before writing.
   The chapter must open with a concrete named/numeric scenario (A3), bridge from the
   prior chapter and name the gap (A4), and use the long-setup / short-punchline rhythm
   (A1). Match the host chapter's citation convention (`VOICE_EXEMPLAR.md`, *Per-chapter
   citation convention*).

2. **Stage the authoring with a tone gate after the first section.** Write the first
   section, then stop and run it past the tone gate (self-check against `VOICE_EXEMPLAR.md`
   Parts A and B, or a fresh copy-edit reviewer) **before continuing**. Catching a drifted
   cadence at section one is cheap; catching it at section eight means rewriting eight
   sections.

3. **Run the validation gauntlet.** All of the following, not a subset:
   - Copy-edit pass (voice consistency, pacing, brief rules).
   - Target-reader beta (`beta-feedback/beta-reader-1-target-reader.md` persona) — does a
     motivated non-expert follow it without hitting an undefined term?
   - Skeptical-veteran beta (`beta-feedback/beta-reader-2-veteran.md` persona) — is the
     depth real, or a surface tour?
   - Blind A/B voice test — can a reader distinguish this from the exemplar chapters?
     Focus on the residual tells (`VOICE_EXEMPLAR.md`, *Highest-value residual tells*).
   - **Citation-accuracy-against-source check** — Rule 0, applied to every claim.

   Only the target-reader and veteran personas run per chapter; the time-constrained
   persona (`beta-feedback/beta-reader-3-timeconstrained.md`) is a TOC/navigation-level
   gate run once against the whole book, not a per-chapter reviewer.

4. **Revise** against the gauntlet's findings.

5. **Reviewers find, a separate gate verifies.** Use a **fresh reviewer per perspective**
   (technical, voice, target-reader). When a fix is applied, a **distinct gate thread
   verifies that specific fix** against the source or the rubric. **Never trust the
   fixer's claim that the fix is correct** — the person who introduced or repaired a claim
   is the worst-placed to certify it. This is the same find/verify split that caught both
   near-misses in Rule 0.

---

## Practical guardrails (distilled from prior cycles)

- **No named-benchmark claim unless the query provably demonstrates the point.** If you
  cite IC10, IC11, or any named query as an example of a behaviour, trace the query
  through the actual planner and confirm it produces that behaviour. A benchmark name is
  not evidence (near-miss 1).
- **Respect edge invertibility.** Plain `out`/`in` MATCH edges are invertible (reverse
  traversal exists); only `WHILE` / `maxDepth` / `optional` edges are not. Any argument
  that turns on "you can't traverse this the other way" must check which kind of edge it
  is (near-miss 2).
- **Keep worked arithmetic internally consistent and cross-checked across sections.** If
  an estimate is `40` in §X, it is `40` everywhere it recurs; a fan-out used in one
  worked example must match the model used in the next. Re-add the numbers by hand.
- **For any cross-cutting change, sweep the WHOLE book.** When a change alters a count, an
  enumeration, a reference-table row, or a cross-link — e.g. adding "a fourth variant" —
  grep the entire book, not just the primary chapter. In a prior cycle a "three hash-join
  variants" claim had leaked into 4+ places (Ch 13 prose, Ch 16, Ch 17 reference tables,
  the TOC cross-reference matrix). Fix every occurrence and the count claims that depend
  on it.

---

A chapter is accepted only when it has cleared the gauntlet (step 3) **and** every code
claim has passed an independent source-level verification (Rule 0). A green tone gate
alone is never sufficient.
