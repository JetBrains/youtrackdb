package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable bundle of the boundary row-projection shaping flags plus the ordered list-shaping
 * post-process a Gremlin terminator pins on the walk. Each terminator ({@code count}, {@code
 * values}, {@code valueMap}, {@code select}, {@code group}, …) builds one instance from {@link
 * #NONE} plus the overrides its shape needs, and {@link AbstractMatchPlanStep} reads it when
 * projecting each MATCH row onto a traverser.
 *
 * <p>{@link #NONE} is the element-path default: no row is dropped, no property is presence-checked,
 * no map value is reshaped, and no list-shaping op runs. A terminator layers its overrides on top
 * through the {@code withX} methods, so the flag combination each one pins is a single expression
 * rather than a reset block.
 *
 * @param dropNullRows skip entire rows whose primary projected value is {@code null} (empty-input
 *     aggregates like {@code mean()} on an empty stream)
 * @param dropOnAbsent skip rows where a presence-checked property is absent on the entity (distinct
 *     from present-with-null)
 * @param presencePropertyKeys property keys checked with {@code EntityImpl.hasProperty} when
 *     projecting {@code values} / {@code valueMap} / {@code elementMap} against the boundary entity;
 *     empty when unused
 * @param aliasPropertyPresences multi-alias presence / emit for {@code select(…).by(key)}; each
 *     names an entity RETURN column, a property key, and the map emit key (select label); empty when
 *     unused
 * @param mapEmitColumnOrder column names to put into the emitted map in order; empty means
 *     iterate {@code Result} property names (HashMap order). Multi-label {@code select} sets this so
 *     map key order matches native Gremlin even when presence entity columns scramble Result keys.
 *     {@code valueMap} / {@code elementMap} set it to token aliases then property keys so presence
 *     keys emit even when they are not RETURN columns (values are read from the boundary entity)
 * @param recordIdMapKeys map emit keys that must stay a {@code RID} rather than wrap as a vertex —
 *     {@code select(label).by(T.id)} columns, beside {@code elementMap}'s {@code id} under
 *     {@code T.id}
 * @param wrapMapValuesInLists wrap {@code valueMap} property values in singleton lists (native
 *     TinkerPop {@code valueMap} shape; {@code elementMap} leaves them unwrapped)
 * @param accumulateMap drain every GROUP BY row into one accumulated map and emit a single
 *     traverser ({@code group} / {@code groupCount})
 * @param unwrapSingletonMap emit a single-column {@code select} value directly rather than a
 *     one-entry map (native {@code SelectOneStep} shape)
 * @param elementMapTokens emit {@code elementMap} id / label columns under TinkerPop {@code T.id} /
 *     {@code T.label} keys rather than plain strings
 * @param emitGroupEntries when true (and {@code accumulateMap} is false), project each GROUP BY row
 *     as a {@code Map.Entry} — native {@code groupCount().unfold()} / post-group order+limit
 * @param rowDedupAlias when non-null, keep the first MATCH row per distinct identity of that RETURN
 *     column before projection — native prior-label {@code dedup(a)} (unique by {@code a}, emit
 *     the current boundary element)
 * @param listShapingOps ordered list-shaping stream stages ({@code fold} / {@code unfold} /
 *     {@code reverse} / {@code tail}) applied to the projected payload stream in declared order;
 *     empty when the traversal has no list-shaping terminator, in which case the boundary base
 *     bypasses the stage entirely (see {@link ListShapingOp} and {@link AbstractMatchPlanStep})
 */
public record ResultShaping(
    boolean dropNullRows,
    boolean dropOnAbsent,
    @Nonnull List<String> presencePropertyKeys,
    @Nonnull List<AliasPropertyPresence> aliasPropertyPresences,
    @Nonnull List<String> mapEmitColumnOrder,
    @Nonnull List<String> recordIdMapKeys,
    boolean wrapMapValuesInLists,
    boolean accumulateMap,
    boolean unwrapSingletonMap,
    boolean elementMapTokens,
    boolean emitGroupEntries,
    @Nullable String rowDedupAlias,
    @Nonnull List<ListShapingOp> listShapingOps) {

  /**
   * The element-path default: every flag false, no presence keys, and no list-shaping op.
   * Terminators layer their overrides on this through the {@code withX} methods.
   */
  public static final ResultShaping NONE =
      new ResultShaping(
          false,
          false,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          false,
          false,
          false,
          false,
          false,
          null,
          List.of());

  /**
   * RETURN column name for an entity loaded only for {@link AliasPropertyPresence} checks. Must not
   * collide with user {@code as} / {@code select} labels; stripped from the emitted map.
   */
  public static String presenceEntityColumnAlias(@Nonnull String internalAlias) {
    return "$g2m_pe_" + internalAlias;
  }

  /** Copies the list components defensively so the record stays immutable. */
  public ResultShaping {
    presencePropertyKeys = List.copyOf(presencePropertyKeys);
    aliasPropertyPresences = List.copyOf(aliasPropertyPresences);
    mapEmitColumnOrder = List.copyOf(mapEmitColumnOrder);
    recordIdMapKeys = List.copyOf(recordIdMapKeys);
    listShapingOps = List.copyOf(listShapingOps);
  }

  /** This shaping with {@code dropNullRows} set to {@code value}. */
  public ResultShaping withDropNullRows(boolean value) {
    return new ResultShaping(
        value,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code dropOnAbsent} set to {@code value}. */
  public ResultShaping withDropOnAbsent(boolean value) {
    return new ResultShaping(
        dropNullRows,
        value,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code presencePropertyKeys} replaced by {@code keys}. */
  public ResultShaping withPresencePropertyKeys(@Nonnull List<String> keys) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        keys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code aliasPropertyPresences} replaced by {@code presences}. */
  public ResultShaping withAliasPropertyPresences(
      @Nonnull List<AliasPropertyPresence> presences) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        presences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /**
   * This shaping with {@code mapEmitColumnOrder} replaced by {@code columns} — select-label order
   * for multi-entry maps.
   */
  public ResultShaping withMapEmitColumnOrder(@Nonnull List<String> columns) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        columns,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        listShapingOps);
  }

  /**
   * This shaping with {@code recordIdMapKeys} replaced by {@code keys} — emit columns that must
   * stay a RID rather than wrap as a vertex.
   */
  public ResultShaping withRecordIdMapKeys(@Nonnull List<String> keys) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        keys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code wrapMapValuesInLists} set to {@code value}. */
  public ResultShaping withWrapMapValuesInLists(boolean value) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        value,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code accumulateMap} set to {@code value}. */
  public ResultShaping withAccumulateMap(boolean value) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        value,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code unwrapSingletonMap} set to {@code value}. */
  public ResultShaping withUnwrapSingletonMap(boolean value) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        value,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /** This shaping with {@code elementMapTokens} set to {@code value}. */
  public ResultShaping withElementMapTokens(boolean value) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        value,
        emitGroupEntries,
        rowDedupAlias,
        listShapingOps);
  }

  /**
   * This shaping with {@code emitGroupEntries} set — each GROUP BY row becomes a {@code Map.Entry}
   * (native {@code groupCount().unfold()}). Clears {@code accumulateMap} when enabling.
   */
  public ResultShaping withEmitGroupEntries(boolean value) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        wrapMapValuesInLists,
        value ? false : accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        value,
        rowDedupAlias,
        listShapingOps);
  }

  /**
   * This shaping with {@code rowDedupAlias} set to {@code alias} — first row per identity of that
   * RETURN column, then project the boundary element (prior-label {@code dedup(a)}).
   */
  public ResultShaping withRowDedupAlias(@Nullable String alias) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        alias,
        listShapingOps);
  }

  /**
   * This shaping with {@code listShapingOps} replaced by {@code ops}, applied to the projected
   * payload stream in the given order. An empty list restores the structural bypass.
   */
  public ResultShaping withListShapingOps(@Nonnull List<ListShapingOp> ops) {
    return new ResultShaping(
        dropNullRows,
        dropOnAbsent,
        presencePropertyKeys,
        aliasPropertyPresences,
        mapEmitColumnOrder,
        recordIdMapKeys,
        wrapMapValuesInLists,
        accumulateMap,
        unwrapSingletonMap,
        elementMapTokens,
        emitGroupEntries,
        rowDedupAlias,
        ops);
  }
}
