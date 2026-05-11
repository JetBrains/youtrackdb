---
name: ai-tells
description: Reviews any draft for AI writing tells and produces a clean rewrite. Catches vocabulary fingerprints (delve, tapestry, leverage, robust, multifaceted, navigate, foster), structural fingerprints (negative parallelism like "It's not X, it's Y", bullet-everything, Title Case headings, adjective triads), tone fingerprints ("Great question!", "I'd be happy to help", "In conclusion"), and punctuation fingerprints (em dash overuse, knowledge-cutoff disclaimers). Use this skill whenever the user asks to humanize, de-AI, edit, polish, or sanity-check writing, including phrases like "does this sound like ChatGPT", "make this less AI", "humanize this", "edit this draft", "remove the AI smell", "is this AI-y", or "clean this up". Also trigger when the user pastes a draft and asks for feedback on the writing without naming AI specifically.
---

# AI tells

A second-pass checker that flags AI writing tells in a draft and produces a clean rewrite. The catalogue is compressed from Wikipedia's "Signs of AI writing" page plus the goblin-era 2026 update.

## Modes

Two modes:

1. **Audit.** Flag every tell, name the category, suggest a replacement. No rewrite.
2. **Rewrite.** Produce a clean version with all tells removed, preserving meaning, intent, and voice.

Default to audit + rewrite unless the user asks for one mode.

## The catalogue

Walk every category below. For each match in the input, note the exact phrase, the category, and a proposed replacement.

### Vocabulary tells

These words appear at AI-anomalous frequency in machine-generated text. Replace with plainer alternatives unless context absolutely demands them.

**Tier 1, high-confidence AI markers:**
delve, tapestry, multifaceted, pivotal, intricate, robust, vibrant, meticulous, nuanced, leverage, foster, navigate, underscore, showcase, ensure, realm, garner, bolster, enduring, elevate, unwavering, testament, journey, landscape, ecosystem, paradigm.

**Tier 2, context-dependent:**
crucial, key, vital, significant, essential, comprehensive, holistic, seamless, dynamic, innovative, transformative, cutting-edge, state-of-the-art, harness, embrace, embark, dive into, dive deep.

**Tier 3, promotional language (cut especially in marketing or about-page contexts):**
boasts a, nestled in, in the heart of, renowned for, exemplifies, stands as a testament, serves as a reminder, represents a shift, marks a turning point, indelible mark, deeply rooted, rich, profound, enhancing, showcasing, commitment to excellence.

When one of these appears, ask: is there a plainer word that means the same thing? If yes, swap it.

### Structural tells

**Negative parallelism** ("not X, it's Y") is the most overused AI structure. Cut every instance unless it's a direct quote from a real human source. Examples:

- "It's not [a tool], it's [a thought partner]"
- "You're not [an X], you're [a Y]"
- "Not just A, but B"
- "It's not about A. It's about B."

**Bullet-everything.** A 1-3 sentence answer broken into bullets, or a markdown header used for a paragraph-length response. Convert to flowing prose.

**Rule of three.** Adjective triads ("creative, thoughtful, and deeply considered") used for false comprehensiveness. Cut to one or two specific adjectives.

**Outline-style conclusions.** Final paragraphs that read "Despite [challenges], [subject] continues to [vague positive outlook]." Replace with a sharp specific kicker, or no conclusion at all.

**Title Case headings** when sentence case is the convention. Convert.

**Skipping heading levels** (H2 directly to H4 with no H3). Fix the hierarchy.

**Inline-header lists** (`- **Term:** description` repeated mechanically). Use only for genuine definition lists. Otherwise convert to prose.

**Excessive boldface.** Bolding every key term mechanically. Cap bold at roughly two instances per section, only on phrases the reader needs to find by skimming.

### Tone and opener tells

Cut these phrases and the sentences they live in:

- "Great question!"
- "Absolutely!"
- "Certainly!"
- "Of course!"
- "I'd be happy to help."
- "I'd love to help."
- "I'm here to help."
- "It's important to note that…"
- "It's worth noting that…"
- "It should be noted that…"
- "Interestingly,"
- "In conclusion,"
- "In summary,"
- "To summarize,"
- "To wrap up,"
- "Furthermore,"
- "Moreover,"
- "Additionally,"
- "However," used as the opening word of multiple paragraphs.

Test: if the sentence still works without the opener phrase, delete the phrase. If the sentence collapses without it, the sentence is filler, so delete the whole sentence.

### Punctuation tells

**Em dash overuse.** More than 2-3 em dashes per 500 words, or em dashes appearing at every clause boundary. Replace most with commas, full stops, or hyphens with spaces. Genuine asides can keep theirs.

**Curly quotes** in plain-text or markdown contexts where the surrounding ecosystem uses straight quotes. Convert.

**Knowledge-cutoff disclaimers.** "As of my knowledge cutoff…", "I may not have current information…". Cut from final output.

**Sycophantic openers.** "What a wonderful question!", "I love this prompt!". Cut.

### Content and analysis tells

**Vague attribution.** "Many experts say…", "Studies have shown…", "It is widely believed that…". Cite a specific source or delete the claim.

**Superficial -ing analysis.** Clauses that add no information: "highlighting their significance", "emphasizing their role", "underscoring their importance". Cut the whole clause.

**Hedge stacking.** "May potentially possibly suggest." Pick the strongest verb that's still accurate.

**Filler hedges.** "Somewhat", "relatively", "arguably", "perhaps", "potentially". Cut where the claim still holds. If the claim doesn't hold without the hedge, the claim is too weak. Sharpen or delete.

### Era-specific tells (current as of May 2026)

**Active right now:**

- Goblin, gremlin, raccoon, troll, ogre, pigeon. OpenAI hard-banned these in May 2025; if they appear, they leaked through.
- "It's not X, it's Y." Still rampant.
- Markdown-bullet-everything. Still rampant.
- "I'd be happy to help" openers. Still rampant.
- Title Case section headings. Still rampant.

**Mostly trained out:**

- "Delve". Paul Graham's 2024 viral tweet pushed OpenAI to train it down. Now uncommon.
- Em dashes at every clause boundary. OpenAI shipped a fix in November 2025. Less common but still leak through.
- "Tapestry". Peak 2023. Now rare.

Update this section quarterly against the Wikipedia source. Demote trained-out tells to the second list, but never delete from the catalogue. Tells are cyclical and may return.

## Workflow

1. **Read the input fully.** Don't skim. Density and combination matter more than any single tell.
2. **Pass 1, flag.** Walk every category. For each match, note the phrase, the category, and a proposed replacement.
3. **Pass 2, score.** Estimate tells per 100 words. Above 3 is heavy. Above 6, recommend a rewrite from scratch rather than editing.
4. **Pass 3, rewrite.** Maintain the writer's intent and voice. Don't substitute one set of AI tells for another.
5. **Pass 4, self-audit.** Read the rewrite. Ask: what still feels AI? Fix it. Repeat until clean.

## Output format

Return three blocks:

**1. Audit.** A bulleted list of every tell flagged. Format: `[category] "exact phrase" → "proposed replacement"`.

**2. Density score.** "X tells per 100 words. Light / Moderate / Heavy / Unsalvageable."

**3. Clean rewrite.** The final version with all tells removed.

If the input scores Unsalvageable, skip the rewrite and tell the user the draft needs to be redone from a fresh outline rather than edited.

## What this skill does not do

- It does not detect whether a text was AI-generated. AI detectors are unreliable. This skill assumes the input may be AI-assisted and cleans it regardless.
- It does not substitute for human editing. It catches surface tells. It cannot detect hollow thinking or missing voice. Those need a human pass.
- It does not produce style. It produces *absence* of AI style. Pair it with a voice guide for the affirmative side.

## Source and credit

- Wikipedia: Signs of AI writing. https://en.wikipedia.org/wiki/Wikipedia:Signs_of_AI_writing


Built by Kyle Balmer at AI with Kyle. MIT licensed. Last updated 2026-05-04. Free to use, share, and modify. If the list dates, update it.
