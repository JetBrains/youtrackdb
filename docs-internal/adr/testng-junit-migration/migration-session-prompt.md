# TestNG → JUnit 5 Migration Session Prompt

Copy the prompt below and paste it at the start of a new Claude Code session to begin
or continue the migration. The prompt is self-contained — the agent will read the plan,
determine the next step, execute it, run verification, and request code review.

---

## Prompt

```
You are executing the TestNG to JUnit 5 migration for the YouTrackDB `tests` module.

## Instructions

1. **Read the implementation plan**:
   - Read `docs/adr/testng-junit-migration/implementation-plan.md` — it contains the
     full migration plan, architecture decisions, annotation mapping table, traceability
     comment convention, and a progress tracking table at the bottom.

2. **Determine which step to execute**:
   - Look at the "Progress Tracking" table at the bottom of the plan.
   - Find the first row with status `NOT STARTED` — that is your step.
   - If all steps are `COMPLETED`, report that the migration is finished and stop.

3. **Read the step details**:
   - Find the step's section in the plan (e.g., "### Step 0:", "#### Step 1:", etc.).
   - Read every instruction and note for that step carefully.

4. **Execute the step**:
   - Follow the step's instructions exactly.
   - For Steps 1–15 (class migration), for EACH class being migrated:
     a. Read the original TestNG class in `com.jetbrains.youtrackdb.auto`.
     b. Create the JUnit 5 equivalent in `com.jetbrains.youtrackdb.junit` (preserve
        subpackage structure if any, e.g., `hooks/`).
     c. Apply ALL conversions from the "Lifecycle Mapping" table in the plan.
     d. **Critical — assertion argument order**: TestNG `Assert.assertEquals(actual, expected)`
        must become JUnit 5 `assertEquals(expected, actual)`. Reverse EVERY assertion's
        argument pair. Same for `assertNotEquals`, `assertSame`, `assertNotSame`. For
        `assertTrue`/`assertFalse`/`assertNull`/`assertNotNull` the argument order is
        the same — do not reverse those.
     e. Add the traceability comment above each test method per the convention in the plan:
        `// Migrated from: com.jetbrains.youtrackdb.auto.ClassName#methodName`
        If the method's annotation semantics changed (e.g., expectedExceptions was converted
        to assertThrows), add a second comment line:
        `// Original used: @Test(expectedExceptions = SomeException.class)`
     f. Remove the class from `embedded-test-db-from-scratch.xml`.
     g. Add the class to the JUnit 5 suite (`EmbeddedTestSuite`).
   - For Step 0 (infrastructure), create all base classes and extensions as specified.
   - For Step 16 (removal), delete TestNG classes and remove traceability comments.
   - For Step 17 (cleanup), perform all listed cleanup tasks.

5. **Verify — all tests must pass**:
   - Run: `./mvnw -pl tests clean test`
   - If tests fail, diagnose and fix before proceeding. Do not commit broken code.
   - If the step involves changes to root `pom.xml`, run `./mvnw clean package -DskipTests`
     first to verify the build compiles.

6. **Code review loop**:
   - After all tests pass, invoke the `code-reviewer` agent to review all changes made
     in this step.
   - If the reviewer finds issues, fix them and re-run the tests.
   - Repeat until the reviewer is satisfied.

7. **Commit**:
   - Stage only the files relevant to this step.
   - Commit with message format: `TestNG migration: Step N — <step description>`
   - Example: `TestNG migration: Step 0 — Add JUnit 5 infrastructure and dual-runner setup`

8. **Update the progress table**:
   - In `docs/adr/testng-junit-migration/implementation-plan.md`, update the row for
     the completed step:
     - Set `Status` to `COMPLETED`
     - Set `Commit SHA` to the commit hash (short form)
     - Set `Agent Session` to the current date
     - Add any relevant notes (e.g., unexpected issues encountered)
   - Amend the commit to include this table update.

9. **Report completion**:
   - Summarize what was done: which classes were migrated, any issues encountered,
     and the current overall progress (e.g., "Step 3 of 17 completed, 11/95 classes
     migrated").

## Key files to know about

- **Implementation plan**: `docs/adr/testng-junit-migration/implementation-plan.md`
- **TestNG suite XML**: `tests/src/test/java/com/jetbrains/youtrackdb/auto/embedded-test-db-from-scratch.xml`
- **TestNG base classes**: `tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseTest.java`,
  `tests/src/test/java/com/jetbrains/youtrackdb/auto/BaseDBTest.java`
- **TestNG listener**: `tests/src/test/java/com/jetbrains/youtrackdb/TestNGTestListener.java`
- **JUnit 5 target package**: `tests/src/test/java/com/jetbrains/youtrackdb/junit/`
- **Root POM**: `pom.xml` (for dependency management)
- **Tests POM**: `tests/pom.xml` (for module-level config)
- **Project conventions**: `CLAUDE.md`

## Reminders

- Do NOT modify the original TestNG test classes (they must keep running until Step 16).
- Do NOT skip the code review loop — iterate until the reviewer is satisfied.
- Do NOT commit if tests fail.
- Assertion argument order reversal is the #1 source of migration bugs — double-check
  every `assertEquals`, `assertNotEquals`, `assertSame`, `assertNotSame` call.
- The JUnit 5 suite class ordering must exactly match the TestNG XML ordering.
- Each session handles exactly ONE step. Do not attempt multiple steps.

Begin by reading the implementation plan and determining your step.
```
