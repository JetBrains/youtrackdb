---
severity: low
phase: phase-b
source-session: 2026-05-07 /execute-tracks read-cache-concurrency-bug
---

# Implementer commit subject can collide with orchestrator's "Record episode for ..." workflow-update prefix

## Symptom

During Phase B Step 6, a Sonnet implementer spawn (`level=step`,
`mode=INITIAL`, `risk: low`) committed its production-code changes
(six `@Deprecated` annotations, expanded `WriteCache.loadOrAdd`
Javadoc, two new smoke tests in `WOWCacheLoadOrAddTest`) under the
subject `Record episode for Track 1 Step 6 (deprecation + javadoc +
smoke tests)`. That subject prefix is reserved by
`commit-conventions.md` for the orchestrator's Workflow update
commit that lands the step episode under `_workflow/tracks/`. After
the orchestrator's own subsequent episode commit (`Record episode
for Track 1 Step 6 episode and mark Step implementation complete`),
two consecutive commits in the branch log carry near-identical
"Record episode for …" subjects despite operating on entirely
different file sets (production source vs the workflow track file).

## Reproduction context

- Phase: phase-b
- Workflow doc(s) involved:
  - `.claude/workflow/implementer-rules.md` §"Sub-step 3 — Stage
    explicit paths and commit"
  - `.claude/workflow/commit-conventions.md` § Commit type prefixes
    (Workflow update / Implementation rows)
- Tool / sub-agent involved (if any): per-step implementer sub-agent
  (general-purpose, `model: sonnet`)
- ADR directory at the time: `docs/adr/read-cache-concurrency-bug/`
- Trigger condition: any Phase B implementer spawn at `level=step`
  with `mode=INITIAL` or `WITH_GUIDANCE` whose author chooses a
  workflow-update-style subject. The current rulebook says only
  "Apply the project's commit-message convention from `CLAUDE.md`
  (imperative summary under 50 chars, blank line, detailed why)"
  and explicitly mentions the `Review fix:` prefix for
  `FIX_REVIEW_FINDINGS` / `WITH_GUIDANCE` track-level fixes — it
  does not list the orchestrator-reserved prefixes the implementer
  must avoid.

## Why it's a problem

Two impacts:

1. **`git log` ambiguity on the branch.** When two consecutive
   commits both start with `Record episode for Track 1 Step 6`, a
   reviewer scanning the branch log cannot tell from the subject
   alone which commit landed the production code and which landed
   the episode. Branch-only commits are squashed at merge so this
   does not affect `develop`, but it does affect draft-PR review
   and any in-flight resume.
2. **Latent risk for Phase B Resume's commit-pattern detection.**
   `step-implementation-recovery.md` § Resume-side commit-pattern
   reference distinguishes implementation, `Review fix:`, and
   workflow-update commits by subject prefix. If a future resume
   scenario adds heuristic checks for "Record episode for …"
   subjects (e.g. to count completed steps or to detect orphan
   workflow-update commits without a paired implementation commit),
   an implementer-side commit using the same prefix would be
   misclassified. The current resume logic does not appear to rely
   on this prefix specifically — but the convention is the only
   thing keeping the two commit types separable in the log.

The root cause is that the implementer rulebook documents one
forbidden / reserved prefix (`Review fix:` is **reserved**, not
forbidden — it is the form the implementer MUST use in fix mode)
but does not name the **orchestrator-reserved** prefixes the
implementer must NOT use for its own commits.

## Proposed fix

Edit `.claude/workflow/implementer-rules.md` § "Sub-step 3 — Stage
explicit paths and commit" to add a sentence (or short bullet)
listing the orchestrator-reserved subject prefixes the implementer
must avoid. Two equivalent options:

**Option A — short list embedded in the rulebook:**
> The following commit-subject prefixes are reserved for the
> orchestrator's Workflow update commits and MUST NOT appear on the
> implementer's commit subject: `Record episode for …`,
> `Record Phase B base commit …`, `Record Phase C iteration …`,
> `Apply plan corrections …`, `Self-improvement reflection from …`,
> `Remove workflow scaffolding`. The implementer's commit subject
> describes the code change in the imperative form per the project's
> `CLAUDE.md`. The `Review fix:` prefix is the one exception — it is
> reserved for the implementer's own commits in
> `mode=FIX_REVIEW_FINDINGS` and `mode=WITH_GUIDANCE` (Phase C),
> per `commit-conventions.md`.

**Option B — pointer to `commit-conventions.md`:**
> Use a fresh imperative subject describing the code change. Do
> NOT reuse any of the **Workflow update** subject forms enumerated
> in `commit-conventions.md` § Commit type prefixes — those are
> reserved for the orchestrator. The only exception is `Review fix:`,
> which the implementer MUST use in `mode=FIX_REVIEW_FINDINGS` and
> in `mode=WITH_GUIDANCE` at `level=track` per the same file.

Option A keeps the rule self-contained in the rulebook (cheaper for
the implementer at runtime, matching the rulebook's "load only this
file" discipline). Option B keeps the canonical list in one place
(cheaper for maintenance when a new orchestrator-reserved prefix is
added). Either would close the gap; Option A is the lighter touch
and avoids forcing the implementer to load a second file at every
spawn.

## Acceptance criteria

- `implementer-rules.md` §"Sub-step 3" names the
  orchestrator-reserved subject prefixes (or links to the
  authoritative list in `commit-conventions.md`) and forbids them
  on implementer-side commits, with `Review fix:` named as the
  single exception.
- `commit-conventions.md` § Commit type prefixes carries (or
  already carries) the canonical list of Workflow update subject
  forms so the rulebook can reference it.
- Sample audit on a fresh Phase B run: an implementer spawn that
  drafts `Record episode for …` as its commit subject is corrected
  by the rulebook before the commit lands (either the implementer
  reads the new rule and rewrites, or — if the discipline is
  enforced via grep — the orchestrator's pre-spawn check / the
  implementer's own self-check would catch it).
- Regression check: `git log {base}..HEAD --pretty='%s'` on a
  Phase B branch shows at most one consecutive commit with each
  Workflow update subject form (no implementation+episode pair
  sharing the `Record episode for …` prefix).
