<!--MANIFEST
dimension: workflow-consistency
prefix: WC
findings: 1
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
index:
  - id: WC1
    sev: Recommended
    anchor: "### WC1 [Recommended] conventions.md §1.5 rename-detection helper lists four of six Tier-B headings"
    loc: ".claude/workflow/conventions.md:570-572"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [Recommended] conventions.md §1.5 rename-detection helper lists four of six Tier-B headings

- **File:** `.claude/workflow/conventions.md` (lines 570-572)
- **Axis:** cross-file rule restatement
- **Cost:** the prose promises the helper enumerates every rename pointer site, but the helper cannot detect a rename of `## Orientation` or `## Plain language` — the two most recently added Tier-B headings.
- **Issue:** the branch correctly flipped the prose at `:570` to "The **six** Tier-B section names are stable headings... a future rename in `house-style.md` requires updating every pointer in the same commit", and the `:572` helper is offered as the way to "enumerate pointer sites before renaming". But the helper grep pattern is `'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline'` — only four of the six headings. It omits `## Orientation` (never added after #1142) and `## Plain language` (this branch likewise did not add it). The referent is the helper's own claimed function: a reviewer who renames `## Orientation` or `## Plain language` and runs the documented helper gets zero hits and concludes there are no pointer sites, while every core-doc enumeration, the hook `tier_b_body`, `house-conversation.md`, and the Tier-B table cell all name those two slugs. The prose-vs-helper contract is broken: "enumerate pointer sites" now under-reports for exactly the two newest slugs.
- **Suggestion:** extend the helper pattern to all six headings: `grep -rn 'Orientation\|Plain language\|Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md`. (`Orientation` and `Plain language` are common enough words to raise some false positives, so an author may prefer to anchor them, e.g. `## Orientation` / `## Plain language` / `§ Orientation` / `§ Plain language`; that is a tuning call.) The track flagged this itself as SD5 and deferred the decision to this Phase C pass, so resolving it here closes the open call rather than carrying it into Tracks 2/3.

## Evidence base
