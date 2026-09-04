# YouTrackDB Documentation

## Table of Contents

| Document | Description |
|---|---|
| [Getting Started](getting-started.md) | Tutorial covering schema, CRUD, MATCH traversals, and transactions using YQL |
| [Object-Oriented Data Modeling](object-oriented.md) | Inheritance, polymorphic queries, property types, schema evolution |
| [Fine-Grained Security](security.md) | Predicate-based security policies, per-role filtering, ALTER/REVOKE lifecycle |
| [Database Migration Procedure](operator-migration-procedure.md) | Operator runbook for export/import format migration — exit-status gates, fresh-target discipline, failure handling, best-effort dumps, crash residue |
| [Gremlin Order By and Missing Properties](gremlin-order-by.md) | How a global-scope Gremlin order treats a record that lacks the ordered property, the setting and the per-traversal override that restore portable Apache TinkerPop behavior, and one known limitation |
| [Release Notes](release-notes.md) | Behavioral changes that affect an existing deployment, with the switch that restores each previous behavior |
| [YQL Reference](yql/README.md) | Reference for the YouTrackDB Query Language (YQL) — commands, functions, methods, syntax, and MATCH traversals |
| [Query Engine Internals](yql-internals-book/chapters/01-why-a-graph-database.md) | Deep-dive book on how YouTrackDB's YQL/MATCH queries compile and run — parser, pattern graph, cost-based planner, execution steps, traversers, and optimisation layers — in 17 chapters. Start at Chapter 1. |
| [Project-Internal Documentation (contributors)](../docs-internal/README.md) | Documentation for people working ON YouTrackDB — development workflow, role guidelines, and the Architecture Decision Record archive |
