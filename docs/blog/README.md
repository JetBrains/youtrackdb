# YouTrackDB Engineering Blog

Long-form engineering articles about how YouTrackDB works inside — one topic per article,
each self-contained, each grounded in the live source tree.

This tree is the blog *target*: it holds finished articles and this index. The articles here
are produced by the machinery in [`../../blog-builder/`](../../blog-builder/), which carries
the house rules, the per-article brief template, the production pipeline, and the
per-developer tone system. To write or edit an article, start there — not here.

## Articles

| Article | Author | Date | Source baseline SHA |
|---|---|---|---|
| _none yet_ | — | — | — |

Each row links a published article under [`articles/`](articles/), names the developer whose
voice it is written in, records its publication date, and records the source commit its code
citations were verified against.

## Source baseline

Line numbers drift as the source tree moves. Any article that cites code records the git
commit SHA its citations were verified against — a **Source baseline** line in the article,
and the matching SHA in the table above. That baseline is what lets a reader tell whether a
citation is still valid against the current tree or was merely valid once. An article that
cites no code has no baseline.

This mirrors the **Source-tree baseline** table in
[`../yql-internals-book/README.md`](../yql-internals-book/README.md); the freshness rule
itself is defined in [`../../blog-builder/BLOG_BRIEF.md`](../../blog-builder/BLOG_BRIEF.md).

## Start here

- **Readers** — pick an article from the table above.
- **Authors and maintainers** — read [`../../blog-builder/README.md`](../../blog-builder/README.md)
  for the overview, then [`../../blog-builder/PRODUCTION_CHECKLIST.md`](../../blog-builder/PRODUCTION_CHECKLIST.md)
  before producing or editing any article.
