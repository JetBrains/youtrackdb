# Chapter 5 — Phase 1: the design document

In the `full` tier, the design is written and reviewed before any plan exists, and frozen before the plan derives from it. This chapter teaches how a design gets authored, how a single discipline gates every edit to it, what the freeze means, and when a design choice stops being the agent's to make and becomes yours. The mental model to leave with: the design is the seed, not a trailing artifact, and the plan grows from a fixed seed.

Chapter 4 left you with a research log: the verbatim aim, every settled decision and its rationale, the surprises the exploration turned up, and a cleared adversarial gate. That gate already attacked the decisions once, on the log, before any artifact derived from them. Chapter 3 told you which changes reach this chapter at all: only the `full` tier authors a design document. A `lite` or `minimal` change skips everything here, because its track files carry their decisions inline and there is nothing a design would add. So read this chapter as the `full`-tier path. If your change is `lite` or `minimal`, Chapter 6 is where you rejoin.

## You have research; now you turn it into a design

Start from a concrete moment. You have just cleared the Phase 0 to Phase 1 boundary in a `full`-tier change. The research log is complete and its gate has passed. You ask the agent to begin the design, and the agent's first instinct is to open `design.md` and start writing. It does not. It invokes a skill called `edit-design` instead, and that skill does the writing on its behalf.

That detour is the entire subject of this chapter. The agent is barred from editing `design.md` directly. Every change to the file, from the first blank-page draft to the last polishing tweak, goes through `edit-design`. The rule is stated plainly in the skill: you must use it, not raw edits, for every modification to the design (`.claude/skills/edit-design/SKILL.md`). To understand why a workflow would forbid the obvious thing, look at what the design document is for.

## The design is authored first, and the plan derives from it

A design document at `_workflow/design.md` explains the structural and behavioral shape of the solution: the classes and their relationships, the flows through them, and a dedicated section for every part that is genuinely hard — concurrency, crash recovery, a performance-critical path, a non-obvious invariant. It is design level, not code level. It names classes and interfaces and the contracts between them; it does not name variables or loop constructs. Its diagrams are Mermaid class and sequence diagrams, paired with prose, because a diagram without a sentence explaining it is ambiguous (`.claude/workflow/design-document-rules.md` §Rules).

The order is the load-bearing part. The design is written **first**, in its own `create-plan` session, and it freezes when its review passes. Only then, in a separate session, does the implementation plan derive from it. This is the inverse of the habit most engineers carry in from elsewhere, where you sketch a plan, start building, and back-fill a design doc afterward to satisfy a reviewer. Here the design is the seed. The plan back-fills nothing the design has not already settled, so the plan's decision records mirror the design's decisions rather than crystallizing ahead of them (`.claude/workflow/planning.md` §Goal).

Calling the design a *frozen seed* earns both words. It is a seed because the live decision records that drive execution are grown from it — Chapter 6 shows how the plan and tracks are seeded from these design records. It is frozen because once Phase 1 ends, the original `design.md` is never edited again. A Phase 3 replan that would have changed the design does not reach back and rewrite it; it records its new intent in the plan instead, and Chapter 14 covers that path. The design you froze stays the historical record of what you set out to build, and Phase 4 writes a separate `design-final.md` for what you actually built (Chapter 13).

Because the design is the thing every later phase trusts, a single bad edit to it is expensive: it propagates into the plan, into the tracks, into the code. That is the cost the direct-edit ban is paying down. If every edit is cheap to make but a wrong one is costly to discover, you make the edits expensive on purpose, by wrapping each one in a review.

## `edit-design` makes every edit one reviewed action

`edit-design` bundles four steps into one atomic action: **apply the edit, auto-review it, iterate until it clears, present the result.** The agent never gets to apply an edit and walk away. The review is welded to the write, so the structural rules that govern a design document (the mandatory Overview, the per-section shape, the length caps) are self-enforcing rather than aspirational. A rule that nobody checks is a wish; a rule the write itself runs is a gate (`.claude/workflow/design-document-rules.md` §Mutation discipline).

Walk the four steps in order.

**Apply.** The skill writes the requested change to disk. For the very first write, which creates the design from nothing, it also stamps the file's first line with the workflow commit it was built against, so a long-lived branch can later tell whether the design predates a workflow change (the stamp machinery is Chapter 15's subject). Every later edit leaves that stamp untouched.

**Auto-review.** Two checks run, cheapest first. A mechanical script scans for the structural violations a regex can catch: a section missing its required parts, a cross-reference that points at nothing, a parenthetical decision aside the style forbids. Then, only if the mechanical pass found no blocker, a *cold-read* runs: a fresh sub-agent reads the design with no memory of the conversation that produced it and answers a fixed set of comprehension questions — what is this design adding, what must stay true after it, where is the subtle gotcha. The cold reader is a stand-in for the human who will read the design next without having sat through its authoring. If that reader cannot build a working mental model from the document alone, the document has failed at its one job (`.claude/workflow/prompts/design-review.md`).

**Iterate.** Findings come back graded: a *blocker* must be fixed, a *should-fix* should be, a *suggestion* is recorded and left. The skill fixes the blockers and should-fixes and re-runs the review, up to a budget of three rounds by default. If the budget runs out with blockers still standing, the skill does not paper over it: it leaves the partial edit on disk, logs the unresolved findings, and hands the diff to you for manual resolution. You are the gate when the action cannot fix itself.

**Present.** The skill shows you the diff it applied and the review verdict, and appends a one-line record of the mutation to an append-only log at `_workflow/design-mutations.md`. The log is the audit trail of how the design was built, edit by edit. It is the one file in this family that carries no workflow stamp, because it is append-only by contract and a stamp would be dead weight on it.

```
  request to change design.md
            │
            ▼
   ┌──────────────────┐
   │ 1. apply edit     │  write to disk
   ├──────────────────┤
   │ 2. auto-review    │  mechanical checks ─► cold-read sub-agent
   ├──────────────────┤
   │ 3. iterate        │  fix blockers, re-review  ◄─┐
   │                   │  ── findings remain ────────┘  (budget: 3)
   ├──────────────────┤
   │ 4. present        │  diff + verdict + log entry to you
   └──────────────────┘
            │ budget exhausted with blockers
            ▼
       you resolve manually
```

**Figure 5.1 — The `edit-design` atomic action.** Every change to `design.md` runs apply, auto-review, iterate, present as one unit; the review is welded to the write so the structural rules enforce themselves.

The agent tells the skill which *kind* of edit it is (the first creation, a small content tweak, a section rename, a structural rewrite), and the kind decides how much the cold reader re-reads and which checks fire. A focused tweak gets a bounded read of the changed section and its neighbors; a rename or a rewrite gets a whole-document read, because a change to one section's name can break references three sections away. The book does not need the full table of edit kinds; the point is that the discipline scales its scrutiny to the blast radius of the edit (`.claude/skills/edit-design/SKILL.md` §Cold-read scope and check-set by mutation kind).

## The design's review is comprehension, because the decisions were already challenged

A reader who skimmed Chapter 4 might expect the design review to argue with the design's decisions — to attack whether the right approach was chosen. It does not, and the reason is the most important structural fact in this chapter.

The decision-and-assumption challenge already ran. It ran in Phase 0, as the adversarial gate on the research log (Chapter 4). The workflow runs that challenge **once**, on the one artifact every tier has, rather than re-running it on each tier-specific document. By the time the design is being authored, its load-bearing decisions came from a log whose decisions survived attack. So the design's own review is the cold-read: it assesses whether a fresh reader can understand the design, not whether the design is right.

The two are ordered, and the order is a hard invariant. The cold-read is *gated* behind the cleared adversarial gate. It will not run while a decision challenge is still open. Concretely: if the act of authoring the design surfaces a new load-bearing decision, something the research never settled, that decision does not get quietly written into `design.md`. It is appended back to the research log, re-challenged at the gate, and only once it clears does the cold-read resume. The design cannot reach the comprehension check while one of its decisions is still contested. This is why Chapter 4 said the research log keeps accepting appends into Phase 1: the gate stays live precisely so a design-time decision is challenged on the log, not slipped past it.

The named reason is sequencing the two questions correctly. There is no point asking a fresh reader to build a mental model of a design whose foundations might still shift under it. Settle the decisions first, then check that the document explains them. The cold-read, when it finally runs on a `full`-tier creation, also runs an *absorption* cross-check: it confirms that every load-bearing decision the research log recorded shows up as a seed record in the design, and that the design invented no decision the log never made. The design must carry the research forward faithfully, no more and no less.

When the design covers a large, dense area, the cold-read fans out across the document, and a copy editor's eye gets added to the comprehension check: the same review pass also flags prose that is too dense, too terse, or hard to read, so the design that freezes is one a human can actually use. The dimensional review agents that pick apart changed *code* are a different machine entirely, and they belong to Chapter 11; here the only reviewer is the cold reader assessing a document.

## When a design choice escalates to you

The agent runs Phase 1 autonomously, but autonomy has one boundary, and it is worth knowing exactly where it sits. Everything inside a phase session is the agent's to decide — except a *design decision*.

A design decision is a choice between real alternatives that affects architecture, the shape of a public API, a data structure, an algorithm, or a behavioral contract, where the research and the design have not already settled which way to go. When the agent hits one of those, it stops and asks you. It does not guess and move on. The form of the ask is fixed: describe the context and where the decision point arose, lay out at least two alternatives with concrete trade-offs, state a recommendation with its reasoning, and then wait for your guidance (`.claude/workflow/design-decision-escalation.md`).

The boundary matters because most of what the agent does is *not* a design decision, and treating routine work as an escalation would grind the workflow to a halt. A mechanical change with one obvious approach, a name that follows the codebase's existing conventions, the choice of which test cases to write, a detail the plan or the research already prescribed — those the agent handles silently. Escalation is reserved for the genuine fork: a new abstraction the research did not anticipate, a public contract that could go two ways, a choice whose consequences reach past the work in front of it.

Escalation is a different mechanism from the adversarial gate, and the two are easy to conflate. The gate is an automated reviewer attacking decisions that have already been made; it keeps bad decisions from passing. Escalation is the agent surfacing a decision it is *not authorized to make alone* and handing it to you, the human, in real time; it keeps the agent from making certain decisions at all without you. Both protect the design, from different angles. (This escalation discipline is written for Phase 3 step implementation, where it fires most often, but the principle holds wherever the agent works: design decisions are yours, everything else is autonomous.)

## Where this leaves you

You finish Phase 1's design step with a `design.md` that has been built one reviewed edit at a time, whose decisions survived challenge on the research log before the document was ever assessed for comprehension, and which is now frozen as the seed for everything that follows. Every edit that produced it is logged; every escalation along the way was yours to settle.

What you do not have yet is a plan. The design says what the solution looks like; it does not say how the work is broken into pieces or in what order. That is the next step, and it runs in a fresh session against the now-frozen design. Chapter 6 picks up there: how the plan and its tracks are derived from the frozen seed, why the plan is derived rather than authored directly, and how a single change is decomposed into dependency-ordered tracks of work.

## Further reading

- `.claude/workflow/planning.md` — the `full`-tier Phase 1 path: design-first authoring and the Step 4a/4b boundary (§Goal), and the design document's required content, mutation discipline, and freeze (§Design Document).
- `.claude/workflow/design-document-rules.md` — what a design document must contain and the rules that govern it (§Purpose, §Rules), the mutation-discipline contract and its four-step atomic action (§Mutation discipline), and the Phase-1 freeze (§Rules, Rule 15).
- `.claude/skills/edit-design/SKILL.md` — the apply / auto-review / iterate / present loop in operational detail, the per-edit-kind cold-read scope, and the gating of the `phase1-creation` cold-read behind the cleared log-adversarial gate (§Workflow, §Step 4).
- `.claude/workflow/design-decision-escalation.md` — when a choice is a design decision the agent must escalate, how to present it, and what it handles autonomously instead.
- `.claude/workflow/prompts/design-review.md` — the cold-read reviewer's comprehension questions, the absorption cross-check against the research log, and the prose-quality additions.
- `.claude/workflow/prompts/adversarial-review.md` — the adversarial reviewer's research-log-scoped gate at the Phase 0→1 boundary (§Research-log-scoped review).
