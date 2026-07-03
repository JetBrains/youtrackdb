# Summary Shape Missing Fixture

## Overview

This fixture pins that the summary-shape check stays live after the
rename. The bare section below carries a footer and body prose with
no summary block in its head lines, so exactly one summary-shape
blocker must fire on it, while the well-formed section beside it
must produce none.

## Good section

### Summary

The good section carries the full mandatory shape and must produce
no summary-shape finding.

### Decisions & invariants

- D-records: none, fixture material only.
- The good section is the fire/no-fire control.

## Bare section

The bare section opens with plain body prose and never introduces a
summary block of any spelling, so the summary-shape check must flag
it. The footer below stays present so the missing-footer blocker
does not fire alongside and blur the assertion.

### Decisions & invariants

- D-records: none, fixture material only.
- Only the summary blocker may fire on this section.
