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
- **Not reference documentation.** The YQL reference and the internals book already do the
  exhaustive job. An article is an argument with a narrative arc, not a catalog of facts.
- **Not a design doc.** Design docs and ADRs describe intent, sometimes for code that
  changed or never shipped. An article describes what the engine *actually does today*.

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

- **Cite code as `ClassName.java:NNN`.** When a specific line is part of the argument, cite
  the class and the line number, verified against the live tree. Citations are load-bearing,
  not decoration: at most one per paragraph, and only where the exact line matters. Bulk
  pointers belong in a closing *Further reading* section.
- **The source is the authority, not the design doc.** ADRs and design docs are untrusted
  for class and method names (see [`PRODUCTION_CHECKLIST.md`](PRODUCTION_CHECKLIST.md),
  Rule 0). Verify every name and behavioural claim against the code before it ships.

### Freshness rule — record the source baseline

Any article that cites code **must** record the git commit SHA its citations were verified
against, as a **Source baseline** line near the top or bottom of the article. Line numbers
drift; the baseline is what lets a future reader (or a maintenance pass) tell whether a
citation is still valid or merely was valid once.

This mirrors the **Source-tree baseline** table in
[`../docs/yql-internals-book/README.md`](../docs/yql-internals-book/README.md). A minimal
form is enough for a single article:

```text
Source baseline: citations verified against commit a9b05e3f56 (develop), 2026-07-21.
```

The same SHA is recorded in the article index in
[`../docs/blog/README.md`](../docs/blog/README.md) at publish time. An article that cites no
code needs no baseline line.

## Code-fence / embedme safety convention

CI's required **CI Status** check runs `npx embedme --verify **/*.md` (see
`.github/workflows/maven-pipeline.yml`). [embedme](https://github.com/zakhenry/embedme) is a
documentation tool that *embeds* the contents of a source file into a code fence when the
fence's **first line is a comment consisting of a lone file path**. In `--verify` mode it
fails the build in two different ways:

- if that path *resolves* to a file, embedme expects the fence body to be a byte-for-byte
  copy of the file and fails when it is not;
- if that path *does not* resolve, embedme fails with `file does not exist`.

Either way the required gate goes red. So the trap is not only a *resolvable* path — it is
**any** first-line comment that is a lone path-like token. A `java` fence whose first line is
`// core/src/main/java/.../Foo.java` is treated as an embed target; a `java` fence whose
first line is the lone token `// Foo.java:512` fails `--verify` with `file does not exist`.
The `:512` suffix does **not** make embedme skip the block — it only makes the lookup fail.
(Verified against embedme 1.22.1, the version CI resolves.)

**The safe rule: never make a fence's first line a comment that is a lone file path.** Any of
these is safe, and all are confirmed with
`npx embedme --verify "blog-builder/**/*.md" "docs/blog/**/*.md"`:

- **Omit the leading path comment.** Show the snippet with no filename comment on the first
  line; put the `ClassName.java:NNN` citation in the surrounding prose or in the caption.
- **Use an illustrative fence embedme does not embed.** A `text` fence has no comment syntax
  for embedme to act on, so it is always skipped.
- **If you want the location marked on the code itself,** keep the citation out of the
  lone-token position by following it with a short note, e.g.
  `// MatchExecutionPlanner.java:5192 (illustrative)`. embedme treats a first line as a
  filename comment only when it is a lone token, so the trailing note makes it skip the block.

Then **run `npx embedme --verify "blog-builder/**/*.md" "docs/blog/**/*.md"` locally before
committing** — always pass the path globs, since a bare `npx embedme --verify` matches no
files and passes vacuously. This scoped command checks the blog machinery and article trees
only, avoiding unrelated pre-existing fixtures elsewhere in the repo; the quoted globs go to
embedme's own recursive matcher, so nested article files such as `docs/blog/articles/<slug>.md`
are checked too. CI also runs an embedme verify pass, so a clean local run keeps the required
gate green.

The two fences below demonstrate the safe forms (a `text` fence, and a `java` fence whose
first line is not a lone path token):

```text
planner.estimateRootEntries(step)   // the citation lives in the prose, not on line 1
```

```java
// MatchExecutionPlanner.java:5192 (illustrative) — trailing note, so embedme skips this fence
long estimateRootEntries(MatchStep step) { ... }
```

The dangerous form to avoid is a fence whose *first* line is a lone path-like comment — a
bare resolvable path such as `// core/src/main/java/.../MatchExecutionPlanner.java`, or a
lone suffixed token such as `// MatchExecutionPlanner.java:5192` with nothing after it. Both
fail `--verify`.

## Formatting conventions

- **Code identifiers** — always in `monospace` (backticks): class names, method names, field
  names, configuration keys, file paths.
- **Defined terms** — in *italics* on first use within the article, immediately followed by
  their plain-English meaning.
- **Figures** — captioned below the closing fence as `**Figure N — caption.**`, numbered
  sequentially within the article (`N` = the figure's index, starting at 1). A figure must
  teach one idea the prose leans on; if it only restates the prose, cut one of them.
- **Tables** — captioned above the table as `**Table N — caption.**`, numbered sequentially
  within the article.
- **No bullet-point fact dumps.** Use a list when the reader is genuinely enumerating cases;
  use prose when explaining a single idea.
