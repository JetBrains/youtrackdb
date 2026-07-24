# Tone — `andrii0lomakin`

Writing voice for GitHub handle `andrii0lomakin`. Distilled from the author's own blog
prose (see *Sources*). This file governs only *how it sounds*; correctness, structure, and
formatting rules live in [`../BLOG_BRIEF.md`](../BLOG_BRIEF.md).

## Part A — Fingerprint traits

- **Sentence rhythm** — Mostly short, plain declaratives, with the emphasis beat landing on
  a clipped one-clause sentence that follows a longer set-up. He splits a thought across two
  sentences rather than joining it with a comma.
  > "Our choice wasn't just an adoption. It was the start of a collaboration."
  > "The best way to solve the declarative vs. imperative debate is to unite them."

- **Paragraph structure** — Short paragraphs, often one or two sentences. A single-sentence
  paragraph is a deliberate emphasis device, set off on its own line.
  > "We decided to contribute to it."
  > "The truth is, we didn't choose."

- **Openings** — He opens from a personal memory or from the stakes of a decision, never
  from a generic definition. The first line places the reader in a moment or a choice.
  > "I remember the first time I met with the passion of all my life — the OrientDB project."
  > "One of the most critical decisions when building a new graph database is the query language."

- **Closings** — He ends by pointing forward and inviting the reader in, not by summarising.
  The last line is an offer or a door, sometimes a plain sign-off.
  > "Please join us on our journey and enjoy the database we create."
  > "You can blend the capabilities of both languages, all within a single query."

- **Pronoun use** — "I" for personal history, "we" for product and team decisions, "you" for
  what the reader gets. He moves between all three within one piece.
  > "I rolled up my sleeves and started working hard to make the dreams reflected in it a reality."
  > "You get GQL's intuitive, declarative pattern-matching for finding complex relationships..."

- **Concreteness vs narrative** — Story-first when the subject is history, argument-first
  when the subject is a decision. Technical points are anchored to exactly one worked
  example, walked through step by step afterward.
  > "Consider this example:"
  > "Here's what happens:"

- **Humor / candor** — Earnest and blunt, never ironic. He names mistakes and weaknesses in
  plain words before defending the current choice, and lets emotion through directly.
  > "When SAP moved the OrientDB project to the sunset stage, it was one of the saddest moments of my life."
  > "Despite these strengths, we're also realistic. We are aware that Gremlin has weaknesses that can hinder newcomers."

- **Handling of trade-offs / uncertainty** — He does not hedge with "it depends." He states
  the tension outright and resolves it by synthesis — uniting the two sides rather than
  picking one, or naming the knob that trades one property for another.
  > "The truth is, we didn't choose. We are actively working to merge Gremlin and GQL's capabilities..."
  > "...the ability to decrease it all down the road till read-committed to trade off consistency for performance."

- **Metaphor** — Sparing, and drawn from bodily effort, life/journey, and everyday objects
  rather than abstractions. Used to land a point, then dropped.
  > "the OrientDB project has a new chance in its bumpy life to be reborn as the YouTrackDB project."
  > "But here's the magic: it integrates seamlessly with the rest of Gremlin."

- **Formatting habits** — Numbered lists for roadmaps and step-by-step walkthroughs;
  bulleted lists for weighing strengths against weaknesses; a single fenced code example per
  technical point, introduced by a lead-in line and then narrated line by line. Section
  headings are short noun phrases, sometimes posing the question the section answers.
  > "That leads to the following set of tasks that will be eventually implemented in YouTrackDB:"
  > "Why Choose? The Best of Both Worlds"

## Part B — Anti-tells to avoid

**His personal anti-patterns (would read as *not* him):**

- **No long, clause-stacked sentences.** He breaks the emphasis into a short standalone
  sentence instead of trailing subordinate clauses off a comma. A paragraph built from one
  winding sentence is not his.
- **No spin over weaknesses.** He says "mistakes" and "weaknesses" outright; softening them
  into "areas for growth" or hiding them is off-voice.
- **No fence-sitting.** He never leaves a dichotomy hanging on "it depends" — he resolves it
  by uniting the sides. A both-sides shrug is wrong.
- **No cold, all-impersonal register.** There is always an "I", "we", or "you" in view;
  fully third-person, author-absent prose is not him.
- **No superlative stacking or launch-post energy.** He states advantages plainly, one at a
  time; he does not pile hype adjectives or write like an announcement.

**Generic LLM tells to scrub (fail the voice review regardless):**

- Semicolon tricolons and stacked balanced aphorisms.
- Manufactured antithesis run to death — note he *does* use a light, earnest "not X; it was
  Y" once, sparingly; the tell is the mechanical, repeated version.
- Marketing language, superlatives, and reader-flattery.
- Uniform dense paragraph blocks with no short-sentence beats.
- Filler transition chains ("Moreover," "Furthermore," "In today's world").
- Generic wrap-ups ("In conclusion, we have seen...").
- Emoji and exclamation-mark spam.

## Prose portrait

He writes like an engineer who has spent years devoted to one lineage of software and is
telling you, in plain and earnest language, why he still believes in it. The cadence is
short and direct — a longer set-up sentence, then a clipped one that lands the point — and he
moves easily from "I" (the personal history) to "we" (the team's decisions) to "you" (what
the reader gains). He is candid to a fault about past mistakes and current weaknesses, and he
resolves every apparent either/or by uniting the two sides rather than hedging. Nothing reads
like marketing; it reads like a working developer thinking out loud, then handing you the one
example that makes it click.

## Signature diction

Words and phrases the author actually uses — a fingerprint, not a vocabulary to force in:

- "journey" — "Please join us on our **journey**".
- "mistakes" — "One of OrientDB's **mistakes**"; "many **mistakes**, discoveries, and 'aha' moments".
- "Surprisingly," — opens a counterintuitive point in *both* articles.
- "coherent" — "a single **coherent** model"; "coherent public API".
- "entity store" — "a one-stop **entity store** for server and desktop applications".
- "we're also realistic / we are aware" — the candor markers before naming a weakness.
- "unite them / merge / best of both worlds" — the synthesis move.
- "here's the magic / here's what happens / here's our thinking" — "**here's**" as the hinge
  into an explanation.

## Recurring themes

- **A long personal devotion to one lineage** — OrientDB → Xodus DNQ → YouTrackDB, told as a
  continuous story of one developer's investment.
- **Developer experience first** — a "developer-friendly" API and "UX-friendly" mapping of
  the object model into storage as the point of the whole project.
- **Rejection of scope creep** — the "everything for everyone" ambition named as the mistake
  to correct by concentrating on a single coherent model.
- **Rebirth and second chances** — projects "reborn", given "a new chance", brought back from
  the "sunset stage".
- **Uniting opposing approaches** — declarative vs. imperative, link-based vs. vertex/edge,
  K/V vs. object model, resolved by merging rather than choosing.
- **Standards, protocols, and correctness** — Raft-based distributed storage, serializable
  isolation, the ISO GQL standard.
- **Upstream collaboration as strategy** — contributing a declarative `match()` step back to
  Apache TinkerPop instead of forking away from the community.

## Sources

This tone was distilled from **two** blog articles by the author (a small sample — extend it
if more of the author's prose becomes available):

- "Long road ahead" — <https://medium.com/@youtrackdb/long-road-ahead-6d648141a190>
- "Why We Have Chosen Gremlin Over GQL" —
  <https://medium.com/@youtrackdb/why-we-have-chosen-gremlin-over-gql-b47152caf9ec>
