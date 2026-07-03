# Implement the Slate thread-weaving agent architecture as a pi extension

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with the PLANS.md methodology archived verbatim at `slate-dev/reference/codex-exec-plans.md` (repository-relative path). Re-read that file whenever it is not in context before revising this plan.

**Mandatory pre-reading, every session, before doing anything else:**

1. `slate-dev/RESEARCH_LOG.md` — the frozen Phase 0 research log. It contains the authoritative initial request, all design decisions with rationale, and all resolved design questions. It is FROZEN: never edit it; all new decisions and discoveries are recorded here, in this ExecPlan.
2. `slate-dev/reference/slate-blog.md` — the verbatim archive of the Slate technical report (the architecture being implemented). The original URL is behind a JavaScript wall; use only this local copy.
3. `slate-dev/reference/codex-exec-plans.md` — the ExecPlan methodology this document follows.

## Purpose / Big Picture

After this change, a developer running pi (the terminal coding agent) in this repository can type `/slate` and get an **orchestrator**: a main agent that no longer executes tactical work itself, but instead dispatches bounded actions to persistent **worker threads**, each with its own isolated context. Every completed action comes back as an **episode** — a compressed, structured summary of what the thread did — which the orchestrator reads, composes, and routes into further thread dispatches. Several threads can run in parallel from a single orchestrator turn.

Concretely, the user can say "investigate the three perf issues described in issue-perf-*.md and propose fixes", watch the orchestrator spin up three parallel threads, see three episodes come back, and see the orchestrator dispatch follow-up implementation actions to the same (context-retaining) threads — all without the orchestrator's own context window filling with file contents and command output.

This implements the architecture from the Slate technical report (Random Labs): threads as bounded workers, episodes as compressed synchronization units, thread weaving as the orchestration pattern, with a depth-1 guard against overdecomposition and synchronous-only dispatch (both per user decision — see `slate-dev/RESEARCH_LOG.md` Decision Log).

## Progress

- [x] (2026-07-03 06:10Z) Phase 0 research complete; research log written and frozen (`slate-dev/RESEARCH_LOG.md`).
- [x] (2026-07-03 06:30Z) Design discussion held with user; all open questions resolved; decisions recorded in research log Decision Log.
- [x] (2026-07-03 06:40Z) This ExecPlan created. AGENTS.md updated to point future sessions at this plan.
- [x] (2026-07-03 07:00Z) Workflow tooling: `.pi/extensions/handoff-guard.ts` added (live ctx% in footer, 40% warning, `/handoff` command). Activate with `/reload`.
- [x] (2026-07-03 06:50Z) M0: Prototype — in-process worker `AgentSession` spawned from an extension tool, with recursion guard and session persistence proven. Completed: `.pi/extensions/slate/index.ts` with `slate_proto`; `.gitignore` entry for `.pi/slate/`; automated print-mode tests passed (task execution, cross-process resume with retained memory, worker tool list free of slate tools). Remaining (fold into M3 acceptance): interactive-TUI observation of streaming progress lines and Esc-abort — mechanics are wired via `onUpdate` and `signal` → `session.abort()`.
- [x] (2026-07-03 07:05Z) M1: Core — ThreadManager, episode compression, `thread`/`threads`/`episode` tools, registry persistence, failure episodes. Completed: modules `state.ts`, `worker.ts`, `episodes.ts`, `threads.ts`, `tools.ts`, `index.ts` under `.pi/extensions/slate/`; all six M1 acceptance criteria verified headlessly (see M1 evidence in Artifacts and Notes): episode with 6 contract sections + file on disk; cross-process registry restore via `pi -c`; composition from injected episode only (no tools); parallel threads started same-millisecond; FAILED episode with diagnostics (`nope/nope` model); same-thread FIFO with retained context (41→42).
- [x] (2026-07-03 07:20Z) M2: Orchestrator mode — `/slate` command, tool restriction, doctrine system prompt, status widget. Completed: `mode.ts` (+ `store.onDidChange` hook in `state.ts`, wiring in `index.ts`); headless acceptance passed: with `/slate on` the orchestrator reports exactly `read, grep, find, ls, thread, threads, episode` (no bash/edit/write); a compound request produced two parallel thread dispatches (episodes t1.e1, t2.e1 on disk) and correct synthesis; mode persisted across process restart (`pi -c` → bash still absent); `/slate off` restored bash. Remaining: widget appearance is TUI-only — verify interactively during M3/M4.
- [ ] M3: Rendering — collapsed/expanded TUI rendering for thread dispatches, usage stats.
- [ ] M4: Validation — scripted print-mode acceptance scenario; dogfood run on a real ytdb task; retrospective written.

## Surprises & Discoveries

- (Phase 0 discoveries are in `slate-dev/RESEARCH_LOG.md` — notably: pi's `subagent` example is fire-and-forget and insufficient for threads; the pi SDK supports per-thread persistent sessions in-process; `complete()` from `@earendil-works/pi-ai/compat` enables episode compression; pi runs sibling tool calls in parallel natively.)

- Observation (M0): The recursion-guard problem has a first-class solution — `DefaultResourceLoaderOptions` supports `noExtensions`, `noSkills`, `noPromptTemplates`, `noThemes`, `noContextFiles`, and direct `systemPrompt` / `appendSystemPrompt` fields (the shipped docs only show `systemPromptOverride`; the `.d.ts` in `dist/core/resource-loader.d.ts` is the authoritative surface).
  Evidence: worker asked to list its tools returned exactly `read bash edit write grep find ls` — no slate tools.

- Observation (M0): `SessionManager.create(cwd, sessionDir?)` accepts an explicit session directory, so thread sessions live at `.pi/slate/threads/*.jsonl` as planned (no need to adopt pi's default sessions dir).
  Evidence: worker session file created at `/tmp/slate-m0-ws/.pi/slate/threads/2026-07-03T06-47-48-724Z_….jsonl`.

- Observation (M0): Cross-process thread persistence works: a second `pi -p` process resumed the worker via `SessionManager.open(file)` and the worker answered "what word did you write earlier" correctly **without tools**, purely from its restored context.
  Evidence: reply `I wrote \`alpha\` to \`check.txt\` in /tmp/slate-m0-ws.`

- Observation (M0): Reliable headless test pattern: run from a scratch dir with `pi -p --no-session -e <abs path to .pi/extensions/slate/index.ts> "<prompt instructing a slate_proto call>"`. Running from a temp cwd avoids project-trust prompts and double-loading the project-local extension. For registry-persistence tests, drop `--no-session` and use `pi -c -p` to continue the previous print-mode session in the scratch dir.
  Evidence: three passing test transcripts in Artifacts and Notes.

- Observation (M1): Safety refusal as harness failure mode — a planted test fact phrased "The launch code is BLUEFIN-7" made the orchestrator model refuse outright (empty stdout, exit 1, stderr note about refusals). Test fixtures must use innocuous wording ("project codename").
  Evidence: first M1 test run produced only "API integrators: you can reduce refusals…" on stderr.

- Observation (M1): D5 compressor resolution works as designed — with no config, the registry picked `anthropic/claude-sonnet-5` (newest available Sonnet) automatically.
  Evidence: episode header line `> date: … | compressor: anthropic/claude-sonnet-5`.

- Observation (M1): Parallel dispatch is real concurrency — two threads dispatched from one assistant message started at the same millisecond (first message timestamp 1783061838745 in both worker session files) with overlapping execution windows.

- Observation (M1): The episode compressor sees only the current action's messages, so it flagged a correct cross-action recall (thread remembered "41" from its prior action) as possible "confabulation". Fixed by extending the compressor prompt: the transcript covers only this action; prior-thread-context use is legitimate. Keep this in mind when tuning episode prompts.
  Evidence: t1.e3 episode text contained a spurious confabulation warning; answer 42 was nonetheless correct.

## Decision Log

Decisions D1–D11 were made in Phase 0 and are recorded with full rationale in `slate-dev/RESEARCH_LOG.md`. Summary for quick reference (do not re-litigate without user input):

- D1: Code in `.pi/extensions/slate/` (committed); runtime data in `.pi/slate/` (gitignored).
- D2: Threads = in-process pi SDK `AgentSession` objects backed by persistent `.jsonl` session files.
- D3: Orchestrator interface = tool family `thread` / `threads` / `episode`; no DSL in v1.
- D4: `/slate` mode strips `bash`/`edit`/`write` from the orchestrator, keeps `read`/`grep`/`find`/`ls` + slate tools, injects doctrine prompt. Off by default.
- D5: Episode compressor defaults to Sonnet (user decision), resolution order: configured `episodeModel` → newest available Anthropic Sonnet → worker's model.
- D6: Failed/aborted actions still produce episodes marked `STATUS: FAILED`.
- D7: Depth-1 threads only; workers never see slate tools. Synchronous dispatch only.
- D8: Episode contract: sections Intent / Actions Taken / Key Findings / Artifacts Changed / Open Issues / Handoff Notes; ~1–2k token target; full text returned to orchestrator; also stored as a file for by-reference composition.
- D9: Thread registry is session-scoped via `pi.appendEntry` snapshots, rebuilt on `session_start`; per-thread FIFO dispatch queue.
- D10: Validation = scripted print-mode scenario + ytdb dogfood task.
- D11: No new npm packages; only imports pi already provides to extensions.

New decisions made during implementation are appended below in the format:

- Decision: …
  Rationale: …
  Date/Author: …

- Decision: Add workflow tooling `.pi/extensions/handoff-guard.ts` (separate from the slate extension): shows live context usage in the pi footer via `ctx.getContextUsage()` (which returns `{ tokens, contextWindow, percent }`), warns once when usage crosses 40%, and provides `/handoff` — a session replacement via `ctx.newSession()` whose kickoff message points the fresh session at AGENTS.md → this ExecPlan. Handoff protocol: when the 40% warning fires, the agent must first flush state into this ExecPlan (Progress, Decision Log, Surprises & Discoveries), then the user runs `/handoff`.
  Rationale: Implementation sessions will burn context fast (reading pi sources, transcripts). Because all durable state lives in this ExecPlan and the frozen research log, a fresh-session handoff is near-lossless and strictly better than compaction — the same argument the Slate report makes about episode boundaries vs. lossy compaction. 40% keeps every working turn inside the model's high-attention region.
  Date/Author: 2026-07-03 / user request + agent implementation.

- Decision: Revise handoff-guard from human-watched footer to agent-self-checkable telemetry. After every turn the extension writes `${TMPDIR:-/tmp}/pi-context/<pi-pid>.json` (pid, sessionFile, model, tokens, contextWindow, percent, thresholdPercent, overThreshold, updatedAt); the file is removed on session shutdown. Verified: the bash tool's `$PPID` is the pi process pid, so the agent self-checks with `cat ${TMPDIR:-/tmp}/pi-context/$PPID.json` (protocol recorded in AGENTS.md § Context budget protocol — check at every milestone boundary). Additionally, on crossing the threshold the extension queues a one-time in-band steer message instructing the agent to flush this ExecPlan and propose `/handoff`, so long autonomous runs self-interrupt even without polling.
  Rationale: User should not have to watch the footer every turn ("write it in a temp file keyed by current session/process id similarly to Claude Code"). File keyed by pid disambiguates concurrent pi instances; in-band steer removes the need for the agent to poll every turn.
  Date/Author: 2026-07-03 / user request + agent implementation.

- Decision: This session measured context via the session `.jsonl` directly: model `anthropic/claude-fable-5` has a 1M context window; usage was ~168k tokens ≈ 17% — well under budget. M0 can proceed in the current session after `/reload`.
  Rationale: Corrects earlier assumption of a 200k window.
  Date/Author: 2026-07-03 / agent measurement.

## Outcomes & Retrospective

(to be written at milestone completions and at the end)

## Context and Orientation

**The repository.** The working tree at `/home/andrii0lomakin/Projects/ytdb/slate-pi` is a git worktree (branch of the YouTrackDB Java database project) dedicated to this experiment. The Java codebase is NOT touched by this work; it is only the dogfooding ground. Everything we build lives in three places: `slate-dev/` (planning docs — this file and the research log), `.pi/extensions/slate/` (the TypeScript implementation), and `.pi/slate/` (runtime data, gitignored).

**pi.** pi is a terminal-based AI coding agent installed globally at `/home/andrii0lomakin/.npm-global/lib/node_modules/@earendil-works/pi-coding-agent` (version 0.80.3 at plan time; Node v22). Its documentation lives inside that package under `docs/` (notably `extensions.md`, `sdk.md`, `session-format.md`) and working examples under `examples/`. pi loads TypeScript **extensions** without compilation (via jiti) from `.pi/extensions/*.ts` or `.pi/extensions/*/index.ts` in a trusted project. Extensions can be hot-reloaded in a running session with `/reload`.

**Terms used throughout this plan:**

- *Orchestrator*: the ordinary interactive pi session the user talks to, with the slate extension loaded. It plans and dispatches; in `/slate` mode it cannot edit files or run bash itself.
- *Thread*: a persistent worker. Technically: a pi SDK `AgentSession` whose conversation is stored in its own `.jsonl` session file, created and driven in-process by the extension. A thread accumulates context across multiple dispatched actions and can be resumed after pi restarts.
- *Action*: one bounded task given to a thread ("run the build and report errors", "find where pricing is loaded and summarize the call graph"). One action = one `session.prompt(...)` call on the worker session, awaited to completion.
- *Episode*: the compressed, structured markdown record of one completed action — produced by a separate LLM call that summarizes the messages generated during that action. The episode text is returned to the orchestrator as the tool result and stored under `.pi/slate/episodes/`.
- *Thread weaving*: the loop the orchestrator runs: dispatch actions (possibly several in parallel, one tool call each), receive episodes, compose episodes into new dispatches (`context` parameter), synthesize.
- *Depth-1 guard*: workers get only ordinary coding tools (`read`, `bash`, `edit`, `write`, `grep`, `find`, `ls`) — never the slate tools — so threads cannot spawn threads.

**Key pi APIs this plan relies on** (all verified in Phase 0 against the local pi docs and examples; re-check the docs named below if anything fails):

- `pi.registerTool({ name, description, parameters, execute(toolCallId, params, signal, onUpdate, ctx), renderCall, renderResult, promptSnippet, promptGuidelines })` — registers a tool the orchestrator's LLM can call. `onUpdate(partial)` streams progress to the TUI; `signal` aborts on Esc. Throwing from `execute` marks the result as an error; returning normally never does. (`docs/extensions.md`)
- `createAgentSession({ cwd, model, tools, sessionManager, resourceLoader, authStorage, modelRegistry, settingsManager, thinkingLevel })` from `@earendil-works/pi-coding-agent` — creates an in-process agent session. `SessionManager.create(cwd)` makes a new persistent session file; `SessionManager.open(path)` reopens one; `session.sessionFile` reports the path; `session.prompt(text)` runs one full agentic action to completion; `session.subscribe(listener)` streams events; `session.abort()` cancels; `session.dispose()` cleans up. (`docs/sdk.md`)
- `DefaultResourceLoader` — discovers extensions/skills/prompts/context files. CRITICAL RISK: by default it would discover `.pi/extensions/slate` and load the slate extension *inside worker sessions*, recursively. M0 exists to find the clean way to exclude extensions from worker resource loading (options: a loader option, a custom minimal `ResourceLoader`, or `systemPromptOverride` + explicit empty overrides). Read the loader source in the pi package (`src/` under the install path) during M0.
- `complete(model, { messages }, { apiKey, headers, env, maxTokens, signal })` from `@earendil-works/pi-ai/compat`, plus `ctx.modelRegistry.getApiKeyAndHeaders(model)` and `serializeConversation(convertToLlm(messages))` from `@earendil-works/pi-coding-agent` — the episode compressor: turn an action's message list into readable transcript text and summarize it with one LLM call. (pattern: `examples/extensions/custom-compaction.ts`)
- `pi.appendEntry(customType, data)` + scanning `ctx.sessionManager.getBranch()` on `session_start` — persistence for the thread registry that survives restarts and respects session branching. (`docs/extensions.md`, State Management)
- `pi.registerCommand`, `pi.setActiveTools`, `pi.getActiveTools`, `pi.on("before_agent_start")` returning `{ systemPrompt }`, `ctx.ui.setWidget/setStatus/notify` — used by `/slate` mode. (pattern: `examples/extensions/plan-mode/`)
- Parallel dispatch: pi executes sibling tool calls from one assistant message concurrently by default. The extension only adds a per-thread FIFO queue so one thread never runs two actions at once, and a global concurrency cap (default 4).

**Existing example to mine for patterns (not copy wholesale):** `examples/extensions/subagent/` in the pi package — tool schema shape, streaming-update bookkeeping, usage-stat aggregation, abort propagation, and collapsed/expanded rendering. Its execution model (spawn `pi --mode json -p --no-session` subprocess) is exactly what we are NOT doing (fire-and-forget, no persistent thread context).

## Plan of Work

The work proceeds in five milestones. Each is independently verifiable, and each leaves the tree in a working state.

### Milestone 0 — Prototype: one in-process worker session (de-risking)

Scope: prove the two riskiest mechanics in isolation, with a throwaway tool, before building the real thing. At the end of M0 there exists a tool `slate_proto` that (a) creates a worker `AgentSession` whose conversation persists to a session file, (b) does NOT load any extensions (recursion guard verified), (c) streams progress into the orchestrator TUI, (d) can be called a second time to resume the same session file and demonstrate the worker remembers the first action, and (e) honors Esc/abort.

Work: create `.pi/extensions/slate/index.ts` exporting a default extension factory that registers `slate_proto` with parameters `{ task: string, resume?: boolean }`. In `execute`, build `AuthStorage.create()`, `ModelRegistry.create(authStorage)`, a resource loader that yields no extensions (investigate `DefaultResourceLoader` options first; if none disables discovery, implement a minimal object satisfying the `ResourceLoader` interface — find the interface in the pi package source, e.g. `grep -rn "interface ResourceLoader" <pi-install>/src`), and a `SessionManager`. For the first call use `SessionManager.create(ctx.cwd)` and print `session.sessionFile`; persist that path in module state and in the tool result details. For `resume: true`, use `SessionManager.open(previousPath)`. Investigate during M0 whether a session file can be created at an explicit path like `.pi/slate/threads/<id>.jsonl` (read `SessionManager` source); if yes, use that layout from M1 on; if no, record the SDK-chosen path in the registry instead (log the finding in Surprises & Discoveries either way). Subscribe to worker events and forward tool-call starts and text deltas through `onUpdate` as short progress lines. Await `session.prompt(task)`, then return the last assistant text. Wire `signal` → `session.abort()`. Add `.pi/slate/` to `.gitignore`.

Acceptance: see Validation section, M0 script. The proof of the recursion guard is that the worker's answer to "list your available tools" names only ordinary coding tools and no `slate_proto`.

### Milestone 1 — Core: threads, episodes, and the real tools

Scope: replace the prototype with the real architecture. At the end of M1 the orchestrator (in a normal pi session, no special mode yet) can call `thread`, `threads`, and `episode` tools; threads persist across pi restarts; every action returns an episode; episodes are stored on disk and composable via the `context` parameter; failures produce FAILED episodes; parallel dispatch of different threads works while same-thread dispatches queue.

Work, by file (all under `.pi/extensions/slate/`):

- `state.ts` — types `ThreadRecord`, `EpisodeRecord`, `SlateState` (see Interfaces and Dependencies); `SlateStore` class holding in-memory state, writing snapshots via `pi.appendEntry("slate-state", snapshot)` on every mutation, and rebuilding from the last `slate-state` entry on the current branch during `session_start` (verify referenced session/episode files still exist; drop stale records with a UI notice).
- `worker.ts` — `createWorkerSession(opts)`: the M0 mechanics productized (no-extension loader, model resolution from a `"provider/id"` string via `ctx.modelRegistry.find`, tools allowlist default `["read","bash","edit","write","grep","find","ls"]`, session create/open). Worker system prompt addition (via loader `systemPromptOverride` or appended prompt — reuse whichever mechanism M0 proved): "You are a worker thread executing ONE bounded action for an orchestrator. Do the action fully, then stop. Your final message must state what you did, what you found, files you touched, and anything the orchestrator must know."
- `episodes.ts` — `compressEpisode({ messages, task, threadName, status, model, auth, signal })`: serialize the action's messages, call `complete()` with the compressor prompt (see Artifacts and Notes for the full prompt text), parse nothing (the output IS the episode markdown), prepend a metadata header (`episode id, thread, status, date, compressor model`), write to `.pi/slate/episodes/<episodeId>.md`, return the text. Model resolution per D5. On compressor failure, fall back to the raw final assistant message truncated to 2k tokens, marked `(uncompressed fallback)`.
- `threads.ts` — `ThreadManager` with `dispatch(opts, signal, onUpdate)`: resolve-or-create the thread record; enqueue on the thread's FIFO promise chain; respect a global semaphore (default 4 concurrent workers); open/reuse the worker session; if `opts.contextEpisodeIds` present, prefix the task with the referenced episodes' full text under a `## Context from prior episodes` heading; capture the message-list length before `prompt`, slice new messages after; on success or failure compress into an episode (status per D6); update records; return `{ episode, record, usage }`. Keep live `AgentSession` objects cached in a map for reuse within the pi process; `dispose()` all on `session_shutdown`.
- `tools.ts` — register the three tools with typebox schemas (see Interfaces and Dependencies), `promptSnippet`s, and `promptGuidelines` that name the tools explicitly. `thread` returns the episode markdown as its content text. `threads` returns a compact table (id, name, status, #episodes, last action, model). `episode` returns the stored file's full text.
- `index.ts` — factory wiring: instantiate store + manager, register tools, subscribe `session_start` (rebuild) and `session_shutdown` (dispose workers).

Acceptance: Validation section, M1 script — including the composition proof: thread B, given only `context: [<episode of A>]`, correctly uses a fact that thread A discovered and that appears nowhere else.

### Milestone 2 — Orchestrator mode (`/slate`)

Scope: the doctrine and the discipline. At the end of M2, `/slate` toggles orchestrator mode: tactical tools are removed from the orchestrator, the system prompt gains the thread-weaving doctrine, the footer/widget shows thread status, and the mode survives pi restarts (persisted in slate state).

Work: in a new `mode.ts`, register command `slate` (handler toggles `state.orchestratorMode`, saves/restores the previous active-tool list via `pi.getActiveTools()`/`pi.setActiveTools()`; when ON, active tools become `["read","grep","find","ls","thread","threads","episode"]`). Subscribe `before_agent_start`: when mode is ON, return `{ systemPrompt: event.systemPrompt + DOCTRINE }` (full doctrine text in Artifacts and Notes; core rules: strategize in your own words; delegate every tactical step as ONE bounded action per `thread` call; parallelize independent actions in a single turn; reuse a thread for follow-ups in the same work stream; pass episodes by id via `context` instead of restating; read-only tools are for cheap peeking only; adapt the plan after every episode). Maintain `ctx.ui.setWidget("slate", [...])` with per-thread status lines (name, state, episode count) refreshed on dispatch start/end, and `ctx.ui.setStatus("slate", "slate: on")`. Restore everything correctly on toggle-off and on `session_start`.

Acceptance: Validation section, M2 checks — with mode ON the model cannot call `bash` (tool absent), and a multi-part request visibly produces parallel `thread` calls in one assistant turn.

### Milestone 3 — Rendering and ergonomics

Scope: make thread dispatches readable in the TUI. At the end of M3, a `thread` call renders collapsed as one line (icon, thread name, task preview) plus streaming progress lines while running and an episode-section digest when done; expanded (Ctrl+O) shows the full episode markdown rendered via the `Markdown` component and usage stats (turns, tokens, cost, worker model) — following the visual conventions of the `subagent` example (`renderCall`/`renderResult`, `getMarkdownTheme`, `Container`/`Text`/`Spacer`).

Work: `render.ts` with `renderCall` and `renderResult` for the `thread` tool; track usage by accumulating `message_end` assistant usage from worker events into the dispatch result's `details`. Keep collapsed view ≤ ~12 lines.

Acceptance: visual inspection in an interactive session; collapsed and expanded views match the description above; a running dispatch shows live progress lines.

### Milestone 4 — Validation, dogfood, retrospective

Scope: prove the behavior end-to-end, then use it for real. Work: write `slate-dev/validate/scenario.sh` implementing the M1/M2 scripted scenario against a temp toy repo (see Validation); run it; fix what breaks; then run one real dogfood task in this repo with `/slate` ON (suggested: "investigate `issue-perf-eager-pricing-load.md` and produce a fix proposal" — investigation-only, no Java edits required); capture the transcript highlights into Artifacts and Notes; write the Outcomes & Retrospective entry comparing the result against the Purpose section and the Slate report's claims (parallel dispatch, episode composition, context economy of the orchestrator).

## Concrete Steps

All commands run from the repository root `/home/andrii0lomakin/Projects/ytdb/slate-pi` unless stated.

Setup (once, already partially done):

    mkdir -p .pi/extensions/slate .pi/slate/episodes
    printf '\n# slate runtime data\n.pi/slate/\n' >> .gitignore

M0 investigation (before writing code):

    PI_DIR=$(dirname $(readlink -f $(which pi)))/..   # or the known install path
    # Find how to construct a loader with no extensions:
    grep -rn "interface ResourceLoader" ~/.npm-global/lib/node_modules/@earendil-works/pi-coding-agent/ --include=*.ts -l
    # Find whether SessionManager supports explicit file paths:
    grep -rn "static create\|static open\|sessionFile" ~/.npm-global/lib/node_modules/@earendil-works/pi-coding-agent/ --include=*.d.ts | head

(If the package ships only compiled output, read the `.d.ts` files; they are sufficient to see the option surfaces.)

M0 run (DONE — actual commands used, all passing 2026-07-03):

    WS=/tmp/slate-m0-ws; rm -rf $WS; mkdir -p $WS; cd $WS
    EXT=/home/andrii0lomakin/Projects/ytdb/slate-pi/.pi/extensions/slate/index.ts
    # 1. execute + persist:
    pi -p --no-session -e $EXT "Call the slate_proto tool with task: 'Write the single word alpha into a file named check.txt in the current directory, then read the file back to confirm its content.' After the tool returns, report the tool result text verbatim."
    #    -> wrote alpha; result names worker sessionFile under $WS/.pi/slate/threads/
    # 2. resume across processes (memory retention, no tools allowed):
    SF=$(ls $WS/.pi/slate/threads/*.jsonl | head -1)
    pi -p --no-session -e $EXT "Call slate_proto with sessionFile='$SF' and task: 'Without using any tools: what word did you write earlier, and to which file? Answer in one short line.' Report the tool result text verbatim."
    #    -> "I wrote alpha to check.txt in /tmp/slate-m0-ws."
    # 3. recursion guard:
    pi -p --no-session -e $EXT "Call slate_proto with task: 'List the names of ALL tools you have available, one per line. Do not use any tools, just list them.' Report the tool result text verbatim."
    #    -> read bash edit write grep find ls — and NO slate tools

M1 run (after `/reload` or restarting pi):

    # single dispatch:      > create a thread named recon to find which file defines GlobalConfiguration and report its package
    # persistence:          quit pi, start pi, resume the session (pi -c), run `threads` via prompt; dispatch a follow-up to the same thread
    # composition:          > dispatch a NEW thread with context of recon's episode: "based only on your context, what package was reported?"
    # parallel:             > in ONE step create two threads: one counting .md files in repo root, one reporting the pom.xml artifactId; do both in parallel
    # failure:              > dispatch an action that cannot succeed ("run the command /nonexistent-binary-xyz and report output") and observe a STATUS: FAILED episode

M2 run (DONE — headless, scratch ws /tmp/slate-m2-ws, 2026-07-03):

    pi -p -e $EXT "/slate on" "First: list the exact names of the tools you currently have; do you have bash, edit, or write? Second: find the demo port number somewhere in this project AND the project codename — get both done and report them."
    #  -> tools: read, grep, find, ls, thread, threads, episode; "do NOT have bash, edit, write";
    #     both facts found via two parallel thread dispatches (episodes t1.e1 + t2.e1 on disk)
    pi -c -p -e $EXT "Is bash among your tools right now? yes/no only."   # -> No   (mode restored across restart)
    pi -c -p -e $EXT "/slate off" "Is bash among your tools right now? yes/no only."  # -> Yes
    # widget: TUI-only, verify interactively

M4 scripted scenario (write this as slate-dev/validate/scenario.sh):

    TMP=$(mktemp -d)
    # seed a toy repo: three small files with planted facts (e.g. a "secret" constant in one file)
    # run: pi -p --no-session -e "$PWD/.pi/extensions/slate/index.ts" -C "$TMP" "<weaving task prompt>"   # check pi --help for the cwd flag; else cd "$TMP"
    # assert: exit 0; output contains facts from BOTH planted files; .pi/slate/episodes in $TMP (or recorded episode paths) contain >= 2 episodes; one episode id appears as context input of a later dispatch
    echo PASS

Update this section with the exact final commands and observed transcripts as work proceeds.

## Validation and Acceptance

Acceptance is phrased as observable behavior:

- **M0:** In an interactive pi session, `slate_proto` (1) returns a worker-produced answer while progress lines stream; (2) a second call with `resume: true` proves the worker remembers the first action ("alpha" test above); (3) the worker's self-reported tool list contains no slate tools; (4) pressing Esc during a run aborts the worker within seconds; (5) a `.jsonl` session file exists for the worker and survives pi restart.
- **M1:** (1) A `thread` call returns an episode containing exactly the six contract sections; the episode file exists under `.pi/slate/episodes/`. (2) After quitting and resuming pi, `threads` lists the same thread and a follow-up dispatch to it demonstrates retained context. (3) The composition test succeeds using only an injected episode. (4) Two `thread` calls in one assistant turn run concurrently (overlapping progress updates). (5) The impossible action returns an episode whose header says `STATUS: FAILED` and whose findings describe the failure. (6) Two dispatches to the SAME thread in one turn execute one-after-another, both succeeding.
- **M2:** With `/slate` ON: `bash` is absent from active tools (asking the model to run a shell command yields delegation or refusal, not execution); the doctrine is in effect (a compound request produces parallel `thread` dispatches); the widget shows live thread status. Toggling OFF restores the previous toolset. Mode state survives restart.
- **M3:** Collapsed/expanded rendering as described in the milestone; no raw JSON dumps in the TUI.
- **M4:** `slate-dev/validate/scenario.sh` prints `PASS` and is idempotent (each run uses a fresh temp dir). The dogfood run produces a written fix proposal for the chosen ytdb issue, with the orchestrator's own context containing episodes rather than raw file dumps (verify via `/tree` or session file inspection: no `read` results of Java files in the orchestrator branch).

## Idempotence and Recovery

- All setup steps are additive and re-runnable (`mkdir -p`, guarded `.gitignore` append — check before appending twice).
- The extension is hot-reloadable: after any code change run `/reload` in the pi session; if the runtime state gets confused, quit pi and start fresh — the registry rebuilds from session entries, and thread session files reopen from disk.
- Stale state is self-healing: on `session_start` the store drops records whose backing files vanished (with a notification).
- Worker sessions are plain pi session `.jsonl` files; they can be inspected (`cat`) or deleted at any time. Deleting `.pi/slate/` resets all runtime state without affecting the extension.
- Nothing in this plan modifies the Java codebase; the dogfood task in M4 is investigation-only. If a milestone leaves the tree broken, `git checkout -- .pi/extensions/slate` restores the last committed state; commit after every milestone (commit messages: `slate: M<N> <summary>`).

## Artifacts and Notes

**Episode compressor prompt** (used in `episodes.ts`; keep in sync with this plan):

    You are compressing one completed action of a worker thread into an episode:
    a durable, structured record another agent will rely on WITHOUT seeing the
    raw transcript. Retain decisions, discoveries, exact identifiers (paths,
    symbols, commands, versions, error messages) and outcomes. Drop tactical
    noise (retries, scrolling, dead ends — unless a dead end is itself a finding).
    Target 300-800 words. Output ONLY markdown with EXACTLY these sections:

    ## Intent
    ## Actions Taken
    ## Key Findings
    ## Artifacts Changed
    ## Open Issues
    ## Handoff Notes

    The action's task was:
    <task>

    Transcript:
    <transcript>

**Orchestrator doctrine** (appended to the system prompt in `/slate` mode; refine during M2/M4 and record changes in the Decision Log):

    # Slate orchestrator mode
    You are the orchestrator of a thread-weaving system. You strategize; worker
    threads execute. Rules:
    1. Do tactical work ONLY by dispatching bounded actions via the `thread` tool
       (one action = one clear, completable task). You cannot edit files or run
       commands yourself.
    2. Dispatch independent actions in PARALLEL by emitting several `thread`
       calls in one turn. Never serialize what can run concurrently.
    3. Reuse a thread for follow-up actions in the same work stream — it
       remembers its prior episodes. Create a new thread for a new work stream.
    4. Compose context by reference: pass prior episode ids in `context` instead
       of restating their content.
    5. Your read-only tools (read/grep/find/ls) are for cheap orientation only;
       anything substantial goes to a thread.
    6. After every episode, update your strategy. Episodes marked STATUS: FAILED
       require adaptation, not blind retry.
    7. Keep your own messages strategic: goals, task routing, synthesis.

Transcripts, diffs, and evidence snippets are added here as milestones complete.

**M1 evidence (2026-07-03, scratch ws /tmp/slate-m1-ws, planted facts: `a.txt` "project codename: BLUEFIN, favorite color: teal"; `src/b.txt` "the demo port number is 9944"):**

    # A: single dispatch → episode t1.e1, STATUS OK, 6 sections, compressor anthropic/claude-sonnet-5,
    #    file .pi/slate/episodes/t1.e1.md, worker session .pi/slate/threads/*.jsonl
    # B+C: pi -c -p (new process) → threads tool listed t1; new thread 'synth' with context ['t1.e1']
    #    answered "codename BLUEFIN, color teal" using NO tools — registry restore + composition proven
    # D: new thread with model 'nope/nope' → "[episode t3.e1 | thread t3 \"broken\" | STATUS: FAILED]",
    #    failure line: Unknown model "nope/nope" — not found in the model registry
    # E: two new threads in ONE message → t4.e1 + t5.e1 both OK; worker session first-message
    #    timestamps identical (1783061838745) — concurrent execution
    # F: two actions to SAME thread t1 in one message → t1.e2 ("remember 41") then t1.e3 ("42") —
    #    FIFO order and retained thread context

## Interfaces and Dependencies

No new npm dependencies (D11). Imports available to extensions without installation: `@earendil-works/pi-coding-agent`, `@earendil-works/pi-ai` (+ `/compat`), `@earendil-works/pi-tui`, `typebox`, Node builtins.

File layout to exist at the end (all committed except `.pi/slate/`):

    .pi/extensions/slate/
      index.ts      # default factory: wiring only
      state.ts      # SlateStore, ThreadRecord, EpisodeRecord, persistence
      worker.ts     # createWorkerSession, no-extension resource loading
      threads.ts    # ThreadManager: dispatch queue, semaphore, lifecycle
      episodes.ts   # compressEpisode, model resolution (D5), episode files
      tools.ts      # thread/threads/episode tool registrations
      mode.ts       # /slate command, doctrine injection, widget
      render.ts     # renderCall/renderResult for the thread tool
    slate-dev/validate/scenario.sh

Core types (in `state.ts`):

    export interface ThreadRecord {
      id: string;              // short, e.g. "t1"
      name: string;
      sessionFile: string;     // absolute path to worker .jsonl
      status: "idle" | "running";
      model?: string;          // "provider/id" if overridden
      episodeIds: string[];
      createdAt: number;
      updatedAt: number;
    }

    export interface EpisodeRecord {
      id: string;              // e.g. "t1.e2"
      threadId: string;
      task: string;
      status: "ok" | "failed";
      file: string;            // absolute path to episode .md
      createdAt: number;
    }

`ThreadManager.dispatch` (in `threads.ts`):

    dispatch(opts: {
      threadId?: string;          // continue existing; omit → create
      name?: string;              // for new threads
      task: string;
      contextEpisodeIds?: string[];
      model?: string;             // "provider/id"
      tools?: string[];           // worker allowlist override
    }, signal: AbortSignal | undefined,
       onUpdate?: (progress: DispatchProgress) => void
    ): Promise<{ episodeText: string; episode: EpisodeRecord; thread: ThreadRecord; usage: UsageStats }>

Tool schemas (in `tools.ts`, typebox; `StringEnum` from `@earendil-works/pi-ai` for any enums):

    thread:   { thread?: string, name?: string, task: string,
                context?: string[], model?: string, tools?: string[] }
    threads:  { }
    episode:  { id: string }

---

Revision note (2026-07-03): Initial version of this ExecPlan, authored after Phase 0 research (`slate-dev/RESEARCH_LOG.md`, frozen) and the design discussion with the user. All D1–D11 decisions incorporated; milestones M0–M4 defined with behavior-phrased acceptance.
