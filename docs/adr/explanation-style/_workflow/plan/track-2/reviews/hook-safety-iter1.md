<!--MANIFEST
dimension: workflow-hook-safety
prefix: WH
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WH1
    sev: should-fix
    anchor: "#wh1-should-fix"
    loc: ".claude/scripts/design-mechanical-checks.py:52-63"
    cert: n/a
    basis: script
-->

## Findings

### WH1 [should-fix] Open-ended participle alternation in INFLATED_ABSTRACTION_LABEL_RE creates a latent false-positive surface on concrete-mechanism prose

- File: `.claude/scripts/design-mechanical-checks.py` (lines 52-63)
- Axis: Python script (regex correctness / false-positive safety)
- Cost: spurious `should-fix` on legitimate technical prose in every future `design.md` / `design-mechanics.md` mutation — review noise, not a halt (`dsc-ai-tell` has no blocker path, so it cannot break the `edit-design` loop)
- Issue: the first capture group `(?:[a-z]+ing|[a-z]+ed|key|core|central|…)` opens the adjective slot to **any** present or past participle, and the label-noun group includes common concrete technical nouns (`mechanism`, `property`, `concept`, `principle`, `factor`, `force`). The cross-product fires on ordinary concrete-mechanism prose where the participle names a real thing rather than inflating an abstraction. Verified by direct probe:
  - `The locking mechanism is held by the writer.` → fires (`The locking mechanism is`)
  - `The polling mechanism is the heartbeat.` → fires
  - `The hashing mechanism provides O(1).` → fires
  - `The indexing property holds.` → fires
  - `The buffered property is set.` → fires
  - `The mapped concept is stored.` → fires

  These are exactly the concrete-noun, lock/poll/hash/index descriptions a YouTrackDB storage-engine design doc routinely contains; none is the "inflated abstraction used AS the thing" tell the rule targets. The calibration corpus (this branch's `design.md` and the three calibration ADRs) is clean **today** — verified zero new-pattern findings — because none happens to use a `The <participle> <label-noun> <verb>` phrasing. But the rule runs on every future design mutation on this repo, so the surface is latent rather than absent. The hand-picked adjective list (`key`, `core`, `underlying`, `fundamental`, `defining`, …) is well-calibrated to the inflation tell; the open `[a-z]+ing` / `[a-z]+ed` arms are the part that over-reaches.
- Suggestion: constrain the participle arms so they do not pair with the concrete-mechanism nouns. Lowest-risk options, in order: (a) drop the bare `[a-z]+ing` / `[a-z]+ed` arms and rely on the curated participle adjectives already present (`underlying`, `unifying`, `guiding`, `governing`, `defining`, `driving`) — these are the inflation-signalling participles, and the open arms add little beyond them; or (b) keep the open arms but narrow the noun group to the genuinely abstract heads (`primitive`, `abstraction`, `insight`, `idea`, `concept`, `principle`, `notion`, `observation`, `realization`) and move the concretely-usable nouns (`mechanism`, `property`, `factor`, `force`, `characteristic`) behind the curated-adjective arm only. Whichever path is taken, add a guard fixture case (e.g. `The locking mechanism is held by the writer.` as a negative case outside `## Overview`) so the calibration test pins the non-firing behavior. This is a should-fix, not a blocker, only because `dsc-ai-tell` exits 1 on blockers alone (D4b) — a false positive degrades review signal but does not stop a mutation.

## Evidence base
