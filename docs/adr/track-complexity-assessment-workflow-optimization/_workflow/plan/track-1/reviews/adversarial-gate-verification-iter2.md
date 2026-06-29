<!-- MANIFEST
role: orchestrator,reviewer-adversarial
phase: 3A
track: "Track 1: Ledger schema, resume routing, and Phase-1 artifact existence"
iteration: 2
review_type: adversarial
verdict_producer: true
findings: 0
verdicts:
  - id: A1
    sev: should-fix
    result: VERIFIED
    cert: "#### Verify A1"
  - id: A2
    sev: should-fix
    result: VERIFIED
    cert: "#### Verify A2"
  - id: A3
    sev: suggestion
    result: VERIFIED
    cert: "#### Verify A3"
  - id: A4
    sev: suggestion
    result: VERIFIED
    cert: "#### Verify A4"
overall: PASS
index: []
-->

# Track 1 adversarial gate verification — iteration 2

All four iteration-1 adversarial findings (A1–A4) were ACCEPTED and fixed in
`track-1.md`. Each fix is verified against the live develop-state source
(workflow-modifying branch, `s17=workflow-modifying`, no staged subtree yet, so
the LIVE files are the comparison surface). All four VERIFIED, no regressions,
no new findings. Overall: PASS.

#### Verify A1: consistency/structural design-presence re-key omits the tier-presence-check sub-block
- **Original issue**: Step (3)'s one-line scope ("the design-presence gate reads `design_gate` instead of the tier") under-described the live `consistency-review.md`, which keys *three* things off `tier`: the design-presence test (lines 60-76, tier-named `full`/`lite`/`minimal` branches), the **tier-presence check** (lines 78-88, a finding-emitter that fires when no `tier` resolves), and the degenerate "tier unreadable" fallback (lines 90+). After removing the ledger `tier` field, the tier-presence check would fire its malformed-plan finding on every post-Track-1 plan. `structural-review.md`'s per-tier artifact checks were likewise under-named.
- **Fix applied**: Step (3) rewritten (`track-1.md:283-292`) to name "**every** tier read, not just the design-presence gate," then enumerate the three coupled `consistency-review.md` sub-blocks — the design-presence guard (tier-named branches become `design_gate`-keyed), the tier-presence check (must "read `design_gate` presence or be retired"), and the degenerate "tier unreadable" fallback — plus, for `structural-review.md`, "the per-tier artifact checks, the design-half skip guard, and the `full`-tier-only seed↔track fidelity gate all re-key onto the axes."
- **Re-check**:
  - Track-file location: `track-1.md:283-292`.
  - Live evidence: `consistency-review.md:55-76` (design-presence guard, tier-named branches), `:78-88` ("Tier-presence check (runs in every tier)" finding-emitter), `:90-94` ("Degenerate case — tier unreadable") — all three sub-blocks exist exactly as the fix now names them. `structural-review.md:257` (per-tier artifact set), `:547` (design document gaps skipped when `design.md` absent), `:342,405,501` (seed↔track fidelity, `full`-tier only) — the three structural targets exist as named.
  - Current state: the step text now names all three consistency-review sub-blocks and the three structural-review targets; the tier-presence-check re-key/retire decision is explicit, so an implementer working from the track file alone cannot leave a live finding-emitter pointed at a removed field.
  - Criteria met: scope-completeness (every live tier read named); the dangling-finding-emitter hazard is closed by the explicit "read `design_gate` presence or be retired" instruction.
- **Regression check**: Checked the structural half (`structural-review.md` per-tier checks) and the Interfaces entries (`track-1.md:413-416`) — Interfaces still list `consistency-review.md` and `structural-review.md` in scope; the widened step is consistent with them. The `implementation-review.md` selector re-key (step 3 tail, `:293-296`) is unchanged and still correct (design-half guard reads `design_gate`, structural-pass skip reads the plan-presence signal). Clean.
- **Verdict**: VERIFIED

#### Verify A2: the `design_gate`-before-`categories` ordering rationale misstates the safety invariant
- **Original issue**: Step (1) justified placing `design_gate` ahead of `categories` with an "embedded spaces end the bare-token scan early" mechanism, which is mechanically false — `ledger_tail_value` takes the **first** ` key=` token and stops, and the quoted-value branch reads `categories="a,b c"` through to its closing quote regardless of field order. The real hazard the live script documents is a **same-named decoy** `key=` substring inside the quoted `categories` value winning the first-match scan if a reader-consumed key is emitted after `categories`.
- **Fix applied**: Step (1) rationale rewritten (`track-1.md:221-233`) to the correct mechanism: "takes the **first** ` key=` token on a line and stops — it runs no left-to-right scan that an embedded space could truncate," "the quoted-value branch already reads a `categories=\"a,b c\"` value through to its closing quote regardless of field order," and "the real hazard … is a **same-named decoy** … a reader-consumed key emitted *after* `categories` would let that decoy win the first-match scan," with the explicit instruction "Carry this corrected rationale into the file-header grammar comment … do not restate the embedded-spaces framing." A first-match-wins decoy test was added (`track-1.md:243-246`): "a `design_gate` placed after a `categories` value carrying a `design_gate=`-shaped decoy still reads the real bare token."
- **Re-check**:
  - Track-file location: `track-1.md:221-233` (rationale), `:243-246` (decoy test).
  - Live evidence: `workflow-startup-precheck.sh:1782-1789` — the live `ledger_tail_value` comment states "The scan takes the FIRST ` $key=` token on the line and stops; it does not loop" and "The safety invariant is that emit order, NOT a quoted-span skip: a key emitted AFTER `categories` would let a same-named decoy inside the quoted value win." The quoted-value branch is `:1794-1797`. The track's corrected rationale matches the script's own documented invariant word-for-word in mechanism.
  - Current state: the embedded-spaces framing is gone; the rationale now names first-match-and-stop + same-named-decoy, and the added test asserts the first-match-wins emit-order property (mirroring the technical/risk T4/R2 fix per the spawn brief).
  - Criteria met: invariant-accuracy (rationale matches the live reader's documented mechanism); the test now catches the actual failure mode (decoy after `categories`) rather than a vacuous embedded-spaces assertion that passes regardless of field order.
- **Regression check**: Checked the test description against the precheck reader semantics — the decoy test as worded ("`design_gate` placed after a `categories` value carrying a `design_gate=`-shaped decoy still reads the real bare token") correctly exercises first-match-wins given the emit-order constraint. No contradiction with the loud-reject grammar (a `design_gate=`-shaped substring inside a quoted `categories` value is not a double-quote, so it is not rejected — the test is constructible). Clean.
- **Verdict**: VERIFIED

#### Verify A3: removing the ledger `tier` field leaves live readers in Track-2-owned files; the cross-track promotion contract
- **Original issue**: Step (1) drops `tier=` while live readers (`track-review.md:484-489`, `inline-replanning.md:164-196`, `create-final-design.md`, `design-review.md`, the reviewer prompts) live in Track-2-owned files. The track stated the I6 promote-together property and named Track 2 as dependent but did not spell out that the schema removal is sound *only because* Track 2 re-keys all remaining live readers in the same promotion.
- **Fix applied**: A "**Forward obligation on Track 2 (reverse coupling).**" paragraph added to `## Interfaces and Dependencies` (`track-1.md:436-448`) stating the `tier=` removal "is internally consistent only because every remaining **live** `tier` reader or writer sits in Track 2's scope and both tracks' staged `.claude/**` edits promote together in the single Phase-4 commit (§1.7 I6)," enumerating the four sites (`inline-replanning.md` ESCALATE write, `track-review.md` §"Tier-driven review selection", `create-final-design.md`, `design-review.md`) and naming it "the named discharge condition that makes Track 1's schema removal safe."
- **Re-check**:
  - Track-file location: `track-1.md:436-448` (Forward obligation paragraph); also the `## Decision Log` adr-predicate note (`:108-114`) and §1.7 staging block (`:450-456`) that it mirrors.
  - Live evidence: the named out-of-scope readers exist — `inline-replanning.md` ESCALATE `--append-ledger --tier` write, `track-review.md` tier-driven selection, `create-final-design.md` Phase-4 carrier, `design-review.md` `tier=full` fidelity gate. The Out-of-scope list (`track-1.md:422-429`) confirms all four are Track-2-owned. The §1.7 I6 promote-together property is stated at `:450-456`.
  - Current state: the reverse-coupling contract is now explicit prose, with the discharge condition named; legibility gap closed.
  - Criteria met: contract-legibility (the schema removal's safety condition is spelled out, not left implicit).
- **Regression check**: Checked that the new paragraph does not contradict the Out-of-scope list or the Inter-track dependencies note (`:431-434`) — all three agree Track 2 owns the re-keys and depends on Track 1's schema. The §1.7 I6 reference is consistent with the staging block (`:450-456`). No invariant disturbed. Clean.
- **Verdict**: VERIFIED

#### Verify A4: `determine_state` never read tier; the minimal-default-track-to-1 logic keys off an empty track
- **Original issue**: Step (2) framed `determine_state`'s `minimal`-default-track-to-1 logic as a tier read to "re-key onto the plan-presence / track-count signal." But `determine_state_from_ledger` reads only `phase` and `track`; the default-to-1 fires on an empty `track`, not on `tier=minimal`. There is no `tier` read to re-key — only stale `tier`/`minimal` comments and the append-side `--tier`/`LEDGER_TIER` plumbing to delete. The "re-key onto the track-count signal" framing risked a phantom edit.
- **Fix applied**: Step (2) reworded (`track-1.md:262-269`): "`determine_state_from_ledger` reads only `phase` and `track` — it has **no `tier` read** to re-key. Its `minimal`-default-track-to-1 behavior already keys off an empty `track=` (`[ -n \"$track\" ] || track=\"1\"`), which is tier-agnostic and stays correct under the new schema. The work here is therefore to delete the append-side `--tier` / `LEDGER_TIER` plumbing (arg case, validation, builder, usage text) and remove the now-stale `tier`/`minimal` comments in the resume functions, then confirm the empty-`track`→1 default still behaves — not to hunt for a tier read that does not exist."
- **Re-check**:
  - Track-file location: `track-1.md:262-269`.
  - Live evidence: `workflow-startup-precheck.sh:1934-2010` (`determine_state_from_ledger`) reads `phase` (`:1945`) and `track` (`:1965-1967`, `[ -n "$track" ] || track="1"`) only; a `grep` for `tier`/`minimal` co-occurring with `determine_state` returned no code read, only stale comments (`:1932,1964`). The live `tier` touch-points are exactly the append surface (usage `:19,134`, accumulator `:119`, arg case `:171-172`, validation `:1696`, builder `:1718`) plus stale comments (`:1932,1964`) — precisely the delete-list the reworded step names. The empty-`track`→1 default at `:1967` is tier-agnostic as the fix asserts.
  - Current state: step (2) now describes the real edit (delete append plumbing + remove stale comments, confirm tier-agnostic default) and explicitly warns against hunting a non-existent tier read.
  - Criteria met: assumption-accuracy (the step no longer asserts a tier read that does not exist); the phantom-edit risk is closed.
- **Regression check**: Cross-checked the delete-list against the live append surface — every `--tier`/`LEDGER_TIER` site the fix names is present and is genuinely append-side (none is a resume read), so deleting them does not touch the resume path. The empty-`track`→1 default is left intact, matching the fix's "confirm it still behaves." Consistent with step (1)'s `--tier` flag-surface drop (`track-1.md:202-204`, Key signatures `:460-465`). Clean.
- **Verdict**: VERIFIED

## Findings

findings: 0

(No new findings. This is a pure-verdict verification pass; all four prior
findings VERIFIED, no regression introduced by any fix.)

## Summary

PASS — A1, A2, A3, A4 all VERIFIED against the live develop-state source; no
regressions; 0 new findings.
