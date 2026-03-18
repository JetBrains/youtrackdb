package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Centralised utility for pre-filter operations shared by both the SELECT
 * engine ({@link ExpandStep}, {@link SelectExecutionPlanner}) and the MATCH
 * engine ({@code MatchEdgeTraverser}, {@code MatchExecutionPlanner}).
 *
 * <p>Consolidates logic that was previously scattered across iterator,
 * execution-step, and planner classes, eliminating tight coupling between
 * query planning and specific iterator/step implementations.
 */
public final class TraversalPreFilterHelper {

  /** Maximum number of RIDs collected from an index or reverse edge lookup. */
  public static final int RIDSET_SIZE_CAP = 100_000;

  private TraversalPreFilterHelper() {
  }

  /**
   * Returns the set of collection (cluster) IDs for the given class and all
   * its subclasses. Used to build a class-based pre-filter that skips
   * vertices whose RID belongs to a different collection without disk I/O.
   */
  @Nonnull
  public static IntSet collectionIdsForClass(@Nonnull SchemaClass clazz) {
    var ids = clazz.getPolymorphicCollectionIds();
    var set = new IntOpenHashSet(ids.length);
    for (var id : ids) {
      set.add(id);
    }
    return set;
  }

  /**
   * Queries the index described by the given descriptor and collects
   * matching RIDs into a {@link RidSet}. Returns {@code null} if the
   * index returned too many entries (exceeding {@link #RIDSET_SIZE_CAP})
   * or the query produced no streams.
   */
  @Nullable public static RidSet resolveIndexToRidSet(
      IndexSearchDescriptor desc, CommandContext ctx) {
    List<Stream<RawPair<Object, RID>>> streams;
    streams = FetchFromIndexStep.init(desc, true, ctx);
    if (streams.isEmpty()) {
      return null;
    }

    var ridSet = new RidSet();
    var count = 0;
    var exceeded = false;
    try {
      for (var stream : streams) {
        var iter = stream.iterator();
        while (iter.hasNext()) {
          ridSet.add(iter.next().second());
          if (++count > RIDSET_SIZE_CAP) {
            exceeded = true;
            break;
          }
        }
        if (exceeded) {
          break;
        }
      }
    } finally {
      for (var stream : streams) {
        stream.close();
      }
    }
    return exceeded ? null : ridSet;
  }

  /**
   * Loads the vertex identified by {@code targetRid}, reads the reverse
   * link bag for the given edge class, and collects the secondary RIDs
   * (the vertices on the other side of each edge) into a {@link RidSet}.
   *
   * <p>For example, if {@code traversalDirection} is {@code "out"} and
   * {@code edgeClassName} is {@code "HAS_CREATOR"}, reads the
   * {@code in_HAS_CREATOR} field on the target vertex.
   *
   * @param targetRid          RID of the vertex whose reverse edges to read
   * @param edgeClassName      the edge class name (e.g. {@code "HAS_CREATOR"})
   * @param traversalDirection the direction of the original edge
   *                           ({@code "out"} or {@code "in"})
   * @param ctx                command context for transaction access
   * @return the set of opposite-side vertex RIDs, or {@code null} if
   *     resolution fails or exceeds {@link #RIDSET_SIZE_CAP}
   */
  @Nullable public static RidSet resolveReverseEdgeLookup(
      RID targetRid,
      String edgeClassName,
      String traversalDirection,
      CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    EntityImpl targetEntity;
    try {
      var rec = db.getActiveTransaction().load(targetRid);
      if (!(rec instanceof EntityImpl entity)) {
        return null;
      }
      targetEntity = entity;
    } catch (RecordNotFoundException e) {
      return null;
    }

    var reversePrefix = "out".equals(traversalDirection) ? "in_" : "out_";
    var fieldName = reversePrefix + edgeClassName;
    var fieldValue = targetEntity.getPropertyInternal(fieldName);
    if (!(fieldValue instanceof LinkBag linkBag)) {
      return null;
    }

    var ridSet = new RidSet();
    var count = 0;
    for (RidPair pair : linkBag) {
      ridSet.add(pair.secondaryRid());
      if (++count > RIDSET_SIZE_CAP) {
        return null;
      }
    }
    return ridSet;
  }

  /**
   * Attempts to find the best index for the given WHERE clause on the
   * specified target class. Only single-OR-branch WHERE clauses are
   * considered (multi-branch OR is too complex for this optimisation).
   *
   * @param pushDownWhere the WHERE clause to analyse (should not reference
   *                      {@code $parent} or {@code $matched})
   * @param className     the target class name
   * @param ctx           command context
   * @return an index descriptor, or {@code null} if no suitable index exists
   */
  @Nullable public static IndexSearchDescriptor findIndexForFilter(
      SQLWhereClause pushDownWhere, String className, CommandContext ctx) {
    if (ctx == null || ctx.getDatabaseSession() == null) {
      return null;
    }
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    var schemaClass = schema.getClassInternal(className);
    if (schemaClass == null) {
      return null;
    }
    var indexes = schemaClass.getIndexesInternal();
    if (indexes.isEmpty()) {
      return null;
    }
    var flatWhere = pushDownWhere.flatten(ctx, schemaClass);
    if (flatWhere.size() != 1) {
      return null;
    }
    return SelectExecutionPlanner.findBestIndexFor(
        ctx, indexes, flatWhere.getFirst(), schemaClass);
  }

  /**
   * Converts a raw value (which may be a {@link RID} or an
   * {@link Identifiable}) to a {@link RID}, or returns {@code null}.
   */
  @Nullable public static RID toRid(@Nullable Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }
    return null;
  }

  /**
   * Returns the intersection of two RID sets using bitmap-level
   * {@link RidSet#intersect}. If either input is {@code null},
   * returns the other. If both are {@code null}, returns {@code null}.
   */
  @Nullable public static RidSet intersect(@Nullable RidSet a, @Nullable RidSet b) {
    return RidSet.intersect(a, b);
  }
}
