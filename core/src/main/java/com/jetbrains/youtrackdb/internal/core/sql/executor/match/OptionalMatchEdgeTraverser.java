package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Edge traverser that implements **optional** (LEFT JOIN) semantics for MATCH patterns.
 *
 * When a target node is declared with `optional: true`, the traversal must not discard
 * the upstream row even if no matching neighbor is found. This traverser achieves that by
 * substituting the special sentinel {@link #EMPTY_OPTIONAL} when the base traversal
 * produces no results. Downstream steps can detect the sentinel via
 * {@link #isEmptyOptional(Object)}, and the final
 * {@link RemoveEmptyOptionalsStep} replaces it with `null`.
 *
 * ### Sentinel lifecycle
 *
 * <pre>
 *   OptionalMatchEdgeTraverser             RemoveEmptyOptionalsStep
 *   ┌──────────────────────────────┐       ┌──────────────────────────────┐
 *   │ base traversal yields results│       │                              │
 *   │   YES → emit results as-is  │       │ For each property in row:    │
 *   │   NO  → emit EMPTY_OPTIONAL │──────→│   value == EMPTY_OPTIONAL?   │
 *   │         sentinel instead     │       │     YES → replace with null  │
 *   │                              │       │     NO  → keep as-is        │
 *   └──────────────────────────────┘       └──────────────────────────────┘
 * </pre>
 *
 * ### Result merging rules
 *
 * | Previous value for alias | Traversal result     | Output                                   |
 * |--------------------------|----------------------|------------------------------------------|
 * | `null`                   | record `R`           | alias → `R`                              |
 * | `null`                   | `EMPTY_OPTIONAL`     | alias → `null`                           |
 * | `EMPTY_OPTIONAL`         | any                  | upstream row unchanged (already optional) |
 * | record `P`               | record `R` (P == R)  | alias → `R`                              |
 * | record `P`               | record `R` (P != R)  | `null` (consistency violation)           |
 *
 * @see OptionalMatchStep
 * @see RemoveEmptyOptionalsStep
 */
public class OptionalMatchEdgeTraverser extends MatchEdgeTraverser {

  /**
   * Sentinel value indicating that no match was found for this optional alias.
   * Uses identity comparison (see {@link #isEmptyOptional}).
   */
  public static final Result EMPTY_OPTIONAL = new ResultInternal(null);

  public OptionalMatchEdgeTraverser(Result lastUpstreamRecord, EdgeTraversal edge) {
    super(lastUpstreamRecord, edge);
  }

  /**
   * Initializes the downstream stream and, if the base traversal yields no results,
   * replaces it with a singleton stream containing the {@link #EMPTY_OPTIONAL} sentinel.
   */
  protected void init(CommandContext ctx) {
    if (downstream == null) {
      super.init(ctx);
      if (!downstream.hasNext(ctx)) {
        // No results from the base traversal → emit the sentinel to preserve the row
        downstream = ExecutionStream.singleton(EMPTY_OPTIONAL);
      }
    }
  }

  /**
   * Returns the next result row with optional semantics. If the previously matched value
   * for this alias is the empty-optional sentinel, the upstream row is returned as-is.
   * If no traversal result was found, the alias is set to `null` in the output row.
   */
  @Nullable
  public Result next(CommandContext ctx) {
    init(ctx);
    if (!downstream.hasNext(ctx)) {
      throw new IllegalStateException();
    }

    var endPointAlias = getEndpointAlias();
    var prevValue = sourceRecord.getProperty(endPointAlias);
    var next = downstream.next(ctx);

    // Decision tree for optional alias merging:
    //
    //   prevValue?
    //     |
    //     +-- EMPTY_OPTIONAL --> return upstream row unchanged
    //     |
    //     +-- null
    //     |     |
    //     |     +-- next is EMPTY_OPTIONAL --> set alias = null
    //     |     +-- next is record R       --> set alias = R
    //     |
    //     +-- record P
    //           |
    //           +-- next == P --> set alias = P  (join satisfied)
    //           +-- next != P --> return null     (consistency violation)

    // If the alias was already set to EMPTY_OPTIONAL by a previous optional traversal,
    // propagate the upstream row unchanged
    if (isEmptyOptional(prevValue)) {
      return sourceRecord;
    }
    // Consistency check: if the alias was previously bound to a real value and the new
    // traversal produced a different value, reject this combination
    if (!isEmptyOptional(next)) {
      if (prevValue != null && !Objects.equals(prevValue, next)) {
        return null;
      }
    }

    var session = ctx.getDatabaseSession();
    var result = new ResultInternal(session);
    for (var prop : sourceRecord.getPropertyNames()) {
      result.setProperty(prop, sourceRecord.getProperty(prop));
    }
    if (next.isEntity()) {
      // The traversal found a matching entity — bind it to the alias
      result.setProperty(endPointAlias, toResult(session, next.asEntity()));
    } else if (next.isRelation()) {
      // The traversal found a matching relation (edge record) — bind it to the alias
      result.setProperty(endPointAlias,
          ResultInternal.toResultInternal(next.asRelation(), ctx.getDatabaseSession(),
              null));
    } else {
      // EMPTY_OPTIONAL sentinel or an unrecognized result type — set alias to null.
      // The sentinel case is the normal "no match found" path for optional nodes;
      // RemoveEmptyOptionalsStep will finalize the null later.
      result.setProperty(endPointAlias, null);
    }

    return result;
  }

  /**
   * Tests whether the given value is the {@link #EMPTY_OPTIONAL} sentinel using
   * **identity** comparison (not `equals()`).
   */
  public static boolean isEmptyOptional(Object elem) {
    return elem == EMPTY_OPTIONAL;
  }
}
