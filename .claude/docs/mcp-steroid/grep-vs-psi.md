# MCP Steroid — Grep vs PSI

Full routing rules for choosing between text search (`grep`/`rg`/Bash) and PSI-based search (mcp-steroid). The summary lives in `CLAUDE.md` § MCP Steroid → "Grep vs PSI — when to switch"; this file is the authoritative source for edge cases.

Default to PSI for **symbol-level questions**, default to grep for **textual / filename / one-shot questions**. Concretely:

| Use `grep`/`rg`/Bash when… | Use mcp-steroid PSI when… |
|---|---|
| Filename or path globs (`**/*Test.java`) | Finding callers / usages of a method, field, or class |
| Searching for a unique literal (config key, error message, log string, annotation value) | Walking a call chain or building a "who-touches-X" set for an audit / refactor |
| You already have the exact file open and just need a line number | Finding overrides / implementations of an interface or abstract method |
| Files outside the IDE-indexed project (build artifacts, logs, generated `target/`, scratch files) | Type hierarchy: subclasses, supertypes, sealed permits |
| One-off check where false positives are obvious by eye | Anything where missed or spurious matches would corrupt a refactor (rename, move, signature change) |

**Rule of thumb:** if the answer to your search needs to be **complete and reference-accurate** (every caller, every override), use PSI. If it just needs to surface a candidate or two for human inspection, grep is fine.

## Common grep traps PSI avoids

- Identifier appears inside Javadoc/comments/string literals — grep counts them, PSI doesn't.
- Method called via interface or generic supertype — text search of the concrete name misses the polymorphic call sites; PSI's reference resolution catches them.
- Two unrelated symbols share a name (e.g. `size()` on dozens of types) — grep can't tell them apart; PSI scopes by declaration.
- Symbol was renamed in a recent commit — grep on the old name returns stale results from comments and tests; PSI follows the actual binding.

## Load-bearing audits require PSI

If a search result will inform a deletion, rename, signature change, or any action where a missed reference would break production code, **PSI is required when the IDE is open** — grep is not acceptable for the audit that drives the change. Grep is fine for orientation (initial sketch, narrowing the search space) but the load-bearing audit must be reference-accurate.

Examples of load-bearing questions:
- *"Are there any production callers of X before we delete it?"*
- *"Is field Y referenced anywhere outside its declaring class?"*
- *"What consumes the version field on this config blob?"*

The cost of a missed polymorphic call site is "tests pass at the deletion commit but production breaks at runtime" — exactly the failure mode PSI exists to prevent.

## Delegating to a sub-agent doesn't bypass the PSI requirement

When passing a symbol-usage question to Explore (or any sub-agent), the prompt MUST explicitly say *"use mcp-steroid PSI find-usages, not grep, for these reference-accuracy questions"* — sub-agents default to Bash/grep, so an unannotated delegation routes through grep regardless of the question's shape. The routing decision lives at the orchestrator's level, not at the sub-agent's; making it explicit in the prompt is the only way to enforce it.

## Cost awareness

PSI calls are slower per-call than grep. Don't use PSI for file globbing or for searching strings/comments — grep wins there.
