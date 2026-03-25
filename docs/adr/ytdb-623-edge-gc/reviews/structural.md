# Structural Review — Edge Tombstone GC During Page Split

**Date**: 2026-03-24
**Status**: PASS (1 iteration)

## Findings

### Finding S1 [suggestion] — REJECTED
**Location**: Plan, Track 1 "What" section
**Issue**: Track description approaches step-level detail.
**Decision**: Rejected — the detail is useful for the execution agent and
stays within track-description scope.

### Finding S2 [suggestion] — VERIFIED
**Location**: Plan, Component Map — AtomicOperation bullet
**Issue**: Did not note that `AtomicOperation` is an interface.
**Fix applied**: Added "(interface)" annotation.

### Finding S3 [should-fix] — VERIFIED
**Location**: Design, "Ghost Resurrection Prevention" section
**Issue**: Missing explicit treatment of concurrent snapshot index
modifications during the GC check.
**Fix applied**: Added "Concurrency with concurrent snapshot insertions"
paragraph explaining why a concurrent snapshot insertion after the
`hasEdgeSnapshotEntries()` check is safe.

### Finding S4 [suggestion] — VERIFIED
**Location**: Design, Performance Characteristics table
**Issue**: Table omitted the bucket rebuild cost.
**Fix applied**: Added "Bucket rebuild" row — O(N) for re-serializing
surviving entries, only when at least one tombstone is removed.

### Finding S5 [should-fix] — VERIFIED
**Location**: Plan, Architecture Notes — Invariants section
**Issue**: "Insert-retry-first" described behavior rather than a testable
invariant.
**Fix applied**: Rephrased as "No unnecessary splits: if tombstone removal
frees enough space for the insert, no split occurs — the tree depth and
bucket count remain unchanged."

### Finding S6 [suggestion] — VERIFIED
**Location**: Plan, Track 2 — Constraints section
**Issue**: Didn't clarify whether LWM manipulation test infrastructure
already exists.
**Fix applied**: Added note that existing SI test classes already
demonstrate `TsMinHolder` manipulation.

### Finding S7 [suggestion] — VERIFIED
**Location**: Design, Workflow section — after sequence diagram
**Issue**: The dual `getEntry()`/`getRawEntry()` pattern was not explained.
**Fix applied**: Added prose note explaining that `getEntry()` deserializes
for eligibility checking while `getRawEntry()` retrieves raw bytes for
the `addAll()` rebuild.
