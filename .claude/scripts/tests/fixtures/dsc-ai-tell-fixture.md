# Some Title Cased Adr

## Overview

TL;DR: a seeded fixture for the `dsc-ai-tell` regex rule in
`design-mechanical-checks.py`. Each `### ` block below either trips
one banned pattern (positive case) or deliberately avoids tripping
a high-FP-risk rule (negative case). The fixture is read by the
runner authored in the next step; the runner asserts every positive
block yields at least one finding and every negative block yields
zero.

The H1 above (`# Some Title Cased Adr`) is itself a negative case:
the Title Case rule skips H1 because document titles for published
ADRs use Title Case by convention. The rule body restricts itself
to `^#{2,6}`, so the H1 here must pass without findings.

The two H2 wrappers in this file (`## Overview`, `## Banned
patterns`) are sentence case and have ≥2-line follow-on bodies, so
they cannot fire the fragmented-header rule even if a content word
overlaps. Inside `## Banned patterns` the demo H3 names are
sentence case so they do not themselves register as Title Case
violations.

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

### Tier 1 vocabulary

Drafts that delve into the codebase rarely embark on the obvious
path; the design instead chooses to foster a simpler shape. This
paragraph is here to trip the Tier-1 banned-vocabulary scan on
multiple base words at once, which is enough for the runner's
"≥1 finding per pattern" assertion.

### Negative parallelism

It's not a question of which page format the cache uses, it's a
question of how the cache reuses the page format across calls.
The sentence above is the canonical negative-parallelism shape
the rule detects.

### Em-dash density

The cache layer ingests writes — applies them to a page buffer —
then flushes through the WAL — once the WAL fsync returns the
page is durable, and the read path can serve the new value
without going through the disk. Three em dashes form an
unbalanced `X — Y — Z — W` cadence (the canonical AI-tell shape);
a balanced two-em-dash parenthetical aside would pass the rule.

### Title Case Demo Heading

The H3 above is the positive case for the Title Case rule: three
title-case words after `### ` trigger the regex. The body here is
deliberately multi-line so the fragmented-header rule does not
also fire on the same heading.

### Signposting opener

Let's dive into the leaf-split path. The opener above is the
canonical signposting trigger; the rule fires regardless of the
sentence that follows, so the body here can be ordinary prose
about anything.

### Copula avoidance

The WAL serves as the durability backbone for in-flight writes
on the disk-cache layer. The verb above is the copula-avoidance
trigger; the rule prefers `is` unless the action is active.

### Authority trope

At its core, the bucket-overflow path balances the cost of a
split against the cost of carrying tombstones forward. The opener
above performs depth instead of naming a mechanism, which the
rule flags.

### Hyphenated pair cluster

The codebase ships a fast-paced, well-crafted, next-generation
storage layer. The comma-separated cluster above is exactly the
canonical AI-tell shape; three distinct hyphenated pairs in one
cluster trips the rule.

### Trailing negative parallelism

This design is a fundamental rethinking, not just a patch. The
sentence above is the trailing-negation "X, not just Y" inversion of
negative parallelism: the emphatic intensifier dismisses the modest
reading to perform depth, which is the shape the rule flags as
distinct from the leading-negation form the canonical
negative-parallelism rule already catches. A plain trailing contrast
without the intensifier is ordinary prose and does not fire.

### Inflated abstraction label

The underlying mechanism is a write-ahead log replayed on
recovery. The sentence above names an abstract category as the
subject instead of leading with the concrete thing, which is the
inflated-abstraction-label tell. This block sits outside the
`## Overview` section, so the Overview skip does not apply and the
rule fires here.

### Fragmented header trigger

The fragmented header trigger header is fragmented header text.

### Hyphenated technical compounds negative

The disk-based write cache provides cache-backed data structures
with double-write log protection and per-file durability handling.
Four distinct hyphenated compounds adjacent to nouns, with no
comma cluster, must not trip the hyphenated-pair rule. The cache
also documents how its write-ahead log integrates with the page
buffer pool layer.

### Single em-dash negative

The cache layer ingests writes, applies them to a page buffer,
then flushes through the WAL on commit — and the read path can
serve the new value without going through the disk. The block
above runs six lines with exactly one em dash, which must not
trip the density rule. The paragraph also avoids any banned
vocabulary so the runner sees zero findings on this block.

### Genuine contrast negative

The fix bans out-of-file assumptions, not in-file terseness. The
contract is held by the subset wiring on the writers, not by this
block. The regex targets the subject-slot inflated label, not the
Overview's sanctioned enumeration element. The count bump is
semantic, not numeric, and it must land atomically. Orientation is
a positive floor, not a ban. The choice is to live-edit, not stage.
The legitimacy comes from amending the rule, not from claiming an
opt-out it does not grant. The note reads as an acknowledged
deviation, not a phantom reference. These eight plain contrasts mirror
the eight a `, not <lowercase>` probe surfaces on this branch's own
`design.md`; none carries the emphatic `not just` / `not merely` /
`not simply` intensifier, so the trailing-negation rule must leave
every one of them alone.

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

### Delve into the holistic foster pattern

The H3 above seeds banned vocabulary inside a heading. The
scanner used to skip headings entirely for the Tier-1 vocab
sweep, so an `if is_heading: continue` short-circuit would let
"delve", "holistic", and "foster" pass; the rule should fire
once per base word against the heading text itself.

### Fragmented one-liner regression
Fragmented one-liner regression body.
### Boundary heading after fragmented body

The H3 directly before this paragraph closes the fragmented
one-liner block above without an intervening blank line. That
structural shape used to suppress the fragmented-header rule
because the paragraph-length guard treated the next heading as
a paragraph continuation; with the bugfix the next heading is
treated as a new block and the one-liner still fires.
