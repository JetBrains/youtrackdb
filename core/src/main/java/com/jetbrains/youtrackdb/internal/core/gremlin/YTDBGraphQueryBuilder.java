package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
      var value = condition.getValue();
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
    if (classLabel != null && !classLabel.isEmpty() && !classes.contains(classLabel)) {
      classes.add(classLabel);
    }
  }

  @Nullable
  public YTDBGraphBaseQuery build(DatabaseSessionEmbedded session) {
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
      var builder = new StringBuilder();

      Map<String, Object> parameters = new HashMap<>();
      var whereCondition = fillParameters(parameters);
      if (classes.size() > 1) {
        builder.append("SELECT expand($union) ");
        var lets =
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
    var whereBuilder = new StringBuilder();

    if (!params.isEmpty()) {
      whereBuilder.append(" WHERE ");
      var first = new boolean[]{true};

      var paramNum = new AtomicInteger();
      params
          .forEach((key, value) -> {
            var param = "param" + paramNum.getAndIncrement();
            var cond = formatCondition(key, param, value);
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
    var builder = new StringBuilder();
    for (var i = 0; i < classes.size(); i++) {
      builder.append(String.format("$q%d", i));
      if (i < classes.size() - 1) {
        builder.append(",");
      }
    }
    return builder.toString();
  }

  protected static String buildLetStatement(String query, int idx) {
    var builder = new StringBuilder();
    if (idx == 0) {
      builder.append("LET ");
    }
    builder.append(String.format("$q%d = (%s)", idx, query));
    return builder.toString();
  }

  protected static String buildSingleQuery(String clazz, String whereCondition) {
    return String.format("SELECT FROM `%s` %s", clazz, whereCondition);
  }

  private static String formatCondition(String field, String param, P<?> predicate) {
    if (T.id.getAccessor().equalsIgnoreCase(field)) {
      return String.format(" %s %s :%s", "@rid", formatPredicate(predicate), param);
    } else {
      return String.format(" `%s` %s :%s", field, formatPredicate(predicate), param);
    }
  }

  private static String formatPredicate(P<?> cond) {
    if (cond.getBiPredicate() instanceof Compare compare) {
      return switch (compare) {
        case Compare.eq -> "=";
        case Compare.gt -> ">";
        case Compare.gte -> ">=";
        case Compare.lt -> "<";
        case Compare.lte -> "<=";
        case Compare.neq -> "<>";
      };
    } else if (cond.getBiPredicate() instanceof Contains contains) {
      return switch (contains) {
        case Contains.within -> "IN";
        case Contains.without -> "NOT IN";
      };
    }

    throw new UnsupportedOperationException("Predicate not supported!");
  }

  private static boolean isLabelKey(String key) {
    try {
      if (key == null || key.isEmpty()) {
        return false;
      }

      return T.fromString(key) == T.label;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
