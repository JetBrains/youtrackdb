package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.lambda;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.AbstractLambdaTraversal;

/**
 * A {@code by(...)} modulator that projects whatever it receives to a {@link RecordIdSortKey}. The
 * projection is total: it never declines a start and never throws, so a stream the installing
 * strategy typed wrongly loses ordering strength instead of failing the query. That is the whole
 * difference from {@code by(T.id)}, which rejects a start that is neither an element nor a property.
 *
 * <p>Installed by {@code YTDBOrderRidTieBreakStrategy}, mapped to a MATCH {@code @rid} sort item by
 * {@code ByModulatorTranslator}, and encoded by {@code GremlinShapeExtractor} so a shape carrying
 * this modulator stays cacheable.
 *
 * <p>No bypass traversal is honoured, and none can be installed: {@code ProductiveByStrategy} wraps
 * a {@link org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal} alone, and this
 * projection has no absent case for such a wrap to fill — a value with no record identifier already
 * projects to {@link RecordIdSortKey#absent()} rather than declining the start.
 */
public final class RecordIdSortKeyTraversal<S> extends AbstractLambdaTraversal<S, RecordIdSortKey> {

  /** The projection of the most recent start, read back by {@link #next()}. */
  private RecordIdSortKey key = RecordIdSortKey.absent();

  @Override
  public RecordIdSortKey next() {
    return key;
  }

  @Override
  public void addStart(Traverser.Admin<S> start) {
    key = RecordIdSortKey.of(start.get());
  }

  /** Stable text form — the shape cache and the step renderers both key on a modulator's text. */
  @Override
  public String toString() {
    return "ridSortKey";
  }
}
