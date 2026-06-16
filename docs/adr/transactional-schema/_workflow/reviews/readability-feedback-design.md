# Readability-feedback audit — design.md (2026-06-16)

Target: `docs/adr/transactional-schema/_workflow/design.md` (847 lines, 15 `##` sections, 4 Parts), frozen Phase-1 seed.
Method: 5 parallel range audits against the live `.claude/output-styles/house-style.md`.

## Headline

55 obscure passages, **0 GAPs**. Every finding is caught by an existing house-style rule, so there is **no rule change to propose** — the ruleset is already complete against this doc. The skill's rule-hardening stream is empty.

The 55 findings below are the doc's own violations against current rules (the handoff's stream a — candidates for `edit-design` content-edits). They cluster into one dominant judgment-heavy category and a small unambiguous cluster.

## By tell

| Tell (house-style section) | Count | Nature |
|---|---|---|
| § Orientation (causal chain folded into run-on; entity used without first-use gloss; terse one-line assertion) | ~34 | Judgment-heavy. Dense mechanism prose. Fixing means splitting sentences / adding glosses → lengthens the doc. |
| § Mechanism traces and inline citations (multi-step mechanism crammed into one sentence; should be a list/trace) | ~9 | Restructuring. Convert run-on step lists into numbered traces. |
| § Banned analysis patterns › Broken grammar around code identifiers (missing copula, subjectless/absolute fragment, split predicate) | ~7 | Unambiguous, local, cheap. |
| § Passive voice and subjectless fragments | ~3 | Unambiguous, local. |
| § Banned sentence patterns (roundabout negation; negative parallelism) | ~2 | Unambiguous, local. |
| § Banned analysis patterns › Filler hedges ("crucially") | ~1 | Trivial delete. |

The unambiguous cluster (broken grammar + passive + banned sentence patterns + filler) is ~13 local fixes that improve readability without restructuring. The § Orientation + § Mechanism-traces cluster (~43) is the density question: each fix splits a dense causal chain or converts a run-on into a list, which lengthens an already-dense design.

## Calibration note

This doc PASSED its Step-4a cold-read, whose reviewer also scans § Orientation density (the `### Prose AI-tell additions` block in `design-review.md`). A dedicated audit then found 34 § Orientation density hits. Two readings: the cold-read tolerates dense mechanism prose in a design doc, or the audit over-flags acceptable design-doc register. The cold-read deliberately deferred density to this pass (memory: "one suggestion deferred to the doc-wide readability-feedback pass"), and the Part-3 terseness reflection pre-flagged exactly this. So the heavy § Orientation count in the Part-1/2/3 mechanism prose is the expected output, not a surprise.

Because the audit is CAUGHT-only by design (the skill classifies every terse/dense passage as CAUGHT by § Orientation, never a GAP), the volume does not produce a rule gap. It is a signal about how aggressively to apply an existing rule to a frozen dense design — a user judgment call, not a rule change.

## Findings

### § Orientation — causal-chain density / unglossed entity / terse assertion (~34)

- `design.md:14-21` — commit diff→create/drop→atomic→recoverable chain in one sentence.
- `design.md:31-36` — second run-on inventory; five mechanisms dropped as bare appositive labels with no gloss.
- `design.md:134-141` — "the derived-state ripple stays inside it" dropped with no motivation.
- `design.md:204-208` — two-branch freeze behavior compressed into one semicolon-joined sentence.
- `design.md:206-208` — back-to-back one-line assertions ("lock-free inner primitives"; promotion re-parse) stated flat.
- `design.md:219-224` — mutate/seed/route/stay-committed + two-part consequence in one sentence.
- `design.md:237-240` — `fromStream` re-parse rationale; "keeps the derived-state ripple … inside the copy" terse.
- `design.md:240-245` — read-routing + proxy re-resolution + leak consequence chained "and … so".
- `design.md:252-257` — long parenthetical in subject slot, then semicolon-spliced three-clause run-on.
- `design.md:257-259` — "feeds a null collection name under a provisional id" dropped with no gloss.
- `design.md:311-318` — two multi-clause run-ons folding several mechanisms; "buffered intent applied only in `commitChanges`, which rollback skips" garden path.
- `design.md:354-357` — trailing simile "the way the index manager binds each index's identity" with no gloss.
- `design.md:359-366` — embedded 2-item parenthetical + "so" + semicolon counterfactual with two failure modes, one breath.
- `design.md:425-431` — handle composition / data location / copy consequence / new-index consequence + parenthetical, all before the conclusion lands.
- `design.md:432-438` — requirement + "because" cause + consequence with signature asides + "or … silently untracked".
- `design.md:438-442` — vague placeholder subject "The per-record tracking this surfaces"; entry-source contrast + cross-ref + flush-race claim in one unit.
- `design.md:476-487` — split definition + population rule + re-derivation rule + deletion exception stacked across colon/em-dash/semicolon.
- `design.md:488-492` — engine absence → read throws → planner skip → scan fallback chained, with trailing formula.
- `design.md:524-529` — colon then "under … so … and" three-link chain in one sentence.
- `design.md:527-531` — rename mechanism + "so" + "while" contrast + unrelated YTDB-1066 deferral spliced on.
- `design.md:559-563` — write lock + four locks + acyclic order + blocking + deadlock-free in one run-on.
- `design.md:569-576` — second sentence chains park → hold → freeze → deadlock in one identifier-dense clause.
- `design.md:583-586` — "joined the order" / "for the same shape one registry over" telegraphic shorthand.
- `design.md:586-589` — "the schema chokepoint" ungloss­ed; two facts in one semicolon run-on.
- `design.md:589-592` — three-clause run-on with "as healthy contention" copula-less appositive.
- `design.md:601-602` — "In-scope mitigations convert the two remaining … sites" dropped with no motivation.
- `design.md:626-630` — release key + compare-and-clear + "fires only if" + "which stops …" chained.
- `design.md:649-655` — "an engage caught mid-flight" runtime event in subject slot; finish-acquire → closed session → gate → lockout → wedge run-on.
- `design.md:684-689` — parenthetical packs four undefined compound entities (identity-keyed snapshot registry, lease-based stranding detection, revocation fenced at storage boundaries).
- `design.md:698-702` — gate description + memory-visibility + zombie-commit + "harmless because …" fragment, one sentence.
- `design.md:713-718` — three mechanism components (taxonomy, two-site gate, cut-and-unpark) folded onto an unprimed reader.
- `design.md:754-758` — long parenthetical case def + four-step consequence chain + em-dash counterfactual; "after its increment" ungloss­ed.
- `design.md:758-763` — nested parenthetical race-def with "including the case where …" sub-case interrupts subject before main clause.
- `design.md:811-815` — two failure paths each with own mechanism noun ("broken-RIDs set", "promote-only-on-success completion flag") chained after colon, no first-use gloss.
- `design.md:822-826` — telegraphic parenthetical gloss; "even in the manifest era" / "enforceable only by v15-aware importers" dropped with no motivation.

### § Mechanism traces and inline citations — multi-step mechanism crammed into one sentence (~9)

- `design.md:23-29` — four primitives as four semicolon-separated multi-clause definitions in one sentence.
- `design.md:282-287` — second sentence is three semicolon-spliced independent mechanism assertions.
- `design.md:298-304` — run-on chaining four comma-separated mechanisms + "including …, or …" tail; mid-clause code asides.
- `design.md:444-447` — three coordinate commit actions + trailing `-ing` clause as a comma-spliced list.
- `design.md:643-647` — two parenthetical justifications wedged mid-clause around split "rather than throwing … or re-releasing".
- `design.md:722-734` — probe + throw-with-exact-type + parenthetical aside + zero-locks + park branch across stacked clauses.
- `design.md:806-809` — manifest-emission mechanism + three-item inline failure enumeration in one breath.
- `design.md:818-822` — semicolon run-on of four facts with code-literal asides (`EXPORTER_VERSION 15`; "primary exception, not a close-path secondary").

### § Banned analysis patterns › Broken grammar around code identifiers (~7)

- `design.md:64-67` — dropped relative pronoun garden path ("A sentinel negative id … a new collection carries"); `<= -2` aside wedged mid-clause.
- `design.md:305-307` — split coordinate predicate; second clause "collection creation before record-position allocation" drops the verb.
- `design.md:522-524` — trailing appositive "the one non-WAL-safe physical collection mutation, removed" missing copula.
- `design.md:565-568` — "acceptable because the schema-change rate is low" dangling subjectless fragment.
- `design.md:655-661` — "An engage/teardown handshake closes it, a store-then-load (Dekker) pair" missing copula.
- `design.md:677-679` — "One rider:" placeholder framing; "wiped early, the normal heal presents nothing and wedges" subjectless absolute clause.

### § Passive voice and subjectless fragments (~3)

- `design.md:71-75` — subjectless fragment, no copula, four lock names with no connective tissue distinguishing them.
- `design.md:732-734` — passive "The write lock is acquired" hides actor; conditional split from the verbs it governs.
- `design.md:815-818` — "spills to a transient file beyond a threshold" drops connective tissue; passive "is exported rather than shed".

### § Banned sentence patterns (~2)

- `design.md:229-235` — "deferred, not rejected" roundabout negation + appositive pile-up with no verb.
- `design.md:802-806` — passive "is rejected … rather than migrated in place" + "becomes X, not Y" negative parallelism restating the same fact.

### § Banned analysis patterns › Filler hedges (~1)

- `design.md:389-396` — "crucially" filler emphasis word; split coordinate predicate across a long interjection.
