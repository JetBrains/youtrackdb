# Session Prompt — Copy and paste this to start each session

```
Read the plan at .claude/plans/errorprone-warnings-fix-plan-all.md, find the first unchecked (- [ ]) step, and execute it. Follow the per-step workflow exactly:

1. Fix all instances of the warning for that step
2. Launch a code-reviewer sub-agent to review the changes — iterate fixes until the reviewer is satisfied
3. Mark the checkbox in the plan file (- [ ] → - [x])
4. Commit both the code fix and the updated plan file with message: `Fix ErrorProne <CheckName> warnings`
5. Stop and wait — the next step will be done in a new session

If all steps show checked (- [x]), report that the plan is complete.
```
