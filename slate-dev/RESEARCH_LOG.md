# Research Log — Slate Architecture on pi ("slate-pi")

> Anchor (initial user request) plus continuous-log capture of Phase 0
> (research) decisions, discoveries, and open questions. Entries are
> durable across `/clear`, `/compact`, and Phase 0 → Phase 1 handoff.
> Phase 1 (planning) reads this file as the primary input to Decision
> Records and Architecture Notes.
>
> **Freeze rule:** this file is frozen once implementation starts. All
> subsequent changes are logged in the ExecPlan
> (`slate-dev/EXECPLAN.md`, created in Phase 1). The ExecPlan must
> reference this log so it is consulted between sessions.
>
> **Durable reference artifacts (read these, do not re-fetch):**
> - `slate-dev/reference/slate-blog.md` — verbatim archive of the Slate
>   technical report (https://randomlabs.ai/blog/slate, behind a JS wall).
> - `slate-dev/reference/codex-exec-plans.md` — verbatim archive of the
>   OpenAI cookbook ExecPlan / PLANS.md methodology article.
>
> **STATUS: FROZEN as of 2026-07-03T08:30+02:00.** Design discussion is
> complete; all open questions below are resolved in the Decision Log.
> Implementation is governed by `slate-dev/EXECPLAN.md`. Do not edit this
> file further.

## Initial request

**User's words:** "Please read
https://github.com/openai/openai-cookbook/blob/main/articles/codex_exec_plans.md
and use it to track implemention of Slate architecture from
https://randomlabs.ai/blog/slate . Before you start implementation let
us create research log of the following format: [format of this file]
and discus design of implementation and only after that please create
ExecPlan and follow it, provide reference to research log so it will
be always conssulted between sessions. Research log stays frozen after
the start of the implmeentation and all changes are logged in ExecPlan.
If you need to install additional packages before start of the
implementation and during impelementation to efficiently use context
please let me know so we will do that and continue in next session. As
it will be likely under JavaScrip wall I will past blog article here:
[full Slate blog article — archived verbatim at
`slate-dev/reference/slate-blog.md`]"

## Decision Log

- 2026-07-03T08:05+02:00 [ctx≈40%] Research artifacts live in `slate-dev/` at the repo root of the `slate-pi` worktree.
  - **Why:** The worktree is dedicated to slate-pi experimentation; a single top-level folder keeps research log, ExecPlan, and archived references together and easy to re-find after `/clear`.
  - **Alternatives rejected:** `.pi/` (that is project config for pi itself, not dev docs); `docs/` (holds YouTrackDB database documentation; mixing would confuse the ytdb docs index).

- 2026-07-03T08:05+02:00 [ctx≈40%] Target platform: implement Slate as a **pi extension** (TypeScript), not a standalone harness.
  - **Why:** pi is the harness in daily use here; its extension API provides custom tools, custom rendering, session persistence, and event hooks, and its SDK (`createAgentSession`) provides in-process worker agent sessions — everything Slate's orchestrator/thread/episode model needs, with zero infrastructure to build from scratch.
  - **Alternatives rejected:** standalone CLI harness built on pi SDK only (loses the interactive TUI, session tree, compaction, and existing model config for free); fork of `@randomlabs/slate` npm package (closed/unknown internals, not an implementation exercise).

- 2026-07-03T08:10+02:00 [ctx≈45%] Archive both source articles verbatim under `slate-dev/reference/` before design work.
  - **Why:** The blog is behind a JavaScript wall and was pasted by the user; the cookbook article is remote. Durable local copies are required for cross-session self-containment (ExecPlan rule: no external docs).
  - **Alternatives rejected:** linking URLs only (violates ExecPlan self-containment; blog not fetchable by the agent).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) Implementation lives in `.pi/extensions/slate/` in this worktree, committed to git; runtime data (thread sessions, episodes) under `.pi/slate/`, gitignored.
  - **Why:** Hot-reloadable via `/reload`, immediately dogfoodable on ytdb tasks; user accepted committing slate code into this ytdb worktree.
  - **Alternatives rejected:** global `~/.pi/agent/extensions/` (harder to version and review); separate repo (premature for v1).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) Thread substrate: in-process pi SDK `AgentSession` per thread, backed by a persistent `.jsonl` session file; ExecPlan starts with a prototype milestone to de-risk (including preventing recursive loading of the slate extension inside worker sessions).
  - **Why:** Persistent per-thread context across actions, streaming progress into the orchestrator TUI, abort propagation, per-thread model choice — all natively supported by the SDK.
  - **Alternatives rejected:** `pi` subprocess per action (fire-and-forget, no cheap persistent context; heavier per-dispatch cost).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) Orchestrator interface: a small tool family — `thread` (dispatch one action), `threads` (list), `episode` (fetch by id) — not a DSL in v1.
  - **Why:** Tool-calling is maximally in-distribution for current models; pi runs parallel tool calls natively, giving parallel thread weaving for free. A DSL can be layered on later without changing the thread/episode core.
  - **Alternatives rejected:** DSL tool (blog's choice; more expressive but out-of-distribution and higher risk for v1).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) Orchestrator mode is an explicit `/slate` toggle: strips tactical tools (`bash`, `edit`, `write`) from the main thread, keeps read-only tools (`read`, `grep`, `find`, `ls`) plus slate tools, and injects thread-weaving doctrine into the system prompt. Off by default.
  - **Why:** Makes delegation "the natural behavior" (blog's inductive-bias argument) without breaking normal pi usage; read-only peeking shouldn't cost a thread dispatch.
  - **Alternatives rejected:** always-on (too invasive); prompt-doctrine-only without tool restriction (model would bypass delegation).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user decision) Episode compression model defaults to **Sonnet**, not Haiku; configurable. Resolution order at runtime: (1) `episodeModel` from slate config if set, (2) newest available Anthropic Sonnet from the model registry, (3) the worker thread's own model as fallback.
  - **Why:** User prefers a stronger compressor — episode quality is the synchronization mechanism of the whole architecture; a weak compressor silently loses state at every boundary.
  - **Alternatives rejected:** Haiku/Flash default (cheaper but riskier information loss).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) Failure semantics: failed/aborted worker actions still produce an episode, marked `STATUS: FAILED`, with diagnostics — returned to the orchestrator like any episode.
  - **Why:** The orchestrator must be able to adapt to failures (blog's synchronization argument); a bare error string would reintroduce message-passing lossiness.
  - **Alternatives rejected:** raising a plain tool error (loses the partial work trace).

- 2026-07-03T08:25+02:00 [ctx≈55%] (user-approved) v1 scope: **depth-1 threads only** (workers do not get slate tools — the overdecomposition guard) and **synchronous dispatch only** (no async/background threads). Go deeper only if v1 proves insufficient.
  - **Why:** Matches RLM's official depth=1 finding and the blog's warning about overdecomposition; async reconciliation is a known failure mode of prior architectures.
  - **Alternatives rejected:** bounded depth=N (guard complexity without demonstrated need); background threads (reconciliation problem).

- 2026-07-03T08:25+02:00 [ctx≈55%] Episode content contract: fixed sections *Intent / Actions Taken / Key Findings / Artifacts Changed / Open Issues / Handoff Notes*, target budget ~1–2k tokens, **full episode text returned into the orchestrator context** (plus stored at `.pi/slate/episodes/<id>.md` for by-reference composition via `episode` tool and `context:` dispatch parameter).
  - **Why:** The episode IS the synchronization mechanism; digest-only return would reintroduce lossy message passing.
  - **Alternatives rejected:** digest + fetch-by-reference-only (orchestrator would routinely act on too little state).

- 2026-07-03T08:25+02:00 [ctx≈55%] Thread registry is session-scoped, persisted via `pi.appendEntry` snapshots and rebuilt on `session_start`; per-thread dispatches are serialized with a FIFO queue (parallelism happens *across* threads, not within one).
  - **Why:** Keeps Slate state consistent with pi's session tree (fork/branch/resume); a thread is by definition a serial work stream.
  - **Alternatives rejected:** global registry file (diverges from session branching); rejecting concurrent dispatches to one thread (needlessly hostile to the model).

- 2026-07-03T08:25+02:00 [ctx≈55%] Validation: scripted acceptance via pi print mode driving a toy repo through a multi-thread task with episode composition, plus dogfooding on a real ytdb task (candidates: repo-root `issue-perf-*.md`).
  - **Why:** ExecPlan requires observable, behavior-phrased acceptance; dogfooding tests the routing behavior the blog says is the remarkable part.
  - **Alternatives rejected:** unit tests only (would not demonstrate working weaving behavior).

## Surprises & Discoveries

- 2026-07-03T07:55+02:00 [ctx≈15%] The cookbook ExecPlan article is fetchable directly via raw.githubusercontent.com (no JS wall).
  - **Source:** `curl https://raw.githubusercontent.com/openai/openai-cookbook/main/articles/codex_exec_plans.md`
  - **Implication:** ExecPlan methodology captured in full: ExecPlans must be self-contained living documents with mandatory `Progress`, `Surprises & Discoveries`, `Decision Log`, `Outcomes & Retrospective` sections, milestone-based narrative, novice-executable steps, and observable acceptance criteria. Our Phase 1 output must follow it to the letter.

- 2026-07-03T08:00+02:00 [ctx≈25%] pi ships an official `subagent` extension example that spawns `pi --mode json -p --no-session` subprocesses with single/parallel/chain modes, streaming updates, and custom TUI rendering.
  - **Source:** `~/.npm-global/lib/node_modules/@earendil-works/pi-coding-agent/examples/extensions/subagent/` (index.ts, agents.ts, README.md)
  - **Implication:** Strong starting skeleton for tool schema, JSON-event stream parsing, usage tracking, abort propagation, and renderCall/renderResult patterns. But it is fire-and-forget (`--no-session`): no persistent worker context, no episode compression, no context composition — exactly the "naive subagent" Slate distinguishes itself from. Slate threads need *persistent, resumable* worker context.

- 2026-07-03T08:02+02:00 [ctx≈35%] The pi SDK (`createAgentSession`) supports per-session `SessionManager` files, custom tools/model/system prompt per session, `steer()`/`followUp()`, event subscription, and `SessionManager.open(file)` to resume.
  - **Source:** `docs/sdk.md` (pi package docs, read in full)
  - **Implication:** A Slate **thread** can be an in-process `AgentSession` backed by its own `.jsonl` session file: it accumulates context across dispatched actions (persistent reusable store per work stream) and survives pi restarts. This makes the subprocess approach unnecessary for v1.

- 2026-07-03T08:03+02:00 [ctx≈35%] Extensions can make direct LLM calls: `complete()` from `@earendil-works/pi-ai/compat` + `ctx.modelRegistry.getApiKeyAndHeaders(model)` + `serializeConversation(convertToLlm(messages))` to render a transcript for summarization.
  - **Source:** `examples/extensions/custom-compaction.ts` and `examples/extensions/summarize.ts`
  - **Implication:** **Episode compression** is feasible as a single cheap LLM call over the messages produced by one thread action — no extra worker process, and the compression model is independently configurable (e.g. Haiku/Flash for compression while workers run Sonnet).

- 2026-07-03T08:03+02:00 [ctx≈35%] pi executes tool calls from one assistant message **in parallel by default** (preflight sequential, execution concurrent), and tools stream progress via `onUpdate`.
  - **Source:** `docs/extensions.md` (tool events, parallel tool mode; read in full)
  - **Implication:** "Massively parallel thread dispatch" from the blog falls out naturally: the orchestrator model emits several `thread` tool calls in one turn and pi runs them concurrently. No custom scheduler needed for v1; only a concurrency cap.

- 2026-07-03T08:04+02:00 [ctx≈38%] Extension placement & loading rules: auto-discovery from `.pi/extensions/*.ts|*/index.ts` (project-local, requires project trust) and `~/.pi/agent/extensions/` (global); hot-reload via `/reload`; `typebox`, `@earendil-works/pi-ai`, `@earendil-works/pi-tui`, and node builtins are importable without any npm install.
  - **Source:** `docs/extensions.md` — Extension Locations / Available Imports
  - **Implication:** **No additional npm packages are needed** for the planned implementation (user asked to be told about package needs). A `package.json` next to the extension is only needed if we later add third-party deps.

- 2026-07-03T08:04+02:00 [ctx≈38%] Extension state persistence patterns exist: `pi.appendEntry(customType, data)` for non-context session state, reconstruction on `session_start` by scanning `ctx.sessionManager.getBranch()`, and tool-result `details` for branch-aware state.
  - **Source:** `docs/extensions.md` — State Management, `examples/extensions/todo.ts`
  - **Implication:** Thread registry (thread id → session file, status, episode list) can be persisted in the orchestrator's session and reconstructed after restart/fork/branch, keeping Slate state consistent with pi's session tree.

- 2026-07-03T08:04+02:00 [ctx≈38%] Orchestrator-side prompt shaping is available: `promptSnippet`/`promptGuidelines` on registered tools, `before_agent_start` can rewrite the system prompt per turn, and `pi.setActiveTools()` can restrict the toolset at runtime.
  - **Source:** `docs/extensions.md`; `examples/extensions/plan-mode/`
  - **Implication:** An explicit "orchestrator mode" is implementable: restrict the main thread to Slate tools (+ read-only tools), and inject Slate usage doctrine into the system prompt — making delegation "the natural behavior" per the blog's inductive-bias argument.

- 2026-07-03T08:04+02:00 [ctx≈38%] Environment facts: pi 0.80.3, Node v22.22.2, no `~/.pi/agent/extensions/` yet; this repo is a git worktree of ytdb (`develop`) named `slate-pi`; `.pi/settings.json` sets defaultModel `claude-opus-4-8`, thinking `xhigh`.
  - **Source:** shell inspection of the working tree and pi installation
  - **Implication:** Fresh slate for extension placement. The ytdb Java codebase itself is not touched by this work; it serves as the dogfooding ground. Repo-root `issue-perf-*.md` files are candidate real-world validation tasks for Slate.

## Open Questions

> All questions below were resolved on 2026-07-03 in the design discussion;
> see the Decision Log entries timestamped 08:25+02:00.

- 2026-07-03T08:06+02:00 [ctx≈40%] Where should the implementation live: project-local `.pi/extensions/slate/` (this worktree), global `~/.pi/agent/extensions/slate/`, or a dedicated repo/package?
  - **Blocking:** ExecPlan file layout, install/validation instructions, git hygiene (this worktree is a ytdb fork — do we want slate commits here?).
  - **Resolved:** project-local `.pi/extensions/slate/`, committed; `.pi/slate/` runtime data gitignored.

- 2026-07-03T08:06+02:00 [ctx≈40%] Worker thread substrate: in-process SDK `AgentSession` per thread (preferred by research) vs `pi` subprocess per action with `--session` resume. Sub-question: interplay of in-process worker sessions with the interactive TUI (rendering, abort, cost accounting).
  - **Blocking:** Core architecture milestone; risk item that likely needs a prototyping milestone in the ExecPlan.
  - **Resolved:** in-process SDK sessions; prototype milestone M0 in ExecPlan.

- 2026-07-03T08:06+02:00 [ctx≈40%] Orchestrator interface shape: small tool family (`thread_run`, `thread_list`, `episode_get`, …) vs a single DSL tool (blog chose a DSL for programmability). Tools are more in-distribution for current pi models; DSL is more expressive.
  - **Blocking:** Tool schema Decision Record; system prompt doctrine.
  - **Resolved:** tool family (`thread`/`threads`/`episode`); no DSL in v1.

- 2026-07-03T08:06+02:00 [ctx≈40%] Should orchestrator mode remove tactical tools (bash/edit/write) from the main thread to force delegation, or keep them and rely on prompt doctrine? Blog: harness should make desired behavior the natural behavior; but removing tools cuts expressivity for trivial tasks.
  - **Blocking:** Mode design; `pi.setActiveTools()` usage; escape hatch definition.
  - **Resolved:** explicit `/slate` toggle; strips bash/edit/write, keeps read-only + slate tools.

- 2026-07-03T08:07+02:00 [ctx≈40%] Episode content contract: what sections must an episode contain (intent, actions, findings, artifacts touched, open issues, handoff notes)? How large may it be (token budget)? Full episode into orchestrator context vs short digest + `episode_get` by reference?
  - **Blocking:** Episode compressor prompt; context routing semantics ("threads by reference").
  - **Resolved:** 6 fixed sections, ~1–2k tokens, full text returned to orchestrator; Sonnet-default compressor (user decision).

- 2026-07-03T08:07+02:00 [ctx≈40%] Episode/thread persistence layout: episodes as files (`.pi/slate/episodes/<id>.md`) + thread sessions (`.pi/slate/threads/<id>.jsonl`) + registry via `pi.appendEntry` — or everything inside session entries only?
  - **Blocking:** Persistence design; branching/fork semantics of the orchestrator session.
  - **Resolved:** episodes as files under `.pi/slate/episodes/`, thread sessions as `.jsonl` files, session-scoped registry via `pi.appendEntry`.

- 2026-07-03T08:07+02:00 [ctx≈40%] Overdecomposition guard: threads must not recursively spawn threads (depth=1 like RLM's official impl?), or bounded depth=N with a guard? Blog warns unbounded decomposition needs a harness-level guard.
  - **Blocking:** Worker toolset definition (do workers get the `thread` tool?).
  - **Resolved:** depth-1; workers never get slate tools (user-approved).

- 2026-07-03T08:07+02:00 [ctx≈40%] Cross-model composition (e.g., Opus orchestrator + Sonnet/Haiku workers; blog: Sonnet + Codex): in scope for v1 as a per-dispatch `model` parameter?
  - **Blocking:** Tool schema; model registry / auth handling per worker.
  - **Resolved:** in scope — per-dispatch optional `model` parameter.

- 2026-07-03T08:08+02:00 [ctx≈40%] Validation strategy: scripted acceptance harness (pi in print/JSON mode driving a toy repo) + dogfooding on real ytdb tasks (e.g. repo-root `issue-perf-*.md`)? What is the observable "it works" demo for the ExecPlan?
  - **Blocking:** ExecPlan Validation and Acceptance section; milestone acceptance criteria.
  - **Resolved:** scripted print-mode acceptance on toy repo + ytdb dogfood task.

- 2026-07-03T08:08+02:00 [ctx≈40%] Failure semantics: worker action fails/aborts/overflows its own context — what does the orchestrator receive (failure episode?), and can a thread's own context be compacted between actions (episode-based self-compaction)?
  - **Blocking:** Thread lifecycle state machine; error handling design.
  - **Resolved:** FAILED episodes with diagnostics (user-approved); per-thread self-compaction deferred (pi auto-compaction covers workers in v1).
