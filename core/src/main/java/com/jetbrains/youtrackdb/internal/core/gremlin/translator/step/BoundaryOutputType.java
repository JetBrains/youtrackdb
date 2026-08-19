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
 *       {@code .has(...)}).
 *   <li>{@link #MAP} — emit a {@code Map<String, Object>} (when the prefix ends in
 *       {@code select(...)}, {@code valueMap(...)}, {@code elementMap()}, {@code project(...)},
 *       {@code group()}, or {@code groupCount()}).
 *   <li>{@link #SINGLE_VALUE} — emit a single property value (when the prefix ends in
 *       {@code values(key)}).
 *   <li>{@link #SCALAR} — emit a scalar aggregate (when the prefix ends in {@code count()},
 *       {@code sum(...)}, {@code min(...)}, {@code max(...)}, or {@code mean(...)}).
 * </ul>
 */
public enum BoundaryOutputType {
  /**
   * Emit the matched vertex/edge bound to the step's boundary alias. The boundary step pulls
   * one {@code Result} row per {@code next}, looks up the property under the boundary alias,
   * and emits it as a TinkerPop element.
   */
  ELEMENT,

  /**
   * Emit a {@code Map<String, Object>} built from the RETURN projection (multi-alias {@code select},
   * {@code valueMap}, {@code elementMap}, {@code project}, {@code group}, {@code groupCount}).
   */
  MAP,

  /**
   * Emit a single property value from the RETURN projection ({@code values(key)} terminators).
   */
  SINGLE_VALUE,

  /**
   * Emit a scalar aggregate from the RETURN projection ({@code count}, {@code sum}, {@code min},
   * {@code max}, {@code mean} terminators).
   */
  SCALAR
}
