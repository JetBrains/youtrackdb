<!-- MANIFEST
review: workflow-writing-style
target: track-2
iteration: 1
findings: 1
evidence_base: 1
cert_index: [C1]
flags: []
index:
  - {id: WS1, sev: Minor, anchor: "### WS1 ", loc: ".claude/workflow/risk-tagging.md:251-257", cert: C1, basis: "faux-symmetric bolded contrast 'mapped, not merged' followed by a closing restatement of the same distinction"}
-->

## Findings

### WS1 [Minor] Faux-symmetric bolded contrast plus restatement in the new "Track-level complexity tag" section

- **File**: `.claude/workflow/risk-tagging.md` (staged copy), lines 251-257
- **Axis**: banned sentence patterns (negative parallelism / faux-symmetry) + elegant-variation-style restatement
- **Cost**: a bolded `X, not Y` contrast pair carrying a closing sentence that re-states the same distinction in different words — minor padding inside otherwise dense, load-bearing prose.
- **Issue**: The paragraph opens "The complexity tag (the seven triggers) and the thirteen `code-review` categories ... stay **distinct** — they are **mapped, not merged**." The `mapped, not merged` pair is a negative-parallelism shape (§ Banned sentence patterns) given visual weight by the boldface. The paragraph's last sentence — "They overlap but serve two purposes, so the design maps one onto the other rather than collapsing them." — restates the same mapped-not-merged point already made by the bolded pair and the two preceding `how hard` / `which dimensions` clauses (§ Elegant variation / § Self-check item 10, a paragraph tail adding no information beyond the previous one). The middle two sentences (`how hard` vs `which dimensions`) already carry the whole distinction; the bolded contrast and the closing restatement bracket them with redundant phrasing.
- **Suggestion**: Drop the `mapped, not merged` bold pair and the closing restatement, and let the two concrete clauses stand:

  > The complexity tag (the seven triggers) and the thirteen `code-review` categories stay distinct and serve two different purposes. The seven triggers answer *how hard* a track is (Phase-A breadth + Phase-C rigor); the thirteen categories answer *which dimensions* a track touches (which Phase-C reviewers run). The design maps one onto the other.

## Evidence base

#### C1 — WS1: faux-symmetry + restatement (CONFIRMED)
`risk-tagging.md:252-253` reads "stay **distinct** — they are **mapped, not merged**", a bolded `X, not Y` pair matching § Banned sentence patterns negative parallelism; the closing sentence at 256-257 "the design maps one onto the other rather than collapsing them" repeats the mapped-not-merged claim the bolded pair and the `how hard`/`which dimensions` clauses already establish, matching § Self-check item 10 (paragraph adding no information beyond the previous one). Severity Minor: the surrounding section is otherwise compliant and the redundancy is local; § Padding-based finding criterion does not apply because the unit is well under the 200-word soft cap, so this is reported on the pattern itself, not on length.
