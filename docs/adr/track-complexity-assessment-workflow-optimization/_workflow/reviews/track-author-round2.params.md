# design-author params — Step-4b track authoring, round 2

- target: tracks
- output_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- plan_dir: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- research_log_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- round: 2

## Settled decomposition (unchanged — honor it)

Same as round 1: keep the track titles, `## Purpose / Big Picture` sections, the
in-scope/out-of-scope file lists and inter-track dependency, the DR titles /
ownership / `**Full design**` pointers, the §1.7 note, and the `## Invariants &
Constraints`. Do NOT touch `## Purpose / Big Picture` (the orchestrator already
applied the one Purpose-section gloss fix) or the plan Component Map.

## flagged_passages — re-draft only these (the round-1 auditor's findings)

Re-ground only the passages below; do not re-author the whole document. Each is
a readability finding (over-dense folding, idiom, garden-path grammar, too-terse,
negative parallelism). Fix the prose per the suggested direction while keeping
the decision content and seed fidelity intact.

### track-1.md
1. **D10 Rationale (~lines 65-74), over-dense.** A four-driver chain
   ("D5's …; D8's …; D1 removed …; All four …") plus a `(a)…(b)…(c)…(d)` field
   enumeration crammed into one sentence, and it forces the reader to resolve
   D5 / D8 (Track-2 DRs) from this slice. Fix: break the four added fields onto
   separate lines (a list, one field per line with its driver); split the
   driver chain into one sentence per driver; refer to the downstream needs by
   plain name (Phase-C tag governance, the adr predicate, the dropped
   `tier=minimal` trigger) rather than requiring the reader to resolve another
   track's D-numbers.
2. **D8a Alternatives (~lines 87-93), hard-to-read.** "both use proxies for
   decision substance" and "where those rejections do not bite" are abstract /
   idiomatic. Fix: name the concrete proxy (keying the artifact off track count
   rather than off whether the change actually needs a design) and replace "do
   not bite" with "do not apply here, because …".
3. **Resume-collision passage (~lines 218-221), hard-to-read.** "is
   byte-identical between the design+single-track steady state and a
   mid-authoring crash" is a garden-path. Fix: restate as "the on-disk file set
   (a `design.md`, no plan, one track file) is identical in two cases: the
   design+single-track steady state and a mid-authoring crash."
4. **Absent-`design_gate` clause (~lines 75-78), too-terse.** It asserts the
   router "already handles" an absent `tier` / "surface the inconsistency" but
   never says what that handling does. Fix: add a clause naming the existing
   behavior (it halts and reports the missing field to the user rather than
   defaulting a phase, so the new field inherits the same posture).
5. **Ledger-field enumeration (~lines 194-204), over-dense.** Four fields with
   nested parenthetical asides plus a mid-clause `[ -n "$LEDGER_X" ] && …` code
   literal. Fix: present the four new fields as a bulleted list (type + meaning
   per line); pull the per-field `reject_bad_ledger_value` + builder-line
   mechanics into a separate short sentence or sub-bullet.
6. **"unwelds" sentence (~lines 50-54), hard-to-read.** Coined verb "unwelds" +
   a three-claim semicolon sentence. Fix: replace "unwelds" with a plain verb
   (separates / splits apart) and split into the new state, its
   unrepresentability under the tier model, and the on-disk collision the
   Phase-1-complete marker resolves.
   (Note: the round-1 F7 Purpose `max(step tags)` gloss is already fixed by the
   orchestrator — leave Purpose alone.)

### track-2.md
7. **D7 sub-cases (~lines 165-171), over-dense.** Three routing sub-cases, each
   with a parenthetical exception, chained with semicolons and `→` arrows into
   one sentence. Fix: break the three sub-cases onto separate numbered/bulleted
   lines, one rule per line with its exception as a sub-clause.
8. **"sacred" (~lines 135-136), hard-to-read.** Metaphor. Fix: use the literal
   "is never suppressed at Phase C" (the phrasing already used elsewhere).
9. **"kills the double-report" (~lines 170-171), hard-to-read.** Metaphor. Fix:
   "the non-overlap rule that prevents the double report".
10. **ADR-substance sentence (~lines 197-199), negative parallelism.** "track
    decision *substance* … not a proxy" is the banned not-X construction whose
    tail repeats the Alternatives bullet. Fix: drop the contrastive tail, keep
    the positive claim ("an ADR is a decision record, so it should track
    decision *substance* — how complex the work was").

Return only a thin summary. Never return the drafted track content.
