## test-structure Review (gate check)

### Verdicts
- TS1: VERIFIED — `GremlinStepWalkerTest.java:1687-1696` now asserts each extracted step against `UnfoldStep` / `ReverseStep` / `TailGlobalStepContract`, and the javadoc records why the check exists.
- TS2: VERIFIED — the loop is gone; `UnionTraversalEquivalenceTest.java:632-681` splits the two shapes into named tests over `assertPostUnionStageSurvives`, which interpolates `label` into all three messages.
- TS3: VERIFIED — `UnionTraversalEquivalenceTest.java:730-736` states the over-determination and names the white-box witness that pins the arm gate.
- TS4: VERIFIED — `declaresOwnPositionalAnswer` at `GremlinStepWalkerTest.java:1739` is shared by both tripwires, and the absence message at lines 1725-1728 names the action a developer must take.
- TS5: VERIFIED — `UnionTraversalEquivalenceTest.java:693-697` now scopes width to the regression hypothetical and states that both sides run the native pipeline on the decline path.

### Summary
- PASS
