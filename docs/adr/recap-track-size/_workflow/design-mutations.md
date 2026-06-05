## Mutation 1 — 2026-06-05 — content-edit (design.md)

**Diff summary**: Phase 2 consistency-review finding CR1. The §"Design freeze" section claimed the inline-replanning mutation trigger sits "four lines from the rule that says `design.md` is never modified after planning". The proximity is false against the live `design-document-rules.md`: the trigger is at line 128 (§Mutation discipline "applies to" enumeration) and the freeze rule, Rule 15, is at line 862 (§Rules), ~734 lines apart in separate sections. Replaced the false proximity with an accurate description — the mutation-discipline list names inline replanning as a trigger, while a separate freeze rule forbids post-planning modification. The self-contradiction the freeze closes is unchanged; only the mislocated "four lines" framing was corrected.

**Mechanical checks** (target=design): PASS
**Cold-read** (scope: bounded): PASS

**Findings**:
- none

**Iterations**: 1 of 3 (PASS)
