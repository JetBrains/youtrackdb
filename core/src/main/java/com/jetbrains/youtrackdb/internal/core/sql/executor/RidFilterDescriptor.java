package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import javax.annotation.Nullable;

/**
 * Describes a RID-based pre-filter that can be resolved at execution time
 * to a set of accepted RIDs. Used by {@link ExpandStep} to skip
 * non-matching vertices without loading them from storage.
 *
 * <p>Two variants are supported:
 * <ul>
 *   <li>{@link DirectRid} — {@code @rid = <expr>}
 *   <li>{@link EdgeRidLookup} — {@code out/in('EdgeClass').@rid = <expr>}
 * </ul>
 */
sealed interface RidFilterDescriptor {

  int REVERSE_LOOKUP_CAP = 100_000;

  /**
   * Resolves this descriptor against the current execution context
   * (which may contain {@code $parent} references) and returns a
   * {@link RidSet} of accepted vertex RIDs, or {@code null} if
   * resolution fails or yields too many results.
   */
  @Nullable RidSet resolve(CommandContext ctx);

  /**
   * Direct RID equality: {@code WHERE @rid = <expr>}.
   * Resolves the expression to a single RID and returns a singleton set.
   */
  record DirectRid(SQLExpression ridExpression) implements RidFilterDescriptor {
    @Override
    @Nullable public RidSet resolve(CommandContext ctx) {
      var value = ridExpression.execute((Result) null, ctx);
      RID rid = toRid(value);
      if (rid == null) {
        return null;
      }
      var set = new RidSet();
      set.add(rid);
      return set;
    }
  }

  /**
   * Reverse edge lookup: {@code WHERE out/in('EdgeClass').@rid = <expr>}.
   *
   * <p>Resolves the target RID from the expression, loads the target vertex,
   * reads the reverse link bag (e.g. for {@code out('X').@rid = Y}, reads
   * the {@code in_X} field on vertex Y), and collects the secondary RIDs
   * (the vertices on the other side of each edge) into a {@link RidSet}.
   */
  record EdgeRidLookup(
      String edgeClassName,
      String traversalDirection,
      SQLExpression targetRidExpression) implements RidFilterDescriptor {

    @Override
    @Nullable public RidSet resolve(CommandContext ctx) {
      var value = targetRidExpression.execute((Result) null, ctx);
      RID targetRid = toRid(value);
      if (targetRid == null) {
        return null;
      }

      var db = ctx.getDatabaseSession();
      EntityImpl targetEntity;
      try {
        var rec = db.getActiveTransaction().load(targetRid);
        if (!(rec instanceof EntityImpl entity)) {
          return null;
        }
        targetEntity = entity;
      } catch (Exception ignored) {
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
        if (++count > REVERSE_LOOKUP_CAP) {
          return null;
        }
      }
      return ridSet;
    }
  }

  @Nullable private static RID toRid(@Nullable Object value) {
    if (value instanceof RID rid) {
      return rid;
    }
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity();
    }
    return null;
  }
}
