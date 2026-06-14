<!--MANIFEST
dimension: workflow-instruction-completeness
prefix: WI
findings: 1
evidence_base:
  certs: 1
cert_index: [C1]
flags:
  evidence_trail_exempt: false
index:
  - id: WI1
    sev: Minor
    anchor: "wi1-rename-grep-blind-spot"
    loc: ".claude/workflow/conventions.md:570-572"
    cert: C1
    basis: judgment
-->

## Findings

### WI1 [Minor] Rename-procedure grep cannot find two of the six headings it governs

- **File:** `.claude/workflow/conventions.md` (line 570-572)
- **Axis:** error and recovery path
- **Cost:** a future heading rename runs the stated rename procedure, gets zero hits for `## Orientation` or `## Plain language`, and concludes there is nothing to update while 33 files name those headings.
- **Issue:** This track flips the prose at `:570` from "five Tier-B section names" to "six" and adds the boundary clause and Tier-B restatement directly below it (`:574`-`:589`), but leaves the rename-aid grep at `:572` unchanged. That grep — `grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` — searches only four of the six heading literals. Its stated contract is "to enumerate pointer sites before renaming," and its verbatim copy at `readability-feedback/SKILL.md:54` (Track 2 scope) is introduced as the way to "find every pointer and update them in the same commit." The procedure is unsatisfiable for `## Orientation` (a pre-existing gap since the #1142 flip) and now, by this track's own addition, for `## Plain language`. Renaming either heading produces no grep output, so the recovery path the section promises silently no-ops. The track recorded this as SD5 and deferred the call to Phase C; this is that call.
- **Suggestion:** Complete the `:572` grep pattern to all six headings: `grep -rn 'Orientation\|Plain language\|Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md`. The track's prose count already reads "six"; aligning the rename aid to the same six closes the contract gap. The identical copy at `readability-feedback/SKILL.md:54` should be fixed in the same family of edits, but it sits in Track 2's skill scope, so flag it for that track rather than editing it here. Note that `## Plain language` matches the `## Voice and tone` "Plain words" prose loosely, but as a heading literal the two-word phrase is distinct enough for the grep; no further disambiguation is needed.

## Evidence base

#### C1: rename-grep blind spot (judgment, confirmed)

The recovery path the section claims at `:571`-`:572` ("a future rename in `house-style.md` requires updating every pointer in the same commit. Run `grep …` to enumerate pointer sites before renaming") is the only documented procedure for a heading rename. Verified the grep pattern carries four heading literals (`Banned vocabulary`, `Banned sentence patterns`, `Banned analysis patterns`, `Em-dash discipline`) and not `Orientation` or `Plain language`. Verified 33 files under `.claude/output-styles/`, `.claude/workflow/`, and `CLAUDE.md` name `Orientation` or `Plain language`, none of which the grep would surface on a rename of those two headings. Verified the identical grep recurs at `readability-feedback/SKILL.md:54` under prose that calls it the way to "find every pointer," confirming the procedure is consumed as a completeness contract, not a representative sample. The track's own `:570` count now reads "six" while the adjacent `:572` enumeration aid reads four, so the section is internally split between the count it asserts and the procedure it offers. Confirmed gap; the suggestion completes the grep to the same six the count names.
