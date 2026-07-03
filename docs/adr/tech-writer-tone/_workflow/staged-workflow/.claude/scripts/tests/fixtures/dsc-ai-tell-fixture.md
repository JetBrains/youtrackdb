# Some Title Cased Adr

## Overview

TL;DR: a seeded fixture for the `dsc-ai-tell` regex rule in
`design-mechanical-checks.py`. Each `### ` block below either trips
one banned pattern (positive case) or deliberately avoids tripping
a high-FP-risk rule (negative case). The fixture is read by the
runner alongside it; the runner asserts every positive block yields
at least one finding and every negative block yields zero.

The two H2 wrappers in this file (`## Overview`, `## Banned
patterns`) have ≥2-line follow-on bodies, so they cannot fire the
fragmented-header rule even if a content word overlaps.

The enabling primitive is the regex set seeded below. That sentence
is the inflated-abstraction-label negative case: it sits inside the
`## Overview` section, where `design-document-rules.md § Overview`
prescribes naming the enabling primitive(s), so the subject-slot
inflated-abstraction rule must skip it even though its shape would
otherwise fire.

### References

- House style file: `.claude/output-styles/house-style.md`.
- Mechanical-check implementation: `.claude/scripts/design-mechanical-checks.py`.
- Runner authored alongside this fixture: `.claude/scripts/tests/test_dsc_ai_tell.py`.

## Banned patterns

### Authority trope

At its core, the bucket-overflow path balances the cost of a
split against the cost of carrying tombstones forward. The opener
above performs depth instead of naming a mechanism, which the
rule flags.

### Inflated abstraction label

The underlying mechanism is a write-ahead log replayed on
recovery. The sentence above names an abstract category as the
subject instead of leading with the concrete thing, which is the
inflated-abstraction-label tell. This block sits outside the
`## Overview` section, so the Overview skip does not apply and the
rule fires here.

### Fragmented header trigger

The fragmented header trigger header is fragmented header text.

### Concrete mechanism negative

The locking mechanism is held by the writer until the page is
flushed. The hashing mechanism provides O(1) bucket lookup. The
polling mechanism is the heartbeat that re-arms the lease. The
indexing property holds across the rebuild. These describe concrete
named mechanisms with a present-participle qualifier; the participle
("locking", "hashing", "polling", "indexing") names a real thing
rather than inflating an abstraction, so the inflated-abstraction-
label rule — whose adjective slot is a curated closed set of
inflation words, not an open participle wildcard — must leave every
one of them alone.

## Banned-pattern regressions

### Fragmented one-liner regression
Fragmented one-liner regression body.
### Boundary heading after fragmented body

The H3 directly before this paragraph closes the fragmented
one-liner block above without an intervening blank line. That
structural shape used to suppress the fragmented-header rule
because the paragraph-length guard treated the next heading as
a paragraph continuation; with the bugfix the next heading is
treated as a new block and the one-liner still fires.
