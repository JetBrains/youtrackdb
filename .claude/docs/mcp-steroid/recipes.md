# MCP Steroid — Recipes

Concrete recipes for common IDE-control tasks. The catalogue is loaded on demand; the index lives in `CLAUDE.md` § MCP Steroid → "Recipes" with one-line pointers to each entry below.

Each recipe assumes mcp-steroid is reachable, the relevant project is open, and the working tree matches (preflight already done per the rules in `CLAUDE.md` § MCP Steroid). Skip the recipe when those don't hold.

Each recipe points at one or more `mcp-steroid://` resources — **fetch them via `steroid_fetch_resource` and adapt the script template** rather than rederiving the IntelliJ API calls from memory. The resources are working scripts kept in sync with the platform; reconstructing them by hand is how subtle API drift creeps in.

## Safe delete (production-caller gate)

- Trigger: about to remove a method, field, or class that may still be referenced — especially across the `api`/`internal` boundary or in deprecated public API.
- Resource: `mcp-steroid://ide/safe-delete` (uses `ReferencesSearch` + `SafeDeleteProcessor`). Run with the dry-run/preview path first; review the caller list; re-run for real only if the list is empty or expected.
- Use case: deprecated public API removal, pruning unused `internal` helpers, API-surface tightening during rollback-log / foreign-memory migrations.

## Inheritance hierarchy search (SPI implementer / override map)

- Trigger: questions of the form "every implementer of X" or "every override of Y" before changing an interface or abstract class.
- Resource: `mcp-steroid://ide/hierarchy-search` (uses `ClassInheritorsSearch` + `OverridingMethodsSearch`).
- Use case: enumerating SPI implementers (`IndexEngine`, `StorageComponent`, engine subclasses, collation strategies, SQL functions) before contract changes — grep on `extends X` misses indirect chains and generic supertypes.

## Call hierarchy (multi-hop upward impact)

- Trigger: changing a low-level signature where you need the caller *tree*, not just immediate callers.
- Resource: `mcp-steroid://ide/call-hierarchy` (uses `MethodReferencesSearch`); adapt to recurse upward, bounded by depth or until reaching test code / public API.
- Use case: signature changes on `WOWCache.store`, `WriteAheadLog.log`, `Index.put`, `AbstractStorage.flush` — assessing propagation distance before committing.

## Structured test failure details / statistics

- Trigger: an IDE-routed Maven test run just failed, or you want the slow-test list / pass-fail breakdown without parsing surefire XML.
- Resources: `mcp-steroid://test/failure-details` (message + stack + duration), `mcp-steroid://test/statistics` (totals + slowest tests). Pair with `mcp-steroid://test/find-recent-test-run` or `mcp-steroid://test/inspect-test-results` when you need to locate the run first. All read `RunContentManager` → `SMTRunnerConsoleView` → `SMTestProxy`.
- Use case: flaky-test investigations (LinksConsistencyException, IC11 dedup, FreezeAndDBRecordInsert crash), CI triage, identifying slow tests in the `core` suite.

## IntelliJ inspections on changed code (pre-PR pass)

- Trigger: about to open a PR; want to surface semantic issues Spotless and `coverage-gate.py` won't catch (redundant casts, unused declarations, atomic-on-volatile, suspicious `equals`/`hashCode`, format-string mismatches, thread-unsafe statics).
- Resources: `mcp-steroid://ide/inspect-and-fix` (`InspectionEngine` + quick-fix application), `mcp-steroid://ide/inspection-summary` (enabled inspection list), `mcp-steroid://lsp/code-action` (per-position quick-fixes / intentions). Intersect the run with `git diff --name-only origin/develop...HEAD`; report findings, never auto-apply without user review.
- Use case: storage- and concurrency-heavy code where the grep-level review misses semantic issues; cheap last pass before push.

## Project / module dependency graph

- Trigger: module-graph question ("does `embedded` depend on `server`?", "what depends on `lucene`?", "is the new arrow in this design doc actually wired?") that would otherwise require reading several `pom.xml` files.
- Resource: `mcp-steroid://ide/project-dependencies` (iterates `ModuleManager.modules` and each module's `OrderEnumerator`).
- Use case: scoping a change to the correct module before editing, confirming the `lucene` exclusion still holds, sanity-checking dependency arrows in design docs and ADRs.

## Class-shape refactors (extract-interface / pull-up / push-down)

- Trigger: consolidating duplicated logic between sibling classes (e.g., `EngineLocalPaginated` vs `EngineMemory`), or formalizing an SPI contract by extracting an interface.
- Resources: `mcp-steroid://ide/extract-interface`, `mcp-steroid://ide/pull-up-members`, `mcp-steroid://ide/push-down-members`. Dry-run first, review the move plan, re-run for real on a clean working tree, finish with `./mvnw -pl <module> spotless:apply` and the module's test suite.
- Use case: rollback-log architecture work, separating public contracts from internal implementations, reducing copy-paste across storage engines or index variants.

## Add parameter via change-signature

- Trigger: adding a new parameter to a method with many overrides (SPI interfaces, abstract storage methods, heavily-overridden hooks).
- Resource: `mcp-steroid://ide/change-signature` (uses `ChangeSignatureProcessor` to update every overrider and call site in one shot).
- Use case: extending contracts like `IndexEngine.put` or `StorageComponent.flush` where raw text replace would miss polymorphic call sites and `super.method(...)` chains in subclasses.
