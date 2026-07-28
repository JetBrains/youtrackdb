# Blog Builder

The production machinery for the YouTrackDB engineering blog. It turns a rough idea
("someone should explain how the pre-filter admission model actually works") into a
finished article. Such an article reads like the developer who wrote it and is accurate
about the live source. It reaches that state only after clearing a review gauntlet.

The articles this machinery produces live in a separate tree at
[`../docs/blog/`](../docs/blog/). This directory (`blog-builder/`) holds only the machinery:
the house rules, the per-article brief template, the production pipeline, the per-developer
tone system, and the review and reader-feedback artifacts.

Its discipline is simple: every code claim is verified against the live tree, and voice drift
is caught early rather than late. Two properties shape everything else.

## Two defining properties

1. **Article-oriented.** Every article stands alone — there is no preceding chapter to lean
   on. Each carries its own brief (see [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md))
   naming its own audience, its own one or two takeaway mental models, and its own scope.

2. **Per-developer pluggable tone.** The machinery does not flatten authors into one house
   voice. Each developer has a tone file at [`tones/<handle>.md`](tones/), keyed by their
   GitHub handle and distilled from their own writing. An article is authored *against its
   author's tone* and reviewed for conformance to *that* tone. The global rules that remain —
   structure, accuracy, formatting — live in [`BLOG_BRIEF.md`](BLOG_BRIEF.md); they are
   explicitly *not* voice.

## Directory map

```text
blog-builder/
├── README.md                  — this file: overview, defining properties, workflow, doc pointers
├── BLOG_BRIEF.md              — global house rules (structure, accuracy, figures, formatting) — NOT voice
├── ARTICLE_BRIEF_TEMPLATE.md  — copy-per-article fill-in brief
├── PRODUCTION_CHECKLIST.md    — the executable pipeline for producing or editing an article
├── TONE_GUIDE.md              — how per-developer tone files work: schema, resolution, capture, lifecycle
├── templates/                 — figure templates; hero.svg is the title card copied into each article folder
├── scripts/                   — render-figures.sh: renders an article's Mermaid + hero.svg sources to PNG
├── tones/                     — one tone file per developer, named <github-handle>.md
├── reviews/                   — review reports, namespaced per article: reviews/<article-slug>/<perspective>.md
└── reader-feedback/           — reader-persona feedback, namespaced per article: reader-feedback/<article-slug>/<persona>.md
```

The output tree is [`../docs/blog/`](../docs/blog/): finished articles land in
`../docs/blog/articles/`, indexed by `../docs/blog/README.md`.

## Producing one article, end to end

Two roles share the work: **you** (the author) supply the judgement and the raw material; the
**machinery** does the execution and the checking. Your inputs come first; the machinery runs
on them; you have the final say. This is the map — [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md)
is the executable version, with the review gauntlet and the acceptance bar.

**What you provide (before anything runs)**
- The idea and its shape: the thesis, who it's for, the one or two takeaways, and what's in
  and out of scope.
- The **code references** the article is built on — the packages/classes/files whose
  behaviour it describes. The machinery treats these as the authority; it does not hunt for
  them.
- Your identity: your GitHub handle (so your tone file can be found). If you have no tone file
  yet, two writing samples (or a short interview) so one can be captured.
- The **figures you want**: the hero title-card text (title, subtitle, byline), and which
  illustrative diagrams to include and what each one should teach.

**What the machinery does**
- Resolves your handle (`gh api user --jq .login`) and loads your tone — or captures one from
  your samples first, never guessing a voice. Details in [`TONE_GUIDE.md`](TONE_GUIDE.md).
- Drafts the brief from your inputs ([`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md)).
- Authors the article in your tone in its own folder; authors the diagrams as Mermaid from
  your plan and fills the hero template; renders both to PNG via
  [`scripts/render-figures.sh`](scripts/render-figures.sh).
- Runs the validation gauntlet: fresh-reviewer citation-accuracy against live source, voice
  conformance to your tone, and a reader-persona pass — per
  [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md).

**Your final say**
- You review the finished draft and approve it; only then does the machinery publish to
  `../docs/blog/articles/<slug>/`, index it in
  [`../docs/blog/README.md`](../docs/blog/README.md), and record the source baseline SHA.
  Nothing ships until you do.

## The machinery documents, and when to read each

Keep the always-loaded surface small. Read a document when you are about to do the thing it
governs — not before.

- [`BLOG_BRIEF.md`](BLOG_BRIEF.md) — read before authoring or editing any article. The
  global house rules: what an article is, how a single article is structured, citation and
  freshness discipline, code-snippet and figure conventions, and formatting.
- [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md) — copy it at the start of every
  new article; it is the per-article scope contract.
- [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md) — read before producing or
  substantively editing an article. The pipeline, the review gauntlet, and the acceptance
  bar, with Rule 0 (verify every code claim against live source) first.
- [`TONE_GUIDE.md`](TONE_GUIDE.md) — read when resolving a handle, capturing a new tone, or
  running the voice-conformance review. The tone-file schema, handle resolution, the capture
  process, and the tone lifecycle.
