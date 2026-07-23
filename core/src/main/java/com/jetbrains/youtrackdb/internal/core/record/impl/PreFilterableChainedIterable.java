package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * A {@link PreFilterableLinkBagIterable} that chains multiple sub-iterables and delegates
 * filter operations to each one independently.
 *
 * <p>This is used to make bidirectional traversals ({@code both()} and {@code bothE()}) eligible
 * for the index-assisted pre-filter. For unidirectional traversals ({@code out}/{@code in},
 * {@code outE}/{@code inE}), each traversal produces a single
 * {@link PreFilterableLinkBagIterable} backed by one LinkBag. For bidirectional traversals, the
 * result spans two LinkBags (one per direction), which were previously chained via
 * {@code IterableUtils.chainedIterable()} — a type that does not implement
 * {@code PreFilterableLinkBagIterable} and therefore silently bypasses pre-filtering in
 * {@code MatchEdgeTraverser.applyPreFilter()} and {@code ExpandStep.nextResults()}.
 *
 * <p>When {@link #withClassFilter(IntSet)} or {@link #withRidFilter(Set)} is called, a new
 * {@code PreFilterableChainedIterable} is returned with the filter applied to each sub-iterable
 * independently. Because each sub-iterable is backed by a single LinkBag, the filter semantics
 * are identical to the single-direction case — only in-memory RID checks, no disk I/O.
 *
 * <p>{@link #size()} returns the sum of sub-iterable sizes, which is the total number of records
 * that would be loaded without any filtering. The adaptive abort guards in
 * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.match.TraversalPreFilterHelper}
 * use this value to decide whether pre-filtering is cost-effective.
 */
public class PreFilterableChainedIterable
    implements PreFilterableLinkBagIterable, Iterable<Object> {

  private final PreFilterableLinkBagIterable[] subs;

  /**
   * Creates a chained iterable wrapping the given sub-iterables. The caller must ensure that at
   * least one sub-iterable is provided.
   *
   * @param subs the sub-iterables to chain; must not be empty
   */
  public PreFilterableChainedIterable(@Nonnull PreFilterableLinkBagIterable... subs) {
    assert subs.length > 0 : "PreFilterableChainedIterable requires at least one sub-iterable";
    this.subs = subs.clone();
  }

  @Nonnull
  @Override
  public PreFilterableChainedIterable withClassFilter(@Nonnull IntSet collectionIds) {
    var filtered = new PreFilterableLinkBagIterable[subs.length];
    for (var i = 0; i < subs.length; i++) {
      filtered[i] = subs[i].withClassFilter(collectionIds);
    }
    return new PreFilterableChainedIterable(filtered);
  }

  @Nonnull
  @Override
  public PreFilterableChainedIterable withRidFilter(@Nonnull Set<RID> ridSet) {
    var filtered = new PreFilterableLinkBagIterable[subs.length];
    for (var i = 0; i < subs.length; i++) {
      filtered[i] = subs[i].withRidFilter(ridSet);
    }
    return new PreFilterableChainedIterable(filtered);
  }

  /**
   * Returns an iterator that visits elements from each sub-iterable in order.
   *
   * <p>The iterator is typed as {@code Iterator<Object>} because sub-iterables may
   * yield different concrete types (e.g. {@code Vertex} vs {@code EdgeInternal}). Callers that
   * need typed access should cast after verifying all sub-iterables are homogeneous — which is
   * always the case in practice (both directions of a {@code both()}/{@code bothE()} traversal
   * yield the same element type). The unchecked cast at the call site mirrors the pre-existing
   * pattern used by {@code IterableUtils.chainedIterable}.
   */
  @Nonnull
  @Override
  public Iterator<Object> iterator() {
    return new ChainedIterator(subs);
  }

  /**
   * Returns the sum of all sub-iterable sizes. This represents the total number of records that
   * would be loaded if no pre-filter is applied.
   */
  @Override
  public int size() {
    var total = 0;
    for (var sub : subs) {
      total += sub.size();
    }
    return total;
  }

  /**
   * Returns {@code true} when all sub-iterables report that their size is exact (i.e., all
   * sub-iterables are backed by LinkBags with known sizes).
   */
  @Override
  public boolean isSizeable() {
    for (var sub : subs) {
      if (!sub.isSizeable()) {
        return false;
      }
    }
    return true;
  }

  /**
   * A minimal iterator that drains sub-iterables in sequence without requiring them to implement
   * {@link Iterable}. Only the {@link PreFilterableLinkBagIterable#iterator()} contract is used.
   */
  private static final class ChainedIterator implements Iterator<Object> {

    private final PreFilterableLinkBagIterable[] subs;
    private int subIndex = 0;
    private Iterator<?> current;

    ChainedIterator(PreFilterableLinkBagIterable[] subs) {
      this.subs = subs;
      this.current = subs[0].iterator();
      advance();
    }

    /** Skips exhausted iterators until we find one with remaining elements or run out. */
    private void advance() {
      while (!current.hasNext() && subIndex + 1 < subs.length) {
        current = subs[++subIndex].iterator();
      }
    }

    @Override
    public boolean hasNext() {
      return current.hasNext();
    }

    @Override
    public Object next() {
      if (!current.hasNext()) {
        throw new NoSuchElementException();
      }
      var value = current.next();
      if (!current.hasNext()) {
        advance();
      }
      return value;
    }
  }
}
