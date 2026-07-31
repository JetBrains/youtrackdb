package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import javax.annotation.Nonnull;

/**
 * Ordered post-concatenation reductions applied by {@link MultiPlanMatchStep} after the N child
 * plans are combined. These are the barriers that must see the <em>concatenated</em> multiset
 * ({@code count}, {@code limit}/{@code range}/{@code skip}, {@code dedup}) — not the Track 9
 * list-shaping terminators ({@code fold}/{@code unfold}/{@code reverse}/{@code tail}), which ride
 * {@link ResultShaping#listShapingOps()}.
 *
 * <p>Push-down: a lone {@link Count} rewrites each child to {@code RETURN count(*)} and sums the N
 * scalar rows (keeps per-arm SQL count / plan-cache optimisations). Any preceding stream op disables
 * that push-down so {@code union().limit(5).count()} counts at most five concatenated rows.
 */
public sealed interface PostConcatOp
    permits PostConcatOp.Count, PostConcatOp.Range, PostConcatOp.Dedup {

  /** {@code union(…).count()} — push-down when it is the only post-concat op. */
  record Count() implements PostConcatOp {
    public static final Count INSTANCE = new Count();
  }

  /**
   * {@code skip}/{@code limit}/{@code range} over the concatenation. {@code limit < 0} means
   * unbounded high (skip-only). Early-stops the concatenator so unopened children never start.
   */
  record Range(long skip, long limit) implements PostConcatOp {
    public Range {
      if (skip < 0) {
        throw new IllegalArgumentException("post-concat skip must be >= 0, got " + skip);
      }
    }
  }

  /** {@code dedup()} over concatenated ELEMENT rows (identity of the boundary entity). */
  record Dedup() implements PostConcatOp {
    public static final Dedup INSTANCE = new Dedup();
  }

  /** Whether {@code ops} is exactly one {@link Count} (the push-down shape). */
  static boolean isPushDownCountOnly(@Nonnull java.util.List<? extends PostConcatOp> ops) {
    return ops.size() == 1 && ops.getFirst() instanceof Count;
  }
}
