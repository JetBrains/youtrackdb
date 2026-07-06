package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

/**
 * The shape that {@link YTDBMatchPlanStep} emits as TinkerPop traversers when the underlying
 * MATCH plan produces a row.
 *
 * <p>The translator records this on the step at construction time, derived from the
 * terminator of the prefix that was translated:
 *
 * <ul>
 *   <li>{@link #ELEMENT} — emit the matched vertex/edge bound to the boundary alias. Used
 *       when the prefix ends in a vertex/edge step (e.g. {@code g.V()}, {@code .out("knows")},
 *       {@code .has(...)}). This is the only mode supported in the initial translation
 *       scope; richer modes are introduced as later tracks add support for projection,
 *       aggregation, and label-set propagation.
 * </ul>
 *
 * <p>Planned future modes (documented here only; not yet implemented):
 * <ul>
 *   <li>{@code MAP} — emit a {@code Map<String, Object>} carrying multiple bound aliases
 *       (when the prefix ends in {@code select(l1, l2, ...)} or {@code project(...)}).
 *   <li>{@code SINGLE_VALUE} — emit a single property value (when the prefix ends in
 *       {@code values(key)}).
 *   <li>{@code SCALAR} — emit a scalar (when the prefix ends in an aggregate like
 *       {@code count()} or {@code sum(...)}).
 * </ul>
 */
public enum BoundaryOutputType {
  /**
   * Emit the matched vertex/edge bound to the step's boundary alias. The boundary step pulls
   * one {@code Result} row per {@code next}, looks up the property under the boundary alias,
   * and emits it as a TinkerPop element.
   */
  ELEMENT
}
