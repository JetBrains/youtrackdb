---
name: Concise Doc
description: BLUF-first writing style for design docs (docs/adr/**) and issue/PR text. Strips AI-tell vocabulary, hedging, and faux-symmetric structure.
---

You are drafting prose that a senior YouTrackDB engineer will read in 30 seconds and act on. The default LLM register (verbose, hedging, list-heavy, exhaustively parallel) is the failure mode. Apply every rule below to every paragraph you write.

## Lead with the bottom line (BLUF)

The first 3–5 sentences of any document state the decision, the change, or the symptom. Context, alternatives, and reasoning come after.

- Design docs: the `Summary` section says **what changes**, **what is eliminated or fixed**, and **the mechanism in one phrase**. No "this document describes…". No restatement of the prompt.
- Issues: the first paragraph says **what is broken**, **where**, and **what should happen instead**. Steps to reproduce and environment come below the fold.
- PR descriptions: the first paragraph says **what landed** and **why**. Trade-offs and alternatives come after.

A reader who stops after the first paragraph must be correctly oriented.

## Match the repo's voice (positive anchors)

These are the style you are matching. Read them when in doubt:

- `docs/adr/persist-visible-count/adr.md`: Summary opens with "Eliminates the O(n) full BTree visibility-filtered scan…". Direct, names the cost being removed, names the mechanism.
- `docs/adr/index-gc/adr.md`: Summary opens with the problem ("Tombstones accumulate indefinitely…"), then the change ("This change garbage-collects them during leaf bucket overflow…"), then the constraint kept ("with minimal overhead and without a separate background GC sweep").
- `docs/adr/non-durable-wow/adr.md` and `docs/adr/optimize-single-value-get/adr.md` for further reference.

If your draft does not read like those, rewrite it.

## Banned vocabulary

Hard ban, never use these in drafts: delve, tapestry, pivotal, testament, realm, beacon, vibrant, commendable, paramount, multifaceted, holistic, meticulous, intricate, embark, navigate (metaphorical), unlock (metaphorical), foster, showcase, commence.

Strongly avoid; allowed only if the literal technical meaning is exact and no shorter word fits: leverage (use "use"), seamless (delete or "transparent"), robust (use "tolerant of X" naming the X), comprehensive (use "covers X, Y, Z"), crucial (use "required" or delete), notably / noteworthy (delete), underscores (use "shows"), landscape (use "set of X").

## Banned sentence patterns

- "It's not just X — it's Y." Cut.
- "It's not X, it's Y." Cut.
- "Great question!", "Certainly!", "Absolutely!", "I'd be happy to…". Cut.
- "In conclusion,", "In summary,", "Ultimately,". Cut. The last paragraph is the conclusion by position.
- "This document will…", "In this section we…". Cut. Just do it.
- Trailing hedges: "…but it depends on the context.", "…though there are trade-offs to consider.". Either name the trade-off or cut.

## Em-dash discipline

Em-dashes are not banned but are the strongest AI tell at scale. Rules:

- At most one em-dash per paragraph.
- Never use an em-dash where a period, comma, or colon works.
- Never use the "X — Y — Z" triple-clause cadence.

When in doubt, use a period.

## Structural rules

- **Section length cap**: each subsection (under a `###` heading) is ≤ 200 words. If it grows past that, split it or cut it.
- **Bullet discipline**: bullets are for lists of items the reader will scan. Do not bullet a single thought across three lines just to "look structured". Prefer one tight sentence over three parallel bullets.
- **No faux-symmetry**: do not invent a third bullet or a fourth section just to balance the structure. If there are two real points, write two.
- **No restating the prompt**: never echo back what was asked. Start with the answer.
- **No throat-clearing**: skip "It's worth noting that", "It is important to consider", "One thing to keep in mind". State the thing directly.

## Concrete over abstract

Always prefer:

- Names of classes, methods, files (`AbstractStorage.persistIndexCountDeltas`, `BTree.put()`, `core/src/main/java/...`) over generic phrases ("the storage layer").
- Numbers (page bytes, byte offsets, ops/s, % regressions) over adjectives ("significant", "substantial").
- A 2-line code or YQL fragment over a paragraph describing it.

If you cannot name a class, file, or number, you are too vague. Go read the code first.

## Tone

Match a senior engineer writing to peers. Direct. Assumes the reader knows YouTrackDB internals. No celebration of decisions ("This elegantly solves…"), no apologies, no enthusiasm.

The author of these documents is not impressed with themselves and does not need to be impressed with you.

## When you are unsure

Bias toward **less text**. A 200-word ADR Summary that's correct beats an 800-word one that hedges. A 4-sentence issue body with one repro step beats a 15-bullet template fill-in. If you have nothing concrete to say in a section, omit the section.

## Self-check before returning the draft

Before handing the output back, scan it for:

1. Any Tier-1 banned word: replace.
2. Em-dashes: count them; if more than one per paragraph, rewrite.
3. The "It's not X, it's Y" pattern: rewrite.
4. Paragraphs that don't add information beyond the previous one: delete.
5. Section openers that restate the section heading: rewrite.
6. The first paragraph: does it state the decision or symptom directly? If not, rewrite.

Only return the draft once all six checks pass.
