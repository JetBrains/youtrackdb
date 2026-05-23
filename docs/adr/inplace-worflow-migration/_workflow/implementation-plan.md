# In-Place Workflow Migration

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Move `/migrate-workflow` from a develop-worktree-driven, two-worktree dance into a single in-branch operation. Replace the fork-point heuristic with per-artifact workflow-SHA stamps so the migration's "what range do I replay?" decision is data-driven and survives rebases. Add a self-improvement reflection step to the skill so the per-session frictions feed the same `dev-workflow` queue that `/execute-tracks` already populates.

The skill currently requires the user to switch to a `develop` worktree, run the migration against a separate branch worktree, and then rebase the migrated workflow files back into the branch's working topology. Each handoff between worktrees is a place to forget which worktree owned which file. The replacement runs entirely inside the branch's own worktree; the migration's input range is read from stamps on the artifacts it migrates, and the resulting edits land directly on the branch where they're needed.

### Constraints

- **Branch is a self-contained capsule.** Workflow commits enter the branch's view only via explicit rebase or merge. Drift detection and migration both range over `BASE_SHA..HEAD`, scoped to the active plan's `_workflow/` directory, never against `origin/develop`; no `git fetch` is part of the gate. See D10 (range bound) and D13 (per-plan scope).
- **Backward compatibility with legacy artifacts.** Branches alive today have no stamps. The drift check treats unstamped-artifact presence as drift unconditionally and routes to migration; the migration prompts the user once for a base SHA covering every unstamped artifact in the active plan, then proceeds. The first successful migration of a legacy branch writes stamps to all its artifacts as a side effect; no separate backfill script. There is no silent fallback to any auto-computed reference — see D8 for why.
- **No Phase 4 stamping.** `design-final.md` and `adr.md` survive merge into `develop` and never get re-migrated. Stamping them is dead weight; the parser explicitly scopes to `_workflow/**`.
- **Markdown-only change.** No Java, no scripts, no hooks beyond what already exists. Bash one-liners embedded in the relevant SKILL / workflow files do the SHA reads.
- **No silent fallback to grep for symbol audits.** This work is markdown-only — PSI doesn't apply. All edits are file-level; no symbol-level audits.
- **House style applies to every Markdown surface touched.** SKILL bodies, workflow rule files, and the artifact templates this work edits all live under §1.5's full Tier-A coverage. AI-tells, em-dash discipline, banned vocabulary, BLUF leads — all enforced as part of each track's commit.

### Architecture Notes

#### Component Map

```mermaid
flowchart LR
    subgraph Writers["Stamp writers"]
        CP["create-plan SKILL"]
        ED["edit-design SKILL"]
    end

    subgraph Artifacts["Stamped artifacts (_workflow/)"]
        IPM["implementation-plan.md"]
        DM["design.md / design-mechanics.md"]
        TF["plan/track-N.md"]
    end

    subgraph Readers["Stamp readers"]
        WDC["workflow-drift-check.md"]
        MW["migrate-workflow SKILL"]
    end

    subgraph Reflection["Self-improvement"]
        SIR["self-improvement-reflection.md"]
    end

    CP -->|stamp on create| IPM
    CP -->|stamp on create| TF
    CP -->|invokes at startup| WDC
    ED -->|stamp on create| DM
    WDC -->|read + normalize stamps| IPM
    WDC -->|read + normalize stamps| TF
    WDC -->|read + normalize stamps| DM
    MW -->|read + stamp to HEAD| IPM
    MW -->|read + stamp to HEAD| TF
    MW -->|read + stamp to HEAD| DM
    MW -->|invoke at end| SIR
```

- **`create-plan` SKILL** — emits the stamp on line 1 of `implementation-plan.md` and each `plan/track-N.md` it creates (Track 2). Invokes the drift gate at session start so re-invocation after the user rebases the branch onto a newer develop catches post-rebase drift before any research investment (Track 6). One-line bash helper computes the SHA: `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills`.
- **`edit-design` SKILL** — emits the stamp on `design.md` (phase1-creation) and `design-mechanics.md` (length-trigger-crossing). Stamp updates only on migration replay, never on subsequent mutation kinds (`content-edit`, `section-add`, etc.). Touched in Track 2. `design-mutations.md` is deliberately excluded; see the Non-Goals section.
- **`workflow-drift-check.md`** — parser walks every `_workflow/**` artifact in the active plan's `_workflow/` directory (D13) and reads each line-1 stamp. Any unstamped artifact triggers drift unconditionally; the gate skips the fold and routes to migration. When every artifact is stamped, the fold runs and the gate compares `BASE_SHA..HEAD` against workflow paths (no `git fetch`). On no-drift with non-uniform stamps, normalizes every stamp to the fold result and creates a separate commit (D11). Resolutions wording updated for in-branch flow. Invoked from both `/execute-tracks` turn 1 (existing) and `/create-plan` between Step 1 and Step 1a (Track 6). Touched in Track 3.
- **`migrate-workflow` SKILL** — runs against the active plan's `_workflow/` directory (D13; one plan at a time, matching today's skill contract). Preflight refuses on develop-worktree requirement (drops) and on tracked-uncommitted or untracked files under the active plan's `_workflow/**` (D12; progress-sentinel carve-out kept). When unstamped artifacts exist, the skill prompts the user once for a base SHA covering the unstamped set, validates it, and folds it in with the stamped set. Range is `BASE_SHA..HEAD`. Per-commit replay loop unchanged in shape; after each successful replay, every stamp in the active plan advances to that commit's SHA in lockstep (crash-resume marker). A final post-loop step re-stamps every artifact in the active plan to `HEAD`'s SHA in one batch (D2). Touched in Track 4a (preflight + range derivation) and Track 4b (per-commit replay + final batch).
- **`self-improvement-reflection.md`** — gains a session-type parameter (`execute-tracks` or `migrate-workflow`) controlling the commit-clean check, phase value, and applicability text. Touched in Track 5. The migrate-workflow SKILL gains a final step that invokes it.

#### D1: Per-artifact SHA stamp, not single sentinel

- **Alternatives considered**: single `_workflow/.workflow-sha` sentinel file; mixed scheme (stamps + summary sentinel cache).
- **Rationale**: per-artifact stamps let any single stamp serve as the durable progress marker mid-migration — a `/clear` between per-commit replays leaves stamps non-uniform, and the next session reads any stamp to find the last-replayed SHA. A single sentinel loses that crash-resume property: the sentinel would have to be written atomically alongside each per-commit replay to play the same role, doubling the writer site and adding a sync-or-corrupt failure mode. Secondary benefits: stamps survive file copies, isolated re-creation carries provenance, the user's framing was explicit ("each workflow artifact has a SHA").
- **Risks/Caveats**: marginally more parsing work in the drift check and migration. Cost is one `head -1` per artifact — negligible.
- **Implemented in**: Track 1 (format), Track 2 (writers), Track 3 (drift-check reader), Track 4a (migration reader at range derivation), Track 4b (migration writer in per-commit loop and final batch).
- **Full design**: `design.md` §"Core Concepts" + §"Stamp range computation"

#### D2: Lockstep per-commit advance + final stamp-to-HEAD batch

- **Alternatives considered**: advance only the stamps of artifacts a given commit edited; skip per-commit advance entirely (only stamp to HEAD at end).
- **Rationale**: per-commit lockstep advance preserves crash-resume (next session reads any stamp; if it equals HEAD the migration completed, otherwise replay resumes from where the stamps point). A final post-loop step then re-stamps every artifact to `HEAD`'s SHA in one batch — including artifacts every per-commit replay skipped. Final invariant: post-migration, every stamp equals `git rev-parse HEAD`. Per-artifact advancement creates an irregular tree of stamps; skipping the per-commit phase loses crash resumption.
- **Risks/Caveats**: an artifact untouched by any replayed commit still ends at HEAD's SHA. Correct semantics — the artifact is synced to the workflow state HEAD reflects, even when the replays didn't touch it. The HEAD-final stamp replaces the prior "last replayed commit's SHA" framing (see D10).
- **Implemented in**: Track 4b.
- **Full design**: `design.md` §"Per-commit replay and lockstep advance"

#### D3: Ephemeral artifacts only, no Phase 4 stamping

- **Alternatives considered**: stamp `design-final.md` and `adr.md` for symmetry.
- **Rationale**: Phase 4 artifacts survive squash-merge into `develop`. They're git history at that point and no per-branch migration ever applies to them. Stamping them adds a writer site without a reader.
- **Risks/Caveats**: parser must explicitly scope to `_workflow/**`. One extra glob check.
- **Implemented in**: Tracks 2 and 3 (writers and reader).

#### D4: "Migrate now" ends session; user re-invokes `/migrate-workflow`

- **Alternatives considered**: run migration inline in the same session after the user picks Migrate now.
- **Rationale**: the migration skill has its own per-commit context-check loop and resume protocol. Mixing two long-running protocols in one session risks a mid-migration context warning triggering the wrong handoff. Ending the session keeps the boundary clean — matches today's contract; only the worktree changes.
- **Risks/Caveats**: one extra `/clear` for the user. Acceptable.
- **Implemented in**: Track 3.

#### D5: No legacy backfill — migration's user-prompt bootstraps stamps

- **Alternatives considered**: backfill script that walks `docs/adr/*/_workflow/` on every active branch and writes stamps en masse.
- **Rationale**: the migration's unstamped-artifact prompt (see D8) is already the bootstrap path. A separate backfill script would duplicate that prompt outside the migration loop, with the added coordination cost of remembering to run it on every active branch. The migration already runs whenever drift surfaces; bundling bootstrap into the migration keeps one path.
- **Risks/Caveats**: legacy branches with no pending drift still need to be migrated to acquire stamps. In practice every legacy branch hits drift the moment any workflow commit lands on `develop` after it was cut, so the bootstrap usually happens on the next `/execute-tracks` startup anyway. Branches that never re-engage with the workflow gate keep their unstamped state, which is fine — they're inert.
- **Implemented in**: Track 4a (migration's bootstrap prompt).

#### D6: Parameterize `self-improvement-reflection.md`, don't fork it

- **Alternatives considered**: new file `migrate-workflow-reflection.md` mirroring most of the protocol.
- **Rationale**: the reflection protocol is genuinely the same. The differences are small (commit-clean check, phase identifier, applicability sentence). Adding a session-type parameter keeps one source of truth; the alternative duplicates ~600 lines of stable protocol for the sake of three conditional clauses.
- **Risks/Caveats**: the parameter has to be plumbed through. Three call sites; the parameterization is one paragraph in the doc.
- **Implemented in**: Track 5.

#### D9: Drift gate fires at /create-plan startup, not only /execute-tracks

- **Alternatives considered**: gate only at /create-plan Step 4 (just-before-write); gate uniformly at every workflow-touching skill startup; rely on the user to re-invoke after rebasing.
- **Rationale**: between planning sessions the user rebases onto a newer develop to pick up critical workflow changes; after a rebase, HEAD's history contains imported workflow commits the artifacts haven't been migrated to. Without a gate at /create-plan startup, Session B would mutate `_workflow/**` atop the drifted shape. Gating /create-plan startup catches that case before research investment. Gating at Step 4 wastes prior session work; uniform gating across every skill is overkill — /edit-design runs only inside parent skills, so transitive coverage holds.
- **Risks/Caveats**: research-only /create-plan sessions on a branch with existing artifacts pay the one-`git log` gate cost even when no writes will follow. Acceptable.
- **Implemented in**: Track 6.

#### D8: Ask user for unstamped-artifact base SHA, don't silently auto-compute

- **Alternatives considered**: silently default unstamped artifacts to `HEAD`; silently default to `git merge-base origin/develop HEAD`; silently default to fork-point with develop; halt the migration with a generic error and refuse to proceed.
- **Rationale**: any auto-computed reference fails after rebase. A legacy branch's unstamped artifacts, rebased onto a develop that has had workflow commits in the meantime, would have any auto-computed reference land at (or near) the new HEAD — and the silent fallback would then declare the artifacts already-synced, skipping the migration. The data loss is silent: artifacts stay at their unmigrated content while the drift gate reports "no drift." A one-time prompt at migration time forces the bootstrap event into the in-conversation paper trail; a silent default produces a fold result with no documented anchor for "what SHA did we assume?" The prompt does not by itself guarantee correctness: a user supplying a semantically wrong but technically valid SHA produces a fold that's only partially caught by the per-commit replay loop's halt-on-ambiguity (a too-old SHA silently bloats the replay range; a too-new SHA silently skips needed migrations). The prompt is the documentation anchor, not the safety net.
- **Risks/Caveats**: one prompt per migration session on legacy branches (a small UX cost). Mitigated by presenting the prompt only when unstamped artifacts exist — fully-stamped branches never see it. The user has to supply a meaningful SHA; if they pick wrong, the per-commit replay loop's halt-on-ambiguity contract surfaces the mismatch.
- **Implemented in**: Track 3 (drift check signals drift on unstamped-artifact presence) and Track 4a (migration prompts and validates).
- **Full design**: `design.md` §"Core Concepts" + §"Stamp range computation"

#### D7: HTML-comment stamp on line 1, before the H1

- **Alternatives considered**: YAML frontmatter; trailing-line footer; first-line H1 attribute.
- **Rationale**: `<!-- workflow-sha: <40-char SHA> -->` on line 1 is invisible in rendered Markdown, parseable with `head -1` + a grep, and gives the artifact a uniform top-of-file location no matter what the H1 says. Frontmatter is the established convention for `.claude/skills/**` SKILL bodies, so the "new convention to learn" framing is wrong — but adopting frontmatter for `_workflow/**` would force a one-shot rewrite of every existing artifact across every in-flight branch (line 1 shifts down by `---\n<keys>\n---\n`), and the migration replay against pre-frontmatter snapshots would have to special-case that line-1 shift on every commit. The HTML-comment scheme touches existing artifacts less intrusively: one new line at the top, no document-shape change otherwise. Trailing-line footer is fragile against append operations.
- **Risks/Caveats**: line 1 has to be the stamp, line 2 has to be the H1 — a writer that gets this wrong leaves a malformed file. Format check is a one-line regex; documented in Track 1.
- **Implemented in**: Track 1.
- **Full design**: `design.md` §"Core Concepts"

#### D10: Comparison range is BASE_SHA..HEAD; branch is a self-contained capsule

- **Alternatives considered**: `BASE_SHA..origin/develop` (the develop-relative comparison the existing /execute-tracks gate uses); a hybrid (compare against `origin/develop` when reachable, fall back to HEAD); compare against `git merge-base origin/develop HEAD`.
- **Rationale**: workflow commits enter the branch's view only when the user explicitly rebases (or merges develop). Until then, the branch's drift is purely a function of its own commit graph. Comparing against `origin/develop` would force a `git fetch` on every gate run and surface drift the user hasn't opted into; comparing against HEAD ties detection to the explicit rebase event. The hybrid options muddy the semantics for marginal benefit.
- **Risks/Caveats**: on a workflow-modifying branch (this very plan's branch), the user's own workflow commits register as drift, triggering migration of in-progress workflow changes. Accepted as dogfood — see Track 4b's intro.
- **Implemented in**: Track 1 (range definition in conventions), Track 3 (drift check), Track 4a (migration range derivation), Track 6 (gate at /create-plan startup).
- **Full design**: `design.md` §"Stamp range computation"

#### D11: On no-drift with non-uniform stamps, normalize to fold result + auto-commit

- **Alternatives considered**: leave stamps as-is on no-drift; normalize but don't auto-commit (let the user fold the stamp change into their next commit); always normalize regardless of stamp uniformity.
- **Rationale**: when the drift gate determines no drift but artifacts carry distinct stamps (typically because they were created or last migrated at different times), normalizing every stamp to the fold result collapses future-gate computation from N-way pairwise `git merge-base` to a single-value read. The performance gain is small on today's plan sizes (~50–250 ms per gate run for a 4–6-artifact plan); the load-bearing reason for the auto-commit is auditability — the stamp change lands in git history rather than riding along with the user's next code commit, and a reviewer can see "the gate normalized stamps" as a discrete history entry. Leaving stamps as-is is also workable; the chosen approach future-proofs the gate against larger plans and against future `_workflow/**` schema changes that shift stamps around. Deferring the commit risks the stamp change tangling with the user's next code commit.
- **Risks/Caveats**: an extra commit appears on the branch on a no-drift gate run with non-uniform stamps. One commit per such run; branches with already-uniform stamps see none. The auto-commit must verify that nothing outside the stamp lines changes in the diff before committing (refuses otherwise to avoid swallowing unrelated edits).
- **Implemented in**: Track 3.
- **Full design**: `design.md` §"Workflow" → "Drift detection at session startup"

#### D12: Migration preflight refuses on uncommitted or untracked `_workflow/**` state

- **Alternatives considered**: silently stash; warn and continue; pure clean-tree check across the whole repo (today's behavior, modulo progress-sentinel).
- **Rationale**: the migration mutates files under `_workflow/**` and commits them. Uncommitted edits or untracked files in that subtree would either get clobbered by the migration's writes or get pulled into the migration's commit boundaries unintentionally. Stashing is destructive (the user might not realize their stash got popped on top of migrated content); warn-and-continue normalizes around the failure mode rather than preventing it. The whole-repo clean check is too strict — unrelated edits under `core/` or `server/` have no bearing on the migration.
- **Risks/Caveats**: the progress-sentinel carve-out remains so the migration can manage its own transient file. Users with unfinished planning work under `_workflow/**` see a refusal until they commit, stash, or remove those files.
- **Implemented in**: Track 4a.

#### D13: Drift detection and migration scope to the active plan directory, not the whole branch

- **Alternatives considered**: walk every `docs/adr/*/_workflow/` on the branch and fold their stamps together; restrict only the migration to one plan while keeping the drift check branch-wide.
- **Rationale**: each plan directory is migrated independently. Folding stamps across plans yields a `BASE_SHA` that's older than the active plan needs, inflating the replay range with commits the active plan was always synced past. The session itself is already plan-scoped — `/create-plan <dir>` and `/execute-tracks <dir>` operate on one plan — and today's `/migrate-workflow` already targets exactly one plan (prompts the user to pick when multiple plan directories exist on the branch; see SKILL.md Step 4). A branch-wide drift check would surface drift the migration that's supposed to resolve it cannot act on as a unit. Convention on this project is one plan dir per branch; the rare multi-plan-per-branch case sees drift in non-active plans only when the user invokes a session against them.
- **Risks/Caveats**: a user on a multi-plan branch who runs a session against plan A doesn't learn about drift in plan B until they invoke a session against plan B. Notification is delayed, not lost; data integrity holds.
- **Implemented in**: Tracks 3 (drift check), 4a (migration range derivation), 4b (migration replay), 6 (gate at `/create-plan` startup). Track 1 defines the active-plan scope inline in `conventions.md` §1.6 so the drift check and the migration cite one source of truth.
- **Full design**: `design.md` §"Stamp range computation"

### Invariants

- **I1**: Every `_workflow/**` artifact stamped by Track 2 carries `<!-- workflow-sha: <40-char SHA> -->` on line 1, with the H1 on line 2.
- **I2**: At the moment the migration's final stamp-to-HEAD batch (sub-step 4.8) completes, every stamped artifact in the active plan's `_workflow/` has its line-1 SHA equal to `git rev-parse HEAD`. The user's subsequent commit of the migration's working-tree changes moves HEAD forward; I2 is a snapshot at sub-step 4.8 completion, not a steady-state claim.
- **I3**: When every artifact in the active plan's `_workflow/` is stamped, the drift detection range is `BASE_SHA..HEAD`, where `BASE_SHA` is the oldest stamp reachable from HEAD — derived by folding the active plan's stamps pairwise through `git merge-base`. When any artifact in the active plan is unstamped, the drift check short-circuits to "drift detected" and the migration extends the fold input set by a user-supplied base SHA covering the unstamped set.
- **I4**: Mutations through `edit-design` (`content-edit`, `section-add`, `section-move`, `structural-rewrite`, etc.) never touch the stamp's text and never displace it from line 1. Only artifact creation, migration replay, and no-drift normalization write the stamp. Line-1 position preservation is the runtime complement to the no-touch rule; together they keep the stamp parseable from `head -1` after any mutation sequence.
- **I5**: After a no-drift gate run with non-uniform stamps in the active plan, one of two states holds: either every stamped artifact's line-1 SHA equals the fold result AND a separate commit captures the normalization, or the working tree is restored to its pre-normalization state and no commit is created (the diff-shape check refused because the working tree carries unrelated edits). The "in-between" state (stamps rewritten on disk without a normalization commit) is not reachable under correct invocation.

### Integration Points

- **`/create-plan` Step 4 templates** — stamp written at the top of `implementation-plan.md` and each `plan/track-N.md` immediately before the H1.
- **`edit-design` skill `phase1-creation`** — stamp written at the top of `design.md`; same for `design-mechanics.md` when mechanics is created during `length-trigger-crossing`. `design-mutations.md` is deliberately excluded (see Non-Goals).
- **`workflow-drift-check.md` Detection section** — replaces `FORK=$(git merge-base origin/develop HEAD)` with stamp-walking logic scoped to the active plan's `_workflow/` directory (D13); range is `BASE_SHA..HEAD` (no `git fetch`); short-circuits to "drift detected" whenever any artifact in the active plan is unstamped. On no-drift with non-uniform stamps in the active plan, normalizes every artifact's stamp in the active plan to the fold result and creates a separate commit.
- **`migrate-workflow` SKILL preflight** — refuses to start if any tracked file under the active plan's `_workflow/**` has uncommitted changes (working tree or index), or if any untracked file lives there (D13 scope). Progress-sentinel carve-out kept.
- **`migrate-workflow` SKILL Step 2** — same stamp-walking logic for range computation, scoped to the active plan's `_workflow/` (D13); range is `BASE_SHA..HEAD`. New Step 2.0 prompts the user for a base SHA covering unstamped artifacts in the active plan (when any exist). Step 4's per-commit loop advances stamps in lockstep at sub-step 4.5 after each commit's replay; sub-step 4.8 re-stamps every artifact in the active plan to `HEAD`'s SHA in one batch after the loop exits. (Step numbers follow Track 4a/4b's renumber-down; today's Step 2 is removed.)
- **`migrate-workflow` SKILL final step** — invokes `self-improvement-reflection.md` with `session-type=migrate-workflow`.
- **`/create-plan` SKILL between Step 1 and Step 1a** — invokes `workflow-drift-check.md` after reading the workflow docs and before the handoff scan. Three resolutions translate symmetrically with `/execute-tracks`: Migrate now ends the session for in-branch `/migrate-workflow`; Defer continues knowing artifacts may be drifted; Suppress same continue path without the session-end reminder.

### Non-Goals

- Stamping Phase 4 final artifacts (`design-final.md`, `adr.md`).
- Stamping `design-mutations.md`. Append-only log; its stamp would always equal `design.md`'s (same creation moment, same lockstep advance, untouched by I4). Track 2 writers and Tracks 3/4 enumerations all skip this file; schema commits affecting the log are replay-immune by the log's append-only contract.
- Backfilling stamps onto existing in-flight branches via a script.
- Refactoring the per-commit classification rules (`format`/`skill`/`rename`/`noop`) — those stay as-is.
- Extending the migration to handle non-workflow commits.
- Adding a helper script under `.claude/scripts/` — the SHA read is a one-liner inlined where needed.
- Rewriting the renames-tracker mechanism — it stays in a transient `.migration-progress` block per session (or wherever it ends up landing in Track 4a's simplified progress file).
- Modifying other phases of the workflow beyond what's strictly needed for the in-branch migration flow.

## Checklist

- [x] Track 1: Stamp format and conventions
  > Define the per-artifact `<!-- workflow-sha: ... -->` stamp format and the one-liner that computes the SHA at creation time. Document the format and the unstamped-artifact protocol (drift check short-circuits; migration prompts) in `conventions.md` so every reader (drift check, migration, future writers) resolves to one source of truth. Foundational — Tracks 2/3/4 depend on the spelling this track lands.
  >
  > **Track episode:** §1.6 of `conventions.md` is now the durable single source for the stamp format, the canonical parser idioms (value-extraction and presence-check regex), the SHA computation rule with empty-output fallback, the `BASE_SHA..HEAD` range with merge-base-failure recovery, the unstamped-artifact bootstrap protocol with bounded retry, the no-silent-auto-default non-rule, the stamped-artifact positive list (and Phase 4 / `design-mutations.md` exclusions), the active-plan scope rule (D13), and the Phase 1 walk bash block. The `### (a)`–`### (h)` subsection rendering freezes the anchor surface so downstream tracks (2/3/4a/4b/5/6) cite anchors verbatim rather than re-quoting prose. Two canonical writer patterns from Phase C land here for Tracks 2 and 4b to copy byte-for-byte: §1.6(b) ships the paired test-and-fallback `WORKFLOW_SHA` idiom (one line for the path-scoped log, one line for the empty-output fallback); §1.6(d) ships the bounded-retry validation policy (three attempts, halt via `/clear`) that Track 4a's migration preflight inherits. §1.1 *Glossary* gains a "Workflow-SHA stamp" row and amends "Workflow drift" to name both `/create-plan` (D9) and `/execute-tracks` triggers; §1.2 *Plan File Structure* gains a cross-reference paragraph and now mirrors §1.1's two-caller spelling (the §1.2 drift-detection paragraph realignment landed here rather than in Track 6 because Track 6's scope is `workflow-drift-check.md` and the `/create-plan` SKILL, not `conventions.md` §1.2).
  >
  > **Track file:** `plan/track-1.md` (3 steps, 0 failed)
  >
  > **Strategy refresh:** ADJUST — track-2.md `## Context and Orientation` and `## Plan of Work` now anchor to `conventions.md` §1.6(b)'s paired test-and-fallback idiom (path-scoped `git log` + `git rev-parse HEAD` fallback) instead of the bare one-liner, matching Track 1's byte-for-byte directive for downstream writers.

- [x] Track 2: Stamp writers
  > Update `/create-plan` and `edit-design` SKILLs to emit the line-1 stamp at every artifact-creation site. Four sites total across `implementation-plan.md`, `plan/track-N.md`, `design.md`, and `design-mechanics.md`; direct mutations leave the stamp untouched. `design-mutations.md` is deliberately excluded — see D6 and the track file.
  >
  > **Track episode:** Stamp writers are now active at every artifact-creation site. `create-plan/SKILL.md` computes `$WORKFLOW_SHA` once per session via §1.6(b)'s paired idiom and prepends `<!-- workflow-sha: $WORKFLOW_SHA -->` above the H1 in the three fenced templates (`implementation-plan.md`, `plan/track-N.md`, `design.md`); a one-line reminder above each template guards against `$WORKFLOW_SHA` leaking through `Write` verbatim. `edit-design/SKILL.md` ships an idempotency-guarded stamp directive in `phase1-creation` covering both invocation paths (direct and `target=both` from `/create-plan`), a new `length-trigger-crossing` paragraph documenting the split procedure plus the stamp prepend on the freshly-created `design-mechanics.md`, an exclusion note in Step 7 for `design-mutations.md`, and a top-of-file Stamp-discipline blockquote pinning invariant I4. The `phase4-creation` paragraph carries an explicit "skip the idempotency-guarded stamp directive" carve-out, blocking literal-followers from stamping `design-final.md` / `design-mechanics-final.md`. Plan deviation: Step 1's Decision Log resolved the dual-seed coverage choice to path (b); no fourth fenced template added to `create-plan`. Cross-track impact for Tracks 3 / 4: Phase C iteration 1 amended `conventions.md` §1.6(b) (no-HEAD precondition: skill halts rather than writes a malformed stamp) and §1.6(f) (Phase 4 exclusion list adds `design-mechanics-final.md`); neither shifts runtime semantics, but Track 3 and Track 4 implementers should re-read both subsections at their next pass.
  >
  > **Track file:** `plan/track-2.md` (3 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — §1.6(b)'s no-HEAD precondition applies only to fresh-stamp writers; Track 3's no-drift normalization rewrites existing stamps to `BASE_SHA` and never enters that path. §1.6(f)'s expanded Phase 4 exclusion list does not shift Track 3's behavior because the §1.6(h) Phase 1 walk block already excludes final artifacts. No track-3.md amendments needed.

- [x] Track 3: SHA-aware drift check
  > Rewrite the Detection section of `workflow-drift-check.md` to walk per-artifact stamps in the active plan's `_workflow/`, compare against HEAD (no `git fetch`), and route to in-branch migration. On no-drift with non-uniform stamps, normalize and commit (D11). Track file carries the bash and the Skip-conditions detail.
  >
  > **Track episode:** `workflow-drift-check.md` now reads line-1 stamps from every artifact under the active plan's `_workflow/` (D13), folds the stamp set pairwise to derive `BASE_SHA`, runs `git log $BASE_SHA..HEAD` against workflow paths with no `git fetch`, and routes unstamped artifacts unconditionally to drift via Phase 2's short-circuit. The Resolutions prompt template, Defer sub-section, intro paragraph, and § After the choice are all HEAD-relative; the develop-worktree re-invocation language is gone. A no-drift normalization sub-section rewrites every stamp in the active plan to `BASE_SHA` on non-uniform stamps and commits the change separately, with a two-pronged diff-shape guard (`git diff -U0` hunk-header check + `git status --porcelain` cross-check) that restores via `git checkout` on mismatch. Skip conditions all scope to the active plan dir. Phase C iterations (2/3 used) closed two silent over-range failure paths in Phase 2 (F1 wired `git merge-base` failure recovery + both-arrays-empty silent skip per `conventions.md` §1.6(c)/(h)), pinned `exit 0` / `exit 1` normalization semantics (F3), defined the unstamped-short-circuit downstream rendering rules in §Resolutions and §Defer (F4 + F11), bounded the Resolutions re-prompt at three attempts per §1.6(d) (F5), and closed the Defer recovery dead end (F11 routes the unstamped variant to `/migrate-workflow` directly). Plan deviation: F6 widened Track 3 scope by one cross-file paragraph; `workflow.md` lines 416-427 ("What to do before ending a session") updated in lockstep with the drift-check Defer rewrite, closing a cross-file follow-up Phase A had recorded as out-of-scope; the alternative would have landed a broken state on `develop`. Cross-track impact for Track 4a: the byte-copy contract verifies the Phase 1 walk is identical between drift-check and migration, but the caller-side branches Phase C added (merge-base failure recovery, both-arrays-empty guard, exit semantics, unstamped-short-circuit rendering) are caller-specific; Track 4a's Step 2 must verify or replicate them on its own copy. WC4/WC5 plan-correction (Component Map Mermaid) re-evaluated and dropped: the existing edge labels ("read + normalize stamps") and the workflow-drift-check.md bullet already carry the normalization-writer behavior; only the subgraph header is mildly imprecise and the same imprecision applies to `migrate-workflow` SKILL, so no correction lands here.
  >
  > **Track file:** `plan/track-3.md` (4 steps, 0 failed)

- [ ] Track 4a: Migration preflight + range computation
  > Rewrite the migration skill's preflight and range computation to run inside the branch's worktree, scoped to the active plan's `_workflow/` (D13). Drops the develop-worktree preflight; adds the unstamped-artifact bootstrap prompt (D8) and the stamp-walking range derivation (D10). Track file carries the step-by-step rewrite.
  > **Scope:** ~4-5 steps covering preflight tighten (develop-worktree drop + uncommitted/untracked refusal), worktree-resolution drop, unstamped-artifact prompt, range-computation rewrite (HEAD), progress file simplification.
  > **Depends on:** Track 1

- [ ] Track 4b: Migration replay + final batch
  > Rewrite the migration skill's per-commit replay loop to advance every stamp in lockstep at each commit, then re-stamp every artifact to HEAD's SHA in one final batch (D2). Workflow-modifying branches accept dogfood semantics — their own workflow commits register as drift and trigger migration of in-progress workflow changes. Track file carries the step-by-step rewrite.
  > **Scope:** ~3-4 steps covering per-commit lockstep advance, final stamp-to-HEAD batch, final summary.
  > **Depends on:** Track 4a

- [ ] Track 5: Self-improvement reflection for migration
  > Parameterize `self-improvement-reflection.md` to accept a session-type input (`execute-tracks` or `migrate-workflow`) that controls the commit-clean check, the phase identifier in the issue body, the applicability sentence in §"When it runs", and the in-scope examples in §"What counts as a worth-recording issue". Then wire a final reflection step into the rewritten `migrate-workflow` SKILL that invokes it with `session-type=migrate-workflow`. Skip rules (YouTrack MCP unreachable, no work happened) carry through unchanged.
  > **Scope:** ~2-3 steps covering reflection parameterization, migrate-workflow final step, applicability text updates.
  > **Depends on:** Track 4b

- [ ] Track 6: Drift gate at /create-plan startup
  > Add a session-start invocation of the SHA-aware drift gate to the `/create-plan` SKILL between Step 1 and Step 1a, catching post-rebase drift before any research investment (D9). Three resolutions stay symmetric with `/execute-tracks`.
  > **Scope:** ~2-3 steps covering `workflow-drift-check.md` intro generalization (name both callers), `/create-plan` SKILL new Step 1.5 invoking the gate, and the resolution prompt's Migrate-now wording referencing in-branch re-invocation.
  > **Depends on:** Track 3

## Plan Review
- [x] Plan review (consistency + structural) — passed at iteration 2

**Auto-fixed (mechanical)**: CR3 (track-5.md section name "Frequency and context-cost gate" → "Cost-benefit gate"), CR4 (track-5.md line-count "~590" → "~660"), CR7 (Track 4 renumber-down propagation across plan / design / track-3 / track-5).

**Escalated (design decisions)**: CR1 → option 1 (insert advance between Steps 4.4 and 4.6); CR2 → option 1 (active-plan scope as 7th deliverable to Track 1 §1.6); CR5 → option 2 (renumber Track 4's top-level steps down, subsumed by S2 split); CR6 → option 1 (SHA-computation one-liner in design.md §Core Concepts); S1 → apply all 4 intro trims (Tracks 2, 3, 4, 6; Track 4's trim subsumed by the S2 split); S2 → split Track 4 into 4a (preflight + range) and 4b (replay + final batch); S3 → skip (DR ordering quirk left as-is); S4 → apply all 7 `**Full design**` links (D1, D2, D7, D8, D10, D11, D13).

## Final Artifacts
- [ ] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
