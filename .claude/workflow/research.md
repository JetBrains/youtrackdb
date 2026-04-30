# Research (Phase 0)

## Overview

This document covers Phase 0 of the development workflow — interactive
research and exploration before planning. The agent answers user questions
related to the aim of the change, performs code research, and does internet
research. This is an open-ended, user-driven conversation — the agent does
not produce any plan or design documents during this phase.

Research completes **only when the user explicitly asks to create the plan
and design documents** (e.g., "create the plan", "let's plan this",
"proceed to planning"). Until then, the agent stays in research mode.

## Goal

Build shared understanding between the user and the agent about:
- The relevant parts of the codebase
- Technical constraints and trade-offs
- Alternative approaches and their implications
- External references (libraries, algorithms, papers, prior art)

The output of this phase is **conversation context** — findings, decisions,
and intermediate conclusions that the user and agent have discussed and
agreed on. These are carried forward into Phase 1 (Planning) within the
same session.

## How it works

1. The user runs `/create-plan` (optionally with a directory name argument).
2. The agent reads workflow documents, then asks the user for the aim.
3. The user provides the aim. The agent enters **research mode**.
4. In research mode, the agent:
   - Answers user questions about the codebase, architecture, and design
   - Explores code (reads files, searches for patterns, traces call chains)
   - Performs internet research when asked (web search, fetch documentation)
   - Presents findings and intermediate conclusions
   - Helps the user evaluate trade-offs and alternatives
   - Does **NOT** produce plan files, design documents, or track decompositions
5. The user drives the conversation — asking questions, requesting deeper
   investigation, or steering toward specific areas.
6. When the user is satisfied with the research and explicitly asks to
   create the plan, the agent transitions to Phase 1 (Planning).

## Transition to Phase 1

When the user says to create the plan:

1. The agent summarizes the key research findings and decisions from the
   conversation. This summary is for the agent's own use — it ensures
   the planning phase builds on what was discussed, not just what the
   agent happens to remember.
2. The agent proceeds to Phase 1 (Planning) — producing the implementation
   plan and design document. All findings, decisions, and intermediate
   conclusions from the research phase **must** inform the plan:
   - Decision Records should reflect alternatives explored during research
   - Architecture Notes should build on codebase exploration findings
   - Track descriptions should incorporate constraints discovered during research
   - The design document should reflect design choices discussed with the user

## Rules

- **No premature planning.** Do not start writing plan files, track
  decompositions, or design documents until the user explicitly asks.
- **Stay responsive.** Answer what the user asks. Do not steer the
  conversation toward planning unless the user signals readiness.
- **Be thorough.** When exploring code, read the actual sources — do not
  guess based on class names or package structure alone.
- **Surface trade-offs.** When presenting findings, highlight alternatives
  and trade-offs so the user can make informed decisions.
- **Record decisions in conversation.** When the user makes a decision
  during research (e.g., "let's use approach X"), acknowledge it clearly.
  These decisions carry forward to planning.
- **Internet research is allowed.** Use web search and web fetch when the
  user asks about external libraries, algorithms, standards, or prior art.

## Tooling for code research

Research routinely produces conclusions that planning, decomposition, or
deletion decisions ride on later — "this method has no production
callers", "this slot has no consumer", "the field is touched only
inside its declaring class". Those are reference-accuracy questions and
must be answered through the IntelliJ PSI search via mcp-steroid when
the IDE is connected, not through grep. See
[`conventions.md`](conventions.md) §1.4 *Tooling discipline* for the
full rule (preflight, fallback when unreachable, sub-agent
delegation). The session-start hook prints the `mcp-steroid: …` status
line — act on it before the first symbol audit. Grep is fine for
orientation (filename globs, unique string literals, "is there
anything called X anywhere") but the load-bearing answer must be
PSI-backed when the IDE is reachable.

When routing exploration to a sub-agent (Explore or any other),
explicitly instruct it to use mcp-steroid PSI find-usages for symbol
questions — sub-agents default to grep otherwise.
