# Blog Builder

The production machinery for the YouTrackDB engineering blog. It turns a rough idea
("someone should explain how the pre-filter admission model actually works") into a
finished article. Such an article reads like the developer who wrote it and is accurate
about the live source. It reaches that state only after clearing a review gauntlet.

The articles this machinery produces live in a separate tree at
[`../docs/blog/`](../docs/blog/). This tree holds only the machinery: the house rules, the
per-article brief template, the production pipeline, the per-developer tone system, and the
review and reader-feedback artifacts.

It is modeled on the book builder at [`../yql-internals-book-builder/`](../yql-internals-book-builder/)
and inherits its discipline: every code claim is verified against the live tree, and voice
drift is caught early rather than late. Two things are deliberately different.

## Two deliberate departures from the book builder

1. **Article-oriented, not chapter-oriented.** The book is one long argument with a fixed
   gradual ramp; a chapter can assume everything before it. A blog article stands alone.
   Each article carries its own brief (see [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md))
   naming its own audience, its own one or two takeaway mental models, and its own scope —
   because there is no preceding chapter to lean on.

2. **Per-developer pluggable tone, not one global voice.** The book has a single
   `VOICE_EXEMPLAR.md` that every chapter must sound like. The blog does not flatten its
   authors into one house voice. Each developer has a tone file at
   [`tones/<handle>.md`](tones/), keyed by their GitHub handle, distilled from their own
   writing. An article is authored *against its author's tone* and reviewed for conformance
   to *that* tone — not to a single global spec. The global rules that remain
   (structure, accuracy, formatting) live in [`BLOG_BRIEF.md`](BLOG_BRIEF.md); they are
   explicitly *not* voice.

## Directory map

```text
blog-builder/
├── README.md                  — this file: overview, departures, workflow, doc pointers
├── BLOG_BRIEF.md              — global house rules (structure, accuracy, embedme safety, formatting) — NOT voice
├── ARTICLE_BRIEF_TEMPLATE.md  — copy-per-article fill-in brief
├── PRODUCTION_CHECKLIST.md    — the executable pipeline for producing or editing an article
├── TONE_GUIDE.md              — how per-developer tone files work: schema, resolution, capture, lifecycle
├── tones/                     — one tone file per developer, named <github-handle>.md
├── reviews/                   — review reports, namespaced per article: reviews/<article-slug>/<perspective>.md
└── reader-feedback/           — reader-persona feedback, namespaced per article: reader-feedback/<article-slug>/<persona>.md
```

The output tree is [`../docs/blog/`](../docs/blog/): finished articles land in
`../docs/blog/articles/`, indexed by `../docs/blog/README.md`.

## Producing one article, end to end

The steps below are the map; [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md) is the
executable version with the review gauntlet and the acceptance bar.

1. **Resolve the author's GitHub handle.** The tone file is keyed by it. The primary
   resolution is `gh api user --jq .login`; the fallbacks are in [`TONE_GUIDE.md`](TONE_GUIDE.md),
   *Handle resolution*.
2. **Load or create the author's tone.** If `tones/<handle>.md` exists, load it. If it does
   not, run the capture process in [`TONE_GUIDE.md`](TONE_GUIDE.md) *before* writing — never
   guess a voice.
3. **Fill an article brief.** Copy [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md)
   and fill every blank: audience, mental models, thesis, scope, sources, caveats, length.
4. **Author against the tone.** Write the article using the brief for *what* and the tone
   file for *how it should sound*, following the structural and accuracy rules in
   [`BLOG_BRIEF.md`](BLOG_BRIEF.md).
5. **Run the validation gauntlet.** Non-code review perspectives, citation-accuracy against
   live source by a fresh reviewer, voice conformance to the tone file, and a reader-persona
   pass — all of it, per [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md).
6. **Publish.** Write the article to `../docs/blog/articles/<slug>.md`, index it in
   `../docs/blog/README.md`, and record the source baseline SHA its citations were verified
   against.

## The machinery documents, and when to read each

Keep the always-loaded surface small. Read a document when you are about to do the thing it
governs — not before.

- [`BLOG_BRIEF.md`](BLOG_BRIEF.md) — read before authoring or editing any article. The
  global house rules: what an article is, how a single article is structured, citation and
  freshness discipline, the embedme code-fence safety convention, and formatting.
- [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md) — copy it at the start of every
  new article; it is the per-article scope contract.
- [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md) — read before producing or
  substantively editing an article. The pipeline, the review gauntlet, and the acceptance
  bar, with Rule 0 (verify every code claim against live source) first.
- [`TONE_GUIDE.md`](TONE_GUIDE.md) — read when resolving a handle, capturing a new tone, or
  running the voice-conformance review. The tone-file schema, handle resolution, the capture
  process, and the tone lifecycle.
