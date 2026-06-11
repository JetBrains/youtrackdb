<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 0, Minor: 1}
index:
  - {id: WP1, sev: Minor, loc: "edit-design/SKILL.md:414-431", anchor: "### WP1 ", cert: n/a, basis: "S3 gate open/closed rule hinges on a log-heading shape the runtime prompt describes only loosely (ellipsis + prose), with the format contract owned by a different file"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Minor]
- File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 414-431)
- Axis: deterministic decision rules
- Cost: borderline reader ambiguity — the gate's open/closed decision rests on a heading-shape match the runtime prompt under-specifies, so a fresh reader must infer the exact format from a sibling file
- Issue: the new S3 gate (Step 4) is the load-bearing addition, and its decision rule is otherwise good: "read the research log's `### Adversarial review of this log …` section … a `NEEDS REVISION` heading with any open blocker or should-fix is an **open** entry. The cold-read **must not run while any log-adversarial entry is open**." The gap is in how the reader *recognizes* an open entry. The prompt names the section with a trailing ellipsis (`### Adversarial review of this log …`) and says the verdict is "encoded in that section's heading," but never pins the exact heading shape the reader must match against. The actual contract — visible in this branch's own log at `_workflow/research-log.md:1228`, `### Adversarial review of this log (2026-06-09T06:30Z, dogfood) — NEEDS REVISION: 2 blockers, 4 should-fix, 2 suggestions` — carries a timestamp, a dash, a `PASS`/`NEEDS REVISION` token, and a count breakdown. The producer of that heading is `create-plan/SKILL.md` (which refers to it generically as "the research log's own gate records," not by this heading), so the format contract is split across two files and never stated in the file that does the matching. A fresh cold-read reader (this is a clean-context invocation — the orchestrator spawns edit-design with no memory of how the gate writer phrased its heading) is told *what to conclude* from the heading but not the precise lexical pattern that distinguishes an open `NEEDS REVISION` entry from a cleared `PASS` one, nor that multiple dated entries can stack (the gate loops, so iteration ≥2 appends a new dated heading — the reader must check the latest, not any). Borderline because an attentive reader resolves it from the live log, and "any open blocker or should-fix" gives the semantic test even without the exact string; it does not break the gate, it just leaves the lexical match implicit.
- Suggestion: add one clause to the Step 4 S3-gate paragraph pinning the heading shape the reader matches, e.g. "The latest such heading reads `### Adversarial review of this log (<ISO timestamp>) — <PASS | NEEDS REVISION[: counts]>`; an entry whose newest heading is `NEEDS REVISION` with a non-zero blocker or should-fix count is **open**, a `PASS` heading is cleared. When the gate has looped, match the most recent dated entry, not any." This makes the open/closed test self-contained in the file that runs it. A lighter alternative: drop the ellipsis and cross-reference the heading format to its definition site (`research.md` / `create-plan` Step 4) so the contract is at least pointer-resolvable from the matching file. This is a candidate to fold into the Phase-C track-level consistency touch already noted in the Step 2 episode (the producer-side heading naming in `create-plan` and `research.md` is the other half of the same contract and sits outside this step's in-remit file).

## Evidence base
