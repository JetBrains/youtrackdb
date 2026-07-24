# Production Checklist — Producing and Editing an Article

This is the executable process for producing a new blog article **and** for any substantive
edit to an existing one (anything beyond a mechanical line-number sweep). Follow it in order.
It references [`BLOG_BRIEF.md`](BLOG_BRIEF.md) for the global house rules,
[`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md) for the per-article scope contract,
and [`TONE_GUIDE.md`](TONE_GUIDE.md) for the per-developer voice system.

---

## Rule 0 — Every code claim is verified against the live source by a fresh reviewer

**This rule is first because it is the one a fluent draft most easily defeats.**

Every code claim — the class name, the method name, the line number, **and the behavioural
assertion** — is independently verified against the **live source** by a **fresh reviewer**
before the article is accepted. The author's own assertions are never trusted. A draft can
be perfectly on-voice, perfectly on-brief, and completely wrong about what the engine does.

**ADRs and design docs are untrusted for class and method names.** They describe intent at a
point in time; code moves past them. The concrete failure mode: a design doc names a class
that has since been renamed or deleted, an article copies the name in good faith, and the
citation points at nothing in the current tree. Read design docs for *why*; verify every
*name* and every *behaviour* against the code as it exists now.

---

## The pipeline

### 0. Resolve the author's handle and load or create their tone

Resolve the author's GitHub handle (`gh api user --jq .login`; fallbacks in
[`TONE_GUIDE.md`](TONE_GUIDE.md), *Handle resolution*). If `tones/<handle>.md` exists, load
it. If it does not, **run the capture process in [`TONE_GUIDE.md`](TONE_GUIDE.md) before
authoring** — do not guess a voice, and do not fall back to a generic one.

### 1. Fill the article brief

Copy [`ARTICLE_BRIEF_TEMPLATE.md`](ARTICLE_BRIEF_TEMPLATE.md) and fill every blank: audience,
the one or two mental models, thesis, scope (in and out), source materials, known caveats,
and target length. A blank left unfilled is undecided scope.

### 2. Confirm the voice against the tone file — BEFORE writing

Read the author's `tones/<handle>.md` and hold its Part A fingerprint traits and Part B
anti-tells in your head before drafting the first sentence. Confirming the target cadence
before writing is what makes the tone gate in step 3 cheap.

### 3. Stage authoring with a tone gate after the first section

Write the first section, then stop and check it against the author's tone file (self-check
against Parts A and B, or a fresh voice reviewer) **before continuing**. Catching a drifted
cadence at section one is cheap; catching it at section eight means rewriting eight sections.

### 4. Run the validation gauntlet

All of the following, not a subset. Use a fresh reviewer per perspective.

- **Internal consistency & cross-references** — terminology is uniform, numbers agree across
  sections, and every internal and outbound link resolves.
- **Instruction / claim completeness** — every claim the article makes is actually supported
  in the article; no dangling "as we saw above" pointing at nothing; the known caveats from
  the brief are all stated.
- **Writing style / voice (mechanical layer)** — the [`BLOG_BRIEF.md`](BLOG_BRIEF.md)
  structural and formatting rules: one idea per section, concrete before abstract, earn every
  name, identifiers in backticks, caption format, no bullet-dumps.
- **Citation accuracy against source (Rule 0)** — a fresh reviewer verifies every class,
  method, line number, and behavioural assertion against the live tree.
- **Voice conformance to the author's tone file** — measured against *that author's*
  `tones/<handle>.md`, not a global spec: does it match the Part A fingerprint, and is it
  free of the Part B anti-tells and generic LLM tells?
- **Reader-persona pass** — a representative target reader (per the article brief's audience)
  reads it start to finish and reports where an undefined term, a rushed step, or an
  unearned name blocked them. Record persona reports under [`reader-feedback/`](reader-feedback/)
  (namespaced per article — see the note below).

Namespace all review and feedback artifacts by the article's slug (the same slug used for
the published article), so artifacts from different articles never collide: reviewer reports
go under [`reviews/`](reviews/) as `reviews/<article-slug>/<perspective>.md` (one file per
perspective), and reader-persona reports under [`reader-feedback/`](reader-feedback/) as
`reader-feedback/<article-slug>/<persona>.md` (one file per persona).

### 5. Run embedme locally

Run `npx embedme --verify "blog-builder/**/*.md" "docs/blog/**/*.md"` locally. This scoped
command checks the blog machinery and article trees only, avoiding unrelated pre-existing
fixtures elsewhere in the repo; the quoted globs go to embedme's own recursive matcher, so
nested article files such as `docs/blog/articles/<slug>.md` are checked too. Always pass the
path globs — a bare `npx embedme --verify` matches no files and passes vacuously. CI also runs
an embedme verify pass over the docs.
Fix any fence flagged as an embed target per the code-fence safety convention in
[`BLOG_BRIEF.md`](BLOG_BRIEF.md) (never make a fence's first line a lone file-path comment —
a `:NNN` suffix alone does not make it safe).

### 6. Revise

Apply the gauntlet's findings.

### 7. Reviewers find, a separate gate verifies

Reviewers **find** issues; a **separate gate thread verifies each fix** against the source or
the rubric. **Never trust the fixer's own claim that a fix is correct** — the person who
introduced or repaired a claim is the worst-placed to certify it. This is the same
find/verify split that Rule 0 depends on.

### 8. Publish

- Write the finished article to `../docs/blog/articles/<slug>.md` (a short, hyphenated slug
  from the title).
- Index it in [`../docs/blog/README.md`](../docs/blog/README.md): a row with the article,
  its author, the date, and the source baseline SHA.
- Record the **Source baseline** SHA the citations were verified against, both in the article
  (per the freshness rule in [`BLOG_BRIEF.md`](BLOG_BRIEF.md)) and in the index row. An
  article that cites no code needs no baseline.

---

## Acceptance bar

An article is accepted only when **all three** hold:

1. It has cleared the full validation gauntlet (step 4) — not a subset.
2. Every code claim has been independently source-verified by a fresh reviewer (Rule 0), and
   each applied fix verified by a separate gate (step 7).
3. It is embedme-clean: `npx embedme --verify "blog-builder/**/*.md" "docs/blog/**/*.md"`
   passes.

A green tone gate alone is never sufficient.
