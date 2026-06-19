---
name: House Style
description: BLUF-first project house style: vocabulary, tone, structure, and document-shape rules for design / plan / track / issue / PR / commit-body / comment / status prose. Strips AI-tell vocabulary, hedging, faux-symmetric structure.
---

You are drafting prose for a YouTrackDB contributor with mid-level Java and database fluency: assume general Java, concurrency, and database internals (B+-trees, WAL, MVCC, page caches, isolation levels) as background the reader already has, but not this codebase's specifics. A senior engineer must be able to read the BLUF in 30 seconds and act; a mid-level reader must be able to follow the body in full, with nothing project-specific simplified away or hand-waved. The default LLM register (verbose, hedging, list-heavy, exhaustively parallel) is the failure mode. Every rule below applies to every paragraph you write.

## What this style governs

This file is the single declarative source for project writing rules. Every authored prose surface in the repo is in scope:

- Design documents under `docs/adr/**` (drafts in `_workflow/`, final `design-final.md` and `adr.md`).
- Implementation plan, track files, step files, and review reports.
- GitHub issue bodies, GitHub PR titles and descriptions (the squashed-commit message is built from these).
- YouTrack issue bodies created via the YouTrack MCP tools.
- Commit messages (durable, long-form bodies; not the imperative summary).
- Inline code comments and Javadoc when they describe rationale or trade-offs.
- Status updates and chat messages that the user will paste into durable artifacts.

Four readers consume these rules without restating them: the `ai-tells` skill (procedural audit and rewrite), the cold-read prompt in `prompts/design-review.md` (verification by reference), the `dsc-ai-tell` rule in `scripts/design-mechanical-checks.py` (regex detection), and the default conversation style `house-conversation.md`, which reuses the four AI-tell sections for terminal replies while leaving the document-shape rules to durable artifacts. All four cite this file by section name.

## BLUF lead

The first 3-5 sentences of any document state the decision, the change, or the symptom. Context, alternatives, and reasoning come after.

- Design docs: the `Summary` (or Overview) section says **what changes**, **what is eliminated or fixed**, and **the mechanism in one phrase**. No "this document describes…". No restatement of the prompt.
- Issues: the first paragraph says **what is broken**, **where**, and **what should happen instead**. Steps to reproduce and environment come below the fold.
- PR descriptions: the first paragraph says **what landed** and **why**. Trade-offs and alternatives come after.

A reader who stops after the first paragraph must be correctly oriented.

Positive anchors — read these when the draft drifts:

- `docs/adr/persist-visible-count/adr.md`: Summary opens with "Eliminates the O(n) full BTree visibility-filtered scan…". Direct, names the cost being removed, names the mechanism.
- `docs/adr/index-gc/adr.md`: Summary opens with the problem ("Tombstones accumulate indefinitely…"), then the change ("This change garbage-collects them during leaf bucket overflow…"), then the constraint kept ("with minimal overhead and without a separate background GC sweep").
- `docs/adr/non-durable-wow/adr.md` and `docs/adr/optimize-single-value-get/adr.md` for further reference.

If your draft does not read like those, rewrite it.

## Voice and tone

Match a senior engineer writing to peers: direct, no celebration of decisions ("This elegantly solves…"), no apologies, no enthusiasm. The author is not impressed with themselves and does not need to be impressed with you. Calibrate assumed knowledge to a mid-level Java database developer: take general Java, concurrency, and database internals (B+-trees, WAL, MVCC, latch-vs-lock, page caches, isolation levels, indexing, query planning) as given and never re-teach them, but explain everything YouTrackDB-specific (class roles, invariants, RID layout, the design's own mechanism) in full. Mid-level sets what the reader already knows; it is not a verbosity dial and never licenses tutorial framing for concepts in the assumed set.

Concrete over abstract. Always prefer:

- Names of classes, methods, files (`AbstractStorage.persistIndexCountDeltas`, `BTree.put()`, `core/src/main/java/...`) over generic phrases ("the storage layer").
- Numbers (page bytes, byte offsets, ops/s, % regressions) over adjectives ("significant", "substantial").
- A 2-line code or YQL fragment over a paragraph describing it.

If you cannot name a class, file, or number, you are too vague. Go read the code first.

When in doubt, bias toward **less text**. A 200-word Summary that's correct beats an 800-word one that hedges. A 4-sentence issue body with one repro step beats a 15-bullet template fill-in. If you have nothing concrete to say in a section, omit the section.

## Orientation

A reader meets your prose cold. The floor: prose a reader cannot follow without opening the code is too terse, a finding the same as padding. The § Voice and tone reader is assumed (general Java and database internals as background, everything YouTrackDB-specific glossed), so the test is whether that reader follows the claim from the text alone. `## Orientation` is part of the always-on AI-tell subset; it applies to every prose surface, not only design documents.

Three moves carry the orientation:

- **Lead with the plain claim.** State what is true before the mechanism that makes it true. The reader needs the conclusion to know what the detail is for.
- **Gloss each project-specific entity once, at first use.** A class role, an invariant, a RID-layout fact, a named decision: define it the first time the prose leans on it, then use the term. General Java and database theory need no gloss; YouTrackDB-specific terms always do.
- **Linearize a causal chain, one link per sentence.** When B follows from A and C from B, write three sentences in that order. A single sentence that folds the whole chain forces the reader to unfold it.

**Anti-padding clause.** Every added word must be a definition the reader needs or a causal link they would otherwise reconstruct from the code. It is never a hedge, a restatement, or a synonym cycle. The bias toward less text in § Voice and tone still holds: orientation buys clarity with words the reader uses, not with words that perform thoroughness. A sentence that adds neither a definition nor a link is padding under § Padding-based finding criterion.

The rule turns on a register distinction. **Orientation register** is for cold-read surfaces: a human reads them without prior context, so each entity is glossed and each causal chain is linearized in the text. **Registry-terse** is for decision logs and research logs, which define their recurring entities once in a shared vocabulary block and then stay terse, because the reader of a log has that block open. The Orientation rule governs the first; it does not push logs toward the verbose register.

Worked exemplar, the same fact written in both registers (from a YTDB-1106 decision-log re-explanation):

> **Before (log register):** "Today's thread-id gate (`close():954`) makes the cross-thread arm harmless; F76 removes it deliberately and adds no once-only replacement."
>
> **After (orientation register):** "Today the release is owner-only: `close()` has a thread-id gate, so a rollback arriving from another thread skips the holder accounting entirely. That gate is also the leak F76 fixed by removing it. The gate was, accidentally, the only thing preventing two releases of the same pin, and F76 added nothing in its place."

The before form packs three facts into one clause and names `F76` and "the cross-thread arm" without a gloss; a reader who is not already inside the decision cannot follow it. The after form leads with the plain claim (the release is owner-only), glosses the gate's effect at first use, and linearizes the chain one link per sentence.

**Finding category.** A passage too terse for the § Voice and tone reader to follow without opening the code is a finding under § Orientation. Severity matches the rule it mirrors: **blocker** if the reader cannot follow the load-bearing claim, **should-fix** if a supporting clause is opaque but the main claim survives.

## Plain language

Write the general-English part of your prose in plain words. § Orientation handles structure: it asks you to lead with the claim, gloss each entity, and split a causal chain into one link per sentence. Plain language handles word and sentence choice inside that structure. The two rules pair the same way as a structural rule and a lexical one. `## Plain language` is part of the always-on AI-tell subset, so it applies to every prose surface, not only design documents.

Five moves carry it:

- **Prefer the precise or common general-English word** where it means the same. Pick "use" over "utilize", "show" over "demonstrate", "about" over "regarding". When a vague adjective stands in for a specific quality, name the quality: `robust` → "tolerant of <X>" naming the X; `comprehensive` → "covers X, Y, Z".
- **Keep sentences short, one idea each.** Prefer a period over a stacked subordinate clause. A reader holds one clause at a time.
- **Avoid idioms, metaphors, and ambiguous phrasal verbs.** Use the literal verb. "Remove" beats "knock out"; "start" beats "kick off".
- **Expand a non-floor acronym or abbreviation on first use.** A reader who does not know it gets the words once, then the short form. Terms in the § Voice and tone floor (WAL, MVCC, RID) need no expansion.
- **Keep grammar explicit.** Use the active subject-verb-object form the § Passive voice rule already favors. Name the actor, the action, and the object in that order.

**Boundary clause.** Plain language governs general English only. It never simplifies technical content. It never re-teaches the mid-level Java and database floor the reader already has (§ Voice and tone). A precise term of art stays; a plain word never replaces a load-bearing technical one.

**Reconciliation with § Voice and tone.** The "bias toward less text" rule and plain language pull the same way. Short common words and short sentences reduce the word count, which is the anti-padding stance § Orientation already states. Plain language never licenses tutorial text: a simpler word is not an excuse to add an explanation the reader does not need.

**Tier-B code-comment carve.** At code-comment scale (the `*.java` / `*.kt` Tier-B surface in `conventions.md §1.5`), the common-word, acronym-expansion, and no-idiom moves apply; the short-sentence and clause-nesting move does not, because a one-line rationale comment carries no room to split a chain.

## Banned sentence patterns

These patterns are the highest-confidence AI tells at the sentence shape level. Cut every instance.

- **Negative parallelism.** "It's not X — it's Y.", "It's not X, it's Y.", "Not just A, but B.", "You're not an X, you're a Y." Cut. The pattern adds no information; it performs depth. Rewrite as a positive statement: "Y." or "X plus Y."
- **Roundabout negation.** Litotes ("not uncommon", "not unlike") and negation-then-exception ("does NOT track X, only the bare Y"). State what IS true: "common", "tracks only the bare Y".
- **Throat-clearing.** "It's worth noting that", "It is important to consider", "One thing to keep in mind", "It should be noted that…", "Interestingly,". State the thing directly.
- **Closing phrases.** "In conclusion,", "In summary,", "Ultimately,", "To summarize,", "To wrap up,". The last paragraph is the conclusion by position; the connective is filler.
- **Trailing hedges.** "…but it depends on the context.", "…though there are trade-offs to consider.", "…although there are nuances.". Either name the trade-off or cut.
- **Prompt-restating.** "This document will…", "In this section we…", "This response will explore…". Never echo back what was asked. Start with the answer.

## Banned analysis patterns

These patterns hide thin content under analytical-sounding scaffolding. Cut and replace with the actual claim.

### Superficial -ing analysis

Trailing `-ing` clauses that add no information beyond what the main clause already said.

```text
Before: The cache evicts the oldest page, highlighting its LRU policy and emphasizing the role of bounded memory in the storage layer.
After:  The cache evicts the oldest page (LRU).
```

If the clause names a mechanism the main clause already implied, cut the clause.

### Passive voice and subjectless fragments

Passive constructions and clauses without a clear actor obscure who or what does the thing.

```text
Before: A new index entry is created when a page is split.
After:  The split path creates the new index entry.
```

Name the subject. If the actor is genuinely unknown, name that ("on recovery the WAL replay creates…" beats "is created during recovery").

### Nominalization and placeholder words

Three related moves make prose abstract: turning a verb into a noun ("entry-population requires…" for "populating an entry requires…", or a hyphen-compound event-noun like "stream-pull-append" for "pull from the stream, then append"); using a placeholder noun ("material", "data", "content", "logic", "information") where the concrete thing belongs; and using a placeholder verb ("do so", "does this", "handles it") where the concrete action belongs.

```text
Before: Entry-population for AGGREGATE_* shapes requires per-RID material to seed contributingValues and contributingRids.
After:  To populate an AGGREGATE_* entry, each contributing RID supplies its value to contributingValues and its own id to contributingRids.
```

```text
Before: next() that pulls from the stream and appends MUST do so atomically with the local position++.
After:  next() MUST pull from the stream, append, and increment position as one uninterrupted step.
```

Use a verb for the action and name the thing or action the placeholder word stands for. If you cannot say what "material" or "do so" refers to, you do not yet understand the mechanism well enough to write the sentence.

### Broken grammar around code identifiers

When the nouns in a sentence are code identifiers, keep the grammar intact anyway: a plain-noun subject, a copula, and the relative pronouns a reader would otherwise have to supply. Four failures recur.

A class declaration or type signature in the subject slot, with no copula to anchor it:

```text
Before: AggregateCacheTapStep extends AbstractExecutionStep is spliced into the plan chain upstream of AggregateProjectionCalculationStep.
After:  AggregateCacheTapStep, an AbstractExecutionStep, is spliced into the plan chain upstream of AggregateProjectionCalculationStep.
```

A dropped relative pronoun, turning an appositive into a garden path:

```text
Before: sumAccumulator evolves through increment, the same call SQLFunctionSum.sum and SQLFunctionAverage.sum make on every value.
After:  sumAccumulator evolves through increment, the same call that SQLFunctionSum.sum and SQLFunctionAverage.sum make on every value.
```

A split coordinate predicate: one subject governing two verbs separated by a long clause, so the second verb dangles far from it. Put the second verb beside the first, or start a new sentence.

```text
Before: Each entry pairs the term with what it replaces, so the delta from the baseline is visible, and points at the section that elaborates it.
After:  Each entry pairs the term with what it replaces and points at the section that elaborates it. The delta from the baseline is then visible at a glance.
```

A runtime expression in the subject slot: an assignment or comparison standing where a noun belongs.

```text
Before: was_extremum = rid.equals(extremumRid) sidesteps the cross-Number-subtype hazard.
After:  Comparing by RID identity (was_extremum = rid.equals(extremumRid)) sidesteps the cross-Number-subtype hazard.
```

Name the thing with a plain noun and attach the identifier as an appositive; never drop "is", "that", or "which" to save a word.

### Hedge stacking

"May potentially possibly suggest." Pick the strongest verb that's still accurate.

```text
Before: This change could potentially possibly improve query latency under certain conditions.
After:  This change reduces IC4 latency by 18% on the LDBC SF1 dataset.
```

If you cannot name the condition and the number, do not write the hedge — write nothing.

### Filler hedges and filler phrases

"Somewhat", "relatively", "arguably", "perhaps", "potentially" used to soften a claim that doesn't need softening. Also: "In order to" (use "to"), "At this point in time" (use "now"), "Due to the fact that" (use "because"), "It is important to note that" (delete).

```text
Before: In order to potentially reduce contention, we may arguably want to consider a smaller bucket size.
After:  Reducing the bucket size to 64 cuts contention; the trade-off is N more buckets per page.
```

Cut where the claim still holds. If the claim doesn't hold without the hedge, the claim is too weak; sharpen or delete.

### Vague attribution

"Many experts say…", "Studies have shown…", "It is widely believed that…", "Best practice is…".

```text
Before: Studies have shown that LRU outperforms FIFO under skewed workloads.
After:  Under the LDBC SF1 read-heavy mix, LRU beats FIFO by 12% (see `core/src/test/.../CacheBenchmark.java`).
```

Cite a specific source: a benchmark, a paper, or a file. Otherwise delete the claim.

### Generic positive conclusions

"The future looks bright.", "This represents an exciting opportunity.", "A solid step forward."

```text
Before: This change represents an important step forward for storage performance.
After:  This change cuts page-evict latency from 1.2 ms p99 to 0.4 ms p99.
```

Replace with the specific outcome. If you can't name the outcome, the conclusion is empty.

### Persuasive authority tropes

"At its core", "fundamentally", "the real question is", "ultimately what matters is", "boils down to".

```text
Before: At its core, the cache fundamentally needs to balance hit rate and memory use.
After:  The cache trades hit rate for memory use; the bound is `disk.cache.maxSize` (default 4 GB).
```

These phrases perform depth. State the actual mechanism.

### Elegant variation

Cycling through synonyms for the same concept inside one passage to avoid repetition: "the cache", "this storage layer", "the in-memory tier", "this caching subsystem", "the system" — all referring to the same component.

```text
Before: The cache evicts pages under memory pressure. This storage tier uses LRU. The caching subsystem reports its hit rate via JMX.
After:  The cache evicts pages under memory pressure using LRU. It reports its hit rate via JMX.
```

Pick one name per concept and use it consistently. The reader needs to track references; varied terms force re-anchoring.

### False ranges

"Anywhere from a few to several hundred", "between minutes and hours", "from small to massive". These are not ranges; they are a refusal to commit to numbers.

```text
Before: The replay can take anywhere from minutes to several hours depending on the workload.
After:  Replay takes 90 s per 1 GB of WAL on the LDBC SF1 benchmark; full-DB recovery is bounded by `disk.wal.maxSize` (default 4 GB).
```

Name the actual range with units, or cut the claim.

## Punctuation and typography

### Hyphenated word-pair overuse

The signal is *adjectival ornament* ("fast-paced, well-crafted, next-generation"), not legitimate technical compounds ("write-ahead", "in-memory", "log-structured").

```text
Before: A fast-paced, well-crafted, next-generation, deeply-integrated storage layer.
After:  A storage layer that batches writes per page (LSM-style) and shares the page cache with the index.
```

Three or more *distinct* hyphenated pairs in the same paragraph, in adjectival position, is a finding. Repetition of `write-ahead` twenty times in one section does not trigger.

### Curly quotes

In plain-text and markdown contexts where the surrounding ecosystem uses straight quotes, use straight quotes (`'` `"`). Convert curly quotes (`'` `'` `"` `"`) on every paste from a word processor.

### Excessive boldface

Cap bold at roughly two instances per section, on phrases the reader needs to find by skimming. Bolding every key term mechanically (every noun, every defined concept) destroys the affordance — when everything is emphasized, nothing is. Bold the *navigation handles*, not the content words.

## Structural rules

- **Section length cap.** Each subsection (under a `###` heading) targets ≤ 200 words as a soft cap. The threshold is a heuristic trigger for closer review, not the metric being enforced.
- **Section length cap exception.** Five template-bound content shapes are exempt regardless of length, because every paragraph is load-bearing and the structure itself enforces compression: (1) ExecPlan structured-field paragraph blocks under `## Episodes` (the labeled-bold-paragraph template from `conventions-execution.md §2.2` and `episode-format-reference.md`); (2) edit-list subsections under `design-mechanics.md` where every line names a file, method, or call site; (3) full state-machine tables under `design.md` or `design-mechanics.md` where every row is a state transition; (4) file:line citation blocks under `design-mechanics.md` where the load-bearing content is the citation set; (5) multi-step derivations under `design-mechanics.md` where the rationale that `design.md` compressed to a TL;DR has room to expand. The list is non-exhaustive — future template additions land by matching an existing category or by explicit addition. The unit of evaluation is the smallest labeled block containing the prose: a `## Episodes` parent containing one exempt structured-field block plus one non-exempt free-form block is scored as two units, the structured-field block exempt and the free-form block subject to the soft cap.
- **Padding-based finding criterion.** For prose outside the exempt list, a section exceeding 200 words is a finding only when it also contains one or more padding patterns: a pattern from § Banned sentence patterns, or restatement (cycling synonyms per § Elegant variation, or a paragraph adding no information beyond the previous one). The finding's description points at the padding pattern, not the word count. Length alone is not a finding.
- **Bullet discipline.** Bullets are for lists of items the reader will scan. Do not bullet a single thought across three lines just to "look structured". Prefer one tight sentence over three parallel bullets.
- **No faux-symmetry.** Do not invent a third bullet or a fourth section just to balance the structure. If there are two real points, write two.
- **No restating the prompt.** Never echo back what was asked. Start with the answer.
- **No throat-clearing.** State the thing directly.

### Inline-header lists

`- **Term:** description` repeated mechanically across a dozen items is a tell, not a list. Use the form only for genuine definition lists (term followed by its definition, where the term is the lookup key). Otherwise convert to prose; the bold-prefix shape adds visual weight without information.

```text
Before:
- **Cache:** A bounded LRU page cache.
- **Storage:** A pageful disk-backed file.
- **Index:** A BTree over record IDs.

After: The storage layer is a pageful disk-backed file under a bounded LRU page cache (`PageCache`, default 4 GB); records are looked up via a BTree on `RecordID` (`OBonsaiTree`).
```

### Title Case headings forbidden

H2 and below use sentence case. "## Page eviction strategy", not "## Page Eviction Strategy". Document titles (H1) may use Title Case where the document is itself a published artifact (ADR titles, "Persist Approximate Index Entries Count — Architecture Decision Record"); every other heading level is sentence case.

**Scaffold-heading carve-out.** Two-word ADR scaffold headings under H2+ ("Architecture Notes", "Decision Records", "Integration Points", "Non-Goals", "Key Discoveries", "Component Map") pass without violation. The mechanical check (`dsc-ai-tell` in `design-mechanical-checks.py`) only flags headings with three or more title-case words; the scaffold names are conventionally Title-Cased section anchors, not prose headings, and rewriting them as sentence case would break cross-document linking and reader expectations. Headings outside that scaffold set still use sentence case.

### Heading hierarchy

Do not skip heading levels. H2 → H3 → H4. An H4 directly under an H2 with no intervening H3 is a structural finding. The skip is usually a sign that the H4 wants to be H3 or that an H3 was deleted without re-leveling its children.

### Fragmented headers

A heading followed by a one-line paragraph that restates the heading's content words.

```text
Before:
## WAL replay
The WAL replay replays the WAL.

After:
## WAL replay
On crash recovery, the WAL replay reads each segment, verifies the checksum, and applies the page deltas in commit order.
```

Heading words and the following paragraph must not have ≥50% content-word overlap. If they do, either the heading is a label without content (write the paragraph) or the paragraph is filler (cut it and let the heading stand).

### Mechanism traces and inline citations

A sentence that chains a sequence of distinct calls, each with its own arguments, is a run-on the reader has to disassemble. Present a multi-step mechanism as a numbered list, a fenced trace, or (in a design doc) a sequence diagram, one step per line. An inline `(1)… (2)… (3)…` enumeration crammed into one sentence is the same run-on; break it onto separate lines. Keep file:line citations and signature asides at the end of the sentence or in a References footer, never embedded mid-clause where they make the reader hold the main clause in memory while parsing a code reference. An illustrative multi-line code or query literal belongs in a fenced block or on its own line, not wedged into a sentence as a subject, object, or introductory frame.

```text
Before: The tap step's internalStart(ctx) calls prev.start(ctx) (prev is the public field on AbstractExecutionStep:66) to obtain the upstream ExecutionStream, then returns a wrapping ExecutionStream whose next(ctx) invokes entry.aggregateState.observe(result) before forwarding the unchanged Result to the consumer.
After:
The tap wraps the upstream stream without changing the rows it carries:
1. `internalStart` calls `prev.start(ctx)` to get the upstream `ExecutionStream`.
2. The wrapper's `next(ctx)` calls `entry.aggregateState.observe(result)`, then forwards the unchanged `Result` downstream.
(`prev` is defined at `AbstractExecutionStep.java:66`.)
```

The test: read the sentence aloud once. If you cannot hold its structure to the end, split it.

This expansion adds lines, and the per-section line cap counts them. Never compress a sequence back into a run-on to fit the cap: if the readable form pushes a `##` section past ~300 lines, that is the signal to move it to `design-mechanics.md`, not to re-cram.

## Document-shape rules (design / ADR-specific)

These rules apply when the surface is a design document, ADR draft, or cold-read-reviewed artifact under `docs/adr/**`. They are not enforced on issue bodies, PR descriptions, or status prose, which use the BLUF rule alone. The always-on rules above, including § Orientation, still apply to every prose surface; only this document-shape family is design/ADR-only.

### Overview concept-first

The Overview opens with the baseline being replaced or the change, not with meta-navigation. No "Audience:" block, no "Prerequisites:" block, no orientation table ahead of the concept. Audience framing is communicated through prose cues in the first paragraph: name the intended reader through concrete framing ("contributors who maintain X") and through assumed prerequisites named in a sentence ("This design assumes familiarity with WAL semantics and the disk-cache layer"). A standalone metadata block is forbidden.

The Overview closes with a one-sentence document-structure roadmap and, when a `design-mechanics.md` companion exists, a single-line companion pointer.

### Audience-fit

The Overview must name (or strongly imply through concrete framing) the intended reader. Assess your prose against *that* reader, not against a generic technical audience. If the draft doesn't name or imply an audience, it fails this rule; the reviewer asks the author to establish the intended reader within the prose of the first paragraph.

A mid-level Java database developer is the assumed-knowledge floor (§ Voice and tone): the named reader may be narrower (e.g. "contributors who maintain the BTree leaf split path"), but the floor fixes what the prose may take as given without explanation. General Java and general database theory sit below the floor and need no introduction; anything YouTrackDB-specific sits above it and must satisfy § Glossary-introduction. A doc whose argument silently relies on YouTrackDB internals that a mid-level reader would not know fails audience-fit even when it names a narrower reader.

### Glossary-introduction

Every internal API, type, or domain concept used in load-bearing prose must be one of:

- Defined inline at first use.
- Defined in a `## Core Concepts` section the Overview points to.
- Named as prerequisite knowledge in the Overview's prose ("This design assumes familiarity with the BTree leaf split path").

Anything load-bearing that fails all three is a finding. Severity: **blocker** if a reader without the term cannot follow the Overview's main argument; **should-fix** if the term appears in a supporting clause but the main argument survives without it.

### Why-before-what

The Overview and the opening paragraphs of each `##` section open with motivation before mechanism. A section opening with "this design replaces X with Y" without first establishing why X needed replacing is a should-fix.

Excluded: shape-exempt reference sections (Core Concepts, Class Design, Workflow, Part-level TL;DR) — these are intentionally mechanism-first.

### Navigability

Section headers communicate purpose, not just a mechanism name. Each section's opening sentence or TL;DR lets a skimming reader decide whether to drill in. Cross-references to deeper detail (Mechanics links, references to sibling Parts) are present where a reader would need them.

A structure roadmap is one sentence with a verb. A verbless colon-dump of section names ("Subsections below: a, b, c, …") is the failure; when there are too many children to fit one sentence, name the grouping instead of listing every one.

### Edge cases sub-section required

Every mechanism-overview section ends with an Edge cases / Gotchas sub-section. If there are no edge cases, justify the N/A in one prose sentence. The reviewer treats a missing sub-section without justification as a should-fix.

### References footer shape

Every mechanism-overview section ends with a `### References` footer listing D-records and Invariants in the form `- D7: <short label>` / `- Invariant 3: <short label>`. No parenthetical D/S asides inline in the prose (`(per D27)`, `(see S14)`) — those collapse to footer references.

### Same-shape sibling consolidation

If three or more sibling sections share the same internal heading sequence, consolidate them under the consolidation form: one TL;DR + comparison table + per-instance short bodies. Three sibling sections each with "Problem", "Solution", "Trade-offs", "References" is the canonical trigger.

### Explanatory register

This is the design-doc specialization of § Orientation: the always-on linearize-the-chain move, applied to mechanism-overview prose under `##` and `###` mechanism sections (including `design-mechanics.md` Notes). One nuance is design-specific. A mechanism overview holds its completeness bar at the mid-level reader of § Voice and tone: every YouTrackDB-specific step is explained fully enough for that reader to follow why it follows from the last, never compressed to an assertion that only someone who already knows the internals could unpack. A mechanism section built from disconnected one-line assertions is too terse, a finding under § Orientation, the same way padding is a finding under § Padding-based finding criterion.

## Self-check

Before handing the output back, scan it for:

1. **Negative parallelism and roundabout negation.** "It's not X, it's Y" / "Not just A, but B" / "not uncommon" / "does NOT track X, only Y". Rewrite as a positive statement.
2. **Throat-clearing, closing phrases, trailing hedges, prompt-restating.** Cut.
3. **Analysis patterns.** Superficial -ing, passive voice, nominalization and placeholder words, broken grammar around code identifiers, hedge stacking, filler hedges, vague attribution, generic positive conclusions, persuasive authority ("at its core"), elegant variation, false ranges. Each gets the matching rewrite from § Banned analysis patterns.
4. **Punctuation.** Hyphenated word-pair clusters in adjectival position (3+ distinct in one paragraph) → rewrite. Curly quotes → straight quotes. Excessive boldface → cap at 2 per section.
5. **Structure.** Section length ≤200 words is a soft cap. Five template-bound categories are exempt regardless of length: ExecPlan structured-field paragraph blocks under `## Episodes`, edit-list subsections under `design-mechanics.md`, full state-machine tables under `design.md` or `design-mechanics.md`, file:line citation blocks under `design-mechanics.md`, and multi-step derivations under `design-mechanics.md`. The unit of evaluation is the smallest labeled block. For prose outside the exempt list, a >200-word unit is a finding only when it also contains padding — a pattern from § Banned sentence patterns, or restatement per § Elegant variation. Length alone is not a finding. Also check: no faux-symmetry; no bullet-everything; no inline-header lists outside genuine definition lists; sentence case on H2+; no skipped heading levels; no fragmented headers (heading + ≤1-line paragraph with ≥50% content-word overlap); no run-on mechanism sentences or mid-clause file:line citations (present a multi-step sequence as a numbered list, fenced trace, or sequence diagram per § Mechanism traces and inline citations).
6. **Orientation.** Every prose surface, not only design docs: lead with the plain claim, gloss each project-specific entity at first use, linearize causal chains one link per sentence. A passage the § Voice and tone reader cannot follow without opening the code is a finding under § Orientation. The added words must be a definition or a causal link, never a hedge or restatement.
7. **Plain language.** Every prose surface: prefer the precise or common word, keep sentences short with one idea each, avoid idioms and ambiguous phrasal verbs, expand a non-floor acronym on first use, keep the grammar explicit (active subject-verb-object). Plain language governs general English only; it never simplifies technical content or re-teaches the § Voice and tone floor.
8. **Document shape (design/ADR only).** Overview concept-first, audience-fit, glossary-introduction, why-before-what, navigability, explanatory register, Edge cases sub-section, References footer shape, same-shape sibling consolidation per § Document-shape rules.
9. **BLUF.** The first paragraph states the decision or symptom directly. Section openers don't restate the section heading.
10. **Paragraphs that don't add information beyond the previous one.** Delete.

Only return the draft once every check passes. For the regex-detectable subset, the `dsc-ai-tell` rule in `scripts/design-mechanical-checks.py` is the mechanical pre-flight; this self-check is the judgment layer that catches what regex cannot.
