<!-- MANIFEST
review: consistency-gate-verification
iter: 1
phase: 2
role: reviewer-plan
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
prefix: CR
tier: full
design_present: true
staged_subtree: absent
tooling: read-grep (workflow-machinery branch; .claude/** Markdown, no Java symbols, PSI N/A)
index:
  - {id: CR4, sev: should-fix, loc: "implementation-plan.md:58-62 Track-1 scope block; plan/track-2.md:211-212 Out-of-scope list", anchor: "### CR4 ", cert: "Ref: S2 deliverable retarget propagation", basis: current-state, class: mechanical}
verdicts:
  - {id: CR1, verdict: VERIFIED}
  - {id: CR2, verdict: VERIFIED}
  - {id: CR3, verdict: VERIFIED}
overall: FAIL
flags: [REGRESSION_FIX_SHIFTED]
-->

# Consistency gate-verification — iteration 1

Branch `understandable-design` (YTDB-1130), `full` tier, `§1.7(b)`
workflow-modifying. Workflow-machinery plan: the "code" under re-check is
`.claude/**` Markdown / SKILL / prompt / agent files, verified by `Read` /
`Grep` over the live develop-state files (no Java in scope, PSI N/A). The
staged subtree does not exist yet at Phase 2, so every `.claude/**` read
resolves to the live file.

CR1 and CR2 are VERIFIED. CR3's five named Track 1 edit sites are VERIFIED, but
the re-scan caught the CR3 retarget shifting rather than fully resolving: two
plan-side summary/mirror sites (the plan-file Track 1 scope block and Track 2's
Out-of-scope list) still name `conventions.md` as the S2 deliverable. New
finding CR4. Overall **FAIL** — a fix-shifted regression was introduced.

#### Verify CR1: existing agent `tools:` frontmatter
- **Original issue**: Track 1 §Context and Orientation claimed existing `.claude/agents/*.md` carry a `tools:` frontmatter key with "minimal allow-lists"; none do.
- **Fix applied**: the bullet now reads "each carrying `name` / `description` / `model` frontmatter; none currently carries a `tools:` allow-list, though the `Agent` tool supports one (the lever D13/D14 add)" (track-1.md:182-185).
- **Re-check**:
  - Search/trace performed: `grep -lE '^tools:' .claude/agents/*.md` (Grep — PSI N/A on Markdown); frontmatter key tally via `awk` over each YAML block.
  - Code location: `.claude/agents/*.md` (20 files); track-1.md:182-185.
  - Current state: 0 of 20 files match `tools:`; key tally is exactly `description` ×20, `model` ×20, `name` ×20, `tools` ×0. The corrected bullet matches ground truth. Plan intent (new agents add `tools:` allow-lists) is preserved — Plan of Work step 1 (track-1.md:234-243), Validation line 304-306, and the diagram node allow-lists (track-1.md:207-210) all still specify the new per-agent allow-lists.
- **Regression check**: scanned the rest of the Track 1 orientation bullet and the D13/D14 records (track-1.md:117-129) — they correctly frame the `tools:` allow-list as the lever D13/D14 add, consistent with the now-corrected current-state claim. Clean.
- **Verdict**: VERIFIED

#### Verify CR2: Phase 4 second check is PSI-against-code, not absorption
- **Original issue**: Track 2 §Context and Orientation claimed today's Phase 4 second check is an "absorption-style comparison"; no absorption runs at Phase 4, and the design's own Core Concepts (design.md:88) says the fidelity check "Replaces a PSI-only comparison against code."
- **Fix applied**: the bullet now reads "its build-time check today is a PSI diagram-to-code verification against the as-built code, not a fidelity check against episodes (no absorption check runs at Phase 4 today)" (track-2.md:81-83).
- **Re-check**:
  - Search/trace performed: Read `design-review.md` §Inputs and §Track-scoped cold-read; Read `create-final-design.md` Sub-steps A/B; cross-read design.md:85-90 (Grep + Read — PSI N/A).
  - Code location: `design-review.md:69-70` ("`research_log_path` … Absent for the interactive mutation kinds and for Phase 4"); the absorption-completeness cross-check fires only for `target=design`/`phase1-creation` and `target=tracks` (design-review.md:54-56, 122-126, 279-284), never `phase4-creation`. `create-final-design.md` Sub-step A is the PSI diagram-to-code verification (lines 170-193), Sub-step B routes `phase4-creation` through `edit-design` (lines 195-217). design.md:88 reads "Replaces a PSI-only comparison against code."
  - Current state: the corrected Track 2 bullet matches both the live prompt behavior (Phase 4 carries no `research_log_path`, runs no absorption; the build-time check is PSI-against-code) and design.md:88. The track and the frozen design now agree on the same current state.
- **Regression check**: the corrected bullet sits beside the D10 record (track-2.md:37-42), which describes the *target-state* swap from PSI-only to doc-against-episodes; the orientation now correctly describes today's PSI-against-code state and D10 the target swap — no contradiction introduced. Clean.
- **Verdict**: VERIFIED

#### Verify CR3: S2 canonical home retargeted from conventions.md to research.md + design-document-rules.md
- **Original issue**: the plan/design attributed the canonical S2 read-scope statement to `conventions.md`; it lives canonically at `research.md` §"Read-scope discipline (S2)", with a restatement in `design-document-rules.md`. `conventions.md` carries only descriptive cross-refs and never uses the `S2` label. D18's deliverable targeted the wrong file. User resolution: D18 targets `research.md` + `design-document-rules.md`; `conventions.md` cross-refs left alone.
- **Fix applied**: five Track 1 sites retargeted — (1) §Context and Orientation S2 bullet (track-1.md:191-197); (2) D18 title/rationale/Implemented-in (track-1.md:145-150); (3) Plan of Work step 4 (track-1.md:263-269); (4) Interfaces in-scope list (track-1.md:337-347); (5) S2 invariant verification (track-1.md:379).
- **Re-check**:
  - Search/trace performed: `grep -rnE` for the "exactly two places" S2 sentence across `.claude/workflow/*.md`; Read research.md:108-127, conventions.md:86, design-document-rules.md:98-109; `grep -nE 'S2|conventions\.md|research\.md|design-document-rules'` across both track files and the plan file.
  - Code location: canonical statement at `research.md:116-118` ("the log is read for decision *content* in exactly two places: at Step 4a/4b artifact authoring … and by the Phase-2 consistency review"), matching the track quote verbatim; restatement at `design-document-rules.md:103-104`; `conventions.md` has no `S2` label and only the descriptive "two sanctioned read points" mention at conventions.md:86.
  - Current state: all five Track 1 sites now attribute the canonical S2 statement to `research.md` (with `design-document-rules.md` as restatement), D18's deliverable targets those two files, and the §Context bullet correctly notes `conventions.md` "never uses the `S2` label." The only surviving Track 1 `conventions.md`+S2 mention is the D18 rationale explaining `conventions.md` carries no `S2` label (track-1.md:147) — exactly the one the spawn expected to survive. Track 1 owns no `conventions.md` S2 deliverable any longer (confirmed: `grep` for a `conventions.md … S2` deliverable in track-1.md returns only the no-label rationale). The frozen design.md Part 4 still carries the misattribution and is correctly recorded as deferred to Phase 4 (design.md is frozen — not edited at Phase 2).
- **Regression check**: re-scanned the *whole* plan/track surface for any other S2-to-`conventions.md` attribution, since the retarget had to propagate to every summary and mirror of the Track 1 deliverable, not just the five edited sites. Two un-propagated sites surfaced: `implementation-plan.md:58-62` (Track 1 scope block still names "the `conventions.md` read-scope invariants" / "`conventions.md` S2/S3/S4 wording") and `plan/track-2.md:211-212` (Out-of-scope list names "the `conventions.md` S2 wording — all Track 1"). The five edited sites are correct; the fix shifted the inconsistency into the two summary/mirror sites rather than resolving it there. → CR4.
- **Verdict**: VERIFIED (at the five named edit sites; the un-propagated summary/mirror sites are a new finding, CR4 below)

## Findings

### CR4 [should-fix]
**Certificate**: Ref: S2 deliverable retarget propagation
**Location**: `implementation-plan.md:58-62` (Track 1 intro + Scope block) and `plan/track-2.md:211-212` (Track 2 §Interfaces and Dependencies → Out-of-scope list); ground truth in the now-corrected Track 1 D18 (track-1.md:145-150) and the live `.claude/workflow/research.md` / `conventions.md` / `design-document-rules.md`.
**Issue**: The CR3 retarget corrected the five Track 1 internal sites but did not propagate to the two plan-side places that *summarize* the same Track 1 deliverable. After the fix, Track 1 D18 targets `research.md` (canonical) + `design-document-rules.md` (restatement) and explicitly leaves the `conventions.md` cross-refs alone, yet: (1) the plan-file Track 1 scope block still says "update the `conventions.md` read-scope invariants" (implementation-plan.md:58-59) and lists "`conventions.md` S2/S3/S4 wording" as a Track 1 deliverable (implementation-plan.md:62); (2) Track 2's Out-of-scope list still names "the `conventions.md` S2 wording — all Track 1" (track-2.md:211-212) as a Track 1 deliverable. Both name a `conventions.md` S2-wording deliverable that no longer exists — the same misattribution CR3 removed from Track 1, now surviving in the plan summary and the cross-track mirror.
**Evidence**: `grep -nE 'S2|conventions\.md'` over the plan file returns implementation-plan.md:62 ("`conventions.md` S2/S3/S4 wording"); the same grep over track-2.md returns line 212 ("the `conventions.md` S2 wording — all Track 1"). The corrected Track 1 D18 Implemented-in (track-1.md:149) reads "the `research.md` S2 wording edit and the `design-document-rules.md` restatement" — `conventions.md` is no longer an S2-wording target there. Live ground truth: `research.md:116-118` holds the canonical S2 sentence, `conventions.md:86` has no `S2` label (descriptive cross-ref only), `design-document-rules.md:103-104` restates it. So the two summary/mirror sites contradict the deliverable they summarize.
**Proposed fix**: Retarget both summary sites to match Track 1 D18. (1) implementation-plan.md:58-62 — change "the `conventions.md` read-scope invariants" to "the `research.md` / `design-document-rules.md` read-scope invariants" and "`conventions.md` S2/S3/S4 wording" to "`research.md` / `design-document-rules.md` S2/S3 wording (S4 in `design-review.md`)" (or the briefer "read-scope (S2/S3) wording in `research.md` / `design-document-rules.md`"). S2's canonical home is `research.md`; S3 freeze-order also lives in `research.md` / `design-document-rules.md` per track-1.md:267; the S4 one-owner prose axis is realized in the de-warmed `design-review.md`, not `conventions.md` (track-1.md:268-269) — so naming `conventions.md` for any of S2/S3/S4 is inaccurate. (2) track-2.md:212 — change "the `conventions.md` S2 wording — all Track 1" to "the `research.md` / `design-document-rules.md` S2 wording — all Track 1" (or "the read-scope (S2) wording — all Track 1"), matching the in-scope file list in Track 1 (track-1.md:337-344). The corresponding Track 1 §Context and Orientation summary (track-1.md:20, "updates the `conventions.md` read-scope invariants") is the same drift one level up; fold it into the same edit for consistency.
**Classification**: mechanical
**Justification**: current-state cross-reference accuracy (the two sites *summarize* a deliverable whose canonical attribution CR3 already fixed), single unambiguous correct rendering (point at the same files Track 1 D18 now names — `research.md` + `design-document-rules.md`), and the fix preserves plan intent (the S2-naming deliverable is unchanged; only which file it lives in is corrected). All three §`mechanical` rules hold. This is the classic fix-shifted regression the re-scan step targets: a deliverable retargeted in its detailed home but not in its plan-file summary and cross-track mirror.
