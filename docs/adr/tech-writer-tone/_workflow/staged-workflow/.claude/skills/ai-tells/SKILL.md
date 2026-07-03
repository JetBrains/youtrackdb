---
name: ai-tells
description: Reviews any draft for AI writing tells and produces a clean rewrite. Catches structural fingerprints (bullet-everything, adjective triads), analysis fingerprints (superficial -ing clauses, passive voice, vague attribution, hedge stacking), and tone fingerprints (throat-clearing). Use this skill whenever the user asks to humanize, de-AI, edit, polish, or sanity-check writing, including phrases like "does this sound like ChatGPT", "make this less AI", "humanize this", "edit this draft", "remove the AI smell", "is this AI-y", or "clean this up". Also trigger when the user pastes a draft and asks for feedback on the writing without naming AI specifically.
---

# AI tells

A second-pass checker that flags AI writing tells in a draft and produces a clean rewrite. The catalogue is compressed from Wikipedia's "Signs of AI writing" page plus the goblin-era 2026 update.

## Modes

Two modes:

1. **Audit.** Flag every tell, name the category, suggest a replacement. No rewrite.
2. **Rewrite.** Produce a clean version with all tells removed, preserving meaning, intent, and voice.

Default to audit + rewrite unless the user asks for one mode.

## Catalogue lookups

All section references below live in `.claude/output-styles/house-style.md`. Walk these sections during Pass 1:

- Structural fingerprints → `house-style.md § Structural rules` and `§ Banned sentence patterns`
- Tone fingerprints → sub-bullets in `house-style.md § Banned sentence patterns` (throat-clearing)
- Punctuation fingerprints → `house-style.md § Punctuation and typography`
- Content and analysis tells → `house-style.md § Banned analysis patterns` (includes the humanizer gap patterns)
- Too-terse / under-orientation fingerprints (a claim stated without its mechanism, an unglossed project-specific entity, a causal chain folded into one clause) → `house-style.md § Orientation`
- Plain-language fingerprints (an uncommon word where a common one fits, a long tangled sentence, an idiom or an ambiguous phrasal verb) → `house-style.md § Plain language`. This covers general-English clarity and word choice, including preferring the precise or common word.

## Workflow

1. **Read the input fully.** Don't skim. Density and combination matter more than any single tell.
2. **Pass 1, flag.** Walk every category in `## Catalogue lookups`. For each match, note the phrase, the category, and a proposed replacement.
3. **Pass 2, score.** Estimate tells per 100 words. Above 3 is heavy. Above 6, recommend a rewrite from scratch rather than editing.
4. **Pass 3, rewrite.** Maintain the writer's intent and voice. Don't substitute one set of AI tells for another.
5. **Pass 4, self-audit.** Read the rewrite. Ask: what still feels AI? Fix it. Repeat until clean.

Apply during Pass 1: if a sentence still reads cleanly without its opener phrase, delete the phrase; if the sentence collapses without the opener (throat-clearing — see `house-style.md § Banned sentence patterns`), delete the whole sentence.

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
