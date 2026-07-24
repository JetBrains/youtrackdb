# Readability-feedback re-audit (pass-2 baseline) — design.md (2026-06-16)

Target: `docs/adr/transactional-schema/_workflow/design.md` — the **committed pass-1** doc (889 lines, 15 `##` sections, 4 Parts), HEAD `387f37bec8` (Mutation 3).
Method: 5 parallel range audits against the live `.claude/output-styles/house-style.md`, same cold-auditor contract as the first audit (`readability-feedback-design.md`). Ranges re-derived from current `# Part` / `## ` headings: R1 1-211, R2 212-425, R3 426-572, R4 573-745, R5 746-889.

## Headline

**76 obscure passages, 0 GAPs.** Every finding is caught by an existing house-style rule (dominant: § Orientation, § Plain language, § Mechanism traces and inline citations, § Banned sentence/analysis patterns). No rule change to propose — the ruleset is complete against this doc, same as the first audit.

**Count caveat (for the YTDB-1130 data point).** The prior pass-1 author's internal verify reported ~54. This fresh, independent 5-auditor re-read of the *same committed doc* reports 76. The 22-finding gap is auditor variance, not a regression. Pass-2's effect must therefore be measured by re-running **these same five auditors** on the post-author doc, so the delta isolates the author's effect from auditor noise. The cross-method "54 → pass-2" comparison would be misleading.

## By range / by tell

| Range | Section | Findings | Dominant tell |
|---|---|---|---|
| R1 1-211 | Overview / Core Concepts / Class Design / Workflow | 18 | § Orientation gloss-at-first-use + § Mechanism inline enumerations + nominalized subjects |
| R2 212-425 | Part 1 (schema model) | 19 | § Orientation linearize/gloss + § Banned sentence patterns (negation) |
| R3 426-572 | Part 2 (index transactionality) | 14 | § Banned sentence patterns + § Orientation + passive voice |
| R4 573-745 | Part 3 (mutex + lifecycle) | 14 | § Orientation gloss-at-first-use + linearize causal chain |
| R5 746-889 | Part 3 freezer + Part 4 (migration) | 11 | § Orientation + § Banned sentence patterns (negation) |

The § Orientation cluster (gloss-at-first-use, linearize causal chain, lead-with-claim, terse-assertion-no-motivation) is the dominant and the hardest. Per the user's pass-1 diagnosis, much of it needs current-system grounding the doc never states — the lever the code-grounded author adds: establish the current-state baseline (grounded in code) before the change. The local-grammar tells (missing copula, split predicate, roundabout negation, negative parallelism, idioms) are cheap fixes that need no code.

## Findings — Range 1 (design.md:1-211)

- `design.md:6-12` — [§ Plain language] 45-word run-on packs self-commit + monolithic record + no-rollback into one comma-splice.
- `design.md:23-29` — [§ Mechanism traces] ~75-word four-item inline enumeration of compound noun phrases ("Four primitives make the inversion possible: …").
- `design.md:24-25` — [§ Orientation] "a per-session copy-on-first-write tx-local `SchemaShared`" — four stacked modifiers, entity named in modifier-soup not glossed.
- `design.md:32-36` — [§ Mechanism traces] ~60-word five-item inline list of compressed noun phrases ("Several subsystems restructure to fit: …").
- `design.md:32-33` — [§ Orientation] "operator freeze", "read outage", "freezer gate" used on first appearance with no gloss.
- `design.md:46-47` — [§ Broken grammar] "the delta from today's behavior is visible at a glance" — split-predicate meta-instruction.
- `design.md:54-58` — [§ Plain language] 40-word verbless fragment (no main copula), two stacked participials before "so".
- `design.md:64-69` — [§ Mechanism traces] subjectless fragment, code aside `<= -2` wedged mid-clause, trailing participial.
- `design.md:71-75` — [§ Broken grammar] `Semaphore(1)` signature in subject slot, no copula; three named locks dropped with no gloss.
- `design.md:134-141` — [§ Orientation] "derived-state ripple" introduced in a bare assertion, glossed only in a trailing parenthetical; "So…" link asserted not linearized.
- `design.md:136-141` — [§ Mechanism traces] inline three-item enumeration ("holds three things: …") of dense identifiers.
- `design.md:172-176` — [§ Orientation] "session-keyed holder record" unglossed; "its engage and release rules are Part 3's subject" is a contentless forward-pointer.
- `design.md:174-176` — [§ Nominalization] "reconciliation, promotion, and overlay publication" stacked as copula subject; "four-lock sequence" named as if pre-defined.
- `design.md:198-199` — [§ Orientation] prose says "four-lock order" but only two named locks + `stateLock` appear; count never reconciled in text.
- `design.md:205-210` — [§ Orientation] "transient quiesce" used as a noun with no gloss of "quiesce".
- `design.md:208-209` — [§ Nominalization] "Reconciliation runs the lock-free inner primitives" — nominalized subject, primitives never introduced.
- `design.md:209-210` — [§ Nominalization] "Promotion re-parses … fires one `forceSnapshot`" — verb-as-noun subject, actor elided, `forceSnapshot` unglossed.
- `design.md:38-41` — [§ Navigability] verbless colon-dump roadmap of every section name (roadmap must be one sentence with a verb).

## Findings — Range 2 (design.md:212-425)

- `design.md:214-217` — [§ Mechanism traces] 50-word run-on chains four colon-introduced topics.
- `design.md:225-227` — [§ Orientation] "two kinds of entry point … first kind … second kind" taxonomy with no motivation; "ride it" unglossed.
- `design.md:232-235` — [§ Plain language] "for free" idiom; colon dumps four identifier terms with no connective; "cross-class derived state" unglossed.
- `design.md:235-238` — [§ Orientation] "recomputed ripple closure", "overlay-aware resolution" dense nominalizations dropped as bare assertions.
- `design.md:240-242` — [§ Orientation] `SchemaClassImpl.owner` in subject slot driving a terse causal claim; reader connects "object references" to "shared instances".
- `design.md:247-251` — [§ Banned sentence patterns] "captured delegate", "tx-local graph" unglossed; "rather than using a captured delegate" negative parallelism.
- `design.md:253` — [§ Orientation] "The inversion" refers across a section boundary to D1 with no local gloss.
- `design.md:253-256` — [§ Mechanism traces] colon dumps five code identifiers as a bare list.
- `design.md:261-264` — [§ Banned sentence patterns] "they … they … They" subjectless-pronoun chain; "do not throw. They instead" roundabout negation.
- `design.md:264-268` — [§ Banned sentence patterns] "feeds a null collection name under a provisional id" telegraphic + mid-clause forward-ref parenthetical; "not only an isolation one" negative parallelism.
- `design.md:291-297` — [§ Mechanism traces] (TL;DR) second sentence crams three semicolon-joined independent assertions as flattened list.
- `design.md:307-310` — [§ Nominalization] "needs care" placeholder for the actual constraint; "it" antecedent ambiguous (range vs layer).
- `design.md:317-318` — [§ Orientation] "Ordering is load-bearing. Engine creation runs before… Collection creation runs before…" — terse assertions, no motivation for why order matters.
- `design.md:320-323` — [§ Orientation] "lock-free inner primitives", "public structural methods", "non-reentrant `stateLock`" stacked unglossed; hazard of re-acquiring stateLock not stated.
- `design.md:325-330` — [§ Passive voice] "must leave no phantom registration and reusable ids" garbled conjunction; two "rules" in passive with no actor.
- `design.md:330-333` — [§ Nominalization] "Structural revertibility" nominalized subject; "File create and delete are buffered intent" telegraphic copula.
- `design.md:359-364` — [§ Broken grammar] (TL;DR) trailing em-dash appositive "the write-amplification reduction YTDB-382 exists for" drops relative pronoun.
- `design.md:379-383` — [§ Orientation] "restarts into a null global-reference error" telegraphic (DB restart, not code); "colliding collection names" no link to stale counter.
- `design.md:408-413` — [§ Orientation] single clause folds the whole causal chain (unified tx → unbuilt index visible same-tx → direct lookup → throws → unless scan fallback).

## Findings — Range 3 (design.md:426-572)

- `design.md:428-431` — [§ Plain language] 50-word sentence folds five topics under one relative-clause subject.
- `design.md:435-440` — [§ Banned sentence patterns] roundabout negation ("not a content copy").
- `design.md:443-446` — [§ Broken grammar] split coordinate predicate: two unrelated causal points comma-joined.
- `design.md:451` — [§ Passive voice] "The snapshot's class-index list is sourced from the index manager" — inverted actor.
- `design.md:457-458` — [§ Banned sentence patterns] "lazy invalidation, not eager reconstruction" negative parallelism; assertion dropped with no explanation.
- `design.md:461-462` — [§ Broken grammar] dropped relative pronoun ("the per-record tracking [that] the rebuild surfaces").
- `design.md:466-471` — [§ Passive voice + § Mechanism traces] three-step commit mechanism as disconnected one-liners; "are written" passive no actor.
- `design.md:493-495` — [§ Banned analysis patterns] two trailing participials ("accepting the stall", "justified by…") as telegraphic fragments, no subject.
- `design.md:499-501` — [§ Banned sentence patterns + § Plain language] roundabout negation ("uses no X and no Y"); dense identifier chain wedged in.
- `design.md:502-508` — [§ Orientation] dense two-source mechanism as stacked terse assertions; "final-state puts only: …, and never a deleted row" negation-then-exception.
- `design.md:512-514` — [§ Orientation] three telegraphic one-liners, causal links implicit; "Reading an engine-less index throws" subjectless fragment.
- `design.md:548-552` — [§ Banned sentence patterns + § Mechanism traces] "generated from a counter, never `<className>_<counter>`" roundabout negation + mid-clause code literal; "non-WAL-safe rename path" named before defined.
- `design.md:554-555` — [§ Orientation] "cosmetically stale" placeholder-adjective compression (the engine file's stored index name no longer matches).
- `design.md:559-561` — [§ Broken grammar] clause after "so" drops its verb ("and a uniform WAL replay model" dangles).

## Findings — Range 4 (design.md:573-745)

- `design.md:575-580` — [§ Plain language] one sentence folds three subsystems, each with its own relative clause, into a run-on.
- `design.md:596-602` — [§ Orientation] "write-routed mutation", "tx-local copy", "the `SchemaProxy` / index-routing layer" stacked load-bearing with no gloss.
- `design.md:608-616` — [§ Orientation] "commit-side promotion", "lock-guarded shared maps", "one registry over" dropped as known entities; causal "while … from the data path" packs two lock orders.
- `design.md:616-620` — [§ Orientation] "legal embedded session alternation" / "self-deadlock the thread on its own hold" asserted with no motivation.
- `design.md:663-666` — [§ Orientation] "a stale presenter" first appearance, no gloss of "presenter".
- `design.md:670-678` — [§ Orientation] identifier soup ("captured ordinal", "surviving session-side record", "the volatile holder", "CAS-clears") across two release paths, little connective tissue.
- `design.md:680-686` — [§ Plain language] the clearest link, but buried at the end of a five-clause window narration that runs the deadlock derivation together.
- `design.md:686-694` — [§ Orientation] "store-then-load (Dekker) pair" named without saying what the store/load are; `STATUS.CLOSED` exclusion nests two causal links in one sentence.
- `design.md:710-714` — [§ Orientation] "the normal heal presents nothing" reuses undefined "present" terminology; `rollbackInternal`'s `clear()`/`close()` wedged in as known wipe sites.
- `design.md:716-720` — [§ Orientation] bare five-item resource list ("freezer engagement", "snapshot-floor holder accounting", "commit-local allocator state") none introduced.
- `design.md:720-724` — [§ Orientation] three YTDB-1114 mechanisms compressed into one sentence, each an unglossed forward reference.
- `design.md:730-733` — [§ Orientation] "commit-phase zombie" glossed only by a telegraphic verbless parenthetical; "whole-commit schema-lock scope" assumes the lock-scope model.
- `design.md:733-737` — [§ Orientation] "no memory edge from the foreign teardown's closed write" / "late-visible status" compress a happens-before argument into one clause.
- `design.md:584-588` — [§ Plain language] (TL;DR) "the four locks" forward-referenced before any is named (arrive at line 608); three causal hops in one breath.

## Findings — Range 5 (design.md:746-889)

- `design.md:748-753` — [§ Orientation] (TL;DR) one sentence stacks four undefined entities (freeze-kind taxonomy, kind-aware gate, throw site, park-decision site, operator-arm cut-and-unpark).
- `design.md:759-763` — [§ Orientation] "registration property", "entrant choice", "registration sites" dropped as terms of art, no gloss.
- `design.md:758-759` — [§ Orientation] "The in-window gate alone cannot deliver the loud-failure promise" — one-line assertion, no motivation, both terms unglossed.
- `design.md:769-773` — [§ Orientation] "the held metadata locks" appears before the loop is said to hold any; "never finishes queueing" assumes the try-acquire-as-queue model.
- `design.md:791-797` — [§ Orientation] "operator-kind arm", "cuts and unparks", "increments the freeze count" named without gloss; "cuts" has no defined object.
- `design.md:797-801` — [§ Orientation] identifier soup ("engage-during-enqueue race", "throw site", "the cut fires", "misses it") with ambiguous "it" antecedent.
- `design.md:846-848` — [§ Banned sentence patterns] "loudly incomplete, and never silently partial" roundabout negation restating I-migration-fail-closed.
- `design.md:850-855` — [§ Orientation] run-on with stacked compounds ("whole-or-fatal", "fail-closed", "completion flag that promotes only on success"); "promotes" no object, "the shared dump" unintroduced.
- `design.md:856-858` — [§ Plain language] "spills to a transient file beyond a threshold" omits the threshold; "shedding it" idiom/phrasal verb.
- `design.md:863-868` — [§ Banned sentence patterns] "structurally-closed-but-malformed", "pending field name" unglossed compounds; "parse-rejected … not accepted as a clean record" negative parallelism.
- `design.md:866-868` — [§ Orientation] run-on three-clause sentence; "best-effort-marked", "acknowledgment flag", "that gate", "v15-aware importer" chained without connective.
