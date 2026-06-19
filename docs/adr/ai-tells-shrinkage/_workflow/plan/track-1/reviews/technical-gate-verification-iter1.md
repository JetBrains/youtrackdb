<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Shrink house-style to comprehension-serving rules"
iteration: 1
mode: gate-verification
findings: 0
blockers: 0
overall: PASS
verdicts:
  - id: T1
    sev: should-fix
    verdict: VERIFIED
    cert: "Move 1 now adds Self-check item 7 to the trim and re-points :359 + :485 to cite only § Banned sentence patterns and § Elegant variation; both survivor sections exist (:302) and the parent of :359 is the kept ## Structural rules (:355)"
  - id: T2
    sev: should-fix
    verdict: VERIFIED
    cert: "Move 5 adds house-conversation.md:21 six→four; Move 2 adds conventions.md:624 six→four; Move 6 group (a) adds hook:262 six→four; all three live sites confirmed to still say six"
  - id: T3
    sev: should-fix
    verdict: VERIFIED
    cert: "Move 6 group (a) names design-document-rules.md:287 as a substantive rewrite — drop the four removed-pattern clauses, eleven→survivor count, co-decompose with Move 4; live line confirmed still says Detects eleven with all four removed descriptions"
  - id: T4
    sev: suggestion
    verdict: VERIFIED
    cert: "Move 1 now states the em-dash (:131) and negative-parallelism (:123) cross-refs sit inside the removed Tier-4 block and self-delete; live lines confirmed inside the Tier-4 word list, no separate fix needed"
  - id: T5
    sev: suggestion
    verdict: VERIFIED
    cert: "Move 6 splits named consumers into group (a) re-point/rewrite and group (b) readability-auditor.md additive-only + design-review.md confirm-benign; live re-read confirms neither carries a removed-section reference"
regression_check: "Re-read Plan of Work Moves 1-7, Context and Orientation, Interfaces and Dependencies, Invariants. Self-check item numbering in Move 1 matches the live file (1 banned-vocab, 2 em-dash, 4 sycophantic+cutoff, 5 copula+signposting, 6 punctuation, 7 Structure). DR6 reconciliation-removal (line 92) still named in Move 1. No move-to-move contradiction, no stale Interfaces/Context line introduced by the fixes."
evidence_base:
  premises: 5
  edge_cases: 0
  integrations: 5
  confirmed: 10
  not_confirmed: 0
-->

# Technical review gate-verification — Track 1 (iteration 1)

PASS. All five iteration-1 findings (T1, T2, T3 should-fix; T4, T5 suggestion) are
VERIFIED. Every fix landed in the track file's Plan of Work, reads correctly, and
each cited live-repo premise still holds: `house-style.md:359` and `:485` still cite
"§ Banned vocabulary" (T1 targets), the three count-word carriers
(`house-conversation.md:21`, `conventions.md:624`, hook `:262`) still say "six" (T2),
`design-document-rules.md:287` still says "Detects eleven" with all four removed
pattern descriptions (T3), and the `:123`/`:131` cross-refs still sit inside the
self-deleting Tier-4 block (T4). No regression: the fixes introduced no move-to-move
contradiction, the Move-1 Self-check item numbering matches the live file, the DR6
reconciliation removal (line 92) stays named, and no Interfaces/Context/Invariant line
went stale.

This is a `§1.7(k)` prose-rule opt-out branch (ledger `s17=opt-out`), so verification
ran against live files via Read + grep. No Java symbol, no `findClass`, mcp-steroid not
needed. Zero new findings.

## Findings

<!-- No new findings. Pure-verdict pass. -->

## Evidence base

Ten certificates, all CONFIRMED. Five live-repo premise re-checks (the fixes' cited
source lines) plus five track-file integration re-checks (the fix text reads correctly
and stays coherent with the rest of the Plan of Work).

#### Verify T1: phantom self-references inside KEPT sections
- **Original issue**: Move 1 missed two phantom refs to the deleted `§ Banned vocabulary` living inside KEPT sections — `house-style.md:359` (`### Padding-based finding criterion`) and Self-check item 7 (`:485`).
- **Fix applied**: Move 1 now adds item 7 to the Self-check trim ("item 7 ... drops the 'a banned term from § Banned vocabulary' clause") and adds an explicit re-point of both `:359` and `:485` to cite only `§ Banned sentence patterns` and `§ Elegant variation`.
- **Re-check**:
  - Track location: track-1.md Move 1, lines 363-373.
  - Live premise: `house-style.md:359` reads "...a banned term from § Banned vocabulary, a pattern from § Banned sentence patterns, or restatement (cycling synonyms per § Elegant variation...)". `house-style.md:485` (Self-check **7. Structure.**) reads "...a banned term from § Banned vocabulary, a pattern from § Banned sentence patterns, or restatement per § Elegant variation." Both confirm the issue and the survivor cross-refs.
  - Survivor sections exist: `### Elegant variation` @ `:302` (kept), `## Banned sentence patterns` survives. Parent of `:359` is `## Structural rules` @ `:355` (kept) — matches Move 1's "under `## Structural rules`" claim.
  - Self-check numbering: live items 1=Banned vocabulary, 2=Em dashes, 4=Sycophantic+cutoff, 5=Analysis(copula/signposting), 6=Punctuation, 7=Structure — exactly the numbering Move 1 enumerates.
- **Regression check**: Checked Move 1 against DR6 (reconciliation removal still named at track line 359-360 prose) and against the Self-check trim list — no contradiction. The padding criterion's two surviving sources are correct. Clean.
- **Verdict**: VERIFIED.

#### Verify T2: stale count word "six" in always-loaded carriers
- **Original issue**: count word "six" un-captured in three always-loaded carriers next to a list shrinking to four.
- **Fix applied**: Move 5 adds `house-conversation.md:21` six→four; Move 2 adds `conventions.md:624` six→four; Move 6 group (a) adds `hooks/house-style-write-reminder.sh:262` six→four.
- **Re-check**:
  - Track locations: Move 5 (line 400), Move 2 (line 383), Move 6 group (a) (line 435) — each names the exact file:line and the six→four edit.
  - Live premises: `house-conversation.md:21` = "Apply these six sections of ..."; `conventions.md:624` = "The six Tier-B section names are stable headings ..."; hook `:262` = "... and the six sections in ...". All three confirm the stale count.
- **Regression check**: Each six→four edit is named alongside (not instead of) the slug-list trim already on that move, so no slug-edit instruction was displaced. Clean.
- **Verdict**: VERIFIED.

#### Verify T3: design-document-rules.md:287 lockstep rewrite
- **Original issue**: Move 6 treated `design-document-rules.md:287` as a one-line pointer re-point; it actually mirrors the checker docstring ("Detects eleven" + four removed-pattern descriptions) and needs a substantive rewrite in lockstep with Move 4.
- **Fix applied**: Move 6 group (a) names `:287` explicitly as "a substantive rewrite, not a one-line pointer re-point" — drop the four removed clauses (`§ Tier 1`, `§ Em-dash discipline`, `§ Signposting`, `§ Copula avoidance`), change "eleven" to survivor count, "Decompose this edit and Move 4 into the same step."
- **Re-check**:
  - Track location: track-1.md Move 6 lines 425-432.
  - Live premise: `design-document-rules.md:287` reads "Detects eleven `house-style.md` patterns ..." and enumerates all four removed by `§`-name. Confirms the issue.
  - Co-decomposition instruction present and consistent with Move 4's "update the docstring count from eleven to the survivor count."
- **Regression check**: Checked Move 4 (line 391-395) against the new Move 6 group (a) — both now route the count edit; no double-edit conflict (Move 4 owns the checker, Move 6 owns the doc-facing row, same step). Clean.
- **Verdict**: VERIFIED.

#### Verify T4: false "§ Banned sentence patterns note" framing in Move 1
- **Original issue**: Move 1 named a nonexistent note in `§ Banned sentence patterns` for the em-dash cross-ref; the real pointer is inside the self-deleting Tier-4 block.
- **Fix applied**: Move 1 now states the em-dash and negative-parallelism cross-refs (`house-style.md:131`, `:123`) sit inside the removed Tier-4 block and self-delete, needing no separate fix.
- **Re-check**:
  - Track location: track-1.md Move 1 lines 373-376.
  - Live premise: `:131` ("Em dashes at every clause boundary ... See § Punctuation and typography.") and `:123` ("It's not X, it's Y. ... See § Banned sentence patterns.") both sit inside the Tier-4 word list under `## Banned vocabulary` (whole-section delete per DR2). Confirms self-deletion.
- **Regression check**: The reworded sentence no longer sends an implementer hunting for a nonexistent note, and correctly hands the surviving in-file fixes to the T1 re-points (`:359`/`:485`) and the DR6 reconciliation removal. Clean.
- **Verdict**: VERIFIED.

#### Verify T5: over-stated re-point set for readability-auditor / design-review
- **Original issue**: Move 6 said "re-point every reference to a removed section" for named consumers including `readability-auditor.md` and `prompts/design-review.md`, neither of which carries a removed-section reference.
- **Fix applied**: Move 6 now splits named consumers into group (a) (actual removed-section reference to re-point/rewrite) and group (b) (`readability-auditor.md` additive ownership note only; `design-review.md` confirm-benign, no edit).
- **Re-check**:
  - Track location: track-1.md Move 6 group (b), lines 442-450.
  - Live premise: `readability-auditor.md:71` = "no rule list is hard-coded here"; only `§ Orientation`/`§ Plain language` cited (both kept). `prompts/design-review.md` cites only kept document-shape sections. Confirms neither needs a re-point.
  - Group (b) text states `readability-auditor.md`'s work is "purely the *additive* part-3 ownership note" and `design-review.md` is "confirm-benign with no edit" — accurate.
- **Regression check**: Group (a) retains the files that do carry removed-section refs (`ai-tells/SKILL.md`, `review-workflow-writing-style.md`, `design-document-rules.md`, `design-author.md`, `readability-feedback/SKILL.md`, root `CLAUDE.md`); no consumer dropped from scope by the split. Clean.
- **Verdict**: VERIFIED.
