---
name: analyze-changes
description: "Analyze git changes (branch, commit, or current branch) and produce a design-oriented markup document with Mermaid diagrams, query/usage examples, data walkthroughs, PR context, and impact assessment."
argument-hint: "[branch-name | commit-sha]"
user-invocable: true
---

Analyze the git changes specified by the argument and produce a design-oriented analysis document.

## Input

The argument `$ARGUMENTS` is either:
- **Empty / not provided** — analyze the **current branch** compared to `develop`
- A **commit SHA** (short or full) — analyze that single commit
- A **branch name** — analyze all commits on that branch relative to `develop`

## Steps

### 1. Determine the input type and gather changes

If `$ARGUMENTS` is empty or blank, get the current branch name:
```bash
git rev-parse --abbrev-ref HEAD
```
Then treat it as a branch name (see branch handling below).

Otherwise, run these git commands to figure out what was provided:
```bash
git cat-file -t "$ARGUMENTS" 2>/dev/null
git branch --list "$ARGUMENTS"
```

- If it resolves to a **commit** and is not a branch name (`git branch --list "$ARGUMENTS"` is empty), treat it as a single commit SHA. Gather:
  - `git show --stat <SHA>`
  - `git diff <SHA>~1..<SHA>` (the full diff)
  - `git log -1 --format="%H%n%s%n%b" <SHA>` (commit message)

- If it resolves to a **branch**, gather:
  - `git log --oneline develop..<branch>` (all commits on the branch)
  - `git diff develop...<branch>` (combined diff)
  - `git log --format="%H%n%s%n%b%n---" develop..<branch>` (all commit messages)

### 2. Check for an associated Pull Request

Use `gh` to find a PR associated with the branch or commit:

```bash
# For a branch:
gh pr list --head "<branch-name>" --json number,title,body,comments,reviews,url --limit 1

# For a commit (search PRs that contain the SHA):
gh pr list --search "<SHA>" --state all --json number,title,body,comments,reviews,url --limit 1
```

If a PR is found:
- Read the PR **title**, **description/body**, and **URL**
- Read all **review comments** and **issue comments** via:
  ```bash
  gh pr view <number> --json comments,reviews,reviewDecision # Fetches top-level issue comments, review summaries, and decision
  gh api repos/{owner}/{repo}/pulls/<number>/comments # Fetches detailed line-level review comments
  ```
- Include the PR context in your analysis

### 3. Read all changed files at their final state

For every file that was added or modified in the diff, use the `Read` tool to read the final version of the file. This gives you the full source to understand the design, not just the diff hunks.

### 4. Analyze the changes

Study the diff, commit messages, PR description, and PR comments carefully. Identify:

- **Purpose**: What is the overall goal of these changes?
- **Architecture**: What are the new algorithms, data structures, and execution flows? How do they connect?
- **Concrete usage**: What queries, API calls, or scenarios trigger each new code path? What does the old behavior look like vs the new?
- **Non-trivial decisions**: Why was this approach chosen? What trade-offs were made?
- **Trivial parts**: Simple renames, formatting, config — note briefly, don't over-analyze.

### 5. Generate the markup document

Create a file named `changes-analysis-<identifier>.md` in the project root directory, where `<identifier>` is the short SHA or branch name (sanitized for filenames — replace `/` with `-`).

The document MUST follow this structure:

```markdown
# Change Analysis: <short description>

**Ref**: `<SHA or branch name>`
**PR**: [#<number> <title>](<url>) *(if PR exists, omit section if no PR)*
**Date**: <commit date(s)>
**Author**: <author(s)>

## Summary

<2-5 sentence high-level summary of what these changes accomplish and why.>

## Design

<Start with the shared concepts that apply across all changes: the overall
algorithm/approach, common data structures, shared invariants, configuration.
Then break into per-feature/per-algorithm subsections.>

### <Shared concept: e.g., overall algorithm, detection pipeline>

<Explain the top-level algorithm or decision flow that ties the changes together.
Include a Mermaid diagram showing the overall flow.>

### <Shared concept: e.g., common data structures, eligibility checks>

<Describe data structures, class relationships, and preconditions shared across
multiple features. Include a class diagram if new types are introduced.>

### <Feature/Algorithm 1>

<For each distinct feature or algorithm, write a SELF-CONTAINED section that
flows in this order:>

**Algorithm.** <What it does, when it triggers, what the eligibility conditions
are. Keep this concise — the reader should understand the approach before seeing
code or examples.>

**Execution flow:**

<Mermaid diagram showing the internal decision/data flow for this specific
feature. NOT a rehash of the overall diagram — zoom into this feature's logic.>

**Query/usage example:**

<A concrete, real-world example that triggers this code path. For a database,
this is a SQL query with schema context. For an API, this is a request/response.
For a library, this is a code snippet. Pick the most representative example —
prefer production queries (e.g., from benchmarks) over synthetic test data.>

**Data walkthrough:**

<Walk through the example step by step with ACTUAL VALUES. Show what the old
code did (with cost), then what the new code does (with cost). Use concrete
data — not "vertex A" but "Alice" or "n1". Show the build phase output, the
probe phase per row, and the final result. This is what makes the design
understandable — abstract descriptions alone are not enough.>

<Repeat for each feature/algorithm.>

### Summary

Provide a table mapping each feature to its trigger condition, implementation class, data structure, and real-world impact (benchmark numbers if available).

| Feature | Trigger | Implementation | Data Structures | Impact |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |>

## PR Discussion Summary

*(Only if a PR with comments exists)*

<Summarize key points from PR comments and reviews. Note any decisions made,
concerns raised, or alternatives discussed.>

## Impact Assessment

- **Risk level**: Low / Medium / High
- **Affected components**: <list>
- **Testing considerations**: <what should be tested>
- **Backward compatibility**: <any breaking changes?>
```

### Important Rules

1. **Design-first, not file-first.** Never organize the document by file, by diff hunk, or by commit. Organize by algorithm/feature. A single feature may span multiple files — the document should explain it as one unit. Each section should be self-contained: a reader should understand one feature without reading the others.

2. **Every feature section must have a concrete example with a data walkthrough.** Abstract algorithm descriptions are insufficient. Show real values flowing through the system. For database changes, use actual SQL queries (from benchmarks or tests) and walk through with sample data. Show both old behavior and new behavior with cost comparison.

3. **Mermaid diagrams are REQUIRED** for non-trivial changes. Use:
   - **Flowchart** for decision logic, algorithm flow, execution paths
   - **Class diagram** for new type hierarchies and relationships
   - **Sequence diagram** for multi-component interactions
   - **State diagram** for state machine changes
   Place diagrams INSIDE the feature section they belong to, right after the algorithm description and before the example. Do not collect all diagrams in one place.

4. **Do NOT include a "Changed Files" table.** It duplicates what `git diff --stat` already shows and adds no design insight. The document is about understanding, not inventory.

5. **Do NOT use file:line references.** They go stale after any rebase or edit and clutter the text. Refer to classes and methods by name (e.g., "`traceBackwardBranch()` in `MatchExecutionPlanner`"). The reader can find them with grep.

6. **Shared concepts go at the top of the Design section.** If multiple features share a data structure, an invariant (like context isolation), a safety mechanism (like a runtime fallback), or a configuration knob — describe it once before the per-feature sections, not repeated in each.

7. **Explain WHY, not just what.** Use commit messages and PR description for motivation. When a design choice has trade-offs, state them. When something looks redundant (e.g., a check that always passes with current data), explain when it matters.

8. **Be honest about limitations.** If a feature is only exercised by synthetic tests and not by any production query, say so. If a pattern never actually filters in symmetric cases, explain what the real win is (cost reduction, not filtering).

9. **Use the Agent tool** with `subagent_type: "Explore"` if you need to understand surrounding code context that isn't in the diff — e.g., to find which real queries trigger a new optimization, or to understand the old behavior being replaced.

10. The output file goes in the **project root directory** (the current working directory).
