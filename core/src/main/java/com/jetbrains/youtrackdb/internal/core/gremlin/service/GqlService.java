package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// TinkerPop service for executing GQL (Graph Query Language) queries.
///
/// Supports both Start and Streaming execution modes to allow chaining.
public class GqlService implements Service<Object, Map<String, Object>> {

  public static final String NAME = "gql";
  public static final String QUERY = "query";
  public static final String ARGUMENTS = "args";

  private final String query;
  private final Map<?, ?> arguments;
  private final Type type;

  public GqlService(String query, Map<?, ?> arguments, Type type) {
    this.query = query;
    this.arguments = arguments;
    this.type = type;
  }

  public static class Factory implements ServiceFactory<Object, Map<String, Object>> {

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
    public Service<Object, Map<String, Object>> createService(boolean isStart, Map params) {
      final Map<?, ?> safeParams = (params == null) ? Map.of() : params;
      String queryString = "";
      Map<?, ?> queryArgs = Map.of();

      if (safeParams.get(QUERY) instanceof String q) {
        queryString = q;
      }
      if (safeParams.get(ARGUMENTS) instanceof Map<?, ?> args) {
        queryArgs = args;
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
  public CloseableIterator<Map<String, Object>> execute(ServiceCallContext ctx, Map params) {
    if (query.isEmpty()) {
      return CloseableIterator.empty();
    }

    // 1. Get traversal from context
    var traversal = (Traversal.Admin<?, ?>) ctx.getTraversal();

    // 2. Plan the query (parse + create step)
    var planner = new GqlPlanner();
    var matchStep = planner.plan(query, traversal);

    // 3. Collect results from the step
    var results = new ArrayList<Map<String, Object>>();
    while (matchStep.hasNext()) {
      results.add(matchStep.next().get());
    }

    return CloseableIterator.of(results.iterator());
  }

  @Override
  public CloseableIterator<Map<String, Object>> execute(
      ServiceCallContext ctx,
      Traverser.Admin<Object> in,
      Map params) {
    // For Streaming mode, execute the query (input traverser is ignored for now)
    return execute(ctx, params);
  }
}
