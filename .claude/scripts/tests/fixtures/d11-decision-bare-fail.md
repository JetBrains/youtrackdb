# D11 Bare-Decision Fail Fixture

## Overview

This fixture proves the decision-cited-without-rationale check is live,
not a no-op. It cites `D9` bare under a `### Decisions & invariants`
footer: no inline rationale on the citation, and no full `D9` record
introduced anywhere in the document. The runner asserts that exactly
this case emits a `decision-cited-without-rationale` should-fix finding.

The baseline is a footer that names a decision it never states. The
change makes that a should-fix. The enabling primitive is the
introduce-once rule. Nothing else is restructured here. The rest of the
document is one shape section.

## Dangling decision

**TL;DR.** This section cites a decision it never introduces, which is
the dangling-reference shape the new check exists to catch.

The body discusses the shared footer regex without ever stating the
full record for `D9`. A reader who follows the footer code finds no
record to read.

### Decisions & invariants

- D-records: D9
- Invariants: S1 (the dangling citation has no backing record)
