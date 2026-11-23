package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphQueryBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.NativeStartEvidence;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.structure.T;

/** Narrow, planning-only native source certificate for the plain index routing. */
final class NativeStartProof {

  private NativeStartProof() {
  }

  static NativeStartEvidence prove(Traversal.Admin<?, ?> traversal,
      DatabaseSessionEmbedded session) {
    if (session.getTxSchemaState() != null
        || !(traversal.getStartStep() instanceof GraphStep<?, ?> start)
        || start.getIds().length != 0) {
      return null;
    }
    var builder = new YTDBGraphQueryBuilder(true);
    String className = null;
    String property = null;
    Object value = null;
    for (int i = 1; i < traversal.getSteps().size(); i++) {
      if (!(traversal.getSteps().get(i) instanceof HasStep<?> has)) {
        break;
      }
      for (var container : has.getHasContainers()) {
        // Complete conversion only. No rejected predicates, UNION label sets or null lookups.
        if (container.getBiPredicate() != Compare.eq || container.getValue() == null) {
          return null;
        }
        var converted = builder.addCondition(container);
        if (converted == YTDBGraphQueryBuilder.ConditionType.LABEL) {
          if (className != null || !(container.getValue() instanceof String name)) {
            return null;
          }
          className = name;
        } else if (converted == YTDBGraphQueryBuilder.ConditionType.PREDICATE) {
          if (property != null || T.id.getAccessor().equals(container.getKey())) {
            return null;
          }
          property = container.getKey();
          value = container.getValue();
        } else {
          return null;
        }
      }
    }
    // The caller also requires polymorphic=true. Non-polymorphic native label residuals
    // are not equivalent to the SQL polymorphic class filter and therefore get no certificate.
    if (className == null || property == null) {
      return null;
    }
    var query = builder.build(session);
    if (query == null || !(query.explain(session) instanceof InternalExecutionPlan plan)) {
      return null;
    }
    try {
      return NativeStartEvidence.from(plan, className, property, value);
    } finally {
      plan.close();
    }
  }
}
