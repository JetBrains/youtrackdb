package com.jetbrain.youtrack.db.gremlin.internal;

import com.jetbrain.youtrack.db.gremlin.api.YTDBGraph;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class YTDBGraphQueryBuilder {

  private static final Logger logger = LoggerFactory.getLogger(YTDBGraphQueryBuilder.class);

  private final boolean vertexStep;
  private List<String> classes = new ArrayList<>();

  private final Map<String, P<?>> params = new LinkedHashMap<>();

  public YTDBGraphQueryBuilder(boolean vertexStep) {
    this.vertexStep = vertexStep;
  }

  public void addCondition(HasContainer condition) {
    if (isLabelKey(condition.getKey())) {
      Object value = condition.getValue();
      if (value instanceof List<?> list) {
        list.forEach(label -> addClass((String) label));
      } else {
        addClass((String) value);
      }
    } else {
      params.put(condition.getKey(), condition.getPredicate());
    }
  }

  private void addClass(String classLabel) {
    if (!classes.contains(classLabel)) {
      classes.add(classLabel);
    }
  }

  @Nullable
  public YTDBGraphBaseQuery build(YTDBGraphInternal graph) {
    var session = graph.getUnderlyingSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    assert schema != null;

    if (classes.isEmpty()) {
      classes.add(vertexStep ? SchemaClass.VERTEX_CLASS_NAME : SchemaClass.EDGE_CLASS_NAME);
    } else {
      classes = classes.stream().filter(schema::existsClass).collect(Collectors.toList());
      if (classes.isEmpty()) {
        return new YTDBGraphEmptyQuery();
      }
    }

    try {
      StringBuilder builder = new StringBuilder();

      Map<String, Object> parameters = new HashMap<>();
      String whereCondition = fillParameters(parameters);
      if (classes.size() > 1) {
        builder.append("SELECT expand($union) ");
        String lets =
            classes.stream()
                .map(
                    (s) ->
                        buildLetStatement(buildSingleQuery(s, whereCondition), classes.indexOf(s)))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " , " + b);
        builder.append(
            String.format("%s , $union = UNIONALL(%s)", lets, buildVariables()));
      } else {
        builder.append(buildSingleQuery(classes.getFirst(), whereCondition));
      }
      return new YTDBGraphQuery(builder.toString(), parameters, classes.size());
    } catch (UnsupportedOperationException e) {
      if (logger.isDebugEnabled()) {
        LogManager.instance().debug(this, "Cannot generate a query from the traversal", logger, e);
      }
    }

    return null;
  }

  private String fillParameters(Map<String, Object> parameters) {
    StringBuilder whereBuilder = new StringBuilder();

    if (!params.isEmpty()) {
      whereBuilder.append(" WHERE ");
      boolean[] first = {true};

      AtomicInteger paramNum = new AtomicInteger();
      params
          .forEach((key, value) -> {
            String param = "param" + paramNum.getAndIncrement();
            String cond = formatCondition(key, param, value);
            if (first[0]) {
              whereBuilder.append(" ").append(cond);
              first[0] = false;
            } else {
              whereBuilder.append(" AND ").append(cond);
            }
            parameters.put(param, value.getValue());
          });
    }
    return whereBuilder.toString();
  }

  private String buildVariables() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < classes.size(); i++) {
      builder.append(String.format("$q%d", i));
      if (i < classes.size() - 1) {
        builder.append(",");
      }
    }
    return builder.toString();
  }

  protected String buildLetStatement(String query, int idx) {
    StringBuilder builder = new StringBuilder();
    if (idx == 0) {
      builder.append("LET ");
    }
    builder.append(String.format("$q%d = (%s)", idx, query));
    return builder.toString();
  }

  protected String buildSingleQuery(String clazz, String whereCondition) {
    return String.format("SELECT FROM `%s` %s", clazz, whereCondition);
  }

  private String formatCondition(String field, String param, P<?> predicate) {
    if (T.id.getAccessor().equalsIgnoreCase(field)) {
      return String.format(" %s %s :%s", "@rid", formatPredicate(predicate), param);
    } else {
      return String.format(" `%s` %s :%s", field, formatPredicate(predicate), param);
    }
  }

  private String formatPredicate(P<?> cond) {
    if (cond.getBiPredicate() instanceof Compare compare) {
      return switch (compare) {
        case eq -> "=";
        case gt -> ">";
        case gte -> ">=";
        case lt -> "<";
        case lte -> "<=";
        case neq -> "<>";
      };
    } else if (cond.getBiPredicate() instanceof Contains contains) {
      return switch (contains) {
        case within -> "IN";
        case without -> "NOT IN";
      };
    }

    throw new UnsupportedOperationException("Predicate not supported!");
  }

  private boolean isLabelKey(String key) {
    try {
      return T.fromString(key) == T.label;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
