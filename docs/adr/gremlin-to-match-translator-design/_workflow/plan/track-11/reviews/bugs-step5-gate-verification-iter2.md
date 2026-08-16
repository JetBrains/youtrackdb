## bugs Review (gate check)

### Verdicts
- BG1: VERIFIED — both readers now call one shared body `postUnionPositionalGateSatisfied` (`GremlinStepWalker.java:969-980`): the in-loop gate at `:522-526` with `ahead=0` over the head it just peeked, the look-ahead at `:944`; `peek(ahead+1)` resolves the successor correctly in both (position untouched, transparent steps skipped), the stale "the look-ahead only ever declines shapes the in-loop gate would decline" sentence is rewritten at `:910-912`, and `walk_positionalMemberBehindAUnionCarrier_declinesOnTheInLoopGateAlone` pins the in-loop decline with a translating control.
- BG2: VERIFIED — the narrowing is recorded at the gate (`UnionStepRecogniser.java:141-150`) and in the class javadoc (`:58-67`), which is the second of the two remedies the finding offered; the `accumulateMap` merge stays pre-existing and out of step scope.
- BG3: VERIFIED — `UnionStepRecogniser.java:30-34` now splits the two terminators: `tail(n)` declines through the positional gate, `fold()` on membership before that question is asked.

### Summary
- PASS
