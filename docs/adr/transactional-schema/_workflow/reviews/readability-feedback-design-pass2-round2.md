# Pass-2 round-1 result + round-2 residual worklist — design.md (2026-06-16)

## Round-1 result (same five auditors, controlled for auditor variance)

| Range | Section | Baseline | After round 1 |
|---|---|---|---|
| R1 1-219 | Overview / Core Concepts / Class Design / Workflow | 18 | 11 |
| R2 220-494 | Part 1 (schema model) | 19 | 6 |
| R3 495-656 | Part 2 (index transactionality) | 14 | 6 |
| R4 657-851 | Part 3 (mutex + lifecycle) | 14 | 8 |
| R5 852-1018 | Part 3 freezer + Part 4 (migration) | 11 | 7 |
| **Total** | | **76** | **38** |

76 → 38 (−50%), 0 GAPs throughout. Code-grounding beat the prose-only floor (pass-1 internal 54 / fresh-audit baseline 76). All load-bearing code claims verified accurate (ScalableRWLock non-reentrant, OperationsFreezer cutWaitingList/unpark/freezeRequests, EXPORTER_VERSION 14, collection-name derivation). Decisions/invariants/four-lock order unchanged.

## Round-2 scope — FIX the safe local-tell cluster only

Round 2 is a tight, **no-new-content** convergence pass: rewrite roundabout negations to the positive claim, repair negative parallelism, name passive actors, split the few remaining run-ons. It adds no current-state grounding and no new code claims, so it carries minimal semantic and accuracy risk. Everything in LEAVE stays untouched.

### FIX (safe, local, no code needed)

- `design.md:505-507` (R3 F1) — roundabout negation "not a content copy": state what the overlay IS.
- `design.md:521-531` (R3 F3) — roundabout negations "does not see overlay changes for free" / "does not eagerly reconstruct"; gloss "cached-out slot"; split the four-link chain.
- `design.md:575-589` (R3 F5) — roundabout negation "with deleted rows left out"; fix telegraphic trailing fragment "on the commit's single atomic operation".
- `design.md:636-638` (R3 F6) — roundabout negation "changes no query result" + dangling antecedent: state queries stay correct.
- `design.md:515-516` (R3 F2) — split coordinate predicate: separate the two points.
- `design.md:968-970` (R5 F6) — roundabout negation "can never leave … that silently holds": keep the positive claim only.
- `design.md:991-994` (R5 F7) — negative parallelism "rejected … rather than read in as a valid record" + passive: name the actor, drop the contrast.
- `design.md:354-359` (R2 F3) — passive register, ambiguous "it" in "validated against it": name the referent.
- `design.md:211-214` (R1 F11) — gloss "transient quiesce" in one clause; give "Reconciliation" a real actor (cheap, no code).

### LEAVE (deliberately — the floor; do NOT touch)

- `design.md:23-36` (R1 F1/F2/F3/F4) — the two Overview inline inventories ("Four primitives…", "Several subsystems…"): cap-constrained forward-pointers; fixing breaches the ≤40-line Overview cap.
- `design.md:65-73` (R1 F5/F6) — Core Concepts term→definition entries: house-style permits inline-header definition lists; the parallel glossary form is intentional.
- `design.md:134-145, 176-182` (R1 F7/F8/F9/F10) — Class Design enumerations and the AbstractStorage.commit action+lock sentence: the four-lock order must be named inline here (load-bearing); leave intact.
- `design.md:854-859` (R5 F1) — the freezer TL;DR names the mechanism before the body glosses it: a TL;DR legitimately names-then-bodies.
- `design.md:787-794` (R4 F5) — the store-then-load (Dekker) handshake core: irreducible concurrency-mechanism density (the prompt's stated floor).
- `design.md:768-776, 818-821, 824-827` (R4 F4/F7/F8) — ownership-record prose, the tx-scoped resource list, the YTDB-1114 forward reference: inherent density; further splitting risks over-fragmentation.
- `design.md:343-346, 425-447` (R2 F2/F5/F6) — per-property-signal, RID-binding run, root-write-set failure chains: dense but correctly linearized; leave.
- `design.md:866-890, 909-921` (R5 F2/F3/F4/F5) — freezer lock-free-engagement consequence and the two race mechanisms: inherent density; targeted only if trivially splittable without new terms.
