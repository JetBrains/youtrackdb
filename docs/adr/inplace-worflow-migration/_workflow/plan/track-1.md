# Track 1: Stamp format and conventions

## Purpose / Big Picture
After this track lands, every reader and writer of `_workflow/**` artifacts resolves the line-1 workflow-SHA stamp format and the unstamped-artifact protocol from a single section of `conventions.md`.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Define the per-artifact `<!-- workflow-sha: ... -->` stamp format and the one-liner that computes the SHA at creation time. Document the format and the unstamped-artifact protocol (drift check short-circuits to "drift detected"; migration prompts once for a base SHA covering the unstamped set) in `conventions.md` so every reader (drift check, migration, future writers) resolves to one source of truth. Foundational — Tracks 2/3/4 depend on the spelling this track lands.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-22T15:43Z [ctx=info] Review + decomposition complete
- [x] 2026-05-22T16:06Z [ctx=safe] Step 1 complete (commit e963517bee)
- [x] 2026-05-22T16:10Z [ctx=safe] Step 2 complete (commit c391554eb6)
- [x] 2026-05-22T16:13Z [ctx=safe] Step 3 complete (commit e65d93aef0)
- [x] 2026-05-22T16:13Z [ctx=safe] Step implementation complete (3/3 steps)
- [x] 2026-05-22T18:05Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)

## Surprises & Discoveries
- [x] 2026-05-22T16:06Z Tracks 2, 3, 4a, 4b, and 5 should cite §1.6 subsection anchors directly (e.g., `§1.6(a1)` for canonical parser idioms, `§1.6(h)` for the Phase 1 walk block). The new section was structured as `### (x)` subsections precisely so anchors stay stable across future edits. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (7 findings, 7 accepted)
- [x] Adversarial: PASS at iteration 2 (12 findings, 11 accepted, 1 rejected — A8 parser tolerance; writer-side line-1 preservation per A2 fix supersedes)

## Context and Orientation

`.claude/workflow/conventions.md` is the shared rule file every other workflow doc cross-references. It already defines plan-file structure (§1.2), review iteration protocol (§1.3), tooling discipline (§1.4), and house-style mapping (§1.5). The stamp rules slot in as a new top-level section (§1.6 or equivalent) named *Workflow-SHA stamps on `_workflow/**` artifacts*, containing the format definition, the SHA computation rule at creation, the unstamped-artifact protocol (drift signal + migration prompt), and the explicit "no silent fork-point fallback" non-rule.

No other file is touched in this track. Tracks 2, 3, and 4 reference this new section from their own edits. The deliverable is a single new `##` section in `conventions.md` plus any cross-reference notes that the structural review will want (e.g., glossary entry, link from §1.2 plan-file structure to the new section).

## Plan of Work

Add a `## 1.6 Workflow-SHA stamps on _workflow/** artifacts` section to `conventions.md` (or whichever number fits the file's numbering). Render the section as subsections or a primary table with follow-on subsections, not a single inline paragraph; each lettered deliverable resolves to a precise anchor downstream tracks can cite.

The eight deliverables in §1.6:

- **(a) Format definition and writer-side position contract.** Stamp is `<!-- workflow-sha: <40-char SHA> -->` on line 1, H1 on line 2. Any tool or skill that edits a stamped artifact MUST preserve the stamp's line-1 position. `edit-design` mutations (`section-add`, `section-move`, `structural-rewrite`, `content-edit`, etc.) treat line 1 as immutable. The position-preservation rule is the runtime complement to I4; together they keep the stamp parseable by the canonical regex in (a1) after any mutation sequence.

- **(a1) Canonical parser idioms.** Two regex forms, both quoted byte-for-byte by Tracks 3, 4a, and 4b:
  - Value extraction (drift check, range derivation): `head -1 file.md | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$'`. The `workflow-sha:` anchor rejects 40-hex sequences in H1 titles like `# Backport of <SHA>: <description>` (verified by reproduction during Phase A adversarial review).
  - Presence check (lockstep advance, validators): `head -1 file.md | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'`.
  - Explicitly NOT canonical: `head -1 | grep -oE '[0-9a-f]{40}'` without the `workflow-sha:` anchor returns false positives on H1 titles containing a 40-hex run.

- **(b) SHA computation at artifact-creation time.** `WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills)"`. When the path-scoped log returns the empty string (no commit in HEAD's ancestry has touched `.claude/workflow/` or `.claude/skills/`), the writer falls back to `git rev-parse HEAD`. In every repo where workflow tooling exists in any reachable commit, the fallback never fires; documenting it keeps the rule portable.

- **(c) Stamp range definition.** `BASE_SHA..HEAD`, where `BASE_SHA` is the oldest stamp reachable from HEAD via a pairwise `git merge-base` fold across the set of stamps. HEAD is the upper bound because the branch is a self-contained capsule (workflow commits enter only via explicit rebase or merge of develop; see plan D10). A fatal `git merge-base` failure (a stamp pointing at a `git gc`-pruned commit, or two stamps with no reachable common ancestor in the local repo) routes to the unstamped-artifact bootstrap prompt: the affected stamp is treated as unstamped for the rest of the session, and the user is asked to supply a base SHA covering it. The recovery path keeps the gate usable when stamps drift onto commits the local repo no longer has.

- **(d) Unstamped-artifact protocol.** When any artifact in the active plan's `_workflow/` is unstamped, the drift check signals drift unconditionally (no fold, no range computation). The migration prompts the user once for a base SHA, validated by `git rev-parse --verify "$SHA^{commit}" && git merge-base --is-ancestor "$SHA" HEAD`. The `^{commit}` peel rejects tag and ref names; only commit SHAs pass. Short prefixes are accepted, but the writer canonicalizes the input to the 40-char form via the rev-parse stdout before storing it in any stamp file (the stamp regex `[0-9a-f]{40}` rejects shorter values on subsequent parse). The prompt records the user's best guess; the per-commit replay loop's halt-on-ambiguity is a partial safety net, not a guarantee. A too-old SHA silently bloats the replay range; a too-new SHA silently skips needed migrations. Document the failure modes so debug sessions have a starting point.

- **(e) Non-rule: no silent auto-computed default.** No auto-computed reference (fork-point with develop, HEAD itself, `git merge-base origin/develop HEAD`, or any other variant) is a silent default for unstamped artifacts. Under rebase any such reference shifts forward, marking unstamped artifacts as already-current and silently skipping the migration; the gate's "no drift" report would then mask data loss the user cannot detect (see plan D8 rationale).

- **(f) Stamped artifact types and exclusions.** Positive enumeration of stamped artifacts: `_workflow/implementation-plan.md`, `_workflow/design.md`, `_workflow/design-mechanics.md` (when the length trigger has fired), and every `_workflow/plan/track-*.md`. Explicitly NOT stamped: Phase 4 final artifacts (`design-final.md`, `adr.md`) because they survive merge into `develop` where per-branch migration never applies; `_workflow/design-mutations.md` because it's an append-only log whose stamp would always equal `design.md`'s and is replay-immune by virtue of the append-only contract.

- **(g) Active-plan scope.** Both the drift check and the migration operate on the plan dir the caller resolved at startup: `/create-plan <dir>`, `/execute-tracks <dir>`, or `/migrate-workflow`'s zero/one/many ladder over `docs/adr/*/_workflow/`. Cite §1.2 *Plan File Structure*'s one-plan-per-branch convention. Cross-plan folding is out of scope (D13): each plan migrates independently, and folding across plans would over-include older commits the active plan was always synced past. State explicitly that this scope rests on the one-plan-per-branch project convention, not a correctness invariant; a future change allowing multiple plans per branch would force a rethink.

- **(h) Phase 1 walk bash block.** Include the shared enumerate-and-classify block verbatim so the durable single source survives Phase 4 (`design.md` is removed in the cleanup commit; `conventions.md` is not):

  ```bash
  PLAN_DIR="docs/adr/<resolved-dir-name>"
  STAMPED_SHAS=""
  UNSTAMPED_FILES=""
  for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
                "$PLAN_DIR/_workflow/design.md" \
                "$PLAN_DIR/_workflow/design-mechanics.md" \
                "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
      SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
      if [ -n "$SHA" ]; then
          STAMPED_SHAS="$STAMPED_SHAS $SHA"
      else
          UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
      fi
  done
  ```

  Tracks 3 and 4a copy this block byte-for-byte. The coordinated-edit cost on a future format change is bounded to the writer sites enumerated in `## Interfaces and Dependencies`.

Then update §1.1 *Glossary*:

- Add a "Workflow-SHA stamp" entry pointing at §1.6.
- Amend the existing "Workflow drift" entry so its description matches post-D9/D10 semantics. Replace "the current `develop` workflow format" with "the workflow format encoded in commits reachable from HEAD" (HEAD-relative comparison; D10). Replace "Detected in turn 1 of `/execute-tracks`" with "Detected at session-start of `/create-plan` (D9) and in turn 1 of `/execute-tracks`".

And add a cross-reference from §1.2 *Plan File Structure* near the artifact list noting that each ephemeral artifact carries a line-1 workflow-SHA stamp (link to §1.6).

Order: §1.1 edits → §1.6 section → §1.2 cross-reference. Existing §1.4 spans ~140 lines; if §1.6 grows beyond comparable section length, Phase A decomposition may split into "Stamp format" (a, a1, b, f) and "Stamp range and protocol" (c, d, e, g, h). The single-section model is the default starting point.

## Concrete Steps

1. Add `## 1.6 Workflow-SHA stamps on _workflow/** artifacts` to `.claude/workflow/conventions.md` with all eight deliverables (a, a1, b, c, d, e, f, g, h) per `## Plan of Work`, rendered as subsections (not a single inline paragraph) with the Phase 1 walk bash block embedded verbatim — risk: low (default: docs / new workflow rule section in one Markdown file)  [x] commit: e963517bee
2. Update `## 1.1 *Glossary*` in `.claude/workflow/conventions.md`: add a "Workflow-SHA stamp" row pointing at §1.6, and amend the existing "Workflow drift" row to drop "current `develop` workflow format" and add `/create-plan` as a second trigger per D9/D10 semantics — risk: low (default: docs / two glossary-row edits in one Markdown file)  [x] commit: c391554eb6
3. Add a cross-reference from `## 1.2 *Plan File Structure*` to §1.6 near the artifact list in `.claude/workflow/conventions.md`, noting that each ephemeral `_workflow/**` artifact carries a line-1 workflow-SHA stamp — risk: low (default: docs / single sentence + link in one Markdown file)  [x] commit: e65d93aef0

## Episodes

### Step 1 — commit e963517bee, 2026-05-22T16:06Z [ctx=safe]
**What was done:** Appended `## 1.6 Workflow-SHA stamps on _workflow/** artifacts` to `.claude/workflow/conventions.md` with deliverables (a)–(h) rendered as nine `### (x)` subsections (a, a1, b, c, d, e, f, g, h) under a short orienting lead paragraph. Subsection (a1) carries the canonical value-extraction and presence-check regex idioms plus the loose form explicitly marked non-canonical. Subsection (b) carries the SHA computation one-liner with the empty-output fallback to `git rev-parse HEAD`. Subsection (c) carries the `BASE_SHA..HEAD` range with `git merge-base` failure recovery into the unstamped-bootstrap prompt. Subsection (d) carries the `^{commit}` peel, short-SHA canonicalization, and too-old / too-new failure modes. Subsection (e) carries the no-silent-auto-default non-rule. Subsection (f) carries the positive stamp list plus Phase 4 and `design-mutations.md` exclusions. Subsection (g) carries the active-plan-scope rule (D13) with the one-plan-per-branch citation. Subsection (h) embeds the Phase 1 walk bash block verbatim including the `<resolved-dir-name>` placeholder.

**What was discovered:** Downstream tracks (2, 3, 4a, 4b, 5) should cite §1.6 subsection anchors directly (e.g., `§1.6(a1)`, `§1.6(h)`) rather than re-quoting the prose. The `### (x)` subsection rendering choice freezes the anchor surface; a future change from subsections to a table would invalidate every downstream citation. Track 6 also reads from §1.6 (D9 gate at /create-plan startup quotes the same canonical parser idioms).

**Key files:**
- `.claude/workflow/conventions.md` (modified)

### Step 2 — commit c391554eb6, 2026-05-22T16:10Z [ctx=safe]
**What was done:** Inserted a "Workflow-SHA stamp" row into the §1.1 glossary table of `.claude/workflow/conventions.md` between the existing "Mid-phase handoff" and "Workflow drift" rows. The new row defines the line-1 HTML-comment stamp format and points at §1.6. Amended the existing "Workflow drift" row per D9/D10: replaced "the current `develop` workflow format" with "the workflow format encoded in commits reachable from HEAD"; replaced the sole "Detected in turn 1 of `/execute-tracks`" trigger with "Detected at session-start of `/create-plan` (D9) and in turn 1 of `/execute-tracks`".

**Key files:**
- `.claude/workflow/conventions.md` (modified)

### Step 3 — commit e65d93aef0, 2026-05-22T16:13Z [ctx=safe]
**What was done:** Added a one-paragraph cross-reference to §1.2 *Plan File Structure* of `.claude/workflow/conventions.md`, sitting between the "tracked / Phase 4 cleanup" paragraph and the "on-disk shape may shift" drift-detection paragraph. The paragraph names the four ephemeral artifact kinds (`implementation-plan.md`, `design.md`, optional `design-mechanics.md`, each `plan/track-*.md`), states the line-1 workflow-SHA stamp invariant, and links to §1.6 for the full rule plus the §1.1 glossary row for the one-line definition. The validation grep `grep -n "Workflow-SHA stamp"` now returns matches in §1.1, §1.2, and §1.6.

**Key files:**
- `.claude/workflow/conventions.md` (modified)

## Validation and Acceptance

After Track 1 lands, the following must hold:

- `grep -n "Workflow-SHA stamp" .claude/workflow/conventions.md` returns at least one match in §1.1 (glossary) and one match in the new §1.6 (or equivalent number).
- §1.6 is rendered as subsections or a primary table plus follow-on subsections, not a single run-on paragraph. Each lettered deliverable resolves to a precise anchor downstream tracks can cite.
- §1.6 carries every deliverable (a)–(h): format definition + writer-side line-1 position contract; canonical parser idioms (value extraction + presence check) with the loose form marked non-canonical; SHA computation at creation with the empty-output fallback to `git rev-parse HEAD`; stamp range definition (`BASE_SHA..HEAD`) with the merge-base-failure recovery route; unstamped-artifact protocol naming the `^{commit}` peel and the short-SHA canonicalization; non-rule against silent auto-computed defaults; stamped artifact positive list + exclusions (Phase 4 artifacts, `design-mutations.md`); active-plan scope rule (D13) acknowledging the project-convention basis; Phase 1 walk bash block verbatim.
- The canonical value-extraction regex `head -1 file.md | grep -oE 'workflow-sha: [0-9a-f]{40}'` (with the optional two-stage tail `| grep -oE '[0-9a-f]{40}$'`) appears at least once in §1.6 as the canonical form. The loose form `head -1 | grep -oE '[0-9a-f]{40}'` (no `workflow-sha:` anchor) is either absent or explicitly marked non-canonical.
- §1.1 *Glossary* "Workflow drift" entry no longer references "the current `develop` workflow format" or "turn 1 of `/execute-tracks`" as the sole trigger. Description matches post-D9/D10 semantics.
- §1.1 *Glossary* contains a new "Workflow-SHA stamp" entry pointing at §1.6.
- §1.2 *Plan File Structure* carries a cross-reference to §1.6 near the artifact list.
- No other workflow file is touched in this track (Tracks 2/3/4/5/6 own those edits).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

All three steps are single-file Markdown additions to `.claude/workflow/conventions.md`. Each step is idempotent in two senses: re-running the implementer against the same input produces the same diff, and a partial application (committed Step 1 but not Step 2) leaves the file in an internally consistent state (Step 1's §1.6 stands alone; Step 2's §1.1 entry and Step 3's §1.2 cross-reference both link to §1.6, so committing them out of order would create dangling links in the intermediate state, hence the chosen order).

Recovery from a failed step: `git reset --hard HEAD` restores the working tree; the step re-runs from the prior committed state. No external state to clean up (no migrations applied, no caches invalidated, no SPI registrations to undo).

Spotless does not enforce Markdown formatting; the build does not exercise `.claude/workflow/**` content. Validation is the grep-based assertion list in `## Validation and Acceptance` plus the track-level code-review pass in Phase C.

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/conventions.md` — one new §1.6 section, one new §1.1 glossary entry, one updated §1.1 glossary entry ("Workflow drift" amendment), one new §1.2 cross-reference.

**Out-of-scope files:**
- All `.claude/skills/**` SKILL bodies (Tracks 2 and 4)
- `.claude/workflow/workflow-drift-check.md` (Track 3)
- `.claude/workflow/self-improvement-reflection.md` (Track 5)
- `_workflow/design.md` and `_workflow/design-mechanics.md` — Track 1 does not amend the design's loose-regex examples at design.md:193; §1.6 is the durable single source, and downstream tracks quote §1.6, not the design draft.

**Inter-track dependencies:**
- This track has no upstream dependencies.
- Tracks 2, 3, 4, 5 all read from §1.6 of `conventions.md` and quote the stamp format / canonical parser idioms / SHA computation rule / Phase 1 walk bash / unstamped-artifact protocol verbatim. A change to the format spelling after Track 1 lands requires a coordinated edit across all downstream writer sites (Track 2 SKILL templates, Track 3 drift-check `sed`, Track 4b lockstep + final-batch `sed`). The blast radius is bounded; a future grep enumerating every literal writer site can land in §1.6 once Tracks 2-4 commit their writer sites.

**External interfaces:**
- `git` CLI invocations: `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` (SHA computation at creation), `git merge-base <a> <b>` (pairwise fold over stamps), `git log $BASE_SHA..HEAD -- workflow paths` (drift / migration range), `git merge-base --is-ancestor "$SHA" HEAD` (unstamped-artifact bootstrap validation), `git rev-parse HEAD` (migration's final-batch stamp value). All POSIX-portable and already used elsewhere in `conventions.md` examples. The `git fetch origin develop` and `git merge-base origin/develop HEAD` invocations referenced by older drafts are deliberately absent — the branch is a self-contained capsule per D10.

## Base commit
77eee0a080be806b35bc94e5b82dace956820f39
