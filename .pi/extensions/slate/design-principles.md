# Slate design principles

Distilled from the Random Labs technical report introducing the Slate
architecture ("thread weaving") — the open-beta release write-up of the
Slate agent (published as the npm package `@randomlabs/slate`); no stable
URL is recorded, so the distillation here is the in-repo source of truth
for its content. This document records the reasoning behind the
extension's design so maintainers — and the orchestrator itself, on
demand — can check changes and behavior against the original intent.

Note: module headers cite ExecPlan decision ids (D3–D9, M1–M3) from the
original implementation plan, which is not part of this repository. This
document is the in-repo source for the architecture rationale.

## 1. The problems Slate is built to solve

Three compounding problems in LLM agents — each tractable in isolation; the
difficulty is that they interact:

1. **Long-horizon tasks** — path-dependent tasks whose minimum successful
   step count exceeds what a minimal tool-calling loop can do. Solving them
   requires adequate working memory, a strategy/tactics balance, and the
   ability to integrate information discovered mid-task without losing the
   overall goal.
2. **Working memory and the "Dumb Zone"** — models cannot attend uniformly
   across the context window; retrieval quality degrades non-uniformly as
   context grows ("context rot"). The usable prefix is working memory; the
   degraded tail is the Dumb Zone. Context must be managed like scarce RAM.
3. **Strategy vs tactics** — strategy is open-ended planning toward the
   goal; tactics are learned local action sequences (run a command, extract
   X from file Y). The AlphaGo/AlphaZero lineage architected this split
   explicitly (value network = positional strategy, policy network = move
   tactics), and probing shows tactical concepts are learned before
   strategic ones. Software engineering spans the whole spectrum — a good
   harness lets the model strategize without drowning in tactical detail.

Supporting concepts:

- **Knowledge overhang** — knowledge a model holds latently but cannot
  access tactically without scaffolding (plans, chain-of-thought, harness
  structure). Corollary: the bottleneck in long-horizon agentic work is
  context management, not model intelligence.
- **Expressivity** — a harness is expressive when few output operations can
  reach many end states (a `sed`-only harness can read, write, and search;
  a `file_read`-only harness can never edit). Rigid task graphs lower
  expressivity.
- **Inductive bias** — models default to interfaces they were trained on.
  The harness builder's job is to make the desired behavior the natural
  behavior.

## 2. Why prior approaches fall short

No prior approach solves all of the above simultaneously; each trades one
or two problems for the others:

- **Compaction** (sliding windows, Claude Code compaction, Amp handoffs) —
  non-deterministically lossy: important information can vanish
  unpredictably.
- **Naive subagents** (Codex/Claude Code) — isolate context well, but
  synchronize by message passing: the parent only gets a response string,
  so information fails to cross the context boundary. Works mainly for
  exploratory search over immutable data.
- **Markdown plans** — force the model to strategize (tapping the knowledge
  overhang) but go stale. Three failure modes: underspecified plans,
  incomplete execution ("declaring victory early"), and forgetting to
  update the plan when new information arrives.
- **Direct task decomposition (task trees, gated steps)** — thorough and
  resistant to early stopping, but rigid: adapting to new information means
  rewriting the tree, and unintegrated subtask results get orphaned. Low
  expressivity.
- **RLM / recursive decomposition** — the right primitives (context by
  reference, natural decomposition through a familiar interface), but
  unbounded recursion needs a guard against over-decomposition, and REPL
  execution yields no intermediate feedback: the model commits to N steps
  blind and only learns the outcome at the end — no course correction in a
  mutating environment.
- **Strategize–delegate–compress stacks (Devin, Manus, Altera/PIANO)** —
  every compress-and-return boundary risks dropping critical state, and the
  strict planner/executor split adds inertia and reduces reactivity.
- **ReAct** — maximally reactive and expressive but has no context
  isolation, no compaction story, and no parallelism: the single context
  fills until quality degrades.

## 3. Slate's answer: threads, episodes, thread weaving

- **Thread** — a persistent worker that executes ONE bounded action at a
  time, then pauses and hands control back. Threads are general workers
  serving the system's current intent, not purpose-specific personas. A
  thread accumulates context across its actions, acting as a reusable store
  for one work stream.
- **Episode** — the compressed, structured record of the steps a thread
  took to complete one action: important results retained, tactical trace
  dropped. Episodes — not message passing — are the synchronization
  primitive. Because a bounded action has a natural completion boundary,
  compaction happens at meaningful moments instead of arbitrarily
  mid-stream. This is a tractable form of episodic memory.
- **Composability** — episodes are inputs: any thread can be initialized
  with prior episodes (from any thread), inheriting conclusions without
  inheriting full context. Context-by-reference routing is what
  distinguishes threads from subagents that return a single string.
- **Thread weaving** — the orchestrator dispatches, threads execute,
  episodes compose. Decomposition is implicit and adaptive: the
  orchestrator never commits to a static plan, but is forced to externalize
  work as bounded, compressible units. Frequent bounded synchronization
  gives per-episode feedback, so strategy updates mid-task instead of
  failing at the end.
- **OS framing** — the orchestrator is the kernel; threads are processes;
  episodes are process return values committed into the kernel's working
  memory; the context window is RAM — scarce and actively managed. Each
  thread return is a scheduled opportunity to decide what is retained,
  compressed, or discarded.

## 4. Operating principles

- **P1 — One dispatch, one bounded action.** An action is a tactic-sized
  unit: clear, completable, verifiable.
- **P2 — Episodes are the synchronization primitive.** No back-and-forth
  message passing between orchestrator and workers.
- **P3 — Compress at completion boundaries.** Compaction is built into the
  action lifecycle, not applied as emergency lossy surgery.
- **P4 — Compose context by reference.** Pass episode ids, not restated
  content; the episode store stays the source of truth.
- **P5 — Decompose implicitly and adaptively.** No upfront static plan;
  update strategy after every episode; failed episodes demand adaptation,
  not blind retry.
- **P6 — Make desired behavior the natural behavior.** In orchestrator
  mode, tactical tools are removed, so delegation is the only way to act.
- **P7 — Guard against over-decomposition.** Workers cannot spawn threads
  (depth-1); recursion is structurally impossible.
- **P8 — Per-episode feedback beats blind N-step execution.** Bounded
  actions return before the next decision, so course correction is always
  possible.
- **P9 — Parallelize independent actions.** Independent work streams run as
  concurrent threads whose episodes are synthesized afterward.
- **P10 — Treat context as RAM.** Budget it; when the budget is exceeded,
  pause dispatching and hand off to a fresh session rather than degrading
  in the Dumb Zone.

## 5. Where each principle lives in the code

| Principle | Implementation |
|---|---|
| P1 bounded actions | `tools.ts` `thread` tool contract; doctrine rule 1 in `mode.ts` |
| P2 episodes as sync | `threads.ts` dispatch lifecycle returns an episode per action |
| P3 boundary compression | `episodes.ts` episode compression on action completion |
| P4 context by reference | `tools.ts` `context` parameter injects prior episodes by id |
| P5 adaptive decomposition | doctrine rule 6 in `mode.ts`; no plan structure imposed anywhere |
| P6 natural behavior | `mode.ts` `ORCHESTRATOR_TOOLS` restriction (read-only + slate tools) |
| P7 over-decomposition guard | `worker.ts` recursion guard — workers load no extensions, so the `thread` tool never exists for them |
| P8 per-episode feedback | `threads.ts` synchronous dispatch; episode returned to orchestrator |
| P9 parallelism | `threads.ts` `maxConcurrent` queueing; doctrine rule 2 in `mode.ts` |
| P10 context as RAM | `handoff.ts` context-budget auto-pause + fresh-session handoff |

## 6. Runtime knowledge: what the orchestrator knows, and when

The orchestrator's knowledge of these principles is two-tier, following the
project's load-on-demand guidance (AGENTS.md):

- **Tier 1 — always loaded.** The doctrine in `mode.ts` is the operational
  distillation of P1–P10, the review discipline defined in
  `review-rules.md`, and a pointer to the repository's track-based
  workflow gates (`docs/dev-workflow/track-development.md`), appended to
  the system prompt every turn while orchestrator mode is on. It costs a
  few hundred tokens and covers everything routine dispatching needs.
- **Tier 2 — on demand.** This document. The doctrine carries a short
  pointer to it (doctrine rule 10); the orchestrator reads it only when
  reasoning about the architecture itself — explaining slate, modifying
  the extension, or making a non-obvious routing/compaction decision.

The same discipline applies to role guidance extracted from AGENTS.md:
`prompt-docs.ts` injects per-role guideline docs (orchestrator via
`before_agent_start`, workers via `appendSystemPrompt`). Defaults are
compiled into `prompt-docs.ts`; the optional `.pi/slate.json` keys
`orchestratorPromptDocs` / `workerPromptDocs` override them. Each role's
always-loaded surface carries only its own rules; the rest stays on demand.

Injecting this full document every turn would be self-defeating: it would
spend the working memory the architecture exists to protect, and most of
its content (prior-approach analysis, background concepts) is rationale,
not operational instruction. Keeping rationale on demand and rules always
loaded is itself an application of P10.
