<!-- MANIFEST
reviewer: reviewer-hook-safety   track: "Track 1: Style-machinery rework"   step: "1.1"   iteration: 1
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0}
cert_index: []
flags: [CONTRACT_OK, EVIDENCE_TRAIL_EXEMPT]
-->

Dimension: workflow hook & script safety (Python-correctness of the reworked
`dsc-ai-tell` checker + tests). Evidence-trail-exempt (no refutation or
certificate phase to persist): the `## Evidence base` is intentionally empty and
`certs: 0`.

Reference-accuracy caveat: this step touches only Python + Markdown under
`.claude/scripts/**`; it names no Java production symbols, so grep/Read is the
correct verification tool and no finding hinges on a PSI symbol search. Findings
below (none) would carry no reference-accuracy risk.

## Findings

No operational-safety or Python-correctness defects found. The step was verified
across every dimension in scope and the two staged test runners were executed
from the staged path to confirm behavior end to end:

- Regex-removal completeness: the four removed regexes and their supporting
  dead code (`NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`,
  `HYPHENATED_PAIR_CLUSTER_RE`, `_title_case_violation`, `iter_paragraphs`,
  `_BULLET_START_RE`, the Title-Case check block, and the per-paragraph scan
  loop) are gone with no dangling callers or references; all three changed
  Python files compile clean (`py_compile`).
- `--fixture-only` CLI flag / calibration-skip logic: correct. `python3
  test_dsc_ai_tell.py --fixture-only` from the staged path exits 0 (fixture
  assertions green in place); full mode from the staged path exits 1, surfacing
  the three unreachable calibration ADRs as designed (full mode must run from
  the live tree at Phase-4 promotion).
- Line-anchored assertion renumbering: `ANCHORED_REGRESSION_CASES`
  (66 = fragmented-header, 40 = inflated-abstraction-label), `NEGATIVE_RANGES`
  (51-62), and `OVERVIEW_INFLATED_LABEL_LINE` (16) all match the post-removal
  fixture line numbers; the fixture-only run passes in place, confirming the
  renumber.
- Summary-shape set case-correctness: `SHAPE_EXEMPT_SECTION_NAMES` carries
  display-case `"Summary"` (compared to raw titles) and
  `MANDATORY_OR_FORM_SUBHEADINGS` carries lowercase `"summary"` (compared
  lowercased); both are pinned by the new `test_dsc_summary_shape.py`
  (part-exempt and siblings cases), which passes from the staged path.
- New shape-test file self-containment: `test_dsc_summary_shape.py` resolves
  `SCRIPT`/`FIXTURE_DIR` through `REPO_ROOT = parents[3]` (the staged mirror
  when run staged), references no `docs/adr/**` corpus, and its `same-shape-siblings`
  / `per-section-shape:tldr` rule-name strings match the checker's emitted rule
  names (so the `assert_no_rule` assertions are not trivially satisfied).
- Operational safety: no `/tmp` usage (no collision surface); both
  `subprocess.run` calls carry `timeout=60` with `TimeoutExpired` handling; no
  `eval` / `shell=True` / `os.system`; no secrets, network calls, or git
  operations in these scripts.

## Evidence base
