<!--MANIFEST
dimension: workflow-hook-safety
prefix: WH
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WH1
    sev: suggestion
    anchor: "#wh1-suggestion"
    loc: ".claude/scripts/design-mechanical-checks.py:2014"
    cert: n/a
    basis: script
-->

## Findings

### WH1 [suggestion] Inflated-abstraction-label scan is per-line, so a subject-slot label split across a soft-wrap break silently does not fire

- File: `.claude/scripts/design-mechanical-checks.py` (line 2014, the `INFLATED_ABSTRACTION_LABEL_RE.finditer(line)` call)
- Axis: Python script (regex scan granularity)
- Cost: a missed tell (foregone review noise) on the rare wrapped-label case; never a false positive, never a halt — `dsc-ai-tell` has no blocker path, so this cannot break the `edit-design` loop
- Issue: the inflated-abstraction-label scan runs per physical line (`for m in INFLATED_ABSTRACTION_LABEL_RE.finditer(line)`), whereas its sibling trailing-negation pattern runs per joined paragraph (`NEGATIVE_PARALLELISM_TRAILING_RE.search(para_text)`). The label frame `The <adjective> <noun> <verb>` matches only when the whole phrase lands on one physical line. A design author who hard-wraps mid-phrase — `The enabling\nprimitive is …` or `The key abstraction\nhere is …` — produces a label the rule will not see. The leading-negation sibling `NEGATIVE_PARALLELISM_RE` is also per-paragraph, so the per-line choice here is the odd one out among the negative-parallelism family.
- Why this is a suggestion, not should-fix: the choice is deliberate and documented (the sentence-start anchor `(?:^|(?<=[.!?]\s)|(?<=[.!?]))` is line-relative, and the surrounding per-line scans share the loop). In practice the label phrase is four short tokens that almost always reflow onto one line, so the real-world miss rate is near zero; the WH1-fix Decision Log entry already frames a missed tell as the accepted trade ("a missed tell is foregone review noise, a false positive is avoided review noise"). The fixture's positive anchor (line 110, "The underlying mechanism is …") sits on one line and fires correctly, so the pattern's contract is met. This is recorded only so a future maintainer who widens the noun set knows the scan operates per-line; if broader recall is ever wanted, joining `para_text` for this pattern (as the trailing-negation arm already does) closes the gap with no false-positive cost, since the sentence-start anchor would then key off paragraph-internal `.!?` terminators.
- Suggestion: leave as-is for this track (the trade is sound and pinned in D4b). If a later track broadens the rule's recall, move the inflated-label scan onto the joined `para_text` to match the negative-parallelism family's granularity, and add a wrapped-label fixture case (`The enabling\nprimitive is …` outside `## Overview`) to pin the firing behavior.

## Evidence base
