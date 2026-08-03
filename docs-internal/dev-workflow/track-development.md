# Track-Based Development: YTDB Deltas

The generic track-based workflow protocol no longer lives in this repository. It ships with
the `ytdb-slate` npm package (pinned in `.pi/settings.json`) as two documents, cited by
absolute path in the orchestrator doctrine so agents can read the authoritative text on
demand:

- **track-workflow.md** — research phase, lazy research log, mandatory user design review,
  mandatory pre-implementation adversarial review, the per-track loop (agent code review →
  mandatory user review → marker commit), marker-commit mechanics, and the change-size
  scaling table.
- **pr-publishing.md** — umbrella draft PR mechanics: creation before implementation,
  description rules (Motivation, Planned changes, Tracks, plus the 16,384-UTF-8-byte body
  target and the size exception that must carry measurements), ready-for-review flip
  checklist, user-performed merge.

This document carries ONLY the YTDB-specific deltas layered on that baseline. Draft-PR
publishing is ENABLED for this repository (`workflow.draftPRs` in `.pi/slate.json`), so
pr-publishing.md applies in full: every change gets an umbrella draft PR before
implementation, and the research log folds into its description.

## Base branch

The repository's default development branch is `develop`, not `main`. The umbrella PR
targets it, and track 01's base is the merge-base with it.

## Branch, title, and template conventions

- Branch names carry the YTDB issue number; CI auto-prefixes the PR title from the branch
  name. Title rules — multi-issue bracket lists, the `[no-test-number-check]` gate marker,
  per-push title/description sync — live in
  `docs-internal/agents/orchestrator-guidelines.md` § Git Conventions.
- The PR description follows the repository template at `.github/pull_request_template.md`,
  which instantiates the generic description rules owned by pr-publishing.md (Motivation,
  Planned changes with its subsections and hard guards, Tracks). What the template does NOT
  carry, and pr-publishing.md § Description rules still requires: keep the description body
  at or below 16,384 UTF-8 bytes (a target, not a gate — measure the GitHub API `body`
  string, never a formatted `git log %b`), and when it runs over, record a size exception in
  Risks & accepted trade-offs carrying the measurements — the overrun, every top-level
  section's byte count, and the before-count of anything condensed. Measure at the
  ready-for-review flip, after every post-flip description change, and again at the final
  handoff for merge (pr-publishing.md § After the flip); YTDB's per-push title/description
  sync, per the bullet above, is what makes those points recur.
- The template has no Open questions subsection, so open questions carried over from the
  research log live in Risks & accepted trade-offs — which is also where the flip checklist
  wants each remaining one resolved, or its user-approved deferral recorded.

## Peer review

YTDB layers an optional peer-review step on the baseline via the slate doctrine
extension (`doctrineExtraPath` → `docs-internal/agents/slate-doctrine-extra.md`). Peer
review is optional and, when the user wants it, runs directly on the ready umbrella PR at
the ready-for-review flip — there are no separate review branches or PRs. The rule that
layered peer review supplements, never replaces, the mandatory per-track user review is
owned by track-workflow.md § Peer review.

## Verification integration

Per-track implementation (step 1 of the generic track loop) follows YTDB's test policy and
pre-commit verification rules in `docs-internal/agents/orchestrator-guidelines.md`; the
exact command lines are in `docs-internal/agents/thread-guidelines.md`.

## Model routing

Action-level model routing is ENABLED for this repository (`router` in `.pi/slate.json`).
The mechanics — guards, resolution, effort ladders, per-model profile data — are owned by
the package's model-routing.md; this section records only the choices this project made and
what follows from them.

**The candidate list.** Four models are routable: `anthropic/claude-opus-5`,
`anthropic/claude-sonnet-5`, `openai/gpt-5.6-sol`, `openai/gpt-5.6-luna`.
`openai/gpt-5.6-terra` is excluded: its shipped profile marks it never-auto-select with no
defensible routing niche, and every routable model adds a row to the routing table Slate
renders into the orchestrator doctrine on EVERY turn, so listing a model no action should
land on spends context every turn and buys nothing. `anthropic/claude-sonnet-5` is listed
despite carrying the same never-auto-select marker, solely for the one niche its profile
names: only if sol is unavailable, at `high`. `anthropic/claude-fable-5` is excluded on two
counts: never-auto-select, and it has no zero-data-retention option.

**Maintaining the list.** The routable set is CLOSED to the nine models the package ships
profiles for — project-supplied profiles do not exist — so adding a model takes a ytdb-slate
release, not a config edit. Write canonical `provider/id` specs only: the profile table's
alias spellings resolve a profile but are not routable specs, since pi's registry decides
what can be dispatched and most aliases are absent from it. A spec that differs by one
character is dropped as unprofiled or as unknown to the registry, leaving a three-model list
that still routes — read the warnings, or the typo passes for a working config. If every
entry drops, the router turns OFF entirely and dispatches fall back to the host session's
model.

**When warnings appear.** Only config-SHAPE checks run at session start: a `router` value
that is not an object, an unknown key under it, a non-array `models`, a malformed entry, a
non-boolean `allowUnmeasuredEffort`. Candidate resolution — profile lookup, registry and
credential checks, effort-ladder readability, failover coverage — is memoized and runs at
the FIRST consultation instead: the doctrine build in orchestrator mode, or the first
dispatch outside it. A session that builds no doctrine and dispatches nothing emits none of
those warnings, which is what the acceptance check below has to work around.

**Effort policy.** `router.allowUnmeasuredEffort` is `false`, so an explicit level that sits
on the model's ladder but carries no capability measurement is refused rather than warned
about. Only a level the dispatch NAMES can be refused: omit `effort` and the router derives
the model's lowest measured level, which by construction clears the guard. What the setting
costs, per model: `off`, `low`, `high` and `xhigh` on `gpt-5.6-luna`; `off` and `low` on
`gpt-5.6-sol`; `off` and `minimal` on `claude-opus-5`; `minimal`, `low` and `medium` on
`claude-sonnet-5`. Of those, the ones this project would otherwise have reached for are
`luna@high` and `luna@xhigh` (escalating luna instead of routing to sol) and `sol@low`. Two
nearby refusals come from elsewhere and stand whatever this key says: `minimal` is off-ladder
on both `gpt-5.6` models, and `off` on `claude-sonnet-5` is rejected outright by the
provider — which is why `off` is absent from that model's list above.

**Unsized dispatches.** A dispatch that names neither `model` nor `effort` runs on the
thread's base. For a NEW thread that base is `openai/gpt-5.6-luna@medium` — the cheapest
preferred candidate at its lowest measured level. A thread that predates the router is the
exception worth knowing: when its stored pre-router pin is itself a listed model, the router
keeps that pin as the base and derives that model's own lowest measured level, silently and
with no warning — a thread pinned to `claude-opus-5` bases to `claude-opus-5@low`. Only a
base that is absent or off-list is re-seeded, and a re-seed does warn. Either way the router
lowers the pre-router floor of `claude-opus-5@xhigh` (`.pi/settings.json`), and no candidate
list restores it: even a list of `claude-opus-5` and `openai/gpt-5.6-sol` alone bases a new
thread to `claude-opus-5@low`. The compensating discipline is the Model routing rule in
`docs-internal/agents/slate-doctrine-extra.md`, which Slate does not enforce in code — name
both arguments on every action that needs more than the base.

**Episode compression.** `episodeModel` is pinned to `anthropic/claude-sonnet-5`. Left
unpinned it resolves from registry state — the newest available Anthropic Sonnet, by numeric
version comparison — so a future model release could move compression onto a model the
failover map does not cover, without saying so. The pin keeps that answer stable.

**The failover map.** `modelFailover` covers three consumers, and Slate checks only the
first: the router's candidates (an uncovered candidate produces one aggregate warning at the
first consultation), the orchestrator's own session model (`defaultModel` in
`.pi/settings.json`, today `claude-opus-5`), and the pinned `episodeModel`. Nothing warns
about the last two, and dropping a model from `router.models` does not end its need for cover
while it is still one of them. The current pairing keeps every route cross-provider, so one
provider's outage cannot take out both sides of a pair: `claude-opus-5 → gpt-5.6-sol` (which
also covers the orchestrator session), `claude-sonnet-5 → gpt-5.6-sol` (also the episode
compressor), `gpt-5.6-sol → claude-opus-5`, `gpt-5.6-luna → claude-opus-5`.

**What failover ignores.** A failover switch bypasses the list and effort guards entirely —
the package's deliberate carve-out, since a model that just failed is worse than an unlisted
one that works — and it requests no level, so pi re-applies the session's current level to
the fallback model. `allowUnmeasuredEffort: false` therefore does not constrain the retry, and
the episode header drops its `(unmeasured level)` marker whenever the model changed
mid-action, so an unmeasured retry level leaves no trace there either. Each pair above retries
on a level its target has a measurement at, with one accepted residual: `claude-opus-5@low`
retries as `gpt-5.6-sol@low`, and sol carries no measurement at `low`.

**Machine-local prerequisite.** Routing here assumes a per-developer, untracked
`~/.pi/agent/models.json` that raises the registry context window of the `gpt-5.6-*` models
to 1,050,000. pi documents this exact override in its own `docs/models.md` § Per-model
Overrides (installed with the `@earendil-works/pi-coding-agent` package), including the
warning that above 272K total input the whole request bills at GPT-5.6's long-context rates:

```json
{
  "providers": {
    "openai": {
      "modelOverrides": {
        "gpt-5.6-sol": { "contextWindow": 1050000 },
        "gpt-5.6-luna": { "contextWindow": 1050000 }
      }
    }
  }
}
```

Slate's profile records 1,050,000 for those models while the base registry reports 272,000,
and the window guard judges against the REGISTRY figure. A developer without the override
sees three extra warnings per session — a context-window divergence for `gpt-5.6-luna`, one
for `gpt-5.6-sol`, and one aggregate line noting that the registry window equals those
models' own long-context billing threshold — and the guard then treats both GPT models as
narrower than `claude-opus-5` (1,000,000), so a long thread is substituted off them onto an
Anthropic candidate rather than the reverse. The override is deliberately not reproduced
repo-side: `models.json` is a user-global pi file, not project config.

**Acceptance check.** Router health is invisible to a bare `pi -p "<prompt>"`: the
orchestrator-mode auto-seed is gated to TUI sessions, so a print-mode run builds no doctrine,
never consults the resolver, and emits ZERO router warnings however broken the list is. Turn
the mode on in the same session instead — `pi -p "/slate on" "hi"`, which runs the command
first and the prompt second — and the warnings reach stderr. A healthy configuration prints
exactly four, one per routable model, each reporting unknown routing-critical data and that
routing decisions for that model are provisional. Anything beyond those four is a finding:
context-window-divergence or long-context-billing lines mean the `models.json` override is
missing, a failover-coverage line means a candidate lost its `modelFailover` entry, a
"no benchmark data" or "not in pi's model registry" line means a spec is misspelled, and an
`allowUnmeasuredEffort` line means the key is no longer a boolean.

**Authority.** The routing table Slate renders into the doctrine each turn is the single
authority on per-model guidance, prices and measured levels; do not restate it under
`docs-internal/`. The effort-policy enumeration above is the one deliberate exception — it
explains a project setting rather than the table — and it is re-checked at every pin bump.
The figures drift: prices are dated schedules (`claude-sonnet-5`'s step up 50% on 2026-09-01,
which changes the table's numbers but not its order, since `nonPreferred` sorts that model
last on both sides of the step), and measured levels, route-for/avoid-for guidance and the
profiled model set itself change whenever the package is republished.

## Package pin bumps

Changing the `ytdb-slate` version pin in `.pi/settings.json` is a tracked change like any
other — it takes the full workflow. In addition, whoever bumps the pin MUST re-read this
document and `docs-internal/agents/slate-doctrine-extra.md` against the NEW package docs and
fix any skew: the deltas here are valid only relative to the package version they were
written against.

Last reconciled against **ytdb-slate 0.5.1**: track-workflow.md, pr-publishing.md,
model-routing.md and model-failover.md — the package documents these deltas actually rest on.
