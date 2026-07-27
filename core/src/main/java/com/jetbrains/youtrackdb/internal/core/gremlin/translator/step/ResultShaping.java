package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.List;
import javax.annotation.Nonnull;

/**
 * Immutable bundle of the seven boundary row-projection shaping flags a Gremlin terminator pins on
 * the walk. Each terminator ({@code count}, {@code values}, {@code valueMap}, {@code select},
 * {@code group}, …) builds one instance from {@link #NONE} plus the overrides its shape needs, and
 * {@link YTDBMatchPlanStep} reads it when projecting each MATCH row onto a traverser.
 *
 * <p>{@link #NONE} is the element-path default: no row is dropped, no property is presence-checked,
 * and no map value is reshaped. A terminator layers its overrides on top through the {@code withX}
 * methods, so the flag combination each one pins is a single expression rather than a reset block.
 *
 * @param dropNullRows skip entire rows whose primary projected value is {@code null} (empty-input
 *     aggregates like {@code mean()} on an empty stream)
 * @param dropOnAbsent skip rows where a presence-checked property is absent on the entity (distinct
 *     from present-with-null)
 * @param presencePropertyKeys property keys checked with {@code EntityImpl.hasProperty} when
 *     projecting {@code values} / {@code valueMap} / {@code elementMap}; empty when unused
 * @param wrapMapValuesInLists wrap {@code valueMap} property values in singleton lists (native
 *     TinkerPop {@code valueMap} shape; {@code elementMap} leaves them unwrapped)
 * @param accumulateMap drain every GROUP BY row into one accumulated map and emit a single
 *     traverser ({@code group} / {@code groupCount})
 * @param unwrapSingletonMap emit a single-column {@code select} value directly rather than a
 *     one-entry map (native {@code SelectOneStep} shape)
 * @param elementMapTokens emit {@code elementMap} id / label columns under TinkerPop {@code T.id} /
 *     {@code T.label} keys rather than plain strings
 */
public record ResultShaping(
    boolean dropNullRows,
    boolean dropOnAbsent,
    @Nonnull List<String> presencePropertyKeys,
    boolean wrapMapValuesInLists,
    boolean accumulateMap,
    boolean unwrapSingletonMap,
    boolean elementMapTokens) {

  /**
   * The element-path default: every flag false and no presence keys. Terminators layer their
   * overrides on this through the {@code withX} methods.
   */
  public static final ResultShaping NONE =
      new ResultShaping(false, false, List.of(), false, false, false, false);

  /** Copies the presence-key list defensively so the record stays immutable. */
  public ResultShaping {
    presencePropertyKeys = List.copyOf(presencePropertyKeys);
  }

  /** This shaping with {@code dropNullRows} set to {@code value}. */
  public ResultShaping withDropNullRows(boolean value) {
    return new ResultShaping(value, dropOnAbsent, presencePropertyKeys, wrapMapValuesInLists,
        accumulateMap, unwrapSingletonMap, elementMapTokens);
  }

  /** This shaping with {@code dropOnAbsent} set to {@code value}. */
  public ResultShaping withDropOnAbsent(boolean value) {
    return new ResultShaping(dropNullRows, value, presencePropertyKeys, wrapMapValuesInLists,
        accumulateMap, unwrapSingletonMap, elementMapTokens);
  }

  /** This shaping with {@code presencePropertyKeys} replaced by {@code keys}. */
  public ResultShaping withPresencePropertyKeys(@Nonnull List<String> keys) {
    return new ResultShaping(dropNullRows, dropOnAbsent, keys, wrapMapValuesInLists,
        accumulateMap, unwrapSingletonMap, elementMapTokens);
  }

  /** This shaping with {@code wrapMapValuesInLists} set to {@code value}. */
  public ResultShaping withWrapMapValuesInLists(boolean value) {
    return new ResultShaping(dropNullRows, dropOnAbsent, presencePropertyKeys, value,
        accumulateMap, unwrapSingletonMap, elementMapTokens);
  }

  /** This shaping with {@code accumulateMap} set to {@code value}. */
  public ResultShaping withAccumulateMap(boolean value) {
    return new ResultShaping(dropNullRows, dropOnAbsent, presencePropertyKeys, wrapMapValuesInLists,
        value, unwrapSingletonMap, elementMapTokens);
  }

  /** This shaping with {@code unwrapSingletonMap} set to {@code value}. */
  public ResultShaping withUnwrapSingletonMap(boolean value) {
    return new ResultShaping(dropNullRows, dropOnAbsent, presencePropertyKeys, wrapMapValuesInLists,
        accumulateMap, value, elementMapTokens);
  }

  /** This shaping with {@code elementMapTokens} set to {@code value}. */
  public ResultShaping withElementMapTokens(boolean value) {
    return new ResultShaping(dropNullRows, dropOnAbsent, presencePropertyKeys, wrapMapValuesInLists,
        accumulateMap, unwrapSingletonMap, value);
  }
}
