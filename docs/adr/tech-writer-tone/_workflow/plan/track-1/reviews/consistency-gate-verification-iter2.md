<!-- workflow-sha: 3c57672e9b12b504d5feb5134ca96be891b3ffbc -->
<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR3, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/plan/track-1.md:111, anchor: "### CR3 ", cert: none, basis: "Context/Purpose still list ai-tells + house-conversation as free-inheritors that restate no rule, contradicting D1, in-scope items 2 & 11, and Validation:150 — the same wrong grouping CR1 fixed only for the dsc checker"}
verdicts:
  - {id: CR1, verdict: VERIFIED}
  - {id: CR2, verdict: DEFERRED}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verdicts

Tooling: workflow-machinery change (Markdown + Python under `.claude/**`). All
references verified with Read/Grep against the live tree; the
`_workflow/staged-workflow/` subtree does not exist yet, so every `.claude/**`
read resolved to the live file (§1.7(d)). No Java symbols, no PSI needed.

#### Verify CR1: dsc-ai-tell checker wrongly grouped with the free-inheriting consumers
- **Original issue**: `design.md` § Overview and its echoes in `track-1.md`
  `## Purpose / Big Picture` (~:15) and `## Context and Orientation` (~:111)
  claimed all four `house-style.md` consumers "inherit the deletion for free /
  at once," wrongly including the `dsc-ai-tell` regex checker, which holds
  independent hard-coded regexes in `design-mechanical-checks.py` needing
  explicit deletion.
- **Fix applied**: track-side Edit to `track-1.md`. `## Purpose` now reads
  "Three prose surfaces cite its rules by section without restating them; a
  fourth, the `dsc-ai-tell` regex checker, mirrors a subset of them as its own
  hard-coded regexes … the three prose consumers inherit the deletion for free
  while the regex checker takes an explicit same-change deletion." `## Context`
  now splits the four consumers: three "cite it by section name and restate no
  rule of their own," and "The fourth, the `dsc-ai-tell` regex checker … mirrors
  a subset of the rules as its own hard-coded regexes, so a removed rule needs
  an explicit same-change deletion rather than free inheritance."
- **Re-check**:
  - Search/trace performed: Grep + Read over `track-1.md:15`, `:111`, `:113`
    (the `dsc-ai-tell` term definition), and D1 (`:38`); confirmed the checker's
    own-regex nature against `design.md` removal table (`:254`–`:261`) and the
    live `design-mechanical-checks.py` regex names cited in D1/item 7.
  - Code location: `track-1.md:15`, `:111`.
  - Current state: the `dsc-ai-tell` checker is now correctly described as a
    subset-mirroring regex holder that takes an explicit same-change deletion,
    not a free inheritor. Consistent with the `dsc-ai-tell` glossary paragraph
    (`:113`, "a regex, its assertion, and its fixture line form one unit"), D1's
    triple-deletion caveat (`:39`), Plan of Work (`:124`), Validation (`:147`),
    and the removal-completeness invariant (`:202`).
- **Regression check**: re-scanned the two modified sentences against D1, the
  in-scope file list, and Validation. The dsc carve-out is clean. **But the same
  rewrite left `ai-tells/SKILL.md` and `house-conversation.md` inside the
  "inherit for free" group, where they do not belong — a shifted residual of the
  identical error CR1 corrected for the dsc checker. Filed as CR3 below.**
- **Verdict**: VERIFIED (the dsc-specific carve-out is correctly applied; the
  broader manifestation of the same grouping error is raised as new finding CR3).

#### Verify CR2 (DEFERRED): design.md:630 frames a non-existent TL;DR-shape test as an existing rename site
- **Rejection reason**: `design.md` is frozen this iteration (Phase 2 does not
  mutate it); the `design.md:630` wording — listing "the `dsc` test suite where
  it pins the TL;DR shapes" among the rename sites — is a Phase-4
  `design-final.md` reconciliation item. No track edit: `track-1` item 10 already
  plans the real work correctly (a *new* shape test).
- **Downstream check**: verified that leaving `design.md:630` as-is introduces no
  inconsistency in the live (track) artifacts.
  - No existing test pins the section TL;DR *shape*: Grep for
    `section_has_tldr` / `MANDATORY_OR_FORM` under `.claude/scripts/tests/`
    returns nothing; the only `TL;DR` occurrences in tests are content lines in
    the D11 footer fixtures and `dsc-ai-tell-fixture.md`, none of which assert
    the TL;DR/Summary shape regex. So CR2's premise (no existing shape test)
    holds.
  - `track-1.md` item 10 (`:181`) plans "dsc shape tests — add both-spellings
    acceptance cases for the Summary rename … (new file or an extension; Phase A
    decides placement)", i.e. a *new* test. Corroborated by Plan of Work (`:126`,
    "pin the acceptance with new shape tests"), Validation (`:148`), and the
    both-spellings-acceptance invariant (`:203`). No track section frames the
    shape test as pre-existing, so the deferred `design.md:630` wording collides
    with nothing on the live-artifact side.
- **Verdict**: DEFERRED (no action needed; rejection-to-defer is sound, no
  downstream inconsistency).

## Findings

### CR3 [should-fix] — `ai-tells/SKILL.md` and `house-conversation.md` still miscategorized as free-inheriting consumers

**Location**: `track-1.md` `## Context and Orientation` (:111) and its echo in
`## Purpose / Big Picture` (:15).

**Classification**: mechanical.

**Issue.** The CR1 fix carved the `dsc-ai-tell` checker out of the "inherit for
free" group but left `ai-tells/SKILL.md` and `house-conversation.md` inside it.
`## Context` names all three prose consumers — "the `ai-tells` skill …, the
cold-read prompt in `design-review.md`, and … `house-conversation.md`" — and
asserts they "cite it by section name and restate no rule of their own. Because
these three hold no copy of a rule, deleting a rule at the source removes it from
all three at once." `## Purpose` echoes it ("the three prose consumers inherit
the deletion for free"). That claim is false for two of the three:

- `house-conversation.md:23` lists removed rules **by name** in its AI-tell
  subset ("negative parallelism ('not X, but Y'), roundabout negation, …,
  closing connectives"). Deleting those rules from `house-style.md` does not
  remove these lines; `track-1` in-scope **item 2** (:173) requires "remove the
  mirrored disguise-rule lines (D1)."
- `ai-tells/SKILL.md:3` — the **always-loaded** `description:` frontmatter —
  names "negative parallelism like 'It's not X, it's Y'", "Title Case headings",
  and "closing phrases like 'In conclusion'"; the body catalogue at `:24` names
  "closing phrases". `track-1` in-scope **item 11** (:182) requires editing the
  description + catalogue (D1), and Validation `:150` asserts those names "are
  gone."
- Only `design-review.md` truly cites `house-style.md § <heading>` on demand and
  restates no rule (verified: `design-review.md:276`, `:319`–`:322`), so only it
  inherits the deletion for free.

**Contradicts** the track's own authoritative sections: D1 (:38) lists
"the always-loaded `ai-tells/SKILL.md` `description:` frontmatter plus its
catalogue … [and] `house-conversation.md`" among "the mirrored consumers touched
in the same change"; in-scope items 2 and 11; and Validation `:150`. This is the
identical grouping error CR1 fixed for the dsc checker — the frozen `design.md`
§ Overview (:28–:31) commits the same error for all four (its own removal table
at `:254`–`:261` lists `ai-tells/SKILL.md` and `house-conversation.md` as
mirrored consumers), so the `design.md` § Overview correction rolls into the
same Phase-4 reconciliation bucket as CR1's dsc deferral. The **track-side**
prose, already corrected for dsc, should extend the same carve-out to these two
now.

**Failure scenario.** A reader taking the orientation prose at face value
concludes `ai-tells/SKILL.md` and `house-conversation.md` need no D1 edit
("deleting at source removes it from all three at once"), directly against D1 and
the actionable in-scope items 2 & 11 that do edit them. If the prose is trusted
over the file list, three of the six removed rules keep leaking through the
always-loaded `ai-tells` description and the `house-conversation` subset list —
the always-loaded surface D1's caveat (:39) flags as the costliest to leave.

**Justification.** Objective cross-reference mismatch, not a judgment call: the
Context/Purpose free-inheritance claim is contradicted by the same file's D1,
in-scope items 2 & 11, Validation `:150`, and by the live files
(`house-conversation.md:23`, `ai-tells/SKILL.md:3` and `:24`). Should-fix rather
than blocker — the actionable in-scope list and D1 remain correct, so an
implementer following them still edits both files; the harm is a false
orientation claim that contradicts the plan's own decision record and file list.

**Suggested fix.** Extend the CR1 carve-out: state that only `design-review.md`
cites `house-style.md` by section and inherits the deletion for free, while
`ai-tells/SKILL.md` (description + catalogue) and `house-conversation.md` (subset
list), like the `dsc-ai-tell` checker, hold their own copies and take explicit
same-change deletions. That is, one prose consumer inherits; the other consumers
need explicit edits.

## Summary

**PASS** — no new blockers.

- CR1: VERIFIED — the `dsc-ai-tell` carve-out is correctly applied to `track-1.md`
  `## Purpose` and `## Context`; the frozen `design.md` § Overview correction is a
  Phase-4 reconciliation item as scoped.
- CR2: DEFERRED — rejection-to-defer is sound; no existing test pins the TL;DR
  shape, `track-1` item 10 plans a new shape test, and the deferred `design.md:630`
  wording collides with nothing on the live-artifact side.
- CR3 (new, should-fix): the same free-inheritance grouping error CR1 fixed for
  the dsc checker still stands for `ai-tells/SKILL.md` and `house-conversation.md`
  in `## Context`/`## Purpose`, contradicting D1, in-scope items 2 & 11, and
  Validation. Not a blocker (actionable sections remain correct); worth folding
  into the next fix round alongside the deferred `design.md` § Overview correction.
