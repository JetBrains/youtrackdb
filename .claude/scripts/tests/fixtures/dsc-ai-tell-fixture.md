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

The cache layer ingests writes, applies them to a page buffer,
then flushes through the WAL — once the WAL fsync returns, the
page is durable — and the read path can serve the new value
without going through the disk. Two em dashes in one paragraph
trips the density rule.

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
