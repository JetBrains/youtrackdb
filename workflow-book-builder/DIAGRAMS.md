# Diagrams — inline Mermaid, everywhere

Every diagram in the book is an inline Mermaid fenced code block, placed where the prose leans on it. There are no committed image files and no render step. Mermaid renders natively on GitHub, matches the convention the workflow's own design documents already use (their class and sequence diagrams are Mermaid), and needs no build step or committed binaries. An author draws a figure by writing a ```` ```mermaid ```` block in the chapter; nothing else has to happen for a reader to see it.

There is one diagram convention and no exceptions. A figure is a Mermaid block, captioned below the closing fence, and it lives in the chapter file next to the prose it teaches.

## Choosing the Mermaid type

Pick the Mermaid type by what the figure has to show:

- **`flowchart LR` or `flowchart TD`** for flows and decision trees: linear sequences, two- or three-way branches, the tier-gate questions and their routing. Use `LR` (left to right) for a pipeline read as a sequence, `TD` (top down) for a decision tree read as a cascade of questions.
- **`flowchart TD` with `subgraph` blocks** for containment and hierarchy: a plan holding tracks, a track holding steps, a step producing an episode. A `subgraph` draws the enclosing box; nodes inside it are the contained items.
- **A dashed back-edge** (`-.->`) for a loop-back or an ESCALATE path: a failed gate returning to an earlier phase, a review sending a step back for rework. The dashed style reads as "exceptional return", distinct from the solid forward edges.
- **`stateDiagram-v2`** where the figure is genuinely a state machine with named states and labelled transitions, and that framing teaches better than a flowchart. The phase state machine is the canonical case.
- **`sequenceDiagram`** where the figure is an ordered exchange between actors over time: an orchestrator delegating to an implementer sub-agent, a review wave fanning out and reporting back.

When two types both fit, choose the one that carries the figure's one idea (`BOOK_BRIEF.md` rule 7) with the least visual noise.

## Authoring rules

- **Short node labels.** A node label is a few words, not a sentence. The prose carries the explanation; the diagram carries the shape. A label that runs long is a sign the figure is trying to teach two ideas.
- **Quote labels with special characters.** Wrap a label in double quotes when it contains characters Mermaid would otherwise parse, such as parentheses, a slash, a colon, or `full`/`lite`/`minimal` written with backticks-as-text. For example `A["Phase 0: research"]` rather than `A[Phase 0: research]`.
- **Caption every figure.** Below the closing fence, write `**Figure N.K — caption.**`, where `N` is the chapter number and `K` the index within the chapter. The caption states the one idea the figure carries.
- **One idea per figure.** A diagram that restates the prose earns nothing; delete one of them (`BOOK_BRIEF.md` rule 7). If a figure needs a second idea, it is two figures.
- **No links inside labels.** A node or edge label is plain text. A Markdown link inside a label — including a chapter cross-reference like `[Chapter 3](03-...md)` — renders as the literal characters `[...]( ...)` and corrupts the diagram. Name the chapter in the surrounding prose, not in the label.

## How to add a figure

Draw it in a ```` ```mermaid ```` block in the chapter, at the point where the prose first leans on it. Caption it `**Figure N.K — caption.**` below the closing fence. Choose the Mermaid type by the rules above, keep the labels short, and quote any label with special characters. Confirm it teaches one idea the prose leans on (`BOOK_BRIEF.md` rule 7); if it restates the prose, delete one of them. No sidecar, no render step, no committed asset, and no change to this file: a new figure is a self-contained block in the chapter that owns it.
