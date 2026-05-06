# MCP Steroid — Skill Loading

Which mcp-steroid skill to fetch via `steroid_fetch_resource` for which task. The session-start preflight rules live in `CLAUDE.md` § MCP Steroid → "Session-start preflight"; this file is reference material loaded on demand.

## Load when relevant

| Trigger | Skill |
|---|---|
| First use of mcp-steroid in a session — base mental model | `coding-with-intellij` |
| Onboarding to the IDE-control API | `coding-with-intellij-intro` |
| Wrapping read/write actions correctly | `coding-with-intellij-context-api` |
| Common idioms when scripting the IDE | `coding-with-intellij-patterns` |
| Navigating Java symbols/usages via PSI (instead of grep) | `coding-with-intellij-psi` |
| Rename/extract/move-class refactors across a large module | `coding-with-intellij-refactoring` |
| Editing files the IDE has cached, or VFS-related issues | `coding-with-intellij-vfs` |
| Avoiding EDT / Read-Action / threading errors | `coding-with-intellij-threading` |
| Multi-site / multi-file literal-text edit through the IDE — replaces 2+ chained native `Edit` calls. Use the dedicated `steroid_apply_patch` tool, not `steroid_execute_code`'s `applyPatch { }` DSL (no kotlinc compile, fits the ~60s MCP per-tool timeout) | `apply-patch-tool-description` |
| Before first `steroid_execute_code` call | `execute-code-overview`, `execute-code-tool-description` |
| Reading run/test output back | `execute-code-feedback` |
| Maven projects (e.g. YouTrackDB — `./mvnw`) | `execute-code-maven` |
| Gradle projects (only if `build.gradle*` exists) | `execute-code-gradle` |
| Diagnosing a Java runtime bug where the stack trace + test output don't explain the failure (concurrency hangs, unexpected branch taken, mid-operation state corruption) — only when mcp-steroid is reachable and the project is open. Skip for clean assertion failures or compile errors where the stack trace tells the whole story. | `debugger/overview` (then per-step recipes — `add-breakpoint`, `debug-run-configuration`, `wait-for-suspend`, `evaluate-expression`, `step-over`) |

## Skip by default

- `coding-with-intellij-spring` — only load if the active project actually has `org.springframework` deps. Not relevant for YouTrackDB.
- `debug-remote-ide-skill` — only for debugging an IntelliJ plugin running in a second IDE instance. Not a typical workflow.

## Loading discipline

- Load only the skills needed for the current task; don't pre-load the whole set.
- Prefer mcp-steroid IDE operations (PSI search, refactor, execute-code) over raw `grep`/`Edit`/`Bash` when the IDE is connected and the task benefits.
