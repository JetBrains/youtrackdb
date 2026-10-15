package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Builds a secured RID superset. Only the retained forward checks decide whether a source passes. */
final class KnownEndpointExistsWalk {

  private final KnownEndpointExistsAccess access;
  private final KnownEndpointExistsStep.Counters counters;
  private final KnownEndpointExistsCost.WorkBudget budget;
  private final KnownEndpointExistsStep.FieldPolicies fieldPolicies;
  private final List<List<String>> labelsByHop;

  KnownEndpointExistsWalk(KnownEndpointExistsAccess access,
      KnownEndpointExistsStep.Counters counters, KnownEndpointExistsCost.WorkBudget budget,
      KnownEndpointExistsStep.FieldPolicies fieldPolicies, List<List<String>> labelsByHop) {
    this.access = access;
    this.counters = counters;
    this.budget = budget;
    this.fieldPolicies = fieldPolicies;
    this.labelsByHop = labelsByHop;
  }

  @Nullable Set<RecordIdInternal> collect(CommandContext ctx, List<LinkBag> initial) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var bags = initial;
    Set<RecordIdInternal> identities = new LinkedHashSet<>();
    for (int i = access.hops.size() - 1; i >= 0; i--) {
      var hop = access.hops.get(i);
      identities = new LinkedHashSet<>();
      var edgeFilter = hop.edge() == null ? null : PreparedFilter.of(ctx, hop.edge().getFilter());
      var endpoint = hop.forward() == Direction.OUT ? "out" : "in";
      var endpointPolicies = new HashMap<String, Boolean>();
      for (var bag : bags) {
        var iterator = bag.iterator();
        while (true) {
          budget.charge(1);
          if (!iterator.hasNext()) {
            break;
          }
          var pair = iterator.next();
          counters.edgeReads++;
          if (pair.primaryRid().equals(pair.secondaryRid())) {
            return fallback("legacy edge entry");
          }
          var source = (RecordIdInternal) pair.secondaryRid();
          if (schema.getClassByCollectionId(source.getCollectionId()) == null) {
            return fallback("unknown candidate collection");
          }
          if (hop.edge() != null) {
            budget.charge(1);
            var loaded = session.executeReadRecord((RecordIdInternal) pair.primaryRid(), null,
                false);
            // Unknown edge visibility or storage must not turn a forward error into an empty
            // candidate set. Read failures reach the shared guard, including interrupt causes.
            if (!(loaded instanceof EntityImpl edge) || !edge.isEdge()) {
              return fallback("edge unavailable");
            }
            // Endpoint fields can hide the relationship even when the adjacency is a LinkBag.
            if (endpointPolicies.computeIfAbsent(edge.getSchemaClassName(),
                className -> session.getSharedContext().getSecurity()
                    .isReadRestrictedBySecurityPolicy(session,
                        "database.class." + className + "." + endpoint))) {
              return fallback("edge endpoint read policy");
            }
            if (!edgeFilter.matches(ctx, edge)) {
              continue;
            }
          }
          budget.charge(1);
          identities.add(source);
        }
      }
      if (i == 0) {
        break;
      }
      var previous = access.hops.get(i - 1);
      bags = new ArrayList<>();
      var reverse = previous.forward() == Direction.OUT ? Direction.IN
          : previous.forward() == Direction.IN ? Direction.OUT : Direction.BOTH;
      var labelList = labelsByHop.get(i - 1);
      var labels = labelList.toArray(String[]::new);
      var fixedProperties = labels.length == 0 ? List.<String>of()
          : VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, reverse, labels);
      var middleFilter = PreparedFilter.of(ctx, previous.vertex().getFilter());
      String upstream = i == 1 ? access.sourceClass : "V";
      for (var rid : identities) {
        budget.charge(1);
        RecordAbstract record = session.executeReadRecord(rid, null, false);
        // A forward local filter can raise on a hidden middle record. Leave that outcome to the
        // current path rather than silently proving that no candidate can match.
        if (!(record instanceof VertexEntityImpl vertex)) {
          return fallback("middle unavailable");
        }
        if (!middleFilter.matches(ctx, vertex)) {
          continue;
        }
        if (fieldPolicies.has(upstream, previous.forward(), labelList)
            || fieldPolicies.has(vertex.getSchemaClassName(), reverse, labelList)) {
          return fallback("edge-list read policy");
        }
        var properties = labels.length == 0 ? vertex.getEdgeNames(reverse) : fixedProperties;
        for (var property : properties) {
          budget.charge(1);
          if (VertexEntityImpl.getConnection(schema, reverse, property, labels) == null) {
            continue;
          }
          var value = vertex.getPropertyInternal(property);
          if (value == null) {
            continue;
          }
          if (!(value instanceof LinkBag bag) || !bag.isSizeable()) {
            return fallback("edge-list storage form");
          }
          bags.add(bag);
        }
      }
    }
    return identities;
  }

  /** Fixed filter metadata is prepared per hop, not rendered or resolved per record. */
  private record PreparedFilter(SQLWhereClause where, String className, SQLRid rid) {
    static PreparedFilter of(CommandContext ctx, SQLMatchFilter filter) {
      var where = filter.getFilter();
      var rid = filter.getRid(ctx);
      // Context-dependent filters remain at their forward position, never in discovery.
      return new PreparedFilter(where == null || where.toString().contains("$") ? null : where,
          filter.getClassName(ctx), rid == null || rid.toString().contains("$") ? null : rid);
    }

    boolean matches(CommandContext ctx, EntityImpl record) {
      var result = new ResultInternal(ctx.getDatabaseSession(), record);
      var previous = ctx.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
      try {
        ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, result);
        return MatchEdgeTraverser.matchesFilters(ctx, where, result)
            && MatchEdgeTraverser.matchesClass(ctx, className, result)
            && MatchEdgeTraverser.matchesRid(ctx, rid, result);
      } finally {
        ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, previous);
      }
    }
  }

  @Nullable private Set<RecordIdInternal> fallback(String reason) {
    counters.reason = reason;
    return null;
  }
}
