# Track 13: Typed emitted-column descriptors (BG2200)

## Purpose

After modulated `select().by`, a trailing overlapping `select(label)` must emit
the map cell (scalar), not the pattern-bound Vertex.

## Plan of work

1. Add emit descriptors on `RecognitionContext` / `WalkerContext`.
2. Write descriptors from modulated `SelectStepRecogniser` /
   `SelectOneStepRecogniser`.
3. Teach `configureSelect` (and select recognisers) to project from descriptors.
4. Clear descriptors on hops that change stream element type; decline unknown shapes.
5. Add `trailingSelectOverlaps` to `SelectOneStepRecogniser`.
6. Flip `mapSelectThenOverlappingSelectOne_knownUnfixedWithoutDedup` to
   `RECOGNIZED`; add SelectOne→SelectOne after dedup.

## Acceptance

- Known-unfixed pin becomes translator-on/off equivalent with scalar results.
- Post-cardinality decline-or-correct cases stay green.
- `ProjectionEquivalenceTest` green; composition suite if terminal `select().by` moves.
