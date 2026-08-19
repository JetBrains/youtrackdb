<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: S5, sev: should-fix, loc: "plan/track-9.md § Plan of Work item 4 (:73) and § Validation and Acceptance (:90), read against plan/track-11.md § Decision Log bullet 4 (:26)", anchor: "### S5 ", cert: "", basis: "the S2 fix's escape clause is keyed to 'changes no production code' while the rule it implements is 'anything that moves the measured behaviour', and under that branch Track 11's own Decision Log excludes the very artifact Track 9 would publish"}
  - {id: S6, sev: suggestion, loc: "plan/track-9.md § Validation and Acceptance, the `embedded` criterion (:83)", anchor: "### S6 ", cert: "", basis: "the re-install criterion still names item 3's re-run as its example; after S2 the handoff measurement is item 4's, and that is where a stale-jar run costs most"}
verdicts:
  - {id: S1, verdict: VERIFIED}
  - {id: S2, verdict: VERIFIED}
  - {id: S3, verdict: VERIFIED}
  - {id: S4, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

# Structural gate verification — iteration 1 (2026-08-03, 11-track plan)

All four iteration-1 findings are fixed as applied, and no blocker remains: **PASS**. S1's two intros
are at three sentences with every displaced fact still homed in `plan/track-9.md`. S2's chosen
rendering (publish last) reaches all eight sites that state the handoff, the flowchart is valid
Mermaid and agrees with the Plan-of-Work ordering, and nothing in either track file or the plan still
names item 3 as the handoff. S3's two `**Implemented in**` lines now match the plan's own Track 7
Strategy refresh and the pending tracks' registry work. S4's `embedded` mechanism appears once, both
pointers resolve, and the two Clarifications bullets sit at 102 and 71 words.

The re-scan surfaces two follow-ups, both created by the S2 fix itself. Its escape clause keys the
re-run on "production code" where the governing rule keys on measured behaviour, and under that
branch Track 11's Decision Log rules out exactly the artifact Track 9 would hand it (S5, should-fix).
The `embedded` re-install criterion still cites item 3's re-run as its worked example when item 4's
is now the published one (S6, suggestion). Neither reopens S2.

Artifacts read: `plan/track-9.md` and `plan/track-11.md` in full; `implementation-plan.md` D7, D9,
the Track 7, 8, 10, 9 and 11 checklist entries, `## Implementation state` and its table, and the
Constraints block; `structural-iter1.md` § Findings; `git diff HEAD` over both track files and the
plan.

**Reference-accuracy caveat.** No PSI query was run, and none was needed — every check below is a
prose read, a sentence or word count, or a cross-reference resolution inside the `_workflow` tree.
This review reads no codebase, so `certs: 0`.

## Verification certificates

#### Verify S1: Both pending Checklist intros run four sentences against the 1–3 cap
- **Original issue**: the Track 9 and Track 11 checklist intros were four sentences each. The
  2026-08-01 pass had already trimmed the then-Track-9 intro to three; the 2026-08-03 split
  re-expanded it and wrote the new Track 11 entry at four.
- **Fix applied**: Track 9's intro dropped the `3148ac14e1` stall detail and the 17 s figure and
  folded the `plan/track-9.md` pointer into its last sentence. Track 11's merged its sentences 3
  and 4 around the same pointer.
- **Re-check**:
  - Plan location: `implementation-plan.md` § Checklist, Track 9 intro `:671-677`, Track 11 intro
    `:695-703`.
  - Current state: three sentences each, matching the proposed targets word for word. Track 9 —
    (1) the measured baseline plus the on/off contrast at 1930 scenarios, (2) localize and record
    each runner's failure set, (3) the dropped filter with the `has(name, vadas)` witness and the
    track-file pointer. Track 11 — (1) runs after Track 9, (2) the four terminators and the D3
    declines, (3) the Cucumber gate, the JMH harness, and the pointer carrying DR-T1 through DR-T3.
  - Criteria met: the 1–3-sentence checklist-intro cap on both pending entries; BLOAT resolved.
- **Fact-preservation check**: every fact the Track 9 intro shed has a home in `plan/track-9.md`
  § Context and Orientation — `3148ac14e1` at `:32`, the zero-scenario / three-kill row at `:36`,
  the 1930 / 17.1 s translator-off row at `:37`, Track 10's closing run dying at the same feature at
  `:39`, and the `GQL Match Support` stall at `:39` and `:41`. Track 11's merge dropped no fact.
- **Regression check**: checked both entries' `**Scope:**` and `**Depends on:**` lines (unchanged)
  and `## Implementation state` (still agrees with both intros) — clean.
- **Verdict**: VERIFIED

#### Verify S2: Track 9 published its handoff baseline before its last behaviour change
- **Original issue**: item 3 re-measured "immediately after item 2" and handed that number to
  Track 11, while item 4 kept fixing behaviour afterwards. A scenario item 4 repaired was recorded
  as failing in the published baseline and could regress back silently; a scenario item 4 broke was
  recorded as passing and arrived at Track 11 as an inherited regression. Track 9's own
  recompute-whenever-behaviour-moves rule forbade both.
- **Fix applied**: rendering (a), publish last. Item 3's measurement became the gate on item 2; item
  4 re-runs both runners after its last fix and publishes that artifact as Track 11's baseline.
- **Re-check** — all eight sites that state the handoff:
  - `plan/track-9.md:24` (Decision Log bullet 2): "item 3 re-measures immediately after item 2
    lands. Item 4 moves the measured behaviour again … **the number handed to Track 11 is item 4's,
    taken after this track's last fix.** Item 3's measurement is the gate on item 2, not the
    handoff." The rule and its application now cover both items.
  - `plan/track-9.md:53-60` (flowchart): `Fix → Rebase["item 3: re-measure — the gate on item 2"] →
    Triage → Handoff["item 4 re-measure: the baseline Track 11 reads"]`. Six unique node ids, all
    labels quoted, `\n` line breaks as used throughout these files — valid Mermaid. The new
    `Handoff` node hangs off `Triage`, which is the Plan-of-Work order (item 4 triages, then
    re-runs); no contradiction.
  - `plan/track-9.md:72` (item 3 title): "**Re-measure immediately after item 2.**" — the "and
    publish that number" clause is gone.
  - `plan/track-9.md:73` (item 4): "**Re-run both runners once more after this item's last fix and
    publish that artifact as Track 11's baseline.**" plus the reason and the escape clause.
  - `plan/track-9.md:90` (handoff acceptance bullet): "The baseline handed to Track 11 is measured
    after item 4's last fix, not after item 2 …". It now sits after the triage criterion at `:89`,
    so the acceptance order and the Plan-of-Work order agree — the internal disagreement the
    original finding recorded is gone.
  - `plan/track-11.md:26` (Decision Log bullet 4): reads Track 9's "last measurement, not its first
    and not its post-item-2 one", and states that Track 9 re-runs both runners after its final fix.
  - `plan/track-11.md:69` (item 6): "the artifact Track 9 publishes after its **last** fix — its
    triage item re-measures on top of the filter fix".
  - `plan/track-11.md:87` (no-regression acceptance bullet): "no regression against the artifact
    Track 9 publishes after its last fix".
  - Criteria met: the cross-track contradiction is resolved and the publication point is stated once
    per site with a single reading.
- **Residue scan**: grepped both track files and the plan for `item 3` / `item-3` / `post-item-2` /
  `post-fix` / `handoff` / `hands`. No site names a post-item-2 handoff. The one surviving
  `post-item-2` (`plan/track-9.md:73`) labels the residue set item 4 reads, not the handoff. The
  bare "post-fix baseline" shorthand appears at six sites (`plan/track-9.md:108`,
  `plan/track-11.md:9`, `:52`, `:69`, `:104`, and the Track 11 checklist intro); each file carries
  the disambiguating statement above, so the shorthand resolves — not raised.
- **Sizing check**: Track 9's `~8–14` needs no move. The extra pass adds runs, not files —
  § Interfaces and Dependencies puts both runners in one artifact file ("one file carrying a
  labelled half per runner"), and item 4 amends that file rather than adding one. This is the same
  argument iteration 1 accepted for the `embedded` absorption. Track 11's `~14–20` is untouched: the
  fix moved no work across the boundary.
- **Regression check**: the fix introduces two new issues in the text it added — the escape clause's
  trigger scope (S5) and a now-stale worked example in the `embedded` criterion (S6). Neither
  reopens the original defect: on the default path every site publishes item 4's number.
- **Verdict**: VERIFIED

#### Verify S3: D7 and D9 named a stale set of implementing tracks
- **Original issue**: D7 credited Track 8 with a broadening Track 7 delivered, contradicting its own
  rationale two lines above. D9 stopped the per-class registry range at Track 7 although Track 8
  added one entry and Track 11 adds four.
- **Fix applied**: D7 → "broadened to the boundary base in Track 7 with the base extraction."
  D9 → "per-class entries added by Tracks 2–8 and Track 11".
- **Re-check**:
  - Plan locations: `implementation-plan.md:232-233` (D7) and `:289-290` (D9).
  - Current state and corroboration: D7's own rationale at `:228` says the scan "keys on the Track 7
    boundary base (D8 revised)", so the record agrees with itself. Track 7's Strategy refresh at
    `:570-571` says Track 7 "already retargeted the D7 idempotency `instanceof` scan to the base",
    and `:571-573` records Track 8's "broaden the scan" sub-item as a no-op the decomposer drops, so
    no Track 8 surface claims the broadening. For D9, Track 8's entry at `:577` names
    `UnionStepRecogniser`, and `plan/track-11.md:101` lists the four new recognisers
    (`FoldStep` / `UnfoldStep` / `ReverseStep` / `TailGlobalStep`). Track 9's § Interfaces lists no
    recogniser, so skipping 9 and 10 in the range is correct.
  - Criteria met: both Decision Records trace to the tracks that implement them; D7's
    self-contradiction is gone.
- **Regression check**: checked D8's `**Implemented in**` at `:274-275` ("Track 7 (boundary base),
  Track 8 (union)") and the decision-conformance sentence at `:734` (D8 "implemented in code across
  Tracks 7–8"; D3's surface reassigned to Track 11's four recognisers) — both agree with the edited
  lines. Clean.
- **Verdict**: VERIFIED

#### Verify S4: the `embedded` install rule was written out twice at full length
- **Original issue**: a 162-word acceptance criterion in `plan/track-9.md` stated the rule and then
  explained the mechanism, and a 105-word `plan/track-11.md` clarification repeated three of those
  mechanism clauses almost verbatim before pointing back at the first copy.
- **Fix applied**: the mechanism moved out of Track 9's acceptance criterion into that file's
  § Clarifications as a new bullet; Track 11's bullet was trimmed to the two commands, its
  track-specific reason, and a pointer.
- **Re-check**:
  - Locations: `plan/track-9.md:83` (criterion) and `:66` (new Clarifications bullet);
    `plan/track-11.md:61` (trimmed bullet).
  - Current state: the criterion is 82 words — the two commands, the three completion conditions,
    the never-measured note that item 1 must establish the off-side number, the repeat-install
    requirement, and "see § Clarifications for why". The new Track 9 bullet is 102 words and carries
    all four mechanism sentences: the reactor exclusion, the local-repository resolution of
    `youtrackdb-core:0.5.0-SNAPSHOT` and the 2026-07-02 jar, the test-jar supplying
    `EmbeddedGraphFeatureTest`'s feature files and `GraphFeatureWorld` (with the `GQL Match Support`
    consequence), and the closing warning to a future reader. Track 11's bullet is 71 words: two
    commands, the repeat rule, its own reason ("this track's whole deliverable is new `core` code"),
    and the pointer.
  - Pointer resolution: `plan/track-9.md:83`'s "§ Clarifications" resolves to the
    `### Clarifications` heading at `:62` of the same file; `plan/track-11.md:61`'s
    "`plan/track-9.md` § Clarifications" resolves to that same heading, which carries the mechanism
    it claims.
  - Bullet-length cap: 102 and 71 words, both well inside the 200-word soft cap at the
    smallest-labeled-block unit.
  - Criteria met: the mechanism is stated once, the criterion states an evaluable claim, and both
    files place the material in the same section type.
- **Fact-preservation check**: no fact was lost. Every clause dropped from Track 11 (reactor
  exclusion, local-repository resolution, test-jar) is in the Track 9 bullet its pointer names.
- **Regression check**: with Track 9's 162-word criterion gone, the longest single block in either
  pending track file is `plan/track-11.md:70` (item 7, 207 words), unchanged by these fixes and
  recorded but not raised in iteration 1 — not raised here either. Track 9's item 1 at 198 words is
  the next longest and also unchanged. Clean.
- **Verdict**: VERIFIED

## Findings

### S5 [should-fix]
**Location**: `plan/track-9.md` § Plan of Work item 4 (line 73, final clause) and § Validation and
Acceptance (line 90, final sentence), read against `plan/track-11.md` § Decision Log bullet 4
(line 26).

**Issue**: The escape clause the S2 fix added is keyed to a narrower trigger than the rule it
implements, and under that branch Track 11's Decision Log rules out the artifact Track 9 would hand
it.

Item 4 closes: "if this item changes no production code, say so in the artifact and the item-3
measurement stands as published." The acceptance bullet repeats it: "Where item 4 changed no
production code, the artifact says so and item 3's measurement is the published one." The rule these
clauses implement sits twelve lines above in the Decision Log: "**Recompute the measured baseline
whenever anything moves the measured behaviour.**" Production code is not the only thing that moves
what these two runners measure, and Track 9's own § Clarifications says so — the `core` **test**-jar
supplies `EmbeddedGraphFeatureTest`'s feature files and `GraphFeatureWorld`, "so a stale install also
means stale local features". An item-4 fix confined to the Cucumber glue or a local feature file
satisfies the escape clause word for word, moves the measured result, and republishes item 3's number
as the handoff — the failure S2 was raised to close, one branch narrower.

The second half is a live contradiction rather than a latent one. `plan/track-11.md:26` reads: "The
baseline this track reads is Track 9's last measurement, not its first and **not its post-item-2
one**." In the escape branch Track 9's last measurement *is* its post-item-2 one, so the bullet names
two different artifacts in the same sentence. A Track 11 decomposer that reaches for the handoff and
finds an artifact stamped item-3 has to decide whether the handoff is valid or whether it must
re-derive the baseline itself — a ~31-minute `core` run plus the two-command `embedded` pair.

The escape clause also appears at only two of the eight S2 sites. Track 9's Decision Log bullet, its
flowchart's unconditional `Triage → Handoff` edge, and all three Track 11 sites state the
publish-last rule flat. That asymmetry is what lets the two files disagree.

**Proposed fix**: Re-key the trigger to the rule. Item 4: "if this item lands no change that either
runner measures — no `src/main` edit and no change to the Cucumber glue or the local feature files —
say so in the artifact and item 3's measurement stands as published." Mirror the wording in the
acceptance bullet, and add the same conditional to `plan/track-11.md:26` so the bullet reads "not its
first, and its post-item-2 measurement only where Track 9's artifact records that item 4 moved
nothing the runners measure."

**Classification**: design-decision
**Justification**: § `design-decision` — "**Track contradictions** — Track 1 assumes X, Track 3
assumes not-X. Which is right is a design call." Track 11 states the post-item-2 measurement is never
its baseline; Track 9 permits publishing it. Which trigger governs the re-run — production code or
measured behaviour — is a planner judgment about Track 9's gate cost, not a text edit, and the
standing tiebreak ("when in doubt … choose `design-decision`") points the same way.

### S6 [suggestion]
**Location**: `plan/track-9.md` § Validation and Acceptance, the `embedded` criterion (line 83, final
sentence).

**Issue**: The re-install criterion's worked example still points at item 3's re-run, which S2
demoted from the handoff.

The sentence reads: "The install step is not optional and is repeated before every `embedded`
measurement, including item 3's post-fix re-run — see § Clarifications for why." It was written when
item 3's re-run was the published one. After S2 there are two post-fix `embedded` re-runs, and the
one the criterion does not name — item 4's — is the artifact Track 11 reads. That is where a run
against the 2026-07-02 jar costs most: it exercises none of the branch and still reports a number,
and Track 11 then measures its whole no-regression claim against it.

The rule itself is universal ("every `embedded` measurement"), so nothing is left unspecified. The
example just points at the lesser of the two cases.

**Proposed fix**: One clause. "… repeated before every `embedded` measurement, including item 3's
gate re-run and item 4's published handoff re-run — see § Clarifications for why."

**Classification**: mechanical
**Justification**: § `mechanical` — "Other findings classify as `mechanical` only when the fix is a
single unambiguous edit that doesn't change plan intent." The universal rule already covers item 4's
re-run; naming it changes no gate, no scope, and no ordering.

## Evidence base

No certificates — plan-internal structural verification, no codebase read. Every claim above is a
direct read of `implementation-plan.md`, `plan/track-9.md`, `plan/track-11.md`, or
`structural-iter1.md`, cited inline by section and line. `certs: 0`.

Re-verified after the S2 fix touched both pending tracks, recorded so the next pass does not
re-litigate:

- **The dependency chain is still acyclic and the annotations still match.** Execution order 7 → 8 →
  10 → 9 → 11. `implementation-plan.md:690` gives Track 9 `**Depends on:** Track 10`, matching
  `plan/track-9.md:108`; `:713` gives Track 11 `**Depends on:** Tracks 7, 8, and 9`, matching
  `plan/track-11.md:104`. No earlier-executing track depends on a later one. The S2 fix changed the
  content of the Track 9 → Track 11 handoff, not its direction.
- **`## Implementation state` agrees with the Checklist on all three surfaces.** The narrative
  (`:718`, "Tracks 1–8 and Track 10 are executed and complete; Tracks 9 and 11 remain, in that
  order"), the eleven table rows with 9 and 11 `not started`, and the decision-conformance sentence
  (`:734`, D3's surface reassigned to Track 11's four recognisers) all match the `[x]`/`[ ]` marks.
  The narrative's closing clause — Track 11 "measured against the baseline Track 9 publishes" —
  survives the S2 fix without an edit, because it names no item.
- **No superseded Decision Records are retained** in either pending track file, and no
  `- [ ] Step:` items or *(provisional)* markers appear in the plan or either track file. The two
  "supersede" hits in the plan (`:39`, `:639`) are a Constraints amendment and a Track 10 episode
  note, neither a retained DR.
- **Sizing holds after the fix.** Track 9 `~8–14` (below the ~12 floor at its lower end, justified in
  writing by DR-S1 — no-shared-file boundary, one-way coupling, review burden) and Track 11 `~14–20`
  (inside the soft range). The extra two-runner pass adds runs to one artifact file, not files.
- **Block lengths after S4.** `plan/track-9.md` `embedded` Clarifications bullet 102 words,
  `embedded` acceptance criterion 82 (was 162); `plan/track-11.md` `embedded` Clarifications bullet
  71 (was 105). The longest blocks in either pending file are now `plan/track-11.md` item 7 at 207
  words and `plan/track-9.md` item 1 at 198, both unchanged by these fixes and both recorded without
  a finding in iteration 1.
- **Standing Phase-4 deferrals, unchanged and not re-raised.** The frozen `design.md` class-diagram
  lag, the `MultiPlanMatchStep : "Track 6"` provenance, the boundary-step `reset()` /
  metrics-capture gaps, and the 7-line Component Map boundary bullet cleared by the 2026-08-01 pass.

**Overall: PASS.** Four verified, no blocker, two follow-ups (S5 should-fix, S6 suggestion).
