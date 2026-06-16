# Diagrams — ASCII by default, committed SVG for a named set

The book draws diagrams two ways. ASCII box-and-arrow diagrams in fenced code blocks are the default for every flow and box figure. A short, explicitly enumerated set of dense figures that ASCII cannot lay out cleanly is authored in D2 source and rendered to a committed SVG. There is no third option and no open "render whatever needs it" category: a figure is either ASCII or one of the named SVG figures below.

The book does not use mermaid. Mermaid renders inconsistently across viewers (GitHub, IDEs, PDF export, static-site generators each support a different subset). ASCII renders identically everywhere and needs no tooling. A committed SVG renders in every image-capable viewer without a reader-side build. Pre-rendering loses some of the diffability of inline source; the book accepts that loss in exchange for reader-side portability, and contains it by keeping the SVG set small and the source diffable in a sidecar `.d2` file.

D2 is the render DSL. The choice is a preference for a single Go binary with no headless-browser dependency, over mermaid-cli's puppeteer and Chromium chain. It is a design preference, not a benchmarked claim.

## When ASCII, when SVG

Use ASCII for anything you can lay out as a grid of boxes connected by `->`, `|`, and `+`: linear flows, two- or three-way branches, small box-and-arrow sketches, short tables of states. Most figures in the book are ASCII. ASCII diagrams diff cleanly, render in a plain terminal, and need no install.

Use a committed SVG only for a figure in the enumerated set below. These are figures with enough nodes, crossing edges, or nested structure that an ASCII grid becomes unreadable. The set is fixed at TOC time: when the first production run builds the chapter map, it pins exactly which figures are SVG, so the rendered-asset footprint is a named list rather than an open category. A later run may add to the set, but only by amending this list and the TOC together (see "How to add a figure" below), never by an author silently rendering a new figure mid-chapter.

## The enumerated SVG figure set

The book commits an SVG only for the figures named here. Each is dense enough that an ASCII layout breaks down. The figure numbers (`fig-1`, `fig-2`, …) are assigned at TOC time and recorded in `docs/workflow-book/TOC.md`; the file names below are the sidecar/SVG pairs under `docs/workflow-book/assets/diagrams/`.

1. **The phase state machine** (`fig-phase-state-machine.d2` → `fig-phase-state-machine.svg`). Phases 0 through 4 with their transitions, the gates between phases, and the loop-back edges (a failed gate returning to an earlier phase). The branching plus the back-edges make this unreadable as ASCII.
2. **The tier-gate decision tree** (`fig-tier-gate.d2` → `fig-tier-gate.svg`). The two gate questions (is there a design question? what is the change scope?) and how their answers route a change to the `full`, `lite`, or `minimal` tier, with the per-tier artifact differences hanging off each leaf. A decision tree with annotated leaves is the canonical case ASCII handles poorly.
3. **The track / step / episode hierarchy** (`fig-track-step-episode.d2` → `fig-track-step-episode.svg`). A plan containing tracks, a track containing steps, a step producing an episode, with the cross-references (a track's dependency on another track, an episode promoted to the track's discovery log). The nested containment plus cross-links exceed an ASCII grid.

These three are the candidate set the design fixed. The first production run confirms the final list when it builds the TOC; if a chapter's teaching arc does not need one of these as a figure, the run drops it from the set rather than committing an unused asset.

## The render step

`scripts/render-diagrams.sh` renders every `.d2` sidecar under `docs/workflow-book/assets/diagrams/` to a committed `fig-*.svg` beside it. The committed SVG is what chapters embed; readers never run the renderer.

```bash
workflow-book-builder/scripts/render-diagrams.sh
```

The script checks for the `d2` binary first. If `d2` is missing it prints the one-time install command (below) and exits without rendering, rather than failing with an opaque tool-not-found error. The render path runs only in a production cycle, and only when a touched chapter added or changed a figure in the enumerated set; ASCII figures never render.

## The one-time `d2` install (operator step)

`d2` is not installed in a fresh environment. Install it once before the first render:

```bash
# macOS / Linux one-line installer:
curl -fsSL https://d2lang.com/install.sh | sh -s --
# or via Go:
go install oss.terrastruct.com/d2@latest
```

After installing, re-run `render-diagrams.sh`. The install is a one-time operator step, not part of the per-chapter authoring loop.

## How to add a figure

A new figure is either ASCII or a new entry in the enumerated SVG set. The two paths differ.

**ASCII figure.** Draw it in a fenced block in the chapter, captioned `**Figure N.K — caption.**` below the closing fence, where `N` is the chapter number and `K` the index within the chapter. No sidecar, no render step, no change to this file. Confirm it teaches one idea the prose leans on (`BOOK_BRIEF.md` rule 7); if it restates the prose, delete one of them.

**New SVG figure.** Adding an SVG figure is a deliberate amendment, not an author's call mid-chapter:

1. Confirm the figure genuinely cannot be ASCII (enough nodes, crossing edges, or nested structure that an ASCII grid is unreadable). If it can be ASCII, make it ASCII.
2. Add the figure to "The enumerated SVG figure set" above, naming the `.d2` sidecar and the `.svg` output and the one idea it carries.
3. Add the figure to the chapter's brief in `docs/workflow-book/TOC.md`, so the cross-reference matrix records it.
4. Author the D2 source in `docs/workflow-book/assets/diagrams/<name>.d2`.
5. Run `scripts/render-diagrams.sh` to produce the committed `<name>.svg` (installing `d2` first if the script reports it missing).
6. Embed the rendered SVG in the chapter, captioned the same way as an ASCII figure.

Keeping step 2 and step 3 together is what keeps the SVG set a bounded, named list. An author who skips them and renders a figure anyway has created an asset the cross-reference matrix does not know about, which an evolution run cannot track for drift.
