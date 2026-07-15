# Structural Review

## Iteration 1

### Finding S1 [should-fix] → FIXED
**Location**: Track 2 description and scope indicator
**Issue**: Track 2 described 5 explicit phases plus substantial additional work
(~10 file deletions, iterator inlining, BidirectionalLinks reparameterization,
ResultInternal cleanup). True scope was 6-7 steps, pushing against the track
sizing upper bound.
**Proposed fix**: Split into Track 2 "Merge StatefulEdge into Edge" (~3-4
steps) and Track 3 "Delete Relation hierarchy" (~3-4 steps), following the
natural phasing already described.
**Resolution**: Accepted — split Track 2 into two dependent tracks. Renumbered
all subsequent tracks (old 3→4, 4→5, 5→6, 6→7). Updated all dependency
annotations and decision record track references.

### Finding S2 [should-fix] → FIXED
**Location**: Track 1 description — constraint vs. body contradiction
**Issue**: Track 1 body said "Remove the now-dead `isStatefulEdge()`/
`asStatefulEdge()`/... methods from all interfaces" while the constraint said
"`Edge` interface retains `asStatefulEdge()`/`asStatefulEdgeOrNull()` until
Track 2." Contradictory instructions for the execution agent.
**Proposed fix**: Clarify boundary: Track 1 removes from non-`Edge` interfaces;
`Edge.asStatefulEdge()`/`asStatefulEdgeOrNull()` retained until Track 2.
**Resolution**: Accepted — constraint rewritten to be explicit about which
interfaces lose which methods.

### Finding S3 [suggestion] → FIXED
**Location**: Track 6 (formerly Track 5) component diagram
**Issue**: `BTREE[BTreeIndex]` node in the Mermaid diagram had no corresponding
annotation bullet.
**Proposed fix**: Add bullet: "BTreeIndex — unchanged; existing index
infrastructure used by the new secondary definition."
**Resolution**: Accepted.

### Finding S4 [suggestion] → FIXED
**Location**: Track 7 (formerly Track 6) description
**Issue**: Unclear whether "verification" meant automated test runs or manual
inspection.
**Proposed fix**: Clarify that verification is automated (Cucumber, SQL,
Gremlin integration tests) with escalation guidance.
**Resolution**: Accepted — approach section rewritten.

### Finding S5 [should-fix] → FIXED
**Location**: Component Map annotated bullet list
**Issue**: Diagram included `VERTEX[Vertex / VertexEntityImpl]` and
`DBSESS[DatabaseSessionEmbedded]` nodes with no corresponding annotation
bullets.
**Proposed fix**: Add bullets describing what changes in each component.
**Resolution**: Accepted — two bullets added. Duplicate DatabaseSessionEmbedded
entry (from original plan + new bullet) merged into one.

### Finding S6 [suggestion] → REJECTED
**Location**: Decision D4 track reference
**Issue**: D4 references only Track 6, but Track 7 also verifies the feature.
**Proposed fix**: Optionally add "verified in Track 7."
**Resolution**: Rejected — Track 7 verifies everything, not just D4. Adding it
to one decision but not others would be inconsistent.

### Finding S7 [should-fix] → FIXED
**Location**: Track 1 scope indicator
**Issue**: Description mentions updating `ResultSet` methods with
`StateFull`/`SateFull` typos but scope indicator didn't list this concern.
**Proposed fix**: Update scope to include ResultSet typo renames and adjust
step count to ~3-4.
**Resolution**: Accepted.

## Iteration 2 (Gate Verification)

- S1: VERIFIED
- S2: VERIFIED
- S3: VERIFIED
- S4: VERIFIED
- S5: VERIFIED
- S6: REJECTED (no action needed)
- S7: VERIFIED
- No new findings.
- **Summary: PASS**
