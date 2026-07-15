# AGENTS.md - YouTrackDB Project Guide

## Project Overview

YouTrackDB is a general-purpose object-oriented graph database developed by JetBrains, used internally in production. It implements the Apache TinkerPop API with Gremlin query language support and features O(1) link traversal, schema-less/mixed/full modes, and encryption at rest. The project is a fork of OrientDB, re-architected under the `com.jetbrains.youtrackdb` package namespace.

- **License**: Apache 2.0
- **JDK**: 21+ required
- **Build**: Maven with Maven Wrapper (`./mvnw`)
- **Group ID**: `io.youtrackdb`
- **Version**: `0.5.0-SNAPSHOT` (CI-friendly: `${revision}${sha1}${changelist}`)
- **Issue tracker**: https://youtrack.jetbrains.com/issues/YTDB (YouTrack project code: `YTDB`)
- **Repository**: https://github.com/JetBrains/youtrackdb

## Project Documentation

The `docs/` folder contains user-facing product documentation. See `docs/README.md` for the index. Project-internal documentation (development workflow, role guidelines, ADR archive) lives in `docs-internal/` — see `docs-internal/README.md` for the index.

## Architecture

Storage engine, Gremlin integration, RID format, generated code pipeline, and the table of key entry-point classes (`YourTracks`, `YouTrackDB`, `ServerMain`, `GlobalConfiguration`, `DiskStorage`, `AbstractStorage`, etc.) are documented in `.claude/docs/architecture.md`. Load on demand when the change actually touches storage / Gremlin / parser / generated-code areas.

## Role Guidelines

Role-specific rules live in two documents, keyed by the kind of activity you are about to do:

- **Planning work, choosing verification scope, PR / branch work, reviewing changes for workflow or review discipline, doc-sync duties** → read `docs-internal/agents/orchestrator-guidelines.md` (track-based workflow, test policy, pre-commit verification rules, git conventions, documentation sync).
- **Writing, editing, or reviewing code (style and test rules), running builds / tests / formatting, committing, hands-on codebase navigation** → read `docs-internal/agents/thread-guidelines.md` (build commands, code style, testing, committing, codebase tips).

The slate extension injects these documents automatically per role (orchestrator vs worker thread). Any agent whose prompt does not already include the relevant document must read it on demand before doing that kind of work.

### Load Guidance Documents on Demand

When designing agents or extensions in this repo (e.g., the slate extension's review rules), load guidance/rules documents on demand instead of injecting them into every prompt. Keep the always-loaded surface to a short pointer naming the trigger condition and the document path.
