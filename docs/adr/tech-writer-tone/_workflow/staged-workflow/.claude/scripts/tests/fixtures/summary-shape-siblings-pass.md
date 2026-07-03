# Summary Shape Siblings Fixture

## Overview

This fixture pins that well-formed sections whose only sub-headings
are the mandatory Summary block and the footer do not trip the
same-shape sibling similarity check. Four such sections follow. If
the similarity computation ever counts the shared Summary heading as
a custom sub-heading, every pair reaches full overlap and the
consolidation finding fires on all four.

## Alpha section

### Summary

The alpha section is the first of four sibling sections that share
only mandatory sub-headings.

The alpha body describes buffered page reads.

### Decisions & invariants

- D-records: none, fixture material only.
- Alpha closes without custom sub-headings.

## Beta section

### Summary

The beta section is the second sibling and shares only mandatory
sub-headings with the others.

The beta body describes write-ahead logging.

### Decisions & invariants

- D-records: none, fixture material only.
- Beta closes without custom sub-headings.

## Gamma section

### Summary

The gamma section is the third sibling and shares only mandatory
sub-headings with the others.

The gamma body describes checkpoint scheduling.

### Decisions & invariants

- D-records: none, fixture material only.
- Gamma closes without custom sub-headings.

## Delta section

### Summary

The delta section is the fourth sibling and shares only mandatory
sub-headings with the others.

The delta body describes page eviction.

### Decisions & invariants

- D-records: none, fixture material only.
- Delta closes without custom sub-headings.
