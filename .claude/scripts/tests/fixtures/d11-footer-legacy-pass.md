# D11 Legacy-Footer Pass Fixture

## Overview

This fixture exercises the backward-compatibility half of D11: the
legacy `### References` footer spelling, with a bare `D3` citation whose
full record is introduced elsewhere in the document. The runner asserts
the file emits zero `decision-cited-without-rationale` findings and zero
blockers, so a pre-rename design whose footer carries bare codes keeps
passing as long as each cited record exists somewhere in the doc.

The baseline is the old footer name. The change keeps it valid. The
enabling primitive is the shared footer regex. Nothing else is
restructured. The rest of the document is one shape section.

## Decision detail

**TL;DR.** This section introduces the full record of `D3` so the
legacy footer below can cite it bare and still pass.

**D3. Legacy footer codes resolve against the whole document.**
Alternatives: require rationale inline in every footer, rejected
because it forces a backfill on pre-rename designs. Rationale: a bare
code whose record exists elsewhere is already self-documenting. Risk:
none material.

### References

- D-records: D3
- Invariants: S1 (legacy footer spelling stays valid)
