# Tone Guide — How Per-Developer Tones Work

The blog does not have a single house voice. Each developer who writes for it has a *tone
file* — a distilled, checkable description of how they write — and every article is authored
against its author's tone and reviewed for conformance to *that* tone. This document
describes the schema a tone file follows, how a file is located, how a new one is captured,
its lifecycle, and how the production pipeline consumes it.

The global rules that are *not* voice — structure, accuracy, formatting — live in
[`BLOG_BRIEF.md`](BLOG_BRIEF.md). A tone file governs only *how it sounds*, never *whether it
is correct*.

## (a) Tone-file schema

Every `tones/<handle>.md` follows this exact structure. A file missing any part is not done
(see *Lifecycle*).

### Part A — Fingerprint traits

The labelled, *checkable* traits that make this author recognisable. Each trait is stated as
something a reviewer can pattern-match a draft against, and each carries **one or two SHORT
verbatim quotes** from the author's own writing that exhibit it. Cover, at minimum:

- **Sentence rhythm** — long-and-winding, clipped, or a characteristic mix (and where the
  emphasis beats fall).
- **Paragraph structure** — length, whether one-sentence paragraphs are used for emphasis.
- **Openings** — how they start a piece or a section.
- **Closings** — how they end one.
- **Pronoun use** — "you", "we", "I", or impersonal.
- **Concreteness vs narrative** — example-first, story-first, or argument-first.
- **Humor / candor** — dry, earnest, self-deprecating, blunt.
- **Handling of trade-offs / uncertainty** — how they admit limits and hedge.
- **Metaphor** — frequency and kind (mechanical, spatial, everyday).
- **Formatting habits** — lists, code, headings, asides, emphasis.

Each trait: a one-line description, then the verbatim quote(s) that prove it.

### Part B — Anti-tells to avoid

What would read as **not this author** — their personal anti-patterns (constructions they
never use) — **plus** the generic LLM tells any draft must be scrubbed of (semicolon
tricolons, stacked balanced aphorisms, manufactured antithesis, marketing language,
reader-flattery, uniform dense paragraph blocks). A draft that trips a Part B item fails the
voice review even if Part A is otherwise matched.

### Prose portrait

Three to five sentences describing the voice as a whole — the thing a new author reads first
to get the cadence in their ear before diving into the trait list.

### Signature diction

Five to eight words or phrases the author *actually* uses (drawn from their samples, not
invented). This is a positive fingerprint, not a vocabulary to force in.

### Recurring themes

The angles and concerns this author returns to — what they tend to care about and reach for.
Useful for matching an article's framing to the author, not for constraining topic.

### Sources

A `## Sources` footer listing the writing samples the tone was distilled from (links, paths,
or descriptions), so a later capture pass can re-derive or extend the tone from the same
material.

## (b) Handle resolution

A tone file is keyed by GitHub handle at `blog-builder/tones/<handle>.md`.

**Primary resolution** — the authenticated GitHub CLI login:

```text
gh api user --jq .login
```

**Fallbacks**, when `gh` is absent or unauthenticated:

- The author states their handle explicitly.
- Optionally persist it so it is not re-asked: a `git config` custom key, e.g.
  `git config blog.handle <handle>` (read back with `git config --get blog.handle`), or a
  small committed handle map kept alongside the tones.

Resolve the handle *before* authoring; it decides which tone file the article is written
against.

## (c) Capture process

When no tone file exists for a handle, capture one before authoring:

1. **Gather material.** Collect **at least two representative writing samples** by the author
   — blog posts, design docs, long PR descriptions, mailing-list or issue prose. If samples
   are scarce, interview the author: ask them to explain a recent piece of work in writing,
   and use that as a sample.
2. **Distill into the schema.** Fill every part of the schema in (a), pulling the Part A
   quotes verbatim from the gathered samples.
3. **Self-review.** Read the file back and apply the DONE test below.
4. **Persist.** Save as `tones/<handle>.md`, with the samples listed in the Sources footer.

## (d) Lifecycle

- **Missing tone** — run the capture process (c) before authoring. Do not guess a voice, and
  do not substitute a generic one; an article with no captured tone is not ready to write.
- **Handle rename** — `git mv tones/<old>.md tones/<new>.md`, and note the prior handle
  inside the file (a line such as "Previously: `<old>`") so history stays traceable.
- **DONE criteria for a tone file:**
  1. The schema in (a) is fully populated — no empty part.
  2. Every Part A trait carries at least one verbatim quote from the author's own writing.
  3. It passes a self-review test: given the tone file and a generic-voice sample, a reader
     could reliably tell which one is this author.

## (e) How the pipeline consumes it

The tone file replaces the single global voice spec the book builder uses. In the production
pipeline ([`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md)):

- **During authoring** it is a **positive checklist** — the author writes *toward* the Part A
  fingerprint, the prose portrait, and the signature diction.
- **During the voice review** it is a **filter and red-flag list** — the reviewer checks the
  draft *against* the same author's Part A traits and scrubs it of the Part B anti-tells.

Conformance is always measured against the article's own author's tone file, never against a
global spec.
