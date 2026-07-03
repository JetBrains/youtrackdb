# Slate: Thread Weaving and Episodes — Random Labs technical report

> Archived verbatim (text only, figures described inline) from
> https://randomlabs.ai/blog/slate on 2026-07-03. The original page is behind
> a JavaScript wall; this copy is the durable source for the slate-pi project.

## Introduction

In this technical report, we introduce a new agent architecture pattern, and demonstrate how single-threaded agents can generalize beyond ReAct and RLM.

Our goal at Random Labs is to build generalized, non-benchmaxxed, end-to-end agents for software engineering. The contents of this report bring us one step closer to this goal.

We begin by examining a series of problems faced by modern LLM based agents: long horizon tasks, strategy vs tactics, and working context management. We explore existing solutions to these problems and their limitations. After enumerating the problems faced by modern agents, we describe Slate's architecture: a thread-based episodic memory system that solves all of them simultaneously.

## Background

Building agents that generalize requires solving three compounding problems: long-horizon task execution, the balance between strategic and tactical reasoning, and working memory management. Each of these is tractable in isolation — the difficulty is that they interact.

### Understanding long horizon tasks

Long-horizon tasks are path-dependent (tasks where the required actions depend on each other), and the minimum number of steps to succeed exceeds what a minimal harness can do. A minimal harness is defined here as a limited tool-calling loop around a model with no additional planning or memory infrastructure. Terminus and Simple Codex both fall into this category while most agents you actually use to get work done do not.

In order to solve this type of task, the agent requires three things: adequate working memory so the model can attend to the right context at the right time, a balance of strategic and tactical execution so the model both plans well and executes correctly, and the ability to integrate new information discovered throughout the task without losing track of the overall goal.

### Working memory

Models cannot attend uniformly across their context window. The model's ability to attend to the information degrades as the context length grows. The usable portion, up to but not including the degraded region, is working memory. Dex Horthy coined the term "Dumb Zone" for the part of the context window where retrieval quality drops. Working memory is effectively the context window up to that point.

[Figure: "Context Window & Dumb Zone" — context windows are not uniformly usable. The right edge — the Dumb Zone — degrades in attention quality as the window fills up.]

[Figure: "Performance Degradation vs. Input Length" — Context Rot: model performance vs input length across Claude Sonnet 4, GPT-4.1, Qwen3-32B, and Gemini 2.5 Flash — performance degrades non-uniformly as context grows. All four frontier models degrade non-uniformly as input length grows, even on simple tasks. Via Hong, Troynikov & Huber — Context Rot (Chroma, 2025).]

### Strategy and Tactics

Strategy is open-ended planning based on knowledge that guides the system towards the goal, and tactics are learned, local action sequences which help materially take steps towards the goal. It's serendipitous that this distinction maps directly onto how RL has historically solved games like go and chess. In chess, traditional engines like Stockfish brute force through a move tree (a sort of tactic based search algorithm). In contrast, self-play RL has yielded systems that learn which positions matter strategically.

"In several games AlphaZero sacrificed pieces for long-term strategic advantage, suggesting that it has a more fluid, context-dependent positional evaluation than the rule-based evaluations used by previous chess programs."

[Figure: "Brute Force vs. Value / Policy Execution" — Brute force evaluates every node at every depth (Stockfish). Value/policy guided search (AlphaZero) uses the value network to judge positions strategically and the policy network to select moves tactically — exploring far fewer nodes to reach stronger play.]

Go makes the separation even more explicit: strategy covers influence, territory balance, and whole-board thinking, while tactics cover reading (calculating specific local sequences) and life-and-death problems. When DeepMind built AlphaGo, this split was literally architected in: the value network handles positional judgment (strategy) while the policy network handles move selection (tactics). Research probing AlphaZero's internal representations during training found that tactical concepts — material value — are learned first, followed by strategic concepts like king safety and mobility. They emerge separately, at different training stages, in different layers of the network.

[Figure: "AlphaZero: Concept Emergence by Training Stage" — training steps 0 → 16k–32k: material value & space (tactical); 32k–64k: king safety, threats, mobility (strategic/positional); 128k+: sophisticated trade-offs (strategic/long-horizon). Concepts become linearly decodable from network activations around layer d=10 of 20, then plateau.]

"...piece value is a keystone concept, developed first. Subsequently, issues around mobility (king safety, attack, and defense) arise. Finally, there is a refinement stage, in which the network learns to make sophisticated trade-offs.." (McGrath et al., PNAS 2022)

The paper's qualitative evaluation by former world champion Vladimir Kramnik confirms the order directly. At 16k steps AlphaZero loses on material; by 32k it has a solid grasp of piece value. The 32k-to-64k leap is dominated by king safety in imbalanced positions. Beyond 128k the gains are in knowing which attacks will succeed — accepting material sacrifices and converting them — rather than in positional or endgame play. Tactical knowledge first, strategic judgment second.

### Software Engineering as an open-ended long horizon game

We see software engineering as a more open ended and infinite game where both strategy and tactics are relevant depending on the task they are applied to. For example, remembering and executing a bash command is a simple tactic. Designing a schema so that it is backwards compatible as it changes is more strategic.

[Figure: "Strategy vs. Tactics Spectrum" — Tactical → Strategic: run a bash cmd → write a test suite → plan a refactor → design a schema. Tactics are immediate and executable; strategy requires reasoning about future states and tradeoffs.]

This can be seen in the fact that if a model is asked to write a plan or think through a task step by step, it's actually first being given a knowledge retrieval task, and then an agentic execution task. The initial planning/thinking can be viewed as the model retrieving knowledge and strategizing about a path to the solution, and then using tactics to execute the plan.

A brief aside — most rules in AGENTS.md files are actually tactical ex. "Never run db commands".

## Prior Approaches

No existing approach solves all of the above problems simultaneously. Each approach accepts a tradeoff of solving one or two at the expense of the others.

### Solving working memory and defeating the Dumb Zone

The first solution that comes to mind is to compress the context for the model! Surely we can reliably drop irrelevant context periodically and solve our problem. This strategy is known as compaction.

#### Compaction

Compaction is one naive solution to working memory in isolation.

Compaction is largely unsolved. Most "compaction" is actually lossy compression (despite having gotten better).

In early 2025 (around may) we built (one of) the first instance(s) of a sliding window based agent that could run for incredibly long session lengths (up to 2 days reported by our users). This agent is deprecated, but is available as an npm package: `npm i -g @randomlabs/slatecli`

There are a few instances of working but lossy compression in the wild:

- Compaction in claude code (notoriously bad)
- The now infamous Ralph Wiggum loop by Geoffrey Huntley
- Amp handoffs (a crowd favorite, but requiring guidance from the user)

Amp probably has the most interesting implementation here since a handoff is designed to bootstrap a new fresh agent session.

The main issue with compaction is that it is not deterministically lossy, which means we can unpredictably lose important information.

#### Subagents

To avoid the lossiness of compaction, we can instead try to isolate the unimportant context. This is where subagents come in.

Subagents are a second naive solution to working memory in isolation. Subagents work relatively well. They isolate context. This isolation means that the naive implementation fails to transfer information across context boundaries since all it returns is a response message (see codex/claude-code subagents).

### Markdown Planning

To make sure we maintain coherence across different parts of a task, compactions, and isolated subagent contexts, we can plan upfront.

Markdown plans are also one method of balancing strategy and tactics. By asking a model to plan the task out, it forces the model to use its knowledge to strategize about the task, which broadly provides a much much better outcome than directly exploiting its own learned behaviors. Giving the model the tactic to track the task progress in the doc allows the model to repeatedly refresh its understanding of its strategy throughout the task and stay aligned.

As models improve and are trained on this style of strategizing through markdown plans, the tasks the model can complete with just a simple markdown file will necessarily increase in scope. However, there will likely always be a difference between planning v.s. directly exploiting the learned behaviors.

We can describe this as knowledge overhang. The knowledge that a given model has access to theoretically, but can't access tactically without a trick like "think step by step" or by planning in files.

[Figure: "Knowledge Overhang as Rollout Sampling" — the model's latent knowledge covers a wide range of trajectories through the task space, but direct tactical sampling only accesses a narrow band. Planning, chain-of-thought, and scaffolding expand the sampled region toward the edge of model knowledge.]

Necessarily, there is a limit to how far this form of planning can go. The limit simply increases as the models get better.

Three key failure modes:

1. The model isn't thorough enough when writing the plan (doesn't specify the plan enough)
2. The model isn't thorough enough while executing the plan (the model loses the plot and misses pieces of the plan)
3. The model forgets it has free will and its learned tactics don't allow it to adapt to new information (it forgets to update the plan in the right direction)

We've probably all seen underspecified plans, which we then ask the model to flesh out further. We've also all seen models incompletely execute a plan or declare victory early on a plan before it's been completed. Additionally in this case, the model has to remember to update the plan when it encounters new information, which isn't ever a guarantee.

All three failure modes have improved as RL for this form of planning has improved (you can just look at how you've used these tools over the past year), however, these failure modes require direct RL to counteract them. As the spend for RL post training increases, the rate of failure for these will decrease for any fixed task complexity but are necessarily non-intuitive for the models (tautologically as evidenced by the need to train for the behavior).

### Direct Task Decomposition

Markdown plans go stale though, so the next move is to make execution structure mandatory and update it as we go. Frequently this gets implemented as a task tree where the model must work through each node before continuing. This solves the early-stopping problem and can leverage subagent context isolation for thoroughness. (see ADaPT)

In this system, the model will take a main task, spawn subtasks, hopefully execute the subtasks, and then return to complete the main task.

[Figure: "Direct Task Decomposition Tree" — main task → subtask A/B/C → leaves; each node is either executed directly or split into subtasks. Thorough but rigid — adapting to new information requires rewriting the tree.]

For additional thoroughness, you can introduce a gating mechanism for the task that requires it to walk through N different steps in order to tag the task as completed.

Two main failure modes:

1. The system has a hard time adapting to new information due to linear task dependence
2. The system fails to completely decompose a main task leaving subtasks and their results unintegrated

Verifying this is left as an exercise for the reader (just try it on the gpt2-codegolf task), but rigidly walking an agent through a task tree where each task has verification steps and is gated behind a sequence of actions helps to keep an agent on track but does not leave room for the agent to flexibly execute the task.

The main premise with using a tree of gated tasks like this is that you avoid the early stopping failure mode models are prone to, but you end up trading the flexibility of natural language and implicit planning for rigidity in the process.

Intuitively, the dependence on the structured task data is the main culprit here. It's also the driver of the thoroughness.

The rigidity makes the system overall less able to express varied behavior and flexibly handle tasks. We can say the system has low expressivity.

### Expressivity and Inductive Bias

An agent harness has high expressivity when it enables many possible end states with relatively few output operations.

To illustrate the expressivity of different tools, consider for example two harnesses. Harness A one has a file_read tool and Harness B can only use the sed command. No matter how hard harness A tries, and regardless of the model provided, harness A can never express the action of editing a file. On the other hand, although arguably less token efficient, Harness B is fully capable of reading, writing, searching text, etc. This is a result of the sed tool's expressivity. As in, you can express a wider variety of operations through a marginally more complex interface.

[Figure: "Harness Expressivity: Reachable Behavior Space" — Harness A (file_read only): can read files; cannot write, search, or edit. Harness B (sed): can read, write, search, edit in place. Expressivity is about reachable behavior space. A more expressive interface unlocks more possible end states from the same model.]

The expressivity of a system is important, but so is the ability of the model to use it. The model's ability to use the provided harness is directly dependent on how in-distribution the interface for using that harness is.

Take, for example, two highly expressive systems: Bash vs. a Python REPL.

The training data for what these things are used for is somewhat different. A harness with a python REPL will be able to do a lot of the same work as a harness with a Bash shell env. However, how quickly the model completes the desired task will be dependent on the prevalence of the required operations in the training data. For example a task where the agent has to solve issues in a package with c bindings on an ubuntu vm and use the patched package might be more challenging from within a python REPL harness than it would be from within a Bash harness.

There exists a bias in how the model understands how to use these different harnesses despite both of them being theoretically equally expressive.

The inductive biases of a model, the expressivity of the system, and the sampling method chosen lead to the specific behaviors we observe. As a harness builder, the goal is to make desired behavior the natural behavior.

Note: Inductive bias here means the default behaviors a model has been trained to prefer from raw pretraining to rubric post training.

As a harness builder, your job is to design a harness where the system naturally expresses the desired behaviors. The ability for the system to express the behaviors is dependent on how expressive the harness is and what the model's inductive biases look like.

Now, back to the problem of task decomposition: strict task graphs that force the model through steps notably constrain the expressivity of the system.

### RLM and Recursive Decomposition

Agent systems need a more flexible way to both decompose and execute tasks. RLM is the approach that comes closest to balancing these needs. Instead of forcing a fixed decomposition, it gives the model a Python REPL and the ability to run operations recursively, letting task structure emerge naturally from the model's own reasoning rather than being imposed upfront.

Subcalls (either direct LLM queries or RLM-like subagents) encapsulate context, the REPL allows the model to iteratively adapt to the problem rather than being forced to use it in a fire and forget pattern, the model has massive amounts of data for scripting in python so it knows the interface and is biased towards using it, context can be passed by reference which maintains the source of truth, and the model has the ability to naturally decompose the task in an unforced way.

Essentially, task decomposition falls out of just having the right primitives and an interface the model is biased towards using.

There's one catch.

Notice how the official implementation has a limited depth? It was only discussed with depth=1. When given the ability to actually recurse (depth=N) the model needs a guard against over decomposition. Not because a model always will, but it can and especially does when tasked with decomposition. Given an interface that offers unbounded decomposition the harness needs an underlying guard against overdecomposition.

However, there's a second question: how does the system adapt to data it discovers mid-execution? The lack of intermediate results from the REPL means the model has to commit to a full plan for that step upfront and only finds out if it worked at the very end. Imagine solving a maze where you have to blindly guess n steps into the future. In this world, the only source of feedback you get is where you end up. There's not much room for course correction especially if the environment is being mutated. You either emerge on the other side or you don't.

[Figure: "Blind N-Step Execution" — one-shot: entire path committed upfront; unexpected state is invisible — can't adapt; the only feedback is where you end up. Reactive (ReAct / tool loop): unexpected state is seen → adapt, per step. REPL: no intermediate state visible. Without intermediate feedback, the model must commit to a full sequence of steps — like navigating a maze blind. It only discovers it hit a wall when execution ends. With per-step feedback, it detects the wall immediately and reroutes.]

This is what we can describe as a lack of synchronization between the levels of the stack. The ability of the model to offload operations to some system (llm or program) that processes the information in isolation and returns only the finalized data constrains the main model's ability to adapt to failures encountered while executing its plan (the program in the repl). This is totally fine for reading information from an environment that isn't changing. However, when implementing, this rigidity from the lack of synchronization is problematic at best.

A brief aside... The above observations, combined with an understanding of deep research agents, should expose a very specific context engineering pattern: stack based isolation works very well for research due to its ability to decompose retrieval tasks into isolated operations on immutable data which can then be synthesized.

Overdecomposition and rigidity are failure modes that ReAct based agents don't suffer from because the planning and execution happen implicitly, one turn at a time, allowing the model to be flexible and reactive.

Now, at this point everyone who is attempting to build an agent has thought "Hey what if I made a planner agent and then an implementer agent and then a review agent".

Let me spare you the trouble. It will sort of work, but you're going to hate its guts while using it. It's slow, clunky, and has a ton of inertia while working. This is largely a consequence of having a very strict pattern for execution rather than allowing the model to intelligently decide how it should handle the task. This will likely improve benchmark scores, but won't actually improve your dev experience. Maintaining general expressivity during execution is incredibly important.

There are a few agent architectures that operate on this principle: Devin, Manus, Claude Code, and Altera's project Sid (now shortcut).

### Devin, Manus, and Altera

Devin, Manus, and Altera's PIANO architecture all fall into a bucket of "plan at a higher level and execute at a lower level" with some way to synchronize system 1 and system 2 thinking to get a long-running agent with persistent state.

They all follow a pattern of strategize with a high level planning agent, delegate to a lower level subagent, reduce the lower level agent context to some compressed representation, and return that formatted context to the higher level agent in order to synchronize the two. Altera's approach additionally allows the agents to do multiple forms of processing simultaneously.

This form of planning is prone to the same type of failure as mentioned above in the task decomposition section or the RLM section where overly strict execution constraints reduce the ability of the system to react to new information and necessarily force the subagent to fail in the same way running a script can fail.

Synchronous subagents (where the main agent blocks and waits for a subagent result) are more reliable but slow. While asynchronous subagents introduce an additional problem: knowing when and how to reconcile results.

[Figure: "Devin / Manus / Altera: Strategize–Delegate–Compress Cycle" — 1. Strategize → 2. Delegate → 3. Execute → 4. Compress & Return (↑ context lost here). The Devin/Manus/Altera pattern: a high-level strategic agent delegates to a lower-level executor, compresses results, and synchronizes back. Every compress boundary risks dropping critical state. Via Lance Martin — Context Engineering in Manus.]

### Codex and Claude Code

These are incredibly simple. They delegate work to a subagent using something like a prompt, and the subagent responds back when done.

This approach explicitly introduces a synchronization problem because the main parent is isolated from the child context and has to rely on some sort of message passing (in this case, it's sending a prompt and receiving a response). This is why originally the subagents ended up being best used just for search since most search operations are exploratory and actually not necessary to retain most context for.

Luckily for the labs, they can just train the models to be good at delegating to subagents and good at being subagents. This is not something to bet against as a harness creator.

Claude unnecessarily defines persistent roles for the subagents, but this is because their approach to synchronization is message passing (which we believe to be incorrect at with a mainthread + subagent architecture given current model behavior, however the models will get better at this because they will be trained for it).

We think single threaded agents have not been solved fully. As an industry, we do not need to move on to teams just yet.

### Agent Architecture Taxonomy

| aspect | ReAct | Markdown Plan | Task Trees | RLM | Devin / Manus / Altera | Claude Code / Codex | Slate |
|---|---|---|---|---|---|---|---|
| planning | implicit | file | explicit | REPL | planning agent | plan mode | implicit |
| decomposition | none | none | direct tree | REPL functions | task based | subagent delegation | implicit |
| synchronization | single thread | single thread | gated steps | REPL return | reduce & return | message passing | episodes |
| intermediate feedback | per step | per step | on task failure | on execution | after compress | message passing | per episode |
| context isolation | N/A | N/A | per subtask | per subcall | subagent | subagent | per thread |
| context compaction | N/A | N/A | Task based | REPL Slicing | Subagent compress | Compaction | Episode compress |
| parallel execution | N/A | N/A | N/A | In REPL | Altera only | Native | Native |
| expressivity | high | high | low | high | medium | medium | high |
| adaptability | Yes | Yes if plan updated | No | Yes | Yes | Limited by message passing | Yes |

Agent architecture comparison across key system properties. Slate has both the expressivity and reactivity of ReAct alongside the context isolation, parallelism, and compaction that other systems have.

## Slate's Approach: Thread Weaving and Episodes

To summarize what we have covered:

- **Compaction**: how to compress an agent trajectory while retaining key information
- **Strategic coherence**: how to allow an agent to strategize about a problem and stay aligned with that strategy throughout the course of the problem
- **Expressivity**: designing interfaces that allow the agent to express more complex behaviors
- **Task decomposition**: how to break down tasks and solve subproblems while maintaining flexibility at the top most level
- **Synchronization**: how to synchronize work being done throughout the system where the execution contexts are isolated

In this section, we propose one architectural primitive for solving these problems: the thread. The key insight is that frequent, bounded synchronization between an orchestrator thread and worker threads gives an actually usable balance of speed, latency, and intelligence.

### Threads

The idea is simple: use a highly expressive interface to access the knowledge overhang in a model, allowing it to strategize about its actions without focusing on implementation tactics. One central orchestration agent delegates actions to worker threads using a highly expressive interface (this can be a tool, a CLI, etc. We chose a DSL due to the flexibility that having access to a programming model gives us). A worker thread executes the action and returns to the main orchestrator.

Sound like a subagent? Not quite.

Threads are very specific. Each thread executes one action and when that action is done, it pauses and hands control back to the main thread. You can think of an action like a tactic: Run a command sequence, extract X from file Y, etc. Unlike purpose-specific subagents, threads are general workers that serve the system's current intent. The orchestrator decides what to do next and the thread does it. Normal subagents are persistent, sometimes launched in the background, and synchronize with the main thread (or with each other) through message passing due to their context isolation. Threads in contrast are only meant to accumulate context acting as a persistent reusable store for that specific work stream, and they don't use message passing as the primary way of communicating with the orchestrator. Instead, every thread action generates a compressed representation of its step history for executing just that action sequence. This compressed representation is called an episode and is directly shared with the main thread.

[Figure: "Threads vs. Subagents: Context Isolation" — Subagents: orchestrator ↔ subagent A / subagent B via msg; each agent has its own isolated context. Threads: shared / composable context; orchestrator passes ctx into each thread (T1, T2), episodes return back, and one thread's episode (T1→T2) can become another thread's input. Context is explicitly shared — episodes compose across threads.]

### Solving Episodic Memory with Threads

The steps a thread takes while completing an action constitute an episode. This gives us a tractable form of true episodic memory in LLMs.

Episodic memory is the compressed representation of a completed episode: only the important results are retained, not the full tactical trace of every step taken to reach them. Subthreads do not do back-and-forth message passing with the main thread. Instead, they execute, and the episode is returned. This built-in completion boundary is what makes compaction natural in Slate's architecture.

Episodes can also be used as direct inputs to other threads. This makes threads composable. A thread can be initialized with the episode of a prior thread inheriting the useful conclusions and work history without inheriting the full context. This composability is what makes a thread-based architecture maximally expressive as a primitive, and what distinguishes it from naive subagent designs that only pass back a single response string.

### Thread Weaving

The result of thread-based execution is a system that decomposes tasks implicitly and adaptively — without ever requiring a static plan. The orchestrator is not forced to commit upfront, but is forced to externalize work in bounded, compressible units. This is thread weaving: the orchestrator dispatches, threads execute, episodes compose.

The mechanism: the orchestrator uses threads by reference, giving it semantics for complex context routing — similar to what RLM achieves through its REPL, but without the rigidity since actions execute inside a thread one step at a time. Because thread scope is bounded, the system naturally synchronizes with the current plan. Because threads are LLM-driven rather than static scripts, they can react to unexpected environment state instead of crashing.

The result is a system that decomposes tasks implicitly and adaptively. The orchestrator manages planning and decomposition as it goes. It's not forced to commit to a static plan upfront. But it is forced to externalize that decomposition in useful units of work that can be compressed and referenced later. Frequent synchronization means the orchestrator can also update its strategy when new information arrives mid-task.

[Figure: "Slate: Thread Weaving & Episode Architecture" — orchestrator dispatches threads T1..T6; episodes return to the orchestrator; episodes compose as inputs (T1+T2 → input, T2+T3 → input, T3+T4 → input) to subsequent threads. Thread weaving: bounded worker episodes dispatched from and synchronized back into one orchestration thread. Threads T1/T2/T3 run independently; their episodes become inputs to subsequent work.]

### Threads as Processes: An OS view into LLM systems

Threads and episodes map directly onto an OS style framing.

[Figure: Karpathy's LLM OS diagram — the LLM as an emerging OS kernel managing context (RAM), processes (tool calls/subagents), storage (files), and peripherals (browser, terminal, APIs).]

Specifically, Karpathy's LLM OS describes the LLM as an operating system kernel: managing context (RAM), spawning processes (tool calls, subagents), reading and writing to storage (files, memory), and coordinating I/O across peripherals (browsers, terminals, APIs). Just as an OS kernel doesn't execute application logic itself, the main thread LLM schedules tasks, manages resources, and maintains process state in order to route work through the system.

Slate's thread architecture maps onto this directly. The orchestration layer is the kernel. Threads are isolated processes. Episodes are the process return values: compressed summaries of what the process did, committed back into the kernel's working memory. The filesystem, terminal, and web are the peripherals. The model's context window is RAM — scarce, precious, and actively managed.

Slate's episode architecture is a direct answer in that framing: instead of letting RAM fill until the process crashes, each thread return is a natural opportunity to decide what gets retained, what gets compressed, and what gets discarded.

### Long horizon task bottlenecks

The real bottleneck in long-horizon agentic tasks is context management, not model intelligence. Models are already capable enough to solve many more tasks than they currently succeed at due to the knowledge overhang. The gap is a systems problem, not a capability problem.

What's remarkable about Slate is that our routing works at all. The models seem to understand how to route context throughout the system in ways that are useful and appropriate, without being explicitly trained to do so. We leave a formal analysis and benchmarking of this routing behavior as future work.

And we leave it as an exercise for the reader to experience.

Today we are releasing this agent into open beta. You can use it by visiting our home page or running `npm i -g @randomlabs/slate`

## Interesting Observations

A few results that surprised us during development and testing:

- **Massively parallel execution in practical workflows.** Real software tasks decompose naturally into parallel thread workstreams. The orchestrator can dispatch several threads simultaneously and synthesize their episodes before continuing. This is qualitatively different from sequential step-by-step agents and in practice it seems to be faster.
- **Cross-model composition.** Using Sonnet and Codex together across the same task works well. The episode boundary acts as a clean handoff between models with no loss of context coherence.

## References

1. RLM — Recursive Language Models (paper)
2. RLM — blog post overview
3. Karpathy: LLM computer framing
4. TerminalBench 2.0: Simple Codex baseline
5. Terminus minimal harness
6. Dex Horthy: the "Dumb Zone"
7. Altera: Project Sid / PIANO architecture
8. Devin / Cognition: don't build multi-agents
9. Manus: context engineering for AI agents
10. Manus: context engineering notes & slides
11. Silver et al.: AlphaZero (Science, 2018)
12. AlphaZero knowledge acquisition probing (PNAS)
13. Stockfish vs LCZero: competing paradigms
14. Silver et al.: AlphaGo (Nature, 2016)
15. DeepMind: innovations of AlphaGo
16. Stockfish chess engine
17. Geoffrey Huntley: the Ralph loop
18. Amp: handoff mechanism
19. ADaPT: as-needed decomposition and planning
20. TerminalBench 2.0: gpt2-codegolf task
21. Yao et al.: ReAct — synergizing reasoning and acting
22. Wei et al.: chain-of-thought prompting
23. Manus: architecture slides
24. Hong, Troynikov, Huber: Context Rot — How Increasing Input Tokens Impacts LLM Performance (Chroma, 2025)
25. Working memory in humans
26. Karpathy: LLM OS
