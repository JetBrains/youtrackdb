# Slim Plan Rendering (for sub-agent contexts)

When assembling a sub-agent prompt that needs the implementation plan as
strategic context, pass a **slim rendering** of the plan instead of the
raw file contents. The full file on disk is unchanged — slimming happens
in-memory when composing the prompt.

This matters because the plan is included as strategic context for every
step-level and track-level code-review sub-agent. In a track with 10
agents × 3 iterations = 30 sub-agents, a 25K-token plan turns into ~750K
tokens of sub-agent prompt tokens. Slim rendering typically cuts this by
30-60% once a few tracks are complete.

---

## Rendering rule

1. **Keep the pre-Checklist content verbatim.** Feature name, Design
   Document link, High-level plan (Goals, Constraints, Architecture Notes,
   Decision Records, Component Map, Invariants, Integration Points,
   Non-Goals) — all strategic and needed by every sub-agent.

2. **Keep the `## Checklist` header.**

3. **For each track in the checklist:**

   | Status | Keep | Drop |
   |---|---|---|
   | `[ ]` (not started) or `[>]` (in progress) | Full description, Scope, Depends-on — all verbatim | Nothing |
   | `[x]` (completed) | Title line, **intro paragraph** (the first quoted block before any `**Keyword**:` subsection), **Track episode**, **Strategy refresh** line (if present) | **What/How/Constraints/Interactions** subsections, **Scope** line, **Depends on** line, **Step file** pointer line |
   | `[~]` (skipped) | Title line, **Skipped:** reason, **Strategy refresh** line | Description body, Scope, Depends-on |

   **Current track exception:** The track currently being executed is
   always `[ ]` or `[>]` in the plan file (it is not marked `[x]` until
   Phase C completes). Apply the `[ ]/[>]` rule — full detail is kept.

4. **Keep the `## Final Artifacts` section verbatim.**

---

## Example — before and after

### Before (full render, 90+ lines for one completed track)

```markdown
- [x] Track 2: Direct Buffer Write Infrastructure
  > Modify `AtomicOperationBinaryTracking` to support direct buffer writes
  > alongside the existing overlay mode, with a per-file flag controlling
  > which path is used.
  >
  > **What**: Add `directBuffer` boolean to `FileChanges`. Make
  > `CacheEntryChanges.changes` non-final and nullable — initialized to
  > `null` for direct-buffer files, `new WALPageChangesPortion()` for
  > overlay files. ... [dozens of lines of implementation detail] ...
  >
  > **How**: ... [more lines] ...
  >
  > **Constraints**: ... [more lines] ...
  >
  > **Interactions**: ... [more lines] ...
  >
  > **Scope:** ~6 steps covering direct-buffer write path, flush ordering,
  > undo log integration.
  >
  > **Depends on:** Track 1
  >
  > **Track episode:**
  > Built direct-buffer write path with applyRollback entry point.
  > Discovered CachePointer lock lifecycle needed extension for
  > cross-component-op undo ordering — see Step 5 episode.
  >
  > **Step file:** `tracks/track-2.md` (8 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.
```

### After (slim render, ~15 lines)

```markdown
- [x] Track 2: Direct Buffer Write Infrastructure
  > Modify `AtomicOperationBinaryTracking` to support direct buffer writes
  > alongside the existing overlay mode, with a per-file flag controlling
  > which path is used.
  >
  > **Track episode:**
  > Built direct-buffer write path with applyRollback entry point.
  > Discovered CachePointer lock lifecycle needed extension for
  > cross-component-op undo ordering — see Step 5 episode.
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.
```

---

## How to identify the "intro paragraph"

The intro paragraph is everything in the blockquote **before** the first
line matching `> **<Keyword>**:` where `<Keyword>` is one of `What`,
`How`, `Constraints`, `Interactions`, `Scope`, `Depends on`, `Track
episode`, `Step file`, `Skipped`, `Strategy refresh`.

If there is no such marker line, the entire description is the intro
paragraph — keep it as-is.

---

## Interaction with the on-disk collapse (Feature #2)

[`track-code-review.md`](track-code-review.md) §Track Completion step 4
also collapses the description **on disk** to match this slim format
when a track is marked `[x]`. Once a plan has been through one track
completion under the new rule, the slim rendering above and the on-disk
form match for completed tracks — the rendering rule is then idempotent
for those entries.

For completed tracks from earlier plans (pre-refactor), the on-disk form
still carries the full description. The slim rendering strips it at
prompt-assembly time, so sub-agent contexts are compact either way. If
you want to also shrink the on-disk plan, manually apply the collapse
rule to those legacy entries.
