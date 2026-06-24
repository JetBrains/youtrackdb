<!--MANIFEST
dimension: workflow-consistency
prefix: WC
iteration: 1
findings_total: 2
sev_counts: {critical: 0, should-fix: 2, minor: 0}
evidence_base: {certs: 0}
cert_index: []
flags: {evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist"}
index:
  - id: WC1
    sev: should-fix
    anchor: "#wc1-should-fix"
    loc: "docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1959-1960"
    cert: n/a
    basis: "stale read-side comment contradicts the new ledger-first code directly below it and the flipped conventions.md clause"
  - id: WC2
    sev: should-fix
    anchor: "#wc2-should-fix"
    loc: "docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:79"
    cert: n/a
    basis: "grammar-comment read-semantics bullet contradicts the same header's new substate description and the flipped conventions.md clause"
-->

## Findings

### WC1 [should-fix] determine_state_from_ledger `C)` arm comment still says the track file owns the within-track sub-state

- **File:** `docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 1959-1960)
- **Axis:** cross-file rule restatement
- **Cost:** comment-vs-code drift inside the function this track rewrites; a future reader trusting the comment routes resume off the wrong source.
- **Issue:** The `C)` arm header comment reads "Execution resume: the ledger owns the active track, **the track file owns the within-track sub-state**." The new ledger-first read block this diff inserts immediately below it (lines 1965-1985) reverses exactly that: it reads the sub-state from the track-scoped ledger `substate` key first and only falls back to the track file. The comment is verbatim-copied from the live file (live lines 1805-1806) and was not updated when the contradicting code was spliced in under it. The parallel clause in `conventions.md` line 89 (*Phase ledger* glossary, "the track file's `## Progress` still owns the within-track sub-state") was correctly flipped to the ledger-first description in this same diff — so the diff updated one restatement of the rule and left this one stale. Referent: the rule's source of truth is now the flipped `conventions.md §1.1 *Phase ledger*` row and D1; this comment contradicts both.
- **Suggestion:** Reword the `C)` arm header to match the new contract, e.g. "the ledger owns the active track and (ledger-first) the within-track sub-state, with the track file as the fallback source." Aligns the comment with both the code below it and the flipped `conventions.md` glossary row.

### WC2 [should-fix] Ledger-grammar header read-semantics bullet still says `## Progress` owns the within-track sub-state

- **File:** `docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 79)
- **Axis:** cross-file rule restatement
- **Cost:** the same header that now defines `substate` as "the within-track resume signal" also denies it owns the sub-state; the grammar block contradicts itself and the conventions glossary.
- **Issue:** The read-semantics bullet closes with "`phase` and `track` feed determine_state's two-level resume (the ledger owns the top-level phase and the active track; **the track file's `## Progress` owns the within-track sub-state**)." Lines 61-64 of the same header — added by this diff — now describe `substate` as "the within-track resume signal ... emitted in the pre-`categories` block," and the new reader treats the ledger `substate` as authoritative for the within-track sub-state (track file is only the fallback). Line 79 is verbatim-copied from the live header (the diff's hunk stopped just above it) and so survived unflipped, even though the byte-identical clause in `conventions.md` line 89 was flipped in this diff. Referent: contradicts the in-delta `substate` definition four lines up and the flipped `conventions.md §1.1 *Phase ledger*` row.
- **Suggestion:** Update the parenthetical to "the within-track sub-state is read ledger-first from the track-scoped `substate` key, falling back to the track file's `## Progress`/`## Concrete Steps`" — matching lines 61-64, the new code, and `conventions.md` line 89.

## Evidence base
