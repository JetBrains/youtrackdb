# Article Brief — Template

**How to use this template.** Copy this file once per article to a working location (a
scratch note, a draft PR description, or a temporary file — it is not itself published).
Fill *every* blank before authoring begins: a brief with an empty blank is a scope you have
not decided yet, and undecided scope is the most expensive thing to discover halfway through
a draft. Blanks are marked `______`; guidance in parentheses explains what belongs there.
Delete the guidance as you fill each field. The brief is the contract the article and its
reviewers hold each other to — [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md) step 1
consumes it, and the global rules it must satisfy are in [`BLOG_BRIEF.md`](BLOG_BRIEF.md).

---

## Working title

______
(A concrete, specific title. "How the pre-filter decides which path to admit", not
"Query optimisation".)

## Author GitHub handle

______
(The handle whose tone this article is written in. Resolve it per
[`TONE_GUIDE.md`](TONE_GUIDE.md) *Handle resolution* — normally `gh api user --jq .login`.
The article is authored against `tones/<handle>.md`; if that file does not exist yet, run the
capture process in the tone guide *before* writing.)

## Audience — for THIS article

______
(Who this specific article is for, and what they already know walking in. Be concrete: "a
Java engineer comfortable with SQL joins who has never opened the YouTrackDB planner". This
is per-article, because an article stands alone — do not inherit an audience from another
piece.)

## Mental models the reader should leave with

1. ______
2. ______ (optional — one or two, no more)
(The one or two durable takeaways. If you cannot state them in a sentence each, the article
is not scoped yet. Everything in the article should serve these.)

## Angle / thesis

______
(The single claim the article argues. One sentence. The narrative arc exists to land this.)

## Scope

**In:**
- ______

**Out:**
- ______
(What the article covers and — just as important — what it deliberately does not. "Out"
prevents scope creep and tells reviewers not to flag an omission that was a decision.)

## Source materials to read

**Docs / ADRs (context only — untrusted for names):**
- ______
(Docs, ADRs, design notes that give background. Note: per [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md)
Rule 0, ADRs and design docs are UNTRUSTED for class/method names — they may name code that
was renamed or removed. Use them for intent, never as a citation source.)

**Code areas to inspect (the authority):**
- ______
(The packages, classes, and files whose behaviour the article describes. Every code claim is
verified against these in the live tree, not against the docs above.)

## Known caveats / traps the article must state

- ______
(Behaviours that surprise readers, edge cases the naive mental model gets wrong, "it looks
like X but is actually Y" gotchas. If a caveat is known, the article states it — omitting a
known trap is a correctness defect, not a stylistic choice.)

## Target length

______
(A rough word or section count. Length is a budget, not a goal: the article is as long as its
one or two mental models require and no longer.)
