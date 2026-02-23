# Session Prompt — Copy and paste this to start each session

```
Read the plan at .claude/plans/errorprone-warnings-fix-plan.md, find the next step with status "pending", and execute it. Follow the per-step workflow exactly:

1. Fix all instances of the warning
2. Add `-Xep:<CheckName>:ERROR` to the ErrorProne plugin args in both root pom.xml and core/pom.xml
3. Run `./mvnw clean compile` — verify zero warnings for that check
4. Run unit tests for affected modules
5. Update the progress table in the plan file (set status to "done", fill in commit hash)
6. Commit with message: `YTDB-507: Fix ErrorProne <CheckName> warnings and elevate to error`

If all steps show "done", run the final verification (Step 8) instead.
```
