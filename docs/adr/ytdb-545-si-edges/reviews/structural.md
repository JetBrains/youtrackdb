# Structural Review

## Iteration 1

### Finding S1 [suggestion] → FIXED
**Location**: Track 2 description, "Interactions" paragraph
**Issue**: Track 2 claims independence from Track 1, but its snapshot index stores `LinkBagValue` which gains a `tombstone` field in Track 1. The independence claim is misleading.
**Proposed fix**: Soften to "partially independent" and acknowledge the shared type.
**Resolution**: Accepted — reworded to note the `LinkBagValue` dependency while clarifying key types are independent.

### Finding S2 [suggestion] → REJECTED
**Location**: Component Map — `SI` node
**Issue**: `SI` node is ambiguous (could mean collection or edge snapshot maps).
**Proposed fix**: Split into two nodes or add clarifying label.
**Resolution**: Rejected — the annotation list below the diagram already clarifies these are edge-specific maps.

### Finding S3 [should-fix] → FIXED
**Location**: Track 3 description and scope line
**Issue**: Track 3's scope lists "IsolatedLinkBagBTreeImpl write path updates" but the "How" section only describes `SharedLinkBagBTree` changes. Unclear what `IsolatedLinkBagBTreeImpl` work belongs in Track 3 vs Track 4.
**Proposed fix**: Add `IsolatedLinkBagBTreeImpl` bullet to Track 3's "How" section; clarify Track 4 covers read/iteration path.
**Resolution**: Accepted — added write-path bullet to Track 3 "How" and clarified Track 3/4 boundary in interactions note.

### Finding S4 [suggestion] → FIXED
**Location**: Track 4 description — spliterator section
**Issue**: States spliterators "already receive" `AtomicOperation` without confirming whether a signature change is needed.
**Proposed fix**: Change to a verification note for the execution agent.
**Resolution**: Accepted — reworded to instruct the execution agent to verify constructor signatures.

### Finding S5 [should-fix] → REJECTED
**Location**: Track 2 scope line (~5-6 steps)
**Issue**: At upper boundary of ~5-7 step limit.
**Proposed fix**: Note that interface + implementation are expected as a single step.
**Resolution**: Rejected — 5-6 is within limits; execution agent decomposes steps just-in-time.

### Finding S6 [suggestion] → REJECTED
**Location**: Architecture Notes — Visibility correctness invariant
**Issue**: Complex invariant could benefit from a concrete example.
**Proposed fix**: Add inline example scenario.
**Resolution**: Rejected — the invariant is precise as-is; examples belong in test design during execution.

### Finding S7 [suggestion] → NO ACTION
**Location**: Architecture Notes — Decision Records D1-D4
**Issue**: All well-structured. Noted for completeness.
**Resolution**: No action needed.

### Finding S8 [suggestion] → FIXED
**Location**: Track 2 description — `EdgeSnapshotKey` and `EdgeVisibilityKey`
**Issue**: `componentId` field not explained; execution agent would need to look it up.
**Proposed fix**: Add note explaining it identifies the `SharedLinkBagBTree` durable component via `DurableComponent.getId()`.
**Resolution**: Accepted — added explanation for both key types.

## Iteration 2 (Gate Verification)

- S1: VERIFIED
- S2: REJECTED (no action needed)
- S3: VERIFIED
- S4: VERIFIED
- S5: REJECTED (no action needed)
- S6: REJECTED (no action needed)
- S7: REJECTED (no action needed)
- S8: VERIFIED
- No new findings.
- **Summary: PASS**
