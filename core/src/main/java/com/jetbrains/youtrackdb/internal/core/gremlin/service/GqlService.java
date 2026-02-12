package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// TinkerPop service for executing GQL (Graph Query Language) queries.
///
/// Executes GQL queries directly using GqlExecutionPlan, supporting all query types
/// (MATCH, RETURN, etc.) that the planner can handle.
///
/// - `MATCH (a:Person)` → returns Map<String, Object> with {"a": vertex}
///
/// Supports both Start and Streaming execution modes to allow chaining.
public class GqlService implements Service<Object, Object> {

  public static final String NAME = "gql";
  public static final String QUERY = "query";
  public static final String ARGUMENTS = "args";

  private final String query;
  private final Type type;

  @SuppressWarnings("unused")
  public GqlService(String query, Map<?, ?> arguments, Type type) {
    this.query = query;
    this.type = type;
  }

  public static class Factory implements ServiceFactory<Object, Object> {

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      // Support both Start and Streaming to allow chaining
      return Set.of(Type.Start, Type.Streaming);
    }

    @Override
    public Service<Object, Object> createService(boolean isStart, Map params) {
      final Map<?, ?> safeParams = (params == null) ? Map.of() : params;
      var queryString = "";
      Map<?, ?> queryArgs = Map.of();

      if (safeParams.get(QUERY) instanceof String q) {
        queryString = q;
      }
      if (safeParams.get(ARGUMENTS) instanceof Map<?, ?> args) {
        queryArgs = args;
      } else if (safeParams.get(ARGUMENTS) instanceof java.util.List<?> argsList
          && !argsList.isEmpty()) {
        if (argsList.getFirst() instanceof String q) {
          queryString = q;
          if (argsList.size() > 1) {
            var rest = argsList.size() - 1;
            if (rest % 2 != 0) {
              throw new IllegalArgumentException("Arguments must be provided in key-value pairs");
            }
            var map = new java.util.LinkedHashMap<>();
            for (var i = 1; i + 1 < argsList.size(); i += 2) {
              map.put(argsList.get(i), argsList.get(i + 1));
            }
            queryArgs = map;
          }
        }
      }

      return new GqlService(queryString, queryArgs, isStart ? Type.Start : Type.Streaming);
    }
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<Object> execute(ServiceCallContext ctx, Map params) {
    if (query.isEmpty()) {
      return CloseableIterator.empty();
    }

    GqlExecutionPlan executionPlan = null;
    GqlExecutionStream stream = null;

    try {
      // 1. Get graph and session from traversal context
      var traversal = (Traversal.Admin<?, ?>) ctx.getTraversal();
      var graph = (YTDBGraphInternal) traversal.getGraph().orElseThrow();
      var graphTx = graph.tx();
      graphTx.readWrite();
      var session = graphTx.getDatabaseSession();

      // 2. Get the query statement (from cache if available)
      var statement = GqlPlanner.getStatement(query, session);

      // 3. Create execution context
      var executionCtx = new GqlExecutionContext(graph, session);

      // 4. Create execution plan from statement
      executionPlan = statement.createExecutionPlan(executionCtx);

      // 5. Execute and return streaming result
      stream = executionPlan.start(executionCtx);
      return new GqlResultIterator(stream, executionPlan);
    } catch (Exception e) {
      if (stream != null) {
        stream.close();
      }
      if (executionPlan != null) {
        executionPlan.close();
      }
      throw e;
    }
  }

  /// Streaming iterator that wraps GqlExecutionStream for lazy result consumption. Handles any
  /// query type, not just MATCH.
  private record GqlResultIterator(GqlExecutionStream stream, GqlExecutionPlan plan) implements
      CloseableIterator<Object> {

    @Override
    public boolean hasNext() {
      try {
        final var hasNext = stream.hasNext();
        if (!hasNext) {
          close();
        }
        return hasNext;
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    @Override
    public Object next() {
      try {
        return stream.next();
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    @Override
    public void close() {
      stream.close();
      plan.close();
    }
  }

  @Override
  public CloseableIterator<Object> execute(
      ServiceCallContext ctx,
      Traverser.Admin<Object> in,
      Map params) {
    // For Streaming mode, execute the query (input traverser is ignored for now)
    return execute(ctx, params);
  }
}
