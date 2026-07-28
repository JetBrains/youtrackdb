# Blog Brief — Global House Rules

These are the rules every YouTrackDB blog article obeys, regardless of who writes it. They
cover *what* an article is, *how* a single article is shaped, and the accuracy, safety, and
formatting discipline that keeps the blog trustworthy.

**These rules are not voice.** Voice is per-developer and lives in a tone file at
[`tones/<handle>.md`](tones/); how tone files work is described in
[`TONE_GUIDE.md`](TONE_GUIDE.md). This brief governs everything a tone file does not: an
article can sound like any of our developers and still be wrong about structure, citation,
or formatting. That is what this document prevents.

Read this before authoring or editing any article. The executable pipeline that enforces it
is [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md).

## What a YouTrackDB blog article is

A blog article is a *self-contained* piece of engineering writing that teaches one thing
about YouTrackDB well. It takes a reader who knows the neighbourhood — Java, databases in
general, maybe a little graph vocabulary — and leaves them with one or two durable mental
models they did not have before. It is grounded in the real source tree, and every claim it
makes about the engine is checkable against that tree.

## What a YouTrackDB blog article is *not*

- **Not a chapter.** It cannot assume the reader has read another article. Every article
  stands alone; if it needs a concept, it either builds that concept or links out for it.
- **Not release notes or marketing.** No feature announcements dressed as teaching, no
  comparative or promotional language, no superlatives. If it reads like a launch post,
  it is out of scope.
- **Not reference documentation.** An article is an argument with a narrative arc, not a
  catalog of facts. Exhaustive, lookup-oriented material belongs in the reference docs, not
  here.
- **Not a plan or a proposal.** An article describes what the engine *actually does today*,
  not what someone intends or once intended to build.

## Structural principles for a single article

1. **A narrative arc with one or two takeaway mental models.** An article tells a story with
   a beginning, a middle, and an end. The reader should finish holding one or two new mental
   models — not a list of facts. If you cannot name the takeaways in a sentence, the article
   is not scoped yet.
2. **One idea per section.** If a section introduces two ideas, split it. Section length
   matters less than the reader meeting exactly one new thing at a time.
3. **Concrete before abstract.** Open every section with a worked example, a real snippet,
   or a named scenario with real numbers. Define the abstraction *after* the reader has seen
   what it does.
4. **Earn every name before you cite it.** A class or method name appears only after the
   role it plays has been described in plain English. Lead with "the planner has to guess
   how many rows each alias will match", then name the method that does it — never the other
   way round.
5. **Open by bridging from what the reader knows.** The first paragraph starts from
   something the reader already understands and names the gap this article closes. No cold
   opens into an abstraction.
6. **Close by pointing forward.** The last paragraph names where a curious reader goes next —
   a follow-up question, a related article, a place in the source to start reading. An
   article ends by opening a door, not by summarising itself.

## Accuracy and citation discipline

- **Cite code as `ClassName.java:NNN`, inline and sparingly.** When a specific line is part
  of the argument, cite the class and line number, verified against the live tree. Citations
  are load-bearing, not decoration: at most one per paragraph, and only where the exact line
  matters. Do not append a trailing catalog of code references — a long *Further reading*
  list of `file:line` pointers is reference-doc behaviour and reads as out of place in a blog.
  If a reader should go deeper, the closing paragraph points them, in prose, to a single
  place in the source worth opening (structural principle 6).
- **The live source is the sole authority.** Verify every name and behavioural claim against
  the code as it exists now (see [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md),
  Rule 0) — never from a secondary description or from memory. The author provides the
  code references the article is built on.

### Freshness rule — record the source baseline

Any article that cites code **must** record the git commit SHA its citations were verified
against, as a **Source baseline** line near the top or bottom of the article. Line numbers
drift; the baseline is what lets a future reader (or a maintenance pass) tell whether a
citation is still valid or merely was valid once. A minimal form is enough for a single
article:

```text
Source baseline: citations verified against commit <SHA> (<branch>), <date>
```

The same SHA is recorded in the article index in
[`../docs/blog/README.md`](../docs/blog/README.md) at publish time. An article that cites no
code needs no baseline line.

## Code snippets

Keep snippets minimal: show only the lines the argument turns on, and describe the rest in
prose rather than pasting a whole method. A snippet stands in for an idea, not for the file it
came from — so cite its location in the surrounding prose or the caption as `ClassName.java:NNN`
rather than as a leading file-path comment inside the fence.

## Formatting conventions

- **Code identifiers** — always in `monospace` (backticks): class names, method names, field
  names, configuration keys, file paths.
- **Defined terms** — in *italics* on first use within the article, immediately followed by
  their plain-English meaning.
- **Figures** — captioned below the closing fence as `**Figure N — caption.**`, numbered
  sequentially within the article (`N` = the figure's index, starting at 1). What a figure
  should teach is covered in the *Figures* section below.
- **Tables** — captioned above the table as `**Table N — caption.**`, numbered sequentially
  within the article.
- **No bullet-point fact dumps.** Use a list when the reader is genuinely enumerating cases;
  use prose when explaining a single idea.

## Figures

Diagrams are authored as *Mermaid* — the versioned source of truth, which renders directly
in-repo — and exported to *PNG* for publication. Medium renders neither Mermaid nor SVG; it
shows only uploaded raster images, so PNG is the Medium-facing format for every figure. The
front-page *hero* is a deterministic title card generated from the template at
`templates/hero.svg` (the author copies it into the article folder and fills in the title,
subtitle, and byline), not a hand-drawn image.

Every figure carries alt text and a caption, and each one teaches a single idea the prose
leans on; if a figure only restates the prose, cut one of them. Never hand-edit a generated
PNG — it is a build artifact. Change the Mermaid or the title-card source and regenerate it
(see the render step in [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md)).
