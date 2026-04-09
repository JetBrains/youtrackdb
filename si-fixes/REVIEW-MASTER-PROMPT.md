# SI Indexes — Second Code Review Fixes — Master Prompt

Use this prompt to work through the review fixes one at a time. Each fix is a
separate session and standalone commit on the `ytdb-523-si-indexes` branch.

## How to use

1. Check `si-fixes/REVIEW-TRACKING.md` for the next `[ ] TODO` fix
2. Read the fix file for that ID (e.g., `si-fixes/review-fix-01-counter-rollback.md`)
3. Implement the fix following the instructions in the file
4. Run the verification commands specified in the fix file
5. Run `./mvnw -pl core spotless:apply` to fix formatting
6. Commit with message: `YTDB-523: [fix description from the file]`
7. Update `si-fixes/REVIEW-TRACKING.md`: change `[ ] TODO` to `[x] DONE` and add the commit hash
8. Stop after one fix per session

## Session prompt

Copy this into a new Claude Code session:

---

I'm working through code review fixes for PR #834 (SI indexes) on branch
`ytdb-523-si-indexes`. Each fix is a separate commit.

**Step 1**: Read `si-fixes/REVIEW-TRACKING.md` to find the next `[ ] TODO` item
in the "Recommended Order" section.

**Step 2**: Read the corresponding fix file (e.g., `si-fixes/review-fix-01-counter-rollback.md`).

**Step 3**: Implement exactly what the fix file describes.

**Step 4**: Run verification:
```bash
./mvnw -pl core spotless:apply
./mvnw -pl core clean test
```

**Step 5**: If tests pass, commit:
```bash
git add -A && git commit -m "YTDB-523: <description from fix file title>"
```

**Step 6**: Update `si-fixes/REVIEW-TRACKING.md`:
- Change `[ ] TODO` to `[x] DONE` for the completed fix
- Record the commit hash in the Commit column

**Step 7**: Stop. One fix per session.

**Important rules**:
- Do NOT modify code beyond what the fix file specifies
- Do NOT fix other issues you notice — they may be covered by later fix files
- If a fix requires changes that conflict with the current code, investigate
  whether an earlier fix already changed the code and adjust accordingly
- If a fix depends on another fix (see Dependencies in REVIEW-TRACKING.md),
  check that the dependency was completed first
- Always verify with `spotless:apply` + `clean test` before committing
- Check the REVIEW-TRACKING.md "Recommended Order" to know which fix to do next

---

## Fix order rationale

Fixes are ordered to minimize risk and maximize independence:

1. **R-01** (docs) — No code logic changes, documents existing behavior
2. **R-09** (minor perf) — Trivial reordering, zero risk
3. **R-06** (quality) — Refactors one method signature, well-tested
4. **R-05** (upgrade) — Adds fallback in load(), straightforward
5. **R-03** (DRY) — Larger refactor extracting shared logic
6. **R-04** (DRY) — Depends on R-03, small addition
7. **R-07** (perf) — New method on IndexesSnapshot, additive
8. **R-08** (correctness) — Uses R-07 if available, modifies histogram build
9. **R-02** (perf) — Largest change: new BTree method, new tests

## Skipping fixes

If the author decides a fix is not worth doing, update REVIEW-TRACKING.md:
change `[ ] TODO` to `[-] SKIP` and add a brief reason in the Commit column.
