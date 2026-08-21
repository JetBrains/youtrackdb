package com.jetbrains.youtrackdb.api.config;

/**
 * Comparison-semantic default for null placement in {@code ORDER BY} when an item omits
 * {@code NULLS FIRST} / {@code NULLS LAST}.
 *
 * <p>Composes with {@code ASC}/{@code DESC}:
 * <ul>
 *   <li>{@link #NULLS_SMALLEST} — nulls first for ASC, nulls last for DESC (legacy behavior)</li>
 *   <li>{@link #NULLS_LARGEST} — nulls last for ASC, nulls first for DESC</li>
 * </ul>
 *
 * <p>An explicit per-item {@code NULLS FIRST}/{@code NULLS LAST} is absolute and overrides both
 * this default and sort direction.
 *
 * @see GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT
 */
public enum OrderByNullsDefault {
  NULLS_SMALLEST, NULLS_LARGEST
}
