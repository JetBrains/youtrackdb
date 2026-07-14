package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/**
 * The result a {@link StepRecogniser} returns to {@link GremlinStepWalker}. There is no third
 * "not mine" value: dispatch is class-keyed, so a recogniser is only ever run for the exact step
 * class it owns and answers a single well-formedness question about the shape at the cursor's head.
 *
 * <ul>
 *   <li>{@link #ACCEPTED} — the recogniser consumed one or more steps through the cursor and
 *       contributed them to the walk. The walker asserts the cursor actually advanced; an accept
 *       that consumed nothing is a recogniser bug.
 *   <li>{@link #DECLINE} — the shape is untranslatable. The walker discards the whole walk and the
 *       traversal stays on the native TinkerPop pipeline (the all-or-nothing rule). Because a
 *       decline throws the entire walk away, a recogniser may read and contribute in any order: no
 *       partial contribution can leak.
 * </ul>
 */
enum Outcome {
  ACCEPTED, DECLINE
}
