# MCP Steroid — Maven Routing

Full rules for routing Maven invocations through mcp-steroid vs Bash `./mvnw`. The summary lives in `CLAUDE.md` § MCP Steroid → "Maven — when to route through mcp-steroid".

**Default:** keep `./mvnw` invocations on Bash. They're durable across IDE restarts, support `run_in_background` for streaming, and integrate with project-specific scripts (`coverage-gate.py`, CI flags, JMH profiles).

**Switch to `steroid_execute_code` when noise reduction is worth the IDE coupling:**

| Use mcp-steroid execute-code when… | Stay on Bash `./mvnw` when… |
|---|---|
| Running a single test class or method (`-Dtest=Foo#bar`) — IDE returns a parsed test tree with per-method status and split stack traces | Full-suite runs (`./mvnw clean package`, `verify`) — same Maven, same noise, but Bash survives IDE crashes |
| Compile-fix loops where you want just the compiler errors, not the surrounding Maven INFO/download chatter | Coverage runs (`-P coverage`) — must pair with `coverage-gate.py` driven by Bash |
| Quick post-edit "did this still compile?" check on a single module | Integration tests (`-P ci-integration-tests`), Docker tests, JMH benchmarks |
| Re-running the failing test after a fix, where seeing structured pass/fail matters | Anything that runs >5 min — IDE liveness becomes a risk, and Bash + `run_in_background` is more robust |

## Preflight before routing Maven through mcp-steroid

Confirm via `steroid_list_projects` that the relevant project is open in the IDE *and* matches the working tree (cwd / worktree). If the project isn't open or points at a different checkout, fall back to Bash — don't open it just for a one-shot run.

## Two-call launch+poll pattern is mandatory

Single-call doesn't work:

- The MCP HTTP transport cancels in-flight tool calls after ~60 s. A Maven test on a fresh checkout often takes 30–120 s, so a single script that launches and `await`s the run will be cancelled mid-run by the client even though the IDE-side script timeout is much larger.
- **Call 1 (launch, returns immediately):** build a `MavenRunnerParameters`, then `MavenRunConfigurationType.createRunnerAndConfigurationSettings(...)` + `RunManager.addConfiguration` + `ProgramRunnerUtil.executeConfiguration(...)` dispatched on `Dispatchers.EDT`. Give the run config a unique name (e.g. `"Maven test (MCP)"`) so call 2 can find it.
- **Call 2 (poll, re-issue every 20–30 s):** look up `RunContentManager.getInstance(project).allDescriptors`, find the one with the matching `displayName`, read `descriptor.processHandler.isProcessTerminated` / `exitCode`, and parse the surefire `<TestClass>.txt` files under `<module>/target/surefire-reports/` for the `Tests run: …` summary line. Each script returns in <2 s — well under the cancel window.
- **Don't use `MavenRunConfigurationType.runConfiguration(...)` directly** — that convenience overload calls `ApplicationManager.getApplication().invokeAndWait(...)` internally and can block the script's coroutine dispatcher.
- **Don't use SMT listeners or `messageBus.connect()`** — SMT events do not fire reliably for Maven surefire, and a long-lived connection is brittle across retries. Polling the descriptor's `ProcessHandler` is the canonical signal.
- On failure with no surefire output, dump the tail (~60 lines) of the Build view's `ConsoleViewImpl.editor.document.text` so you see `BUILD FAILURE`, missing-artifact errors, or compile errors without a follow-up call.

## Hard rules for IDE-routed Maven

- **`-am` (also-make) is BANNED.** It walks the upstream graph and OOM-kills the IDE container. Pin to the targeted submodule with `-pl <module>`. If the build then complains about a missing in-reactor sibling artifact (`Could not resolve artifact …:sibling:jar:X`), run a separate `install -pl <missing-module> -DskipTests` round through the same launch+poll shape; for missing parent POMs (`Could not resolve parent POM`) use `install -pl . -N -DskipTests` (the `-N` flag installs only the root, not the reactor). Stop after at most two install rounds — beyond that, escalate rather than chain installs.
- **Read only the surefire `Tests run:` summary line per class**, not the full Maven console. Spring/heavy tests generate 100 KB+ of output and will overflow the response. The `<TestClass>.txt` first line is the same number a human reads from the Run tool window.
- **One test class per `-Dtest=…`.** Don't run `-Dtest=A,B,C,D` — token overflow on long output. If you need several classes, launch them sequentially.
- **After editing `pom.xml` or any reactor POM**, re-import via `MavenProjectsManager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("post-pom-edit", explicit = true))` and `Observation.awaitConfiguration(project)` *before* running tests or inspections. Without it, the IDE shows phantom "cannot resolve symbol" errors from undownloaded deps. Note: `MavenSyncSpec` is in package `org.jetbrains.idea.maven.buildtool` (not `.project`).

## Don't use mcp-steroid for

Dependency resolution debugging (`./mvnw dependency:tree`), Spotless apply/check, version bumps, or anything where you'll want to grep the full output afterward — Bash is simpler and the output is right there.

## JDK selection

JDK selection is a Bash-tool problem, not an IDE one: when Maven fails with `Unsupported class file major version`, `Fatal error compiling`, or parent-POM resolution errors on a fresh container, set `JAVA_HOME` in the shell *before* the next Maven invocation. Pick the lowest available JDK ≥ the project's `<java.version>` / `<maven.compiler.release>` requirement (don't trial-and-error upward from the lowest). For YouTrackDB: project requires JDK 21+, set `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-*` if the default differs.
