<!--MANIFEST
dimension: workflow-context-budget
prefix: WB
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index: []
-->

## Findings

No always-loaded surface, load-on-demand discipline, or instant per-operation
consumption impact in this diff, and the workflow-reindex `--check` run on the
three changed workflow-machinery files (`design-review.md`,
`design-document-rules.md`, `readability-feedback/SKILL.md`) exited 0 with no
findings. Detail by axis:

- **Always-loaded surface (Axis 1)** — untouched. The `readability-feedback`
  SKILL.md change edits only the body (`## Rule sync map` bullet at line 47 and
  the audit sub-agent prompt's STEP 1 / STEP 4 text at lines 70 / 76); the
  always-loaded `description:` frontmatter field is byte-identical. No CLAUDE.md
  change, and no CLAUDE.md pointer references any changed file. The
  `design-mechanical-checks.py` path appears in `house-style-write-reminder.sh`'s
  blacklist, but that hook is wired to **PreToolUse**, not SessionStart — it
  prints nothing on a normal turn and nothing at session start, and the hook
  file itself is not in this diff.
- **Load-on-demand discipline (Axis 2)** — no structural drift.
  `design-review.md` grew ~47 lines (under the >100-line trigger) and the added
  `### Prose AI-tell additions` block is a reviewer-instruction cold-read scan
  procedure, the correct content for a load-on-demand workflow prompt, not
  inline rules / recipes that belong in CLAUDE.md. `design-document-rules.md` is
  a single table-cell rewrite (nine→eleven enumeration). No always-loaded
  pointer to a changed file is broken.
- **Instant per-operation consumption (Axis 3)** — no inflation. The new
  cold-read block loads on the same fire as the 450-line prompt it lives in
  (~10% of an already-loaded surface, not a new orchestrator-side read), points
  at `house-style.md § <Section>` rather than inlining rule text, and adds no
  sub-agent dispatch, full-file read, or `/tmp`-staging gap. The two new regexes
  run inside `design-mechanical-checks.py` under `edit-design`; regex matching
  pulls nothing into orchestrator context, and both are line/paragraph-scoped
  scans with bounded alternations (curated closed sets, no catastrophic
  backtracking).

## Evidence base
