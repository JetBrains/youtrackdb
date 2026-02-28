# YTDB-576 Session Prompt

Use this prompt to start or continue work on YTDB-576. Copy-paste it into Claude.

---

## Prompt

```
Read the plan at .claude/plans/ytdb-576-record-iterators-instead-count.md and check the
Progress section to find the next unchecked step ([ ]). Execute that step following this
workflow:

1. Read the step's description and all referenced source files
2. Make the code changes described in the step
3. Write or update tests to cover the changed behavior
4. Run tests: ./mvnw -pl gremlin-annotations,core clean test
5. If tests fail — fix and re-run until green
6. Launch code-reviewer sub-agent to review all changes; iterate until it's satisfied
7. Mark the step's checkbox as [x] in the Progress section of the plan file
8. Commit all changes (including the updated plan file):
   YTDB-576: <step description from the plan>

If all steps are already checked [x], report that the task is complete.
```
