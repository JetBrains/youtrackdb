<!-- workflow-sha: 0000000000000000000000000000000000000000 -->
# Research-log adversarial review (iter 2) — no-track-for-minimal

Phase 0 → 1 gate, iteration 2 (verdict-producer variant). Target:
`_workflow/research-log.md` (`## Decision Log`, `## Surprises & Discoveries`,
`## Open Questions`). Matched categories: Workflow machinery, Architecture /
cross-component coordination — the workflow-machinery prose-scrutiny stance
applies (rule coherence, instruction completeness, context-budget impact); no
`review-workflow-*` dispatch, no `.claude/**` diff exists at this boundary. All
references verified via grep + Read against live workflow Markdown/Bash, not
PSI.

This iteration re-challenges the five iter-1 findings against the now-current
log and the live codebase, emits a verdict per prior finding, and surfaces any
new finding. No new finding survived the certificate bar.

## Manifest

```yaml
<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: REJECTED}
overall: PASS
flags: [CONTRACT_OK]
-->
```

## Evidence base

### Prior-finding re-challenges

#### Assumption test (A1 re-challenge): does the Step 1c rewire close the minimal-resume regression?
- **Prior claim**: a plan-less `minimal` resume hits `create-plan` Step 1c's
  "**Neither file exists — fresh start**" branch and re-runs Step 4 (research +
  tier + this gate), reintroducing the regression the ledger exists to prevent.
- **Revision under test**: Decision Log entry "Step 1c resume routing rewired to
  the ledger (not plan presence)" (`research-log.md:109-120`) — minimal resume
  disambiguates on "ledger present + tier line readable"; lite/full keep
  plan-presence; `plan/track-1.md` glob is the secondary signal.
- **Code evidence**: live `create-plan/SKILL.md:203-210` still routes
  neither-file to fresh-start and reasons "with no `implementation-plan.md` on
  disk there is no tier line to read" — exactly the consumer the revision
  rewires. The rewire targets the right line. Two coherence checks pass:
  (1) The revision does not contradict Decision 3/6 (ledger = single source of
  truth for resume state). Step 1c is a *tier-shape* disambiguator (which
  artifacts should exist for this tier), distinct from `determine_state`'s
  *phase-state* read; keeping plan-presence for lite/full is sound because the
  plan still exists by design there, while the ledger's tier line is the
  authoritative minimal signal. (2) The seeding timing holds: the ledger's
  branch fields are "set at first entry" and create-plan "seeds ledger" at
  Step 4 (`research-log.md:94,262`), the same point Step 4 writes the tier line
  into the plan today — so the tier line is on the ledger before any
  planning-session interruption, and the minimal resume can read it. The
  secondary `plan/track-1.md` glob is reliable: Surprise #1 establishes
  `track-1.md` is "always-present stamped" under the canonical-track model
  (`research-log.md:224`), and `conventions.md:82` confirms the track file is
  created in Phase 1 in every tier.
- **Verdict**: VERIFIED. The fourth consumer is now surveyed, the rewire is
  added to the Open-Question ripple list (`research-log.md:262`), and the
  routing is internally consistent with the ledger-authoritative model.

#### Assumption test (A2 re-challenge): is the minimal→lite/full ESCALATE materialization now covered?
- **Prior claim**: `inline-replanning` is a tier-line WRITER, not just a reader;
  a `minimal`→lite/full upgrade must materialize the `implementation-plan.md`
  (and `design.md` for full) the minimal tier never had — a structural step the
  log did not carry.
- **Revision under test**: Decision Log entry "minimal→lite/full ESCALATE
  materializes the dropped plan/design" (`research-log.md:122-132`) — the upgrade
  carrier writes the upgraded tier as a ledger event AND creates the now-required
  plan/design. Ripple list updated (`research-log.md:264-265`).
- **Code evidence**: live `inline-replanning.md:150-163` confirms the writer
  shape unchanged — "The first artifact an upgrade lands is the D18 tier line
  rewrite"; "A `lite`→`full` upgrade that also gains a `design.md` writes the new
  design seed alongside the tier-line rewrite." The revision names exactly this
  writer and adds the missing materialization (plan for lite, plan+design for
  full) plus the tier-event ledger write. The decision's `**Why**` correctly
  identifies the root cause: "the five tier-line readers → ledger routing
  under-specified the writer/materialization side."
- **Verdict**: VERIFIED. The writer/materialization side is now explicit; the
  destination-tier-needs-source-tier-lacks-artifacts case is addressed.

#### Assumption test (A3 re-challenge): does the branch's stated §1.7 mode match the criteria?
- **Prior claim**: the log never states the branch's own §1.7 mode; §1.7(k)
  criterion 1 disqualifies it (moves resume-state field + adds track-file
  section), so it is staging-bound under §1.7(b).
- **Revision under test**: Decision Log entry "This branch is workflow-modifying
  (§1.7(b)); it stages — not §1.7(k)-eligible" (`research-log.md:134-149`).
- **Code evidence**: live `conventions.md §1.7(k):1234-1245` — criterion 1
  disqualifies a plan when "a track-file section, resume-state field, drift-gate
  format, or stamp format moves." The branch moves the resume-state field (plan
  checkboxes → ledger, Decisions 3+6) and adds a track-file section
  (`## Invariants & Constraints`, Decision 9); each independently fails criterion
  1, so the §1.7(b) staging-bound classification is correct. The decision also
  records the right downstream consequences: all `.claude/**` edits stage under
  `_workflow/staged-workflow/`, the derived plan's `### Constraints` carries the
  §1.7(b) marker (whose canonical spelling I confirmed at
  `conventions.md:918`), implementer/reviewer steps use staged-read precedence,
  and the branch runs under the current (develop) workflow while shipping the new
  one (so its own artifacts use today's format). The "consistent with the MEMORY
  hidden-research-log finding" cross-reference is apt and, if anything, this
  branch is more clearly staging-bound (it also moves the resume-state field, not
  only touches a SKILL.md).
- **Verdict**: VERIFIED. The classification is deterministic from the criteria
  and the log now states it; the design will derive under the staging assumption.

#### Open-question test (A4 re-challenge): is the ledger-stamp half settled on sound precedent?
- **Prior claim**: the ledger half of the stamp open question is pre-decided and
  deferrable (append-only, replay-immune, follows the `research-log.md`
  precedent); only the plan-review-doc half is genuinely open.
- **Revision under test**: Open Question tightened — "ledger SETTLED — unstamped,
  follows the `research-log.md` precedent, not added to the §1.6(h) walk (A4).
  Only the plan-review-doc stamp status stays open" (`research-log.md:254-257`).
- **Code evidence**: live `conventions.md §1.6(f):753-768` excludes
  `research-log.md` with the verbatim rationale the ledger inherits — "an
  append-only Phase-0/1 ledger that no §1.6(h) walk enumerates and no phase
  machinery rewrites or re-derives… replay-immune by construction." The drift
  walk glob at `workflow-startup-precheck.sh:488-491` enumerates exactly four
  types (`implementation-plan.md`, `design.md`, `design-mechanics.md`,
  `track-*.md`); the ledger is not in that set, so an unstamped ledger is neither
  walked nor flagged — the precedent transfers exactly. The §1.6(f) text also
  records that growing the stamped set would force edits to all three precheck
  walks plus the conformance fixture, which §1.7 staging's S1 invariant forbids
  on this branch (`conventions.md:765-766`) — an independent reason to keep the
  ledger unstamped, reinforcing the SETTLED verdict.
- **Verdict**: VERIFIED. The ledger half is settled on the correct precedent; the
  reduction to "only plan-review-doc stamp open" is accurate.

#### Decision challenge (A5 re-challenge): does the plan-review doc survive without folding into the ledger?
- **Prior claim**: Decision 7 adds a third `_workflow/` artifact for a fact the
  log calls "rarely read… mostly states the fact"; an unlisted cheaper
  alternative carries the review fact as a ledger `review=passed` event with the
  summary inline, at zero new-artifact cost. Survival was WEAK — the doc holds
  only if the summary is large enough to bloat the ledger tail.
- **Revision under test**: the decision now carries a strengthened size rationale
  and an explicit user disposition (`research-log.md:168-174`): "the Phase-2 audit
  summary is multi-line review prose (consistency + structural findings,
  auto-fixes, escalations), not a one-liner; embedding it in the append-only
  ledger would bloat the tail `determine_state` greps. **User confirmed (gate
  iter1): keep the separate doc.**"
- **Code evidence**: the strengthened rationale resolves the exact WEAK condition
  iter1 named. The ledger tail is grepped by `determine_state` on every resume
  (`research-log.md:96-98`, ledger read by `determine_state` as the resume hot
  path), so keeping multi-line audit prose (findings, auto-fixes, escalations)
  out of the greppable tail is a legitimate context-budget / hot-path argument,
  not ceremony. The split (review *state* → ledger event; review *fact + summary*
  → cold doc) is coherent with the "one canonical home" principle: the hot-path
  state lives where `determine_state` reads it, the cold multi-line record lives
  where it is not grepped on every resume. The decision is held by the owner
  (user) with a now-sound rationale. The remaining sub-details (filename,
  single-vs-per-iteration, stamp status) stay correctly parked as a non-blocking
  Phase-1 detail Open Question (`research-log.md:252-253`).
- **Verdict**: REJECTED. The challenge does not survive: the size rationale is
  sound (multi-line audit prose vs greppable hot-path tail) and the decision is a
  held owner decision. The plan-review doc stands.

### New-finding scan

A scan for gaps the iter-1 revisions might have introduced found none that clear
the certificate bar:
- The A1 rewire's lite/full-keep-plan-presence branch does not contradict the
  ledger-authoritative model (Step 1c is a tier-shape disambiguator, not a
  phase-state read) — checked above.
- The A2 materialization decision is internally complete for both upgrade
  destinations (lite: plan; full: plan+design) and names the ledger tier-event
  write — checked above.
- The A3 staging classification's downstream consequences (staged subtree,
  §1.7(b) marker on the derived plan, staged-read precedence) are all named in
  the decision and match the live §1.7(b)/(k) text — checked above.
- The remaining Open Questions (`research-log.md:248-267`) are explicitly framed
  as Phase-1 design detail, not blocking the tier classification or design
  transition; none is a load-bearing not-yet-made decision that an artifact would
  derive over. The full consumer-update ripple (`:258-267`) is correctly deferred
  to track decomposition.

## Findings

No new findings. All five iter-1 findings are resolved (A1–A4 VERIFIED, A5
REJECTED as a held owner decision with sound rationale); the gate clears.
