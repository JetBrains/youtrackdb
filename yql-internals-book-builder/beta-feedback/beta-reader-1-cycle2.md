# Beta Reader 1 — Cycle 2 Re-read

## Ch 8 histogram addition

The new sub-section ("Inside the histogram tier — bucket-level interpolation") drops in
cleanly. Chapter 8 had already introduced the three-tier table (Table 8.1) and described
Rule 3 at the level of "bucket-level interpolation with three fast-path shortcuts." The
new section delivers exactly what that one-line description promised — so arriving at the
heading feels like a door opening rather than a detour. The bucket-structure diagram
(Figure 8.1) and the step-by-step worked example (Table 8.2) are the same pedagogical
format the book has used throughout: a picture, then arithmetic, then a sentence on
what the numbers mean. That consistency is reassuring.

Two small friction points. First, `ScalarConversion.scalarize()` appears without
introduction. It is the method that maps any key to a `double` for the interpolation
arithmetic, and its brief definition ("for integers and floating-point values the mapping
is the identity") is technically accurate but arrives mid-formula, which forces a mental
context switch. A one-sentence placement before the formula — "values of any type are
first converted to `double` by `ScalarConversion.scalarize()`, which is the identity for
numeric types and a lexicographic mapping for strings" — would let me follow the
arithmetic without interruption.

Second, `FractionMode` is named without being defined anywhere in the section. It
appears as part of a parenthetical about the `fractionOf` helper: "returns 1.0, 0.0, or
0.5 depending on `FractionMode`." I read it twice. I still do not know what modes exist
or what they control. Because the worked example then proceeds to use `continuousFraction`
exclusively, the `FractionMode` comment ends up being a dangling detail that the reader
has to decide to ignore. Either give it two sentences or drop the parenthetical entirely
and let the worked example speak for the common case.

The closing sentence — "This is why a histogram estimate is a *ranking signal*, not a
prediction — which is exactly what the planner needs it to be, as the next section
explains" — ties the interpolation math back to the chapter's running argument. That
sentence is good. I felt the section land.

On whether the histogram math earns its complexity: yes. The linear interpolation
formula is not hard to follow with the worked example next to it. The "word on the
approximation" paragraph — explaining that the formula over-counts when values cluster
near a bucket boundary — converts what could feel like a hand-wavy formula into a model
I can reason about. That paragraph does more trust-building work than any diagram in the
section. The honest framing ("the error is bounded by the width of one bucket … can be
off by a factor of two or more") makes the formula feel reliable rather than suspect.

---

## Ch 12 §12.7.1 addition

This is the strongest of the three additions. The existing §12.7 ends with a sentence
that mentions `LazyRecursiveTraversalStream` by name — "The full recursion machinery
— depth counter, `$matchPath` accumulator, visited-RID deduplication via
`LazyRecursiveTraversalStream` — runs exactly as it would for a top-level recursive
edge" — and then moves on. Before this addition, that sentence was a
forward-reference to nowhere. Now it has a destination. The hand-off is natural.

The internal structure of §12.7.1 follows the same pattern as the other traverser
sections: here is the hand-off point (the `executeTraversal` branch at line 368), here
is the data structure (explicit DFS stack), here is the key invariant (visited-RID
deduplication), here is the opt-out (pathAlias), here is the worked example. Each
sub-heading is a sentence, not a label, so I know what I am about to read before I
start reading it. The chapter never broke that rhythm for the other five traversers, and
it does not break it here.

One genuine difficulty: the `pathAlias` opt-out sub-section arrives before the worked
example, and it introduces the concept of "path" without any grounding in what
`$matchPath` looks like as a data value. I know from the SQL snippet that `pathAlias: p`
binds a path to `p`, but I do not know what `p` contains — is it a list of RIDs, a list
of records, a count? The surrounding text calls the cons-cell chain "PathNode objects
materialised into a list," which is correct but does not tell me what that list contains
at the SQL layer. The worked example that follows is about depth and deduplication, not
about path content. The `pathAlias` section therefore leaves me with a pending question
that no subsequent paragraph answers. A single sentence — "The list is materialised as a
`List<Result>` and available in a RETURN clause as `p` with one element per node on the
path, in traversal order" — would close it.

The "why WHILE edges are not invertible" paragraph at the end is the right kind of
forward pointer. It names `InvertedWhileHashJoinStep` and says "Chapter 13," which is
exactly enough. I know the topic exists, I know where to find it, and I am not expected
to understand it here.

The worked example trace is correct and readable. The "RID deduplication would prevent
Dave from appearing twice if both Bob and Carol knew him" note at the bottom of the
trace is the kind of casual "here is the dedup rule applied to a concrete case" aside
that makes me confident the earlier description was right.

The section does not break the chapter's rhythm. It occupies the same space and weight
as the optional-traversal and reverse-traversal sections. Its length is proportionate to
its complexity — longer than §12.8 (back-reference enforcement, which is a two-line
check) but shorter than §12.3 + §12.4 combined, which is the right calibration for a
mechanism that does genuinely more work.

---

## Ch 7 §7.9 addition

This section is the only one of the three that gave me a slight feeling of gear-shift.
Chapter 7 up to §7.8 is a high-level corridor tour: eight phases, each described in one
to two paragraphs, each ending with a forward reference. The detail level is
deliberately kept low. §7.9 then drops into a considerably more technical presentation
— cache keys, copy-on-read contracts, per-cache invalidation triggers, `putInternal` and
`getInternal` line numbers — without a corresponding increase in the surrounding
sections. The contrast is jarring on first contact.

That said, the content is both necessary and useful. Chapter 4 mentioned
`YqlStatementCache` briefly and left the cache story incomplete. Readers who had that
note pending — "why does the planner deep-copy the AST?" — get a complete answer here,
and the "complementary purposes" paragraph at the end of the copy-on-read sub-section
closes the loop explicitly: "The deep-copy on planner entry (Chapter 4's AST copy) and
the copy-on-read here serve complementary purposes." That sentence is excellent; it
converts a forward-reference into a resolved callback.

The section is also internally well-structured. The "two-cache design" sub-section names
the two caches, says where they live (`SharedContext`), and explains their shared
configuration knob. The "cache key" sub-section answers the most practically important
question — "do literal values in WHERE clauses break plan reuse?" — before discussing
named parameters. The "concurrent sharing" sub-section walks the write path and read
path in order. The "when bypassed" sub-section lists the four bypass conditions in an
easy-to-scan block. The "what this means in practice" closing converts the mechanism
into actionable advice.

The gear-shift problem is real but fixable. The section currently opens with: "Chapter 4
mentioned `YqlStatementCache` as the reason the planner deep-copies its input AST. That
is half the story." That is the right opening sentence. But it immediately becomes dense.
A short paragraph between that opener and "The two-cache design" sub-heading — something
like "Before diving into the mechanics, the key fact is this: the first execution of a
new query template pays full planning cost; all later executions pay only a copy. The
rest of this section explains why that copy is necessary and when the cache is
bypassed." — would give me the destination before the route, and the subsequent
technical detail would feel purposeful rather than sudden.

Regarding whether §7.9 concepts feel reasonable at the end of Chapter 7, right before
"Looking ahead": yes, with the caveat above. The material belongs here — this is exactly
the place where a reader who has just seen the eight phases needs to understand their
operational economics. The eviction mechanics (schema migration clears the entire cache,
`COMMAND_TIMEOUT` change forces a clear) are the one sub-topic I could imagine trimming
or moving to Chapter 17's configuration reference without loss, because they are more
operational than conceptual. But their presence does not break the section; they are
confined to a single paragraph each.

---

## Pacing check

Chapter 7's corridor-tour pacing was one of cycle-1 report's noted strengths: eight
doors, each briefly opened, Chapter 8 and beyond will open them wider. §7.9 departs
from that pacing by opening one door all the way. I understand why — the caching story
cannot be decomposed across later chapters the way scheduling can — but the transition
from "brief overview" to "full treatment" within a single section of the same chapter is
the one structural decision I would revisit. One option: trim §7.9 to a single two-
paragraph summary (two caches, shared config, copy-on-read in one sentence, list of
bypass conditions) and move the full treatment to a dedicated §17.X or an appendix.
That would keep Chapter 7 at its promised altitude and give the operational details a
home where readers who need them can find them.

Chapters 8 and 12 introduce their additions with no pacing disruption. Both stay within
the altitude their chapters had already established. Chapter 8's histogram section is
exactly as technical as the selectivity section that precedes it. Chapter 12's §12.7.1
is exactly as detailed as the other traverser sub-sections.

---

## Overall

The book is as readable as it was in cycle 1. The two additions in Chapters 8 and 12
are the kind of "filling in a promised detail" writing that makes a second read feel
more complete rather than longer. The Chapter 7 addition is the only one that creates
genuine tension — it is good content in a chapter that had not prepared the reader for
its level of detail — and a single bridging paragraph at the opening of §7.9 would
resolve that. My cycle-1 observation about Part IV accelerating faster than Part III
still holds; §7.9's density is a small symptom of the same pattern, not a new problem.
