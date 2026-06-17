# Research Log — understandable-design

## Initial request

Implement the **two-role author/cold-read loop** for design-document authoring,
the authoring-side companion described in YTDB-1130 comment 3 (2026-06-16).
Pair a purpose-built **author** sub-agent (the generate half) that drafts
`design.md` for a reader who has only the finished design doc, with the
dedicated **cold auditor** pass (the verify half) that reports every passage a
cold reader cannot reconstruct; loop generate→verify until the auditor is
clean. Builds on YTDB-1130's dedicated cold auditor; all `.claude/**` workflow
machinery, so the branch is workflow-modifying (§1.7 staging-bound).

User refinement to YTDB-1130 comment 4's "domain-density floor": the floor is
lower than that comment claims. The target reader is a **mid-level developer**
who may not know a domain term such as "Dekker gate". A term like that should
**stay** in the doc (it teaches the unaware reader) but be **glossed in-place**,
with alternative plain-language reasoning that substitutes for not knowing the
term. Density that is really an unglossed term is reducible by glossing; only
genuinely irreducible mechanism density is the true floor.

## Decision Log

### 2026-06-16T13:33Z [ctx=safe] — Target reader is a mid-level developer; domain terms stay and are glossed in-place
The author and the cold auditor both treat the target reader as a mid-level
developer. A domain term the reader may not know (e.g. "Dekker gate") stays in
the doc and is glossed in-place at first use with plain-language alternative
reasoning that substitutes for not knowing the term. Separate two things
YTDB-1130 comment 4 conflated under "domain-density floor": (1) unglossed-term
density — a reader blocked by an unfamiliar name — is **reducible** by glossing;
(2) inherent mechanism density — the actual store-then-load interleaving — is
the true floor. The cold auditor flags an unglossed domain term as a finding
and rejects "it is a real term" as a dismissal, the same way YTDB-1130 rejects
"it is technical."
- **Why:** a reader blocked on a name is blocked for a reducible reason;
  glossing keeps the mechanism's precision and the term's teaching value while
  unblocking a mid-level reader. The floor is therefore lower than comment 4
  claimed.
- **Alternatives rejected:** (a) accept comment 4's floor as-is — leaves
  readers blocked on names the doc could gloss in one clause; (b) delete or
  replace the term with a plain phrase — loses both precision and the term's
  teaching value.

### 2026-06-16T13:38Z [ctx=safe] — Mechanisms get explained, not just terms glossed; the bar is reconstructibility
Extends D1 to intricate mechanisms, not only unfamiliar names. An intricate
mechanism (the store-then-load-and-recheck handshake, mutex ownership records)
is explainable with tools other than glossing: a worked interleaving (a
concrete two-thread trace showing the bad outcome without the barrier), a
diagram (a thread timeline), and a stated purpose for each step ("the recheck
exists because both threads can set flags at the same instant"). So YTDB-1130
comment 4's "irreducible domain-density floor" is retracted: ~38 was the
ceiling of the code-grounding-from-worklist technique, which established
current-state but was not mandated to explain mechanisms with worked traces and
diagrams. The author role gets that explicit mandate.
- **The one genuine residual:** the reader must still spend attention tracing
  the interleaving; the doc makes every step followable but cannot trace it for
  the reader. "Needs attention" is not a defect, so it is not a finding.
- **Stopping criterion (load-bearing for the auditor's must-enumerate
  obligation):** the bar is **reconstructibility** — can a mid-level developer
  rebuild the mechanism from the doc alone (term glossed, interleaving worked,
  each step's purpose stated, diagram where it helps)? Pass when reconstructible
  even if intricate; flag when blocked. This sits between "terse and blocked" (a
  finding) and tutorial bloat (banned by § Orientation), so the loop converges
  rather than chasing one-more-sentence forever.
- **Why:** removes the dismissal license at its root — neither "it is a real
  term" (D1) nor "it is inherently dense" can wave a blocked passage through.
- **Alternatives rejected:** keep comment 4's floor framing — it lets the
  auditor accept genuinely-blocking mechanism prose as irreducible.

### 2026-06-16T13:55Z [ctx=safe] — Branch scope: the full two-role authoring loop; 1128/1129 deferred
This branch implements both halves — the code-grounded author and the dedicated
cold auditor — as one generate→verify→iterate authoring loop wired into design
creation. YTDB-1128 and YTDB-1129 (the authoring rules and the unanchored-ordinal
rule) move to a separate PR.
- **Why:** the loop is the unit of value (generate + verify together fix the
  curse-of-knowledge that produces dense prose); the rules dependency is soft,
  because the auditor reads the live `house-style.md` and absorbs 1128/1129 when
  they land.
- **Alternatives rejected:** (a) 1130-only this branch — ships the auditor but
  leaves the generate half (the main-agent author with the whole conversation
  loaded) unfixed, so dense prose is still produced and only flagged; (b)
  carry 1128/1129 first in this branch — defers the loop behind unrelated rule
  edits the auditor picks up automatically anyway.

### 2026-06-17T07:37Z [ctx=safe] — The auditor owns the § Prose AI-tell axis on every prose-checked surface; design-review.md drops it (resolves the 13:55Z prose-axis open question; gate A1)
Resolves the open question on where `design-review.md`'s prose axes land. The
dedicated cold readability auditor owns the **entire § Prose AI-tell axis**
(over-dense / too-terse / hard-to-read) plus the prose-anchored § Human-reader
items 15:30Z assigned it (glossary-introduction, why-before-what, explanatory
register, the prose half of audience-fit), on **every surface where prose
readability is assessed**: `edit-design` `phase1-creation` and
`phase4-creation`, and `create-plan` Step 4b `target=tracks`. The de-warmed
`design-review.md` **drops the § Prose AI-tell additions block entirely** on
those surfaces and keeps only the comprehension half (the 7 questions), the
structural-findings set (TL;DR / References / length / Mechanics & Full-design
link resolution), and the whole-doc § Human-reader items (navigability, and the
structural "does the Overview name a reader" half of audience-fit).
- **Invariant:** no surface ever runs § Prose AI-tell on **both** the auditor
  and the warm reviewer. The auditor owns it wherever prose is checked,
  including the track path (14:58Z routes the loop through Step 4b).
- **The `design-sync` and other non-creation mutation kinds (the 4th surface):**
  the principle is one-owner-per-surface. Wherever a mutation kind triggers a
  prose-readability assessment, the auditor owns it and `design-review.md` does
  not re-run § Prose AI-tell there. Whether the author/auditor loop runs on
  `design-sync` (vs `design-review.md` keeping the axis for sync-only) is an
  `edit-design` wiring detail settled in Step 4a; the invariant (never both on
  one surface) holds either way.
- **Why:** the dilution thesis (Surprise 13:40Z) — if any prose axis stays on
  the warm multi-axis reviewer, the diluted-pass failure mode YTDB-1130
  diagnosed returns on that surface. Removing it everywhere is the only
  resolution consistent with the branch's purpose.
- **Alternatives rejected:** keep the § Prose AI-tell half on the warm reviewer
  for `target=tracks` (or `design-sync`) — re-creates the dilution on the
  unclaimed surface, defeating the branch's purpose.

### 2026-06-17T07:37Z [ctx=safe] — Dual-clean loop convergence argument; detailed cost bound waived to planning (resolves the 13:55Z loop-topology open question sub-part b; gate A2)
Resolves the open cost/convergence sub-part of the loop-topology question (the
topology and phase1-vs-phase4 sub-parts were already settled by 14:32Z / 14:50Z
/ 14:38Z / 14:58Z).
- **Convergence of the two-check loop:** the readability auditor and the
  absorption agent re-open the loop for **disjoint** reasons and are not
  adversarially coupled. Fixing a density finding (adding a worked interleaving
  or the why-of-each-step) adds code-accurate prose; it does not drop a
  decision. Adding a dropped decision is new prose the next round's auditor
  polishes like any other. Neither check's fix re-triggers the other in a cycle,
  so the loop moves monotonically toward dual-clean — typically 1–2 rounds
  (round 1's absorption catches the author's initial omission; later rounds
  catch the rare cross-slice drop, see the A3 entry below). The
  `iteration_budget` (3) plus escalation is the backstop for the pathological
  ping-pong, not the expected path.
- **Cap-exhaustion for a creation loop:** identical to today's `edit-design`
  Step 6 — on exhaustion the design freezes with open findings and escalates to
  the user (the user is the gate), who accepts the residual or extends. No new
  escalation machinery.
- **Cost:** worst case per round = 1 author spawn (whole-doc grounding round 1,
  targeted re-grounding after, per 16:10Z) + ~6 auditor range-agents + 1 Sonnet
  absorption agent, × ≤3 rounds, amortized by the fan-out cache warm-up (16:00Z:
  ~1 cold + (N−1)×0.1×). The exact worst-case token figure is a
  planning/decomposition concern — **waived to planning** as out of the gate's
  scope (the gate needs the convergence argument, not the budget).
- **Why:** the gate asked for a convergence argument specific to the dual-clean
  loop (not the single-axis one) and a treatment of cost; this supplies the
  former and scopes the latter to where it belongs.

### 2026-06-17T07:37Z [ctx=safe] — Per-round absorption is justified by the auditor's range-slicing, not refuted by "auditor never deletes" (strengthens 14:32Z; gate A3)
Strengthens the 14:32Z rationale where it was in tension with its own 14:18Z
premise. The 14:18Z observation "the auditor never requests deletions (it flags
prose, not content)" does **not** defuse the back-edge, because the readability
auditor is **range-sliced** (15:30Z / 16:00Z: the `readability-feedback` fan-out
reads ~200-line slices with no whole-doc view). A later round's restructure can
**move** a decision's prose across slice boundaries, or merge/split content so a
decision present in round-1's audited slice falls into a gap or is reworded out
of recognizability in a later round — a drop with no agent "deleting" anything.
A single before-loop absorption check cannot re-catch this cross-slice drop
after the restructure; the per-round whole-doc absorption check does.
- **Why:** names the actual load-bearing failure (cross-slice drop under
  range-slicing) that makes per-round absorption beat the cheaper single-check,
  replacing the deletion failure mode the log elsewhere argues cannot occur.

### 2026-06-17T07:37Z [ctx=safe] — The de-warm extends S2 to name the absorption agent as a sanctioned reader; the conventions.md S2 edit is a stated deliverable (strengthens 14:05Z / 14:12Z; gate A4)
Strengthens the de-warm decision's "no third S2 read site" claim. That claim
holds only under an **extended** reading of S2: the live S2 (`research.md`
§The research log — "the log is read for decision content in exactly two
places: at Step 4a/4b artifact authoring … and by the Phase-2 consistency
review") names the authoring read as the author / cold-read reviewer, not a
separate absorption-only spawn. This branch **extends** S2's "Step 4a/4b
authoring read" to include the distinct warm absorption agent as a sanctioned
reader. Updating S2's prose in `conventions.md` to name the absorption agent (or
to scope the authoring read to "the author and the creation-time absorption
agent") is a **stated deliverable** — already on the 14:50Z files-of-change list
("conventions.md S2/S3 prose"), now bound to this decision rather than left as
an implicit reinterpretation.
- **Why:** prevents a latent S2 violation if a later reader (or the Phase-2
  consistency review) reads S2 literally; makes the implied invariant edit
  explicit.

### 2026-06-17T07:37Z [ctx=safe] — Add a workflow-prose Phase-3 dogfood target (strengthens 17:40Z; gate A5)
Strengthens the dogfood plan. The 17:40Z targets (`readability-feedback` on this
design now; `transactional-schema` design.md in Phase 3) never exercise the loop
on **workflow prose** — the branch's actual domain (the matched HIGH category is
Workflow machinery; the densest decisions are the S2/S3 invariants, staging, and
gate semantics in this very log). Add a Phase-3 validation point: run the
implemented routine against a known-dense **workflow-prose** artifact — a prior
workflow branch's `design-final.md` (e.g. `plan-slimization` or
`no-track-for-minimal`) or a `conventions.md §1.7` section — alongside the
`transactional-schema` storage-domain cross-check. Validate the loop on workflow
prose **before** promotion, not only post-promotion.
- **Why:** the chosen targets validated only storage-domain prose and the
  verify-half-only `readability-feedback` run; a workflow-prose density gap would
  otherwise surface post-promotion and cost a follow-up branch.

### 2026-06-17T07:37Z [ctx=safe] — Collapsing the 4a/4b boundary is a staged auto-resume-contract change with a hard by-reference requirement (strengthens 17:25Z; gate A6)
Strengthens the boundary-collapse decision. Collapsing the Step-4a/4b session
boundary is **not** a clean UX win — it modifies the `create-plan` **auto-resume
contract** (the startup routing keyed on `design.md` committed-and-clean → 4b vs
dirty → 4a, and the "Step 4a ends the session, does not flow straight into 4b"
rule). That is a staged, **execution-procedure** change under §1.7(b), not a
§1.7(k) opt-out-eligible prose edit — resume-state routing is exactly the schema
a running phase reads, which §1.7(k) keeps in staging.
- **Crash-recovery re-spec:** the auto-resume path must fire only on a
  dirty/absent plan after a committed-clean design; Step 1c becomes
  crash-recovery-only (the happy path no longer crosses the boundary).
- **By-reference is a hard requirement**, not "adopted anyway": if any author
  sub-agent ever returns more than a thin summary, the combined session
  re-accumulates the design + plan context the boundary existed to prevent, and
  the collapse regresses context isolation. If by-reference cannot hold, the
  boundary is retained.
- **Why:** the gate flagged that the decision understated the machinery it
  touches and the hard dependency on by-reference orchestration; this binds
  both.

### 2026-06-17T07:37Z [ctx=safe] — Suggestion refinements folded in (gate A7, A8, A9)
Three suggestion-severity refinements recorded; the underlying decisions hold.
- **A7 (warm-up timing, refines 16:00Z):** the ~1-min warm-up delay may need to
  be author-vs-auditor specific — a heavy code-grounded author's first turn (PSI
  + multi-file reads) can exceed 1 min, so the cold-write lands after the fan-out
  starts. Exact warm-up plumbing is deferred to implementation and flagged as
  the most intricate orchestration in the branch.
- **A8 (phase4 fidelity residual, refines 15:45Z):** widen the PSI-residual
  trigger from "diagram/signature claims only" to **"any `design-final.md` claim
  with no corresponding episode trace."** When the episode record is silent on a
  behavioral point design-final asserts, fall back to code/PSI rather than
  trusting the episode-match.
- **A9 (reconstructibility upper bound, refines 13:38Z):** pin the auditor's
  upper-bound stop to **named clauses** — § Orientation's anti-padding clause
  plus § Plain language's no-re-teach-the-floor boundary — rather than the loose
  "tutorial bloat" phrasing, so the auditor has a citable stop and the
  one-more-sentence ratchet has a hard rule to halt on (the iteration cap is the
  other brake).

## Surprises & Discoveries

### 2026-06-16T13:40Z [ctx=safe] — Today's `edit-design` is already a generate→verify→iterate loop
`edit-design` Step 1 (Apply) authors `design.md` for `phase1-creation` — done
by the invoking/main agent, not a sub-agent (matches YTDB-1130 comment 3).
Step 4 spawns `prompts/design-review.md` as a `general-purpose` cold-read
sub-agent. Steps 5-6 merge findings and iterate apply→re-cold-read up to
`iteration_budget` (default 3). So the two-role loop is not a new control
structure — it is two swaps inside the existing loop: (1) replace Step-1
main-agent authoring with a dedicated **author** sub-agent; (2) split the
prose-readability axis out of the Step-4 reviewer into a dedicated **cold
auditor** pass.

### 2026-06-16T13:40Z [ctx=safe] — The prose axis already exists in `design-review.md` but is diluted
`design-review.md` already carries the readability work: § Prose AI-tell
additions (over-dense / too-terse / hard-to-read, citing § Banned analysis
patterns, § Orientation, § Plain language) and § Human-reader cold-read
additions (audience-fit, glossary, why-before-what, navigability, explanatory
register). But the same agent also runs 7 comprehension questions, the
absorption-completeness cross-check, structural findings, length budgets, and
diagram checks. That is exactly YTDB-1130's "dedicated vs diluted" diagnosis:
the prose axis gets a sliver of a multi-axis pass.

### 2026-06-16T13:40Z [ctx=safe] — The absorption cross-check is what warms the reviewer; the auditor must not carry it
`design-review.md` Step-4 cold-read for `phase1-creation` reads
`research_log_path` to run the absorption-completeness check (every load-bearing
log decision appears as a seed D-record). That read is what warms the reviewer
on the log and the D-records — the root of YTDB-1129's warming. So YTDB-1130's
"genuinely separate cold agent" condition means the dedicated readability
auditor reads `house-style.md` + the doc only and never the research log, which
is what structurally dissolves YTDB-1129. The absorption check stays on the
warm reviewer; the prose axis moves to the cold auditor.

### 2026-06-16T13:40Z [ctx=safe] — `readability-feedback` already encodes the auditor contract, but for rule-hardening not creation
The `readability-feedback` skill fans out ~200-line-range `general-purpose`
auditors (cap ~6), each reading `house-style.md` in full and its range only,
each obligated to enumerate findings classified CAUGHT-by-§ vs GAP. That is the
auditor contract YTDB-1130 wants. The gap: it audits a *finished* doc to harden
*rules* and explicitly does not rewrite the doc, and nothing runs it at
design-creation time. Its audit sub-agent prompt is the reuse template for the
verify half.

### 2026-06-16T13:50Z [ctx=safe] — 1128/1129 both still Submitted; the auditor's rules dependency is soft, not hard
YTDB-1128 (authoring rule + dsc-ai-tell check + TL;DR→Summary/### Details
section-shape rider + § Orientation carve-out) and YTDB-1129 (unanchored-ordinal
rule) are both State=Submitted — not landed. The recommended order is rules-first
(1128→1129→1130). But the dependency is softer than it reads: the cold auditor
reads the **live** `house-style.md` in full on every run, so it automatically
picks up 1128/1129's rules whenever they land — it hard-codes no rule list. The
only hard coupling to frozen rules is the dry-run catch-rate validation and the
close-the-loop re-audit (YTDB-1130's two sequencing notes), which want the final
ruleset to measure against. So 1130 can be built against the current ruleset and
strengthens automatically as the rules land.

### 2026-06-16T13:50Z [ctx=safe] — Okular SVG-in-Markdown is unreliable; for agents an external SVG is opaque regardless
Okular's Markdown backend is Discount + QTextDocument, which has weak SVG
support; embedded/referenced SVG in a `.md` file likely does not render in the
native backend (a webview renderer such as KMarkdownWebView would). Inconclusive
from web search — needs a direct test or an Okular-source check to confirm.
Independent of Okular: the author and auditor are **agents** that read the raw
Markdown text, so an external `.svg` reference is opaque to them — only the
committed diagram *text source* (ASCII inline, or a D2/Mermaid source block) is
agent-readable. So any SVG path must commit the text source alongside the
rendered SVG. ASCII renders in every consumer (terminal, Okular, GitHub, IDE,
agent). Precedent: the workflow-book branch already chose hybrid ASCII +
committed D2→SVG over Mermaid.

### 2026-06-16T13:40Z [ctx=safe] — Sub-agent homes: prompts/ for spawned reviewers, agents/ for /code-review dimensions
Workflow-spawned reviewers (design-review, adversarial-review, the gate
verifications) are prompt-files under `.claude/workflow/prompts/`, invoked as
`general-purpose`. The `.claude/agents/*.md` set is the `/code-review`-dispatched
dimensional reviewers plus standalone agents. So the author role's natural home
is a new `prompts/design-author.md`, and the cold auditor is either a new
`prompts/` sibling reusing the readability-feedback audit prompt or the prose
axis extracted from `design-review.md`.

### 2026-06-16T13:46Z [ctx=safe] — Author is code-grounded, not log-only
The author sub-agent reads the research log **and** has codebase + mcp-steroid
PSI access plus a translate-current-state-then-change mandate; it does not see
the authoring conversation. The "write for a reader who has only the doc"
mandate governs the output discipline, not the author's grounding inputs.
- **Why:** YTDB-1130 comment 4's measured result — code-grounding cut findings
  ~50% below the prose-only floor (Part 1: 19→6→3). Log-only hit a floor it
  could not beat because much residual needed current-system grounding the doc
  never stated.
- **Alternatives rejected:** log-only authoring (YTDB-1130 comment 3's original
  sketch) — the experiment showed it insufficient.

### 2026-06-16T14:32Z [ctx=safe] — Absorption is a co-equal per-round check inside the inner loop, not a separate stage (refines 14:18Z, settles 14:18Z residual + Sonnet)
A separate after-loop absorption re-check creates a back-edge: a re-check finds
a dropped decision → the author adds it → the new prose re-enters the readability
loop → the loop can drop it again → re-check again → thrash (bounded by the cap,
but still thrash). Fix: fold absorption **into** the inner loop as a second
per-round check beside the readability auditor.
- **Loop shape:** each round = author revises → **both** the readability auditor
  (cold, `house-style.md` + doc) and the absorption agent (warm Sonnet, log +
  doc) check the revised doc → the round passes only when both are clean →
  bounded by `iteration_budget` (3) → escalate to the user on exhaustion.
- **Why this is clean:** "add a dropped decision" is no longer a special event
  that restarts anything — it is the next iteration's finding, handled like any
  readability finding. No back-edge. The loop's exit condition guarantees the
  frozen doc is **both** readability-clean and absorption-clean by construction.
- **Subsumes the before/after split:** round 1's absorption check is the old
  before-loop check (catches the author's initial omission); every later round's
  catches a loop-induced drop. So there is no separate before-loop stage (14:18Z)
  and no separate pre-freeze re-check (14:18Z residual) — both fold in.
- **Absorption model = Sonnet** (user choice 14:32Z): the task loses nothing on
  Sonnet and saves cost; the readability auditor and the cold comprehension gate
  keep the workflow default.
- **Convergence:** a decision the author was just told to keep, and that the
  readability auditor never asks to delete, is very unlikely to drop twice, so it
  converges in a round or two; the cap + escalation is the safety net for the
  pathological ping-pong.
- **Stage order now:** author drafts → inner loop {readability + absorption}
  to dual-clean (cap 3, else escalate) → cold comprehension+structural gate →
  freeze.

### 2026-06-16T14:18Z [ctx=safe] — Absorption gate sits between the author and the readability inner loop (SUPERSEDED by 14:32Z — absorption folds into the loop as a per-round check)
Stage order at design creation: (1) author drafts; (2) **absorption gate** —
coverage check, author adds any missing load-bearing decision; (3) readability
inner loop (auditor ↔ author, cap 3) on the now-complete content; (4) cold
comprehension+structural gate; (5) freeze.
- **Why:** absorption is a content gate, the inner loop is prose polish; content
  must be complete before polishing. A gap fixed at stage 2 is new prose that the
  stage-3 loop then polishes like any other — only possible if absorption runs
  before the loop. Absorption after the loop would polish an incomplete doc, then
  force an add-and-re-polish.
- **Residual risk (accepted):** the readability loop could in principle drop a
  decision while restructuring, which a before-loop check would not re-catch.
  Low risk — the auditor never requests deletions (it flags prose, not content),
  and the stage-4 comprehension gate partially backstops it (question 4 asks what
  invariant must remain true). Single absorption check at stage 2 by default; a
  cheap pre-freeze re-run is available as belt-and-suspenders if wanted.

### 2026-06-16T14:12Z [ctx=safe] — Absorption stays at creation as a small dedicated agent, not deferred to Phase 2 (revises 14:05Z)
Revises the 14:05Z "move absorption to Phase 2" clause. Instead of deferring,
add a small **dedicated absorption-gap agent** that runs at carrier-creation
time: after `design.md` authoring (before freeze) and after track authoring
(Step 4b, before commit). It is warm (reads the log + the carrier) with a single
narrow objective — coverage matching: every load-bearing log decision (its
`**Alternatives rejected:**` names a real fork) appears as a seed D-record /
track DR, and no record invents a decision the log lacks. The cold readability
auditor and the de-warmed cold comprehension/structural review stay cold and
log-free; the absorption agent is a separate spawn.
- **Why:** warmth corrupts *readability judgment*, not *coverage matching*, so
  an absorption-only agent can be warm without re-introducing the YTDB-1130
  warming. Running it at creation catches gaps before the freeze, so ripple #1
  (post-freeze `edit-design` fix) disappears. This is strictly better than both
  the warm-reviewer (kept comprehension warm) and the move-to-Phase-2
  (post-freeze risk).
- **Model:** Sonnet is a fit for this specific task — it is structured two-way
  set matching with light semantic-equivalence judgment, not deep reasoning or
  generation, well within Sonnet 4.6. But the check is one narrow non-fan-out
  agent, so the cost saving over the workflow default is marginal; choose by
  preference, not as a cost lever ([[no-weak-models-for-cost-levers]]).
- **S2 preserved:** the absorption agent reads the log at the **authoring** site
  (creation-time carrier verification is part of the Step-4a/4b authoring read,
  just spawned as a distinct agent), and Phase-2 consistency remains the second
  site — still exactly two sanctioned decision-content read sites. The cold
  reviews drop their log read entirely.
- **S3:** the absorption agent runs after the Phase 0→1 adversarial gate clears
  (automatic — it runs at creation, which is post-gate), so it never checks
  coverage of decisions still under challenge.

### 2026-06-16T14:05Z [ctx=safe] — One cold design-creation review; absorption coverage moves to the Phase-2 consistency review (absorption clause SUPERSEDED by 14:12Z — stays at creation as a small agent)
The design-creation review becomes a **single cold pass** that reads
`house-style.md` + the doc only (never the research log): readability (the
auditor axes) + comprehension (the 7 questions, now genuinely cold) + structural
integrity (TL;DR present, References footer, length, Mechanics/Full-design link
resolution — all doc/plan-structural, not log-dependent). The **absorption
coverage check** (every load-bearing log decision → seed D-record in design.md,
and → track DRs) moves to the **Phase-2 consistency review**, which already
reads the log. Both the `target=design` and the `target=tracks` Step-4b
cold-reads go cold; absorption leaves both and lands once in Phase 2 against the
fully-derived design + plan + tracks.
- **Why:** absorption was the *only* log-dependent check in the cold-read;
  moving it removes the warming at its source and makes the comprehension
  assessment genuinely cold (today the same agent reads the log, so "could a
  cold reader follow this" is answered by a non-cold reader — a latent defect
  this fixes). One place reads the log for coverage; every cold-read stays cold.
- **S2 preserved, in fact tightened:** S2 already names the Phase-2 consistency
  review as a sanctioned log-read point, so absorption adds no third read site;
  and the cold-read drops its log read, removing a site.
- **Ripples to confirm:** (1) an absorption gap now surfaces at Phase 2, after
  the design is frozen, so the fix routes through an `edit-design` mutation on
  the frozen design — heavier than a creation-time catch, but rare (the author
  is mandated to absorb the log) and the path exists. (2) The S3 freeze-order
  gate on the cold-read retires: a cold readability/comprehension pass does not
  read the log, so it needs no "decisions cleared challenge" gate; the author
  simply does not start until the Phase 0→1 adversarial gate clears (already the
  structure). (3) `research_log_path` drops from the cold-read spawn on both
  targets.
- **Alternatives rejected:** two-pass split (warm reviewer kept) — leaves the
  comprehension verdict warm-compromised; re-lens the existing warm reviewer —
  the YTDB-1130 anti-pattern (warming returns).

### 2026-06-16T16:00Z [ctx=safe] — Cost: minimal tool surface via agent definitions (refines the prompts/-sibling choice)
Restrict every spawned role to its real tool needs. The per-spawn tool surface
is ~25-35K tokens; on the ~6-agent auditor fan-out that is ~150-200K in schemas
before any work. The `Agent` tool restricts tools only via an **agent definition**
(`.claude/agents/*.md` with a `tools:` allow-list) — a `general-purpose` spawn
always carries the full surface. So the new roles become agent definitions, not
`prompts/` files spawned general-purpose (refines 13:46Z).
- **Per-role allow-lists:** readability auditor = `Read`, `Grep` (no Edit/Write,
  no PSI — cold text-only); absorption agent = `Read` (+ maybe `Grep`);
  comprehension reviewer = `Read`; author = `Read`, `Write`, `Edit`, mcp-steroid
  PSI, `Bash` (no WebSearch/YouTrack); phase4 fidelity agent = `Read` + PSI.
- **Risk:** a too-tight list fails the agent mid-task — get each list right.
- **Aligns with** the project's existing YTDB-1094 tool-allow-list direction.

### 2026-06-16T16:45Z [ctx=safe] — CORRECTION: MEMORY.md / CLAUDE.md are NOT per-agent configurable; 16:30Z retracted
A `claude-code-guide` lookup against the official docs overturns the 16:30Z
"MEMORY=none" decision. Custom sub-agents inherit **both** CLAUDE.md and the full
memory hierarchy; there is **no frontmatter field or setting** to skip them. Only
the built-in **Explore** and **Plan** agents skip CLAUDE.md + memory + git status,
and even that is not tunable.
- **Explore does not fit our roles:** read-only (rules out the writing author);
  its built-in "locate code, don't audit" disposition fights the readability
  auditor's load-bearing audit/enumerate disposition (the YTDB-1130 thesis); and
  it defaults to a weaker model than our Sonnet/default picks. So we use custom
  agents and accept the baked-in ~10K (CLAUDE.md ~7.4K + memory ~2.8K)/spawn.
- **Mitigation = the cache warm-up (idea 2), not removal.** This project's round-3
  measurement puts injected CLAUDE.md inside the ~21K shareable user body — shared
  when the spawn prompt is byte-identical. So the warm-up amortizes CLAUDE.md +
  memory across the fan-out (~once cold + (N−1)×0.1×), even though we cannot
  remove them. The warm-up now does double duty. Single spawns still eat the ~10K
  per spawn (recovered only across byte-identical cross-round re-spawns in TTL).
- **`tools:` allowlist CONFIRMED works** (idea 1 survives): the allowlist form
  cuts tool-schema boot cost (use `tools:`, not `disallowedTools` which loads the
  full surface then excludes); MCP filters by server (`mcp__localhost-6315` for
  the PSI set, no per-tool cherry-pick). Tool surface (~25K) is the bigger chunk
  than CLAUDE.md+memory (~10K) anyway.
- **Surviving cost levers:** idea 1 (tools allowlist), idea 2 (cache warm-up — now
  also the CLAUDE.md/memory mitigation), idea 3 (author grounds once). Idea 4
  (memory/CLAUDE.md trim) is dead for custom agents.

### 2026-06-16T16:30Z [ctx=safe] — Cost: spawned agents carry no MEMORY.md; CLAUDE.md per-role (RETRACTED 16:45Z — not per-agent configurable; see 16:45Z)
The spawned roles are narrow and stateless; MEMORY.md (cross-session branch
states, cost studies, feedback) is orchestrator-level knowledge none of them
acts on. So **MEMORY=none for all five roles** — pure boot cost, zero benefit
(measured ~2.8K/spawn; tens of thousands of tokens across a fan-out × rounds).
- **CLAUDE.md (~7.4K) is per-role, not blanket none:** the read-only narrow
  agents (auditor, absorption, comprehension) need none; the **author** is
  code-grounded and uses PSI, so it may want CLAUDE.md's PSI-routing / code-style
  rules — trimmed-but-present. Same lever as the existing sub-agent-boot-cost
  work (CLAUDE.md monitor-sections → `.claude/docs/`).
- **Helps cache-sharing too:** removing memory injection removes a potential
  source of prefix variation (session-specific framing would bust the
  byte-identical prefix idea 2 needs) and shrinks the cold-write. Trimming the
  prefix and amortizing it are complementary.
- **Mechanism to verify (design phase):** the exact config surface to set
  MEMORY=none and trim CLAUDE.md per agent is uncertain — agent-definition
  frontmatter, a `settings.json` key, or tied to the memory tool's presence in
  the allow-list. Confirm against the Claude Code docs (`claude-code-guide`)
  before relying on it.

### 2026-06-16T16:10Z [ctx=safe] — Author keeps code/PSI access every round; saving is "no full re-grounding", not "prose-only after round 1"
Corrects a too-strong cost lever ("author grounds once, prose-only after"). A
too-terse / unexplained-mechanism readability finding is fixed by *adding*
code-accurate explanation (a worked interleaving, the why-of-each-step), which
the author cannot induce from the existing dense text — it must re-consult the
code. So:
- **The author keeps code/PSI access in every round**, not just round 1; its tool
  allow-list (16:00Z) keeps PSI for the whole loop, never round-dependent.
- **The real saving is no *full* re-grounding:** round 1 grounds the whole doc;
  later rounds re-consult the code only for the specific passages the auditor
  flagged as under-explained (its enumerate-or-fail findings are specific enough
  to target). Density / word-choice / sentence-shape findings need no code; only
  the too-terse subset triggers targeted re-grounding. Scoped delta, same shape
  as the fidelity and absorption deltas.
- **Author later-round cost is genuinely variable and partly irreducible:** if the
  auditor demands explanation, the author does real code work to supply it. The
  bound is the reconstructibility bar (auditor stops at "a mid-level dev can
  follow it", § Orientation bans tutorial bloat) plus the iteration cap, not a
  prose-only restriction.

### 2026-06-16T16:00Z [ctx=safe] — Cost: fan-out cache warm-up + byte-identical prompt + params-in-file
Validated by this project's measured sub-agent cache-sharing results.
- **Warm-up (precise timing):** sub-agents share the prompt cache, but a
  cold-write double-write with ~1-turn propagation lag means starting all N at
  once races the cold write and each pays it cold. Routine: (1) spawn agent 1;
  (2) wait a **short fixed delay ~1 min** — long enough for agent 1's cold-write +
  propagation (lands in its first turn, so seconds-to-a-turn; ~1 min is a safe
  margin); (3) spawn agents 2–N **concurrently**, reading the warm common prefix;
  all N then run in parallel. Takes the fan-out from N cold prefixes to ~1 cold +
  (N−1)×~0.1×.
  - **Do NOT wait for agent 1 to finish.** Two reasons: **staleness** — the cache
    TTL is ~5 min, so blocking until agent 1 completes (minutes for a heavy agent)
    pushes the fan-out start toward/past the TTL and risks the prefix aging out;
    once all N run they each refresh the entry, keeping it warm for the fan-out.
    **Concurrency** — waiting for completion serializes the fan-out (agent 1 then
    2–N) and loses the parallelism; the short delay overlaps the rest with agent
    1, so wall-clock ≈ ~1 min + slowest single agent, not the sum.
  - **Delay window:** `[cold-write done, TTL)` ≈ `[~seconds, 5 min)`; ~1 min is
    the safe middle, tunable but must stay past the first turn and well under 5
    min.
- **Byte-identical spawn prompt:** a stable prefix + variable tail gets ZERO
  reuse (the varying tail busts the whole ~21K user body). So per-agent
  parameters (range start/end, target path) go in a **param file the agent reads
  as its first action after the static load**, never in the spawn prompt. Static
  content first (identical), divergence (the range read) last, so the identical
  read trajectory shares and only the range delta is per-agent.
- **Not a naive parallel fan-out:** the warm-up is deliberate sequencing (spawn
  one → await first read → fan out the rest), not `parallel()` which races the
  cold write.
- **Iterate-loop extension:** write each round's auditor findings to a **file the
  author reads**, keeping the author's re-spawn prompt static across rounds so
  re-spawns within the 5-min cache TTL share the prefix.
- **Compounding:** the minimal tool surface (16:00Z above) shrinks the shared
  prefix; the warm-up amortizes what remains; the residual is each agent's actual
  range-read + finding-generation (the real work).
- **Trade-offs (accepted):** warm-up adds ~1 turn of latency and its "agent 1
  warm yet" trigger is heuristic; the orchestration in `edit-design` /
  `create-plan` Step 4b gets more intricate (param files, sequencing,
  findings-in-file). Justified because the loop re-runs the fan-out every round.

### 2026-06-16T13:46Z [ctx=safe] — Cold auditor is a new prompts/ sibling, not an extraction from design-review.md (REFINED 16:00Z — roles become agent definitions for tool allow-lists; the readability auditor's contract/prompt can still live in a prompts/ file the agent def points at)
Add a new `.claude/workflow/prompts/` reviewer prompt that reuses the
`readability-feedback` audit sub-agent contract. It reads `house-style.md` + the
doc only, never the research log.
- **Why:** the cleanest guarantee of YTDB-1130's separate-cold-agent / no-log
  condition, and it reuses an already-proven enumerate-or-fail contract. A new
  file is also lower-risk than refactoring the load-bearing `design-review.md`.
- **Alternatives rejected:** extract the prose axis out of `design-review.md` —
  keeps the auditor adjacent to the warm reviewer and churns a load-bearing
  file with no offsetting benefit.

### 2026-06-16T15:12Z [ctx=safe] — PR-description authoring is out of scope (tracked by an existing issue)
Applying the loop to the Phase-4 PR description was considered (same
curse-of-knowledge root cause, durable human-facing surface) and **dropped from
this branch** — an existing issue tracks it. This branch's authoring-loop scope
is the design docs and track files only.
- **Why:** keeps an already-multi-component branch bounded; the PR-description
  surface adds a third target plus new Phase-4 / `gh pr edit` wiring that an
  existing issue owns.
- **Non-Goal for the plan / PR `## Motivation`:** PR-description readability is
  not addressed here. The validation that informed the drop (author=Sonnet OK,
  but the readability auditor should not be downgraded to Sonnet without a
  dry-run; the PR failure mode is thinness not density; add a fidelity/coverage
  check) is carried here for whoever picks up that issue.

### 2026-06-16T17:25Z [ctx=safe] — Collapse the Step-4a/4b session boundary into one /create-plan invocation (full tier)
The `full`-tier Step-4a/4b session boundary was a context-isolation proxy: Step
4a authors the design with the research conversation warm in main context, and
the boundary forces a `/clear` so Step 4b derives the plan from the frozen design
cold. Sub-agent authoring provides that isolation directly — the plan/track
author is a fresh cold spawn reading the frozen committed design regardless of
session — so the boundary is redundant. Collapse 4a + 4b into **one** invocation:
author the design (loop → freeze → commit), then same-session spawn the plan/track
author. UX win (one invocation, no manual re-invoke).
- **Condition:** the orchestrator must work **by-reference** (sub-agents write
  files, orchestrator reads thin summaries) so the main session does not
  accumulate authoring context across the design loop + the plan loop. We adopt
  that discipline anyway (params-in-file, findings-in-file). If the orchestrator
  pulled full artifacts into context, the boundary would still earn its keep.
- **Retained:** the freeze + commit stays as a logical gate (design frozen +
  committed before the plan derives) and a crash checkpoint — Step 1c still
  resumes into 4b from a committed-clean design if the session dies mid-derivation.
  The commit-after-freeze was always the recovery checkpoint; the session boundary
  just coincided with it.
- **Step 1c simplifies:** the auto-resume-into-4b becomes crash-recovery-only, not
  the happy path.
- **Residual risk:** a very large design makes even the by-reference combined
  session long — mitigated by the mid-phase handoff + context monitor, same as any
  long phase.
- **Broader pattern (noted, not chased here):** sub-agent work dissolves
  context-isolation session boundaries generally; this branch takes only the
  4a/4b instance.

### 2026-06-16T14:58Z [ctx=safe] — The loop also authors track files (create-plan Step 4b), so lite/minimal get readability (expands 14:38Z)
`edit-design` only handles `design.md`, which exists only in `full`. In
`lite`/`minimal` there is no design; the durable human-facing carrier is the
**track files**, authored by the planner inline in `create-plan` Step 4b. So a
loop wired only in `edit-design` leaves the no-design tiers with zero readability
help — and those tiers are where dense log-derived prose lands with no design
buffer. The loop therefore runs at **both authoring points**, `target`-keyed:
- **`edit-design` Steps 1/4/6** → `design.md`/`design-final.md` (`target=design`),
  `full` only.
- **`create-plan` Step 4b** → the track files (`target=tracks`), **every tier**.
- **Same loop, reused roles:** one author prompt and one readability auditor,
  both `target`-parameterized; absorption and comprehension already key on
  target. Author source: frozen `design.md` in `full`, the research log directly
  in `lite`/`minimal` (the heavy lift — dense log → cold-readable track prose).
- **Track absorption** = log/design decisions → track DRs (warm Sonnet), so the
  `target=tracks` cold-read de-warms too, mirroring the design side.
- **Resolves the open track-coverage thread:** tracks get the full author loop,
  not "at most a verify pass." `full` runs the loop twice (design, then tracks);
  `lite`/`minimal` run it once (tracks).
- **Second wiring point of change:** `create-plan` Step 4b (the planner-inline
  track derivation becomes an author-spawn + the inner loop), beside the
  `edit-design` rework.

### 2026-06-16T14:38Z [ctx=safe] — Wire the loop into both phase1-creation and phase4-creation (expanded 14:58Z — also wired into create-plan Step 4b track authoring)
The two-role loop wires into both `edit-design` creation kinds. phase4 is the
more important target — `design-final.md` is the durable human-facing doc that
survives merge, while the phase1 `design.md` is removed at cleanup.
- **Same loop shape**, parameterized by `(source-of-truth, second-check)`:
  - phase1: source = research log; second check = **absorption** (log decisions
    → seed D-records; warm Sonnet).
  - phase4: source = as-built code + episodes + track Decision Logs; second
    check = **fidelity** (`design-final.md` ↔ as-built code), building on the
    PSI-backed verification `create-final-design.md` already runs.
- **phase4 must not absorb the log:** implementation may have superseded planned
  decisions (inline replans, scope-downs in episodes), so phase4 reflects what
  was built, not the plan. Re-asserting a superseded log decision would be a
  fidelity bug.
- **Readability auditor + cold comprehension gate are identical** across both.
- **Per-round fidelity cost — RESOLVED 15:45Z (episode-primary):** see the
  15:45Z entry. The primary fidelity source is the episodes (cheap text match),
  not the code; PSI shrinks to a narrow diagram residual.
- **Likely two tracks** at decomposition: phase1 wiring + the author/auditor
  prompts as one unit, phase4 wiring (create-final-design rewire + fidelity
  check) as a dependent second — a planning concern, noted not decided.

### 2026-06-16T14:50Z [ctx=safe] — The authoring loop lives in `edit-design` (Steps 1/4/6); tracks are a separate boundary
The two-role authoring loop is a rework of `edit-design`'s existing Workflow:
Step 1 (apply/author) spawns the **author** sub-agent instead of authoring
inline; Step 4 (verify) spawns the readability auditor + absorption agent and,
after convergence, the cold comprehension+structural gate; Step 6 is the bounded
inner loop (cap = `iteration_budget`). `edit-design` becomes the multi-agent
orchestrator. Both phases get it for free because both callers route through
`edit-design`: `create-plan` Step 4a (`phase1-creation`) and
`create-final-design.md` (`phase4-creation`).
- **Track boundary (REVISED by 14:58Z):** the `target=tracks` Step-4b cold-read
  is **not** in `edit-design` — `create-plan` Step 4b owns track authoring. Per
  14:58Z the loop is wired there too, so tracks **do** get the full author loop
  (the planner-inline derivation becomes an author-spawn + inner loop), not just
  a verify pass. The loop is wired at two points, not design-doc-only.
- **Files of change (preliminary):** `edit-design/SKILL.md` (Steps 1/4/6 +
  drop the S3 gate and `research_log_path` from the cold-read) is the
  concentration point; two new `prompts/` files (author, readability auditor);
  the small absorption agent; `design-review.md` de-warmed (drop log read +
  absorption; lose the prose axis to the auditor); `create-final-design.md`
  lightly rewired for the fidelity check; `conventions.md` (S2/S3 prose,
  per-tier mentions of the loop). Track split firms up at planning.

### 2026-06-16T15:45Z [ctx=safe] — phase4 fidelity is primarily doc-vs-episodes (cheap), PSI only for the diagram residual
phase4 fidelity is checked **primarily against the episodes**, not the code. The
step + track episodes plus the track `## Decision Log` inline-replan entries are
the as-built record — written as the work happened, carrying both what was built
and what diverged from the plan and why. Checking design-final.md against them is
text-vs-text (Sonnet, cheap), like absorption. This collapses the phase1/phase4
asymmetry: both second-checks are cheap text matching (phase1 doc-vs-log, phase4
doc-vs-episodes).
- **Episodes beat code for the bulk:** the code shows the final state but not the
  *why*; the episodes carry the divergence reasoning design-final.md must explain.
- **Where episodes run out (the PSI residual):** (1) precision — an episode says
  "added a per-class record helper" but the class diagram states an exact
  signature and the sequence diagram draws a specific call arrow; only PSI
  confirms those. (2) staleness — episodes are per-step and a later step can
  rework earlier code, so later episodes supersede earlier ones.
- **Design:** primary per-round check = doc-vs-episodes (Sonnet). PSI is the
  narrow residual for the diagram/signature-level claims — `create-final-design`'s
  existing diagram→code verification tables, run once at entry and re-verified
  per-round only if a round touched a diagram (rare — readability edits do not
  reword Mermaid signatures). So per-round cost is mostly the cheap episode-match.
- **phase4 author source firmed:** episodes + track Decision Logs + the original
  design (+ PSI/code for diagrams).
- **Supersedes** the 14:38Z "entry-gate PSI + light delta" framing — the bulk of
  fidelity is episode-match, not a PSI baseline.

### 2026-06-16T15:30Z [ctx=safe] — § Human-reader items split by context-need; auditor gets anchor sections
Resolves the open thread on where `design-review.md`'s five § Human-reader items
land. They split by the context each check needs, because the readability
auditor is range-sliced (`readability-feedback` fan-out) and cannot see
whole-doc properties.
- **Auditor (range-sliced + standing anchor sections):** each range agent reads
  `house-style.md` + its ~200-line slice **plus the Overview + Core Concepts as
  standing context**. With the anchors in view it owns the § Prose AI-tell axes
  (over-dense / too-terse / hard-to-read) + **explanatory register** (it is §
  Orientation specialized) + **why-before-what** + **glossary-introduction** (the
  anchors resolve "defined in Core Concepts / named as Overview prereq", which a
  pure slice could not — a pure range agent would false-positive on every
  Core-Concepts-defined term) + the "prose written for the named reader" half of
  **audience-fit**.
- **Cold comprehension reviewer (whole-doc):** **navigability** (cross-section
  refs, the roadmap, scan-ability — overlaps comprehension Q7) + the structural
  half of audience-fit ("does the Overview name a reader at all?") + the 7
  comprehension questions + the structural-findings set.
- **Why not all five on the whole-doc reviewer:** that re-loads it with
  readability axes and re-creates the multi-axis dilution YTDB-1130 diagnosed
  (now cold, so warming is gone, but dilution returns). Concentrating readability
  on the dedicated auditor preserves the per-slice attention; the anchor sections
  are the minimal whole-doc context it needs.
- **Transfers to `target=tracks`:** the auditor's standing anchors become the
  plan Component Map + the track `## Purpose / Big Picture`; navigability across
  track files stays whole-doc on the comprehension reviewer.

### 2026-06-16T17:40Z [ctx=safe] — Dogfood on real existing targets, not self-referentially (self-wiring is blocked)
Considered dogfooding: have the new routine author this branch's own design.md +
plan. The literal version is **blocked** two ways; the instinct is honored via
real targets instead.
- **Blocked — §1.7 staging (I6):** workflow-modifying branch, so the new routine
  accumulates in the staged subtree and the live `.claude/` stays at develop until
  the Phase 4 promotion. This branch's Phase-1 `/create-plan` runs the OLD routine.
- **Blocked — temporal bootstrap:** the new routine's code is this branch's output
  (built in Phase 3); it does not exist at Phase 1 when the design is authored.
- **Circular manual version skipped:** hand-spawning author+auditor on this very
  design is chicken-and-egg (prompts not final at design time) and a non-standard
  Phase-1 flow.
- **Real dogfood points (make 1 + 2 explicit plan/validation items — both already
  YTDB-1130 deliverables):**
  1. **Now:** run the existing `readability-feedback` skill on this branch's
     design.md once authored (YTDB-1130's "dry-run readability-feedback against a
     fresh design doc"). Available today, validates the verify half.
  2. **Phase 3:** validate the implemented routine against the known-dense
     `transactional-schema` design.md (YTDB-1130's "close the loop: confirm the
     dedicated pass subsumes the hole"). Clean target, no circularity.
  3. **Post-promotion:** the next design uses the routine naturally — the full
     dogfood.

### 2026-06-16T17:00Z [ctx=safe] — Agent SDK + cross-model review considered, deferred (keep Agent tool)
Considered moving sub-agent spawning to the Agent SDK (Python script) to (a)
control boot context and (b) enable a vendor-independent API for cross-model
review (cover one model's blind spots by authoring and reviewing with different
models). Recommended **against for this branch**; keep the Agent tool.
- **Memory variability conceded:** within one fan-out all N agents inherit the
  same memory snapshot (byte-identical), so memory adds no intra-fan-out
  variability — the earlier "memory could bust the prefix" concern was about
  cross-session drift, not the fan-out.
- **SDK and cache-sharing — CORRECTED 17:10Z (earlier claim was wrong):** prompt
  caching is content-keyed, not session-keyed. The cache key is `(org, model,
  exact token prefix up to a cache_control breakpoint)`; a byte-identical prefix
  from any client/process hits the same entry within TTL. The Agent tool's
  sharing is "free" only because Claude Code builds byte-identical sub-agent
  prefixes by construction and sets the breakpoints automatically — convenience,
  not a session-scoped cache. So the SDK does **not** forfeit caching: it shares
  among its own agents trivially (you control the prefix + `cache_control`), and
  could even build a *smaller* prefix (omit CLAUDE.md/memory) and self-cache — on
  raw token cost potentially better, not worse. The real case against the SDK is
  **engineering burden** (you reimplement prefix assembly, breakpoint placement,
  spawn lifecycle, warm-up plumbing, and keep the prefix byte-identical across
  versions — Claude Code does all this for free) and **paradigm consistency**, not
  a cache-capability loss. Minor genuine cache nuance favoring the Agent tool: SDK
  agents won't share the orchestrator's cache (different prefix), only each other.
  YTDB-1105 still chose Agent-tool-not-SDK, but the load-bearing reasons are
  burden + paradigm, not "SDK can't cache."
- **Cross-model review — sound in principle, wrong here:** different models catch
  different defects, and same-model author+auditor risks a shared blind spot. But
  it needs the SDK (inherits the cache loss), is a major paradigm departure from
  the Claude-Code-native workflow (skills / Agent tool / prompts), and — for
  *readability specifically* — YTDB-1130's evidence is that the gain was
  **disposition**, not model (the same model re-tasked as auditor caught the
  density). Cross-model is more compelling for *correctness* review (bugs,
  security) than prose.
- **Disposition:** keep this branch on the Agent tool, one model. Cross-model
  review is a separate, larger, workflow-wide initiative with its own cost/benefit
  (likely targeting correctness reviews), orthogonal to making design docs
  readable. Revisit only if the user overrides.

## Open Questions

### 2026-06-16T13:40Z [ctx=safe] — Author input: log-only vs log + code-grounding (PSI) — RESOLVED 13:46Z, see Decision Log (code-grounded)
YTDB-1130 comment 3 says the author drafts from the research log alone (context
approximates the reader's). Comment 4 found code-grounding (audit worklist +
codebase + PSI + TRANSLATE-current-state mandate) beat the prose-only floor by
~50%. Provisional resolution to settle: the author reads the log **and** has
codebase/PSI access to ground current-state, but not the authoring
conversation; the "write for a reader who has only the doc" mandate governs the
*output* (gloss, orient, explain), not the author's grounding inputs.

### 2026-06-16T13:40Z [ctx=safe] — Cold-auditor placement: extract from design-review.md vs new sibling prompt
Either extract the prose axis out of `design-review.md` into a dedicated pass,
or add a `prompts/` sibling reusing the readability-feedback audit prompt. The
load-bearing constraint is that the auditor stay a separate cold agent (no
research-log read); placement is the open call.

### 2026-06-16T14:45Z [ctx=safe] — Keep Mermaid; no diagram-format change in this branch
Keep the current Mermaid convention. No ASCII/SVG requirement, no D2/mermaid-cli
toolchain. The author emits Mermaid where a diagram earns its place (the
reconstructibility bar — worked interleavings, timelines — is carried by Mermaid
sequence/flowchart diagrams).
- **Why:** scope-reducer — the diagram format is orthogonal to the two-role
  loop, and Mermaid is already agent-readable (source), GitHub-native, and
  toolchain-free. Pulling diagram tooling into this branch would expand scope
  with no benefit to the loop.
- **Accepted trade-off:** Mermaid does not render in a terminal or reliably in
  Okular (YTDB-1130 comment 3's complaint). It renders on GitHub + IDE preview,
  and the source is agent-readable, so the durable `design-final.md` (read on
  GitHub) and the author/auditor agents are unaffected.
- **Alternatives rejected:** hybrid ASCII + committed SVG (workflow-book
  precedent) and ASCII-only — both solve terminal rendering but add a toolchain
  / convention change outside this branch's purpose.

### 2026-06-16T13:40Z [ctx=safe] — Diagram format: Mermaid vs ASCII vs matplotlib raster (RESOLVED 14:45Z — keep Mermaid, see Decision Log)
Now load-bearing, not a side note: D1/D2 make diagrams part of the
reconstructibility bar (work the interleaving, draw the timeline), and the
author creates them because the log carries none. The target reader is a
mid-level dev who may read in a terminal where Mermaid does not render. Back in
scope now that the branch carries the author (which emits diagrams). Leaning
ASCII-default + SVG-with-committed-text-source for genuinely complex diagrams;
an external SVG alone is opaque to the author/auditor agents (13:50Z finding).
Settle before the author role hard-codes an emitted format.

### 2026-06-16T13:55Z [ctx=safe] — Fate of design-review.md's prose axes once the auditor owns readability — RESOLVED 2026-06-17T07:37Z, see Decision Log (auditor owns § Prose AI-tell on every prose-checked surface; design-review.md drops it; gate A1)
Once the dedicated cold auditor owns prose-readability, design-review.md's
§ Prose AI-tell additions (over-dense / too-terse / hard-to-read) and
§ Human-reader cold-read additions (audience-fit, glossary, why-before-what,
navigability, explanatory register) should not also run there, or the diluted
version YTDB-1130 diagnosed still fires. Open: remove the prose axis from the
warm reviewer entirely (auditor owns it), or keep the structural/comprehension
half (glossary, why-before-what, navigability) on the warm reviewer and move
only the sentence-level § Prose AI-tell axis. Wrinkle: § Prose AI-tell also runs
on the `target=tracks` Step-4b track cold-read, so its removal touches the track
path too.

### 2026-06-16T13:55Z [ctx=safe] — Loop topology, cost, iteration cap, and phase1-vs-phase4 wiring — sub-parts (a)/(c) resolved 14:50Z/14:38Z/14:58Z; sub-part (b) cost/convergence RESOLVED 2026-06-17T07:37Z, see Decision Log (gate A2)
The author and auditor must be separate spawns (the auditor stays cold), so the
`edit-design` skill orchestrates rounds: author writes → auditor enumerates →
fresh author spawn revises with the findings → re-audit, until the auditor is
clean or an iteration cap is hit (reuse `iteration_budget`, default 3). Open
questions: (a) is the author↔auditor a tight inner loop that converges first,
with the existing warm comprehension/absorption review (design-review.md) as the
outer gate on an already-readable doc? (b) cost — each author round is
code-grounded (PSI + codebase reads) and each auditor round fans out ~6 range
agents, so the cap and convergence matter; (c) wire into `phase1-creation` only,
or `phase4-creation` too (where `create-final-design` already does PSI-backed
verification and could route through the author)?

## Baseline and re-validation

<!-- Workflow-modifying branch (.claude/** machinery): this section anchors the
develop baseline the staged workflow forks from, filled as the baseline is
established during planning. -->

## Adversarial gate record

### Adversarial review of this log (2026-06-17T07:37Z) — NEEDS REVISION: 0 blocker, 6 should-fix, 3 suggestion
Review file: `_workflow/reviews/research-log-adversarial-iter1.md`. Two load-bearing
open questions still open (A1 prose-axis landing site, A2 dual-clean loop
convergence/cost); A3–A6 are decisions that survive with under-stated rationale; A7–A9
are suggestions. Resolutions appended to the Decision Log this iteration; re-challenge
at iteration 2.
