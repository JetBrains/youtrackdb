package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;

public class YTDBGraphQueryBuilder {

  private final boolean vertexStep;
  private final List<String> classes = new ArrayList<>();
  private final List<Param> params = new ArrayList<>();

  public YTDBGraphQueryBuilder(boolean vertexStep, List<HasContainer> conditions) {
    this.vertexStep = vertexStep;
    conditions.forEach(this::addCondition);
  }

  public YTDBGraphQueryBuilder(boolean vertexStep) {
    this(vertexStep, List.of());
  }

  public ConditionType addCondition(HasContainer condition) {
    final var predicate = condition.getBiPredicate();
    if (isLabelKey(condition.getKey())) {
      if (predicate != Compare.eq && predicate != Contains.within) {
        // do we want to support conditions like "has(label, TextP.startingWith())" ?
        throw new IllegalArgumentException("Label condition not supported: " + predicate);
      }
      final var value = condition.getValue();
      if (value instanceof List<?> list) {
        list.forEach(label -> addClass((String) label));
      } else {
        addClass((String) value);
      }
      return ConditionType.LABEL;
    } else {
      final var predicateStr = formatPredicate(predicate);
      if (predicateStr == null) {
        return ConditionType.NOT_CONVERTED;
      } else {
        params.add(new Param(
            condition.getKey(), predicateStr,
            requiresAdditionalNotNull(predicate), condition.getValue()
        ));
        return ConditionType.PREDICATE;
      }
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

    final var classList =
        classes.isEmpty() ?
            List.of(vertexStep ? SchemaClass.VERTEX_CLASS_NAME : SchemaClass.EDGE_CLASS_NAME) :
            this.classes.stream().filter(schema::existsClass).toList();

    if (classList.isEmpty()) {
      return new YTDBGraphEmptyQuery();
    }

    final var builder = new StringBuilder();

    final var parameters = new HashMap<String, Object>();
    final var where = buildWhere(parameters);

    if (classList.size() > 1) {
      builder.append("SELECT expand($union) ");
      final var lets =
          classList.stream()
              .map(c -> buildLetStatement(buildSingleQuery(c, where), classList.indexOf(c)))
              .collect(Collectors.joining(" , "));
      builder.append(
          String.format("%s , $union = UNIONALL(%s)", lets, buildVariables()));
    } else {
      builder.append(buildSingleQuery(classList.getFirst(), where));
    }
    return new YTDBGraphQuery(builder.toString(), parameters, classList.size());
  }

  private String buildWhere(Map<String, Object> parameters) {
    if (params.isEmpty()) {
      return "";
    }

    final var where = new StringBuilder(" WHERE ");

    for (var i = 0; i < params.size(); i++) {
      final var param = params.get(i);

      if (i > 0) {
        where.append(" AND ");
      }

      final var sqlName = "param" + i;
      where.append(formatCondition(param.name, sqlName, param.predicateStr, param.requiresNotNull));
      parameters.put(sqlName, param.value);
    }
    return where.toString();
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

  private static String formatCondition(
      String name, String sqlParamName,
      String predicateStr, boolean addNotNullCondition
  ) {
    return T.id.getAccessor().equalsIgnoreCase(name) ?
        String.format(" %s %s :%s", "@rid", predicateStr, sqlParamName) :
        addNotNullCondition ?
            String.format("`%s` IS NOT NULL AND `%s` %s :%s", name, name, predicateStr,
                sqlParamName) :
            String.format(" `%s` %s :%s", name, predicateStr, sqlParamName);
  }

  @Nullable
  private static String formatPredicate(BiPredicate<?, ?> predicate) {
    if (predicate instanceof Compare compare) {
      return switch (compare) {
        case Compare.eq -> "=";
        case Compare.gt -> ">";
        case Compare.gte -> ">=";
        case Compare.lt -> "<";
        case Compare.lte -> "<=";
        case Compare.neq -> "<>";
      };
    } else if (predicate instanceof Contains contains) {
      return switch (contains) {
        case Contains.within -> "IN";
        case Contains.without -> "NOT IN";
      };
    }

    return null;
  }

  // negative predicates function differently in SQL and Gremlin, so we need to add a
  // not-null condition to the resulting SQL
  private static boolean requiresAdditionalNotNull(BiPredicate<?, ?> predicate) {
    return predicate == Compare.neq || predicate == Contains.without;
  }

  public enum ConditionType {
    LABEL, PREDICATE, NOT_CONVERTED
  }

  private record Param(
      String name,
      String predicateStr,
      boolean requiresNotNull,
      Object value
  ) {

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
