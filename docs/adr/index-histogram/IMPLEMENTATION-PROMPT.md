# Index histogram — implementation session prompt

Copy the section below (between the `---` markers) and paste it at the start of a
fresh Claude Code session to begin or continue implementation.

---

## Prompt

You are continuing the implementation of **persistent equi-depth histograms** for
YouTrackDB's index engine.

### Context

- **Implementation plan (ADR):**
  `docs/adr/index-histogram/index-histogram-implementation-plan.md`
- **Branch:** `ytdb-584-index-histogram`
- **Tracking table:** Section 9 → "Implementation Progress" in the ADR above.

### Determine current step

Before doing anything else, read the "Implementation Progress" table in Section 9
of the ADR. Find the **first row whose checkbox is unchecked** (`- [ ]`). That row's
step number is your `CURRENT_STEP`. If all checkboxes are checked, tell the user
that all steps are complete and stop.

Then implement **Step {CURRENT_STEP}** as described in:
- **Section 8 → "Step {CURRENT_STEP}"** of the ADR (detailed specification with code
  snippets and rationale)
- **Section 9 → Phase 1 table** (summary: files, LOC estimate)

### Workflow (strictly follow this order)

1. **Read the ADR.** Read Sections 8 and 9 of the implementation plan. Understand
   the full specification for Step {CURRENT_STEP}, including referenced sections
   (e.g., "Section 5.2", "Section 6.2.1"). Also read any earlier steps' code if
   the current step depends on them (see the Dependency Order in Section 9).

2. **Implement.** Write the code for this step. Follow all project conventions from
   `CLAUDE.md` (2-space indent, 100-char line width, no wildcard imports, comment
   non-obvious code, etc.). Keep changes minimal and focused — do not touch code
   outside the step's scope.

3. **Write tests.** After the implementation is complete, write **behavior-driven
   tests** for the code produced in this step. Follow these guidelines:
   - **Focus on observable behavior**, not implementation details. Each test should
     describe a scenario (given/when/then) in its name or comment, making the
     expected behavior clear to a reviewer.
   - **Cover all public API surface** introduced or modified in this step: normal
     paths, edge cases, error/exception paths, and boundary conditions.
   - **Meet or exceed the project coverage thresholds** (85 % line / 70 % branch
     for new code — see `CLAUDE.md`). Use the `coverage-gate.py` script to
     verify; do not compute coverage by hand.
   - **Place tests in the right module and class**: prefer adding to an existing
     test class when the scope matches; create a new test class only when no
     suitable one exists. Follow the same test framework as the surrounding code
     (JUnit 4 in `core`/`server`, TestNG in `tests`).
   - **Do not write trivial getter/setter tests.** Tests must exercise meaningful
     logic — state transitions, computations, invariant enforcement, serialization
     round-trips, concurrency safety, etc.
   - If achieving the coverage threshold is impossible without testing
     implementation internals (e.g., unreachable defensive branches), note this
     when reporting and ask the user how to proceed rather than writing brittle
     white-box tests.

4. **Review loop.** After implementation and tests are complete, launch the
   `code-reviewer` agent to review all changes (both production code and tests).
   Read its feedback carefully. Fix every issue it raises. Re-launch the
   `code-reviewer` agent after fixes. Repeat this loop until the reviewer reports
   no issues. Do NOT skip or shortcut this loop.

5. **Run tests and verify coverage.** Follow the Pre-Commit Verification section
   from `CLAUDE.md`:
   - Run unit tests for affected modules.
   - Run integration tests if the step touches storage, WAL, or index code.
   - Run coverage check for new code using the `coverage` profile and
     `coverage-gate.py` (see `CLAUDE.md`). If coverage is below the threshold,
     go back to step 3 and add more tests before proceeding.
   - Do not proceed if tests fail — fix first, then re-run.

6. **Update the tracking table.** In the ADR file, Section 9 → "Implementation
   Progress" table:
   - Check the checkbox for this step: change `- [ ]` to `- [x]`.
   - Fill in the "What was done" column with a brief summary of what was actually
     implemented (files created/modified, any deviations from the plan).
   - Also check the checkbox in the Phase 1 table row for this step.

7. **Report and wait for approval.** Tell the user:
   - What was implemented (short summary).
   - Which files were created or modified.
   - Test results (pass/fail, coverage numbers if applicable).
   - Any deviations from the plan and why.
   - Then explicitly ask: *"Ready to commit. Shall I proceed?"*
   - **Do NOT commit until the user explicitly approves.**

8. **Commit.** Once approved, create a single commit for this step:
   - Message format: `YTDB-584: Step {CURRENT_STEP} — <short description>`
     (get the YTDB issue number from the branch name or ask the user).
   - Stage only the files relevant to this step.
   - Verify with `git status` after committing.

9. **Session complete.** After the commit, tell the user:
   *"Step {CURRENT_STEP} is committed. Clear this session and start a new one
   to continue."*

### Rules

- **One step = one commit = one session.** Do not implement multiple steps.
- **Do not commit without explicit user approval.**
- **Do not skip the test-writing step.** Every step that produces new or modified
  code must have behavior-driven tests before proceeding to review.
- **Do not skip the code review loop.** The reviewer must be satisfied before you
  move to running tests.
- **Do not modify the implementation plan** except for the tracking table updates
  in step 6.
- **If the plan is ambiguous or seems wrong**, stop and ask the user before
  proceeding. Do not guess.
- **If tests fail and the fix is non-trivial**, explain the situation to the user
  and ask how to proceed rather than making large unplanned changes.

### Code quality guidelines

- **Refactoring internal classes is allowed.** You may refactor, rename, restructure,
  or otherwise modify any class under `com.jetbrains.youtrackdb.internal` as needed
  to produce clean, well-factored code or to make code testable (e.g., extracting
  methods, breaking dependencies, introducing seams). Only the public API surface
  (`com.jetbrains.youtrackdb.api`) must remain unchanged.
- **Use Java assertions generously.** Add `assert` statements liberally to enforce
  invariants, preconditions, and postconditions — but **only when the assertion has
  zero cost in production**. Java `assert` statements are compiled out when assertions
  are disabled (the default in production), so they are free. Do not use
  `if (...) throw` for invariant checks that are only useful during development;
  prefer `assert` for those cases. Use `if (...) throw` only for checks that must
  execute in production (e.g., validating external input).
