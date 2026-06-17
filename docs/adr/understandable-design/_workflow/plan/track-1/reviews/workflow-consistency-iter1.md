<!--
MANIFEST
dimension: workflow-consistency
target: Track 1 — the two-role authoring loop, wired into design creation
range: 4d3962c97441218d8a78272e92f18b83955bef37..HEAD
iteration: 1
findings_total: 3
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: WC1
    sev: should-fix
    anchor: "### WC1 [should-fix] design-sync loses its prose-axis owner"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:459-462; .claude/workflow/design-document-rules.md:478-486; .claude/workflow/prompts/design-review.md:198-203"
    cert: n/a
    basis: "Read of all three staged files; design-sync classified interactive (no auditor), comprehension-review disclaims prose axis, design-document-rules claims the rework wires the auditor"
  - id: WC2
    sev: suggestion
    anchor: "### WC2 [suggestion] new role tokens absent from every workflow TOC Roles column"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md:16; readability-auditor.md:16; absorption-check.md:16"
    cert: n/a
    basis: "grep for author / reviewer-readability / reviewer-absorption across .claude/workflow + .claude/skills + staged tree returned no TOC Roles-column hit"
  - id: WC3
    sev: suggestion
    anchor: "### WC3 [suggestion] §Tools used TOC cell and inline summary drifted from the multi-agent body"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:40,927"
    cert: n/a
    basis: "Read of SKILL.md TOC region + §Tools used body; cell/comment say 'cold-read sub-agent spawn', body spawns four review roles"
-->

## Findings

### WC1 [should-fix] design-sync loses its prose-axis owner

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 459-462), with the contradicting restatements at `.claude/workflow/design-document-rules.md` (line 478-486) and `.claude/workflow/prompts/design-review.md` (line 198-203).
- Axis: cross-file rule restatement (S4 one-owner-per-surface invariant).
- Cost: a live mutation kind (`design-sync`, which re-distills human-facing `design.md` prose) runs the prose AI-tell axis on neither reviewer, violating the named S4 invariant the branch tightens.

Three staged files disagree about who owns the prose axis on `design-sync`:

- The de-warmed comprehension reviewer disclaims it. `design-review.md:198-203` says "This reviewer runs **no** prose AI-tell axis ... on any surface. That axis is the `readability-auditor` agent's, on every prose-judged surface — the design creation kinds, `design-sync`, and the Step-4b track cold-read." `comprehension-review.md:39` and `readability-auditor.md:58` restate S4: "never both **and never neither**."
- `design-document-rules.md:478-486` asserts the auditor IS spawned on `design-sync` and points at this rework as the place that wires it: "the prose AI-tell axis are the `readability-auditor` agent's on `design-sync`, the one prose owner per surface (S4); the exact `edit-design` wiring that spawns the auditor on `design-sync` is settled in the `edit-design` rework."
- But the `edit-design` rework (the referent) does **not** wire it. `SKILL.md:191-197` classifies `design-sync` as an interactive kind, and `SKILL.md:459-462` states interactive kinds "run one review role — the cold `comprehension-review` gate alone. There is no author spawn ... **no auditor**, and no second check." A grep for `design-sync` against the auditor/creation path in `SKILL.md` returns nothing.

So on `design-sync` the prose axis runs on neither reviewer — the exact "never neither" violation S4 forbids, and exactly the A3 risk the track file's Plan-of-Work step 2 flagged ("The de-warm must not leave `design-sync` with the prose axis on neither reviewer ... it lands at exactly one owner (S4)"). The de-warm half (Step 1) moved the axis off the comprehension reviewer for `design-sync`; the rework half (Step 2) left `design-sync` interactive without a compensating auditor spawn, so the cross-reference in `design-document-rules.md` resolves to a wiring that does not exist. The same `SKILL.md:459-462` claim that the interactive comprehension read is "unchanged in substance, only re-pointed onto the `comprehension-review` agent" is also inaccurate for `design-sync`: substance changed, because the pre-de-warm `design-review.md` carried the prose axis on `design-sync` and the de-warmed one drops it.

Suggestion: align the two sides. Either (a) route `design-sync` through a path that spawns the `readability-auditor` (give `design-sync` its own scoped auditor spawn so S4 holds with the auditor as owner, matching `design-document-rules.md`), or (b) if `design-sync` is meant to keep the prose axis on the comprehension reviewer, restore a `design-sync`-scoped prose block on `comprehension-review`/`design-review.md` and correct the three "auditor owns it on `design-sync`" / "runs it nowhere including `design-sync`" restatements to match. Until one side moves, `design-document-rules.md`'s "settled in the `edit-design` rework" pointer is dangling. This is the documented Phase-4 / S4-reconciliation surface, not a phantom; flagging the cross-file disagreement, not picking the resolution.

### WC2 [suggestion] new role tokens absent from every workflow TOC Roles column

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/design-author.md` (line 16), `readability-auditor.md` (line 16), `absorption-check.md` (line 16).
- Axis: glossary and term consistency (TOC role-token registration).
- Cost: latent fragility — a future workflow section authored for these readers would have no registered token to gate it to; benign today.

Each new agent's TOC-protocol header declares a role token: `design-author` → `author`, `readability-auditor` → `reviewer-readability`, `absorption-check` → `reviewer-absorption`. A grep across `.claude/workflow/`, `.claude/skills/`, and the staged tree finds none of these three tokens in any TOC `Roles` column or inline `roles=` ref (the fourth token, `comprehension-review`'s `reviewer-design`, is pre-existing and registered widely). The TOC protocol matches a row when its `Roles` contains the agent's role; a token that appears in zero rows can only ever match `Roles=any` rows.

This breaks nothing in this track: the three agents are directed to read `house-style.md` (an output style, outside the `.claude/workflow/` TOC protocol), the research log, the draft, and their slice — none of which is a role-gated workflow section, and none carries an inline ref with a roles suffix that would exclude these tokens. The referent (a role-gated section meant for these readers) simply does not exist yet. Record it so a later maintainer who adds a workflow section for the author or auditor registers the token in that file's TOC rather than silently gating these readers out. Suggestion: when Track 2 or a later change first needs one of these roles to read a role-gated workflow section, add the token to that section's TOC `Roles` cell; no change needed now.

### WC3 [suggestion] §Tools used TOC cell and inline summary drifted from the multi-agent body

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 40 TOC cell, line 927 inline `<!-- summary= -->`).
- Axis: cross-file rule restatement (TOC/summary vs body).
- Cost: cosmetic — the §Tools used summary describes a single cold-read spawn while the body now spawns four review roles; the reindex gate cannot catch it because the two stale cells agree with each other.

The rework updated the §Workflow, §Step 1, §Step 4, §Step 5, and §Step 6 TOC cells to the two-shape loop, but the §Tools used row was left at its pre-rework wording. Both the TOC cell (line 40) and its mirrored inline comment (line 927) read: "The tools the skill invokes: the mechanical-check script, Edit/Write, and the cold-read sub-agent spawn." The §Tools used body (line 938-942) now spawns `design-author`, the `readability-auditor` fan-out, the `absorption-check`, and the `comprehension-review` gate. `workflow-reindex.py --check` validates cell↔comment equality and would pass (both stale strings match each other), so the cell-vs-body drift goes uncaught by the gate the track's acceptance relies on. Suggestion: update both the line-40 TOC cell and the line-927 inline summary to name the review-role spawns (e.g. "... and the review-role spawns — author, readability auditor, absorption check, comprehension gate") so the summary matches the body.

## Evidence base
