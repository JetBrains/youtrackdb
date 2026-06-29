<!--MANIFEST
role: orchestrator,reviewer-risk
phase: 3A
track: "Track 1: Ledger schema, resume routing, and Phase-1 artifact existence"
iteration: 2
verdict: PASS
findings: 0
blockers: 0
verdicts:
  - id: R1
    sev: should-fix
    result: VERIFIED
    loc: "track-1.md §Interfaces 'Forward obligation on Track 2 (reverse coupling)' (lines 436-448); live sites inline-replanning.md:169, track-review.md:625, create-final-design.md:42/90, design-review.md:235"
    cert: "Exposure: forward obligation names all four live tier readers/writers + §1.7 I6 promote-together rationale"
  - id: R2
    sev: should-fix
    result: VERIFIED
    loc: "track-1.md §Plan of Work (1) (lines 220-233); precheck.sh ledger_tail_value:1780-1789"
    cert: "Assumption: emit-order rationale corrected to first-` key=`-token + same-named-decoy; embedded-spaces framing repudiated, carry-to-header-comment instruction added"
  - id: R3
    sev: suggestion
    result: VERIFIED
    loc: "track-1.md §Validation 'Resume routes every combination' + 'The collision is resolved' (lines 350-364); precheck.sh determine_state_from_ledger:1934-2010; create-plan/SKILL.md Step 1c:131-285"
    cert: "Testability: validation bullets now split script-decided half (precheck unit suite) from Step-1c prose half (consistency/structural reviews)"
overall: PASS
index: []
-->

# Track 1 — Risk review gate verification (iteration 2)

All three iteration-1 risk findings (R1 should-fix, R2 should-fix, R3 suggestion) were ACCEPTED and their fixes applied to `track-1.md`. Each fix is VERIFIED against the live develop-state source. No regressions, no new findings. Overall: PASS.

This is a workflow-modifying branch (`s17=workflow-modifying`, confirmed in `phase-ledger.md`); no staged content exists yet, so all source reads are against live develop-state files. Entirely Markdown + Bash + Python — verified via grep + Read, no PSI.

#### Verify R1: Forward obligation on Track 2 (reverse coupling)
- **Original issue**: Track 1 drops `--tier` from the script's flag surface and the ledger grammar, but five live runtime sites outside its scope still write/read `tier`. The §1.7 I6 promote-together invariant is what neutralizes the blast radius, yet the track file named no cross-track promotion-consistency obligation, leaving Track 2's completeness without an auditable target.
- **Fix applied**: a "Forward obligation on Track 2 (reverse coupling)" paragraph added to `## Interfaces and Dependencies` (track-1.md lines 436-448), naming the remaining live tier readers/writers as Track-2's named discharge condition before the single Phase-4 promotion, with the §1.7 I6 rationale.
- **Re-check**:
  - Track-file location: track-1.md lines 436-448. The paragraph states the schema removal is internally consistent "only because every remaining **live** `tier` reader or writer sits in Track 2's scope and both tracks' staged `.claude/**` edits promote together in the single Phase-4 commit (§1.7 I6 — the live tree never holds a `tier`-less script beside a `tier`-using consumer)," then enumerates the sites and closes "Track 2 must re-key all four in the staged subtree before promotion; this is the named discharge condition that makes Track 1's schema removal safe."
  - Live-source verification of the four named sites: `inline-replanning.md:169` = `.claude/scripts/workflow-startup-precheck.sh --append-ledger --tier <new-tier>` (the ESCALATE write — exact match, would hit `exit 2` after the flag drop); `track-review.md:625` reads the ledger `tier` field (last value wins) under §"Tier-driven review selection" at :623; `create-final-design.md:42` and `:90` read the confirmed-tier ledger `tier` field for the Phase-4 carrier; `design-review.md:235` is the `tier=full` full-tier fidelity gate. All four confirmed present and accurately described.
  - Criteria met: the cross-track promotion-consistency obligation is now documented with a named, auditable site set, mirroring the adr-predicate cross-track note in `## Decision Log`. The §1.7 I6 promote-together rationale is stated as the safety basis.
- **Regression check**: Checked the §Interfaces "Out of scope (Track 2 owns these)" block (lines 422-429) — it lists `inline-replanning.md` and `prompts/create-final-design.md` / `prompts/design-review.md` consistently with the new paragraph; `track-review.md` is also in the out-of-scope list. The named-four set is a subset of the out-of-scope set, no contradiction. The R1 paragraph correctly drops `track-review.md`'s general scope to the specific §"Tier-driven review selection" read. Clean.
- **Verdict**: VERIFIED

#### Verify R2: emit-order rationale for `design_gate` before `categories`
- **Original issue**: the §Plan of Work (1) justification for emitting bare fields before `categories` claimed "the quoted value's embedded spaces … end the bare-token scan early" — not how `ledger_tail_value` works. The reader takes the FIRST ` key=` token and stops; the real hazard is a same-named decoy substring inside the quoted `categories="…"` value letting the decoy win a reader-consumed key emitted after `categories`. An implementer coding to the wrong reason could place a future reader-consumed key after `categories` believing a space-free bare value is safe there.
- **Fix applied**: track-1.md §Plan of Work (1) lines 220-233 restate the rationale to match the live invariant and add a carry-to-header-comment instruction.
- **Re-check**:
  - Track-file location: lines 220-233. The text now says the ordering is load-bearing "but not for the reason a 'scan stops at the first space' model would suggest: `ledger_tail_value` … takes the **first** ` key=` token on a line and stops — it runs no left-to-right scan that an embedded space could truncate, and the quoted-value branch already reads a `categories="a,b c"` value through to its closing quote regardless of field order. The real hazard the emit order guards against is a **same-named decoy** `key=` substring sitting inside the quoted `categories="…"` value: a reader-consumed key emitted *after* `categories` would let that decoy win the first-match scan." It closes: "Carry this corrected rationale into the file-header grammar comment the step rewrites — do not restate the embedded-spaces framing."
  - Live-source verification: `precheck.sh` `ledger_tail_value` comment (lines 1780-1789) confirms the reader "takes the FIRST ` $key=` token on the line and stops; it does not loop or re-examine the rest," and that "a key emitted AFTER `categories` would let a same-named decoy inside the quoted value win, so keep every reader-consumed key ahead of it. The safety invariant is that emit order, NOT a quoted-span skip." The code (lines 1790-1802) implements `case " $line" in *" $key="*)` taking the first match — no left-to-right walk. The track file's corrected wording matches the live invariant exactly.
  - Criteria met: the stated reason now generalizes correctly (a future reader-consumed bare key after `categories` is recognized as unsafe), and the corrected rationale is explicitly slated for the file-header grammar comment the step rewrites.
- **Regression check**: grepped track-1.md for stale "embedded spaces end the scan early" framing — the only matches (lines 222, 224) are the *corrective* clauses that repudiate that model ("not for the reason a 'scan stops at the first space' model would suggest", "no left-to-right scan that an embedded space could truncate"), plus the line-233 "do not restate the embedded-spaces framing" instruction. No stale wrong rationale survives. Clean.
- **Verdict**: VERIFIED

#### Verify R3: collision-acceptance testability split (script vs prose)
- **Original issue**: the load-bearing acceptance item (design.md + no plan + one track file → steady state when the Phase-1-complete marker is set, crash-recovery when unset) is resolved in Step-1c router prose in `create-plan/SKILL.md`, not in the script, so the precheck unit suite structurally cannot exercise it. The §Validation bullets presented the collision acceptance as if uniformly test-verified.
- **Fix applied**: track-1.md §Validation "Resume routes every combination" and "The collision is resolved" bullets (lines 350-364) now distinguish the script-decided half from the Step-1c prose half.
- **Re-check**:
  - Track-file location: lines 350-364. "Resume routes every combination" closes: "The script-decided half (`determine_state_from_ledger`'s re-keyed arms) is covered by the precheck unit suite; the Step-1c router half is LLM-instruction prose, so it is verified by the consistency / structural plan reviews, not by a unit test." "The collision is resolved" closes: "This resolution lives in the Step-1c router prose, so its acceptance is a prose-review item (the precheck suite cannot host it); the unit suite covers only what the script decides (schema round-trip, loud-reject, last-value-wins, track-scoped no-leak, torn-append, and `determine_state_from_ledger`'s re-key)."
  - Live-source verification: `determine_state_from_ledger` (precheck.sh:1934-2010) is a Bash function reading only `phase`/`track`/`substate` — exercised by `test_workflow_startup_precheck.py` via `run_precheck` and the stub's `write_ledger` (confirmed present in the suite). The Step-1c router is `create-plan/SKILL.md` prose (the `LEDGER_TIER` parse at :162 and crash-recovery branching :176-285) — LLM instructions, not code. The split the track file now draws matches the actual script/prose boundary.
  - Criteria met: the decomposition is steered to scope precheck test obligations to what the script decides and to route the prose collision to consistency/structural review acceptance, so Phase A will not write a step promising a test the precheck suite cannot host.
- **Regression check**: Checked the §Invariants "Step 1c / `determine_state` routes every artifact combination … never a dead end" bullet (lines 505-508) — it remains a behavioral invariant statement and does not over-claim unit-test coverage of the prose half; consistent with the re-scoped Validation bullets. Clean.
- **Verdict**: VERIFIED

## Findings

findings: 0

No new risk findings surfaced by this verification pass.

## Summary

PASS — R1, R2, R3 all VERIFIED. Fixes applied correctly to `track-1.md`, each consistent with the live develop-state source (`workflow-startup-precheck.sh`, the four named tier-reader/writer files, `create-plan/SKILL.md`). No regressions, no new findings.
