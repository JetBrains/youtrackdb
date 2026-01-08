package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.T;

public class YTDBGraphQueryBuilder {

  private final boolean vertexStep;

  /// A two-level collection of the requested classes. The outer level is for each `hasLabel` step,
  /// the inner level is for each requested class/label within the `hasLabel` step.
  ///
  /// All labels within a single step are combined using UNION semantics, e.g., `hasLabel("A", "B")`
  /// will match all vertices with labels `A` or `B.` `hasLabel` steps themselves are combined using
  /// INTERSECT semantics, e.g., `hasLabel("A").hasLabel("B")` matches all vertices with both labels
  /// `A` and `B.` Note, that this only makes sense when `A` and `B` have a parent-child
  /// relationship, i.e. `A` is a subclass of `B` or `B` is a subclass of `A.` Otherwise, the result
  /// will be empty.
  private final Set<Set<String>> requestedClasses = new HashSet<>();

  private final List<Param> params = new ArrayList<>();

  public YTDBGraphQueryBuilder(boolean vertexStep, List<HasContainer> conditions) {
    this.vertexStep = vertexStep;
    conditions.forEach(this::addCondition);
  }

  public YTDBGraphQueryBuilder(boolean vertexStep) {
    this(vertexStep, List.of());
  }

  public ConditionType addCondition(HasContainer condition) {
    var predicate = condition.getPredicate();
    final BiPredicate<?, ?> biPredicate;
    if (predicate == null) {
      biPredicate = null;
    } else {
      biPredicate = predicate.getBiPredicate();
    }

    if (isLabelKey(condition.getKey())) {
      if (biPredicate != Compare.eq && biPredicate != Contains.within) {
        // Here we handle only eq and within cases, they will get translated into SQL
        // FROM clauses. Other cases will be handled later by in-memory filtering.
        return ConditionType.NOT_CONVERTED;
      }
      final var value = condition.getValue();
      if (value instanceof List<?> list) {
        requestedClasses.add(
            list.stream()
                .map(s -> (String) s)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet())
        );
      } else if (StringUtils.isBlank((String) value)) {
        requestedClasses.add(Set.of());
      } else {
        requestedClasses.add(Set.of((String) value));
      }
      return ConditionType.LABEL;
    } else {
      final var value = condition.getValue();
      if ((biPredicate == Compare.eq || biPredicate == Compare.neq) && value == null) {
        // Gremlin's eq(null) and neq(null) should be translated into DB "IS NULL" and "IS NOT NULL"
        final var predicateStr = biPredicate == Compare.eq ? "IS NULL" : "IS NOT NULL";

        params.add(new Param(
            condition.getKey(),
            predicateStr,
            false,
            false,
            null
        ));
        return ConditionType.PREDICATE;
      } else {
        final var predicateStr = formatPredicate(biPredicate);
        if (predicateStr == null) {
          return ConditionType.NOT_CONVERTED;
        } else {
          params.add(new Param(
              condition.getKey(),
              predicateStr,
              requiresAdditionalNotNull(biPredicate),
              true,
              value
          ));
          return ConditionType.PREDICATE;
        }
      }
    }
  }

  private List<String> buildClassList(HierarchyAnalyzer hierarchyAnalyzer) {
    final var hierarchyRoot =
        vertexStep ? SchemaClass.VERTEX_CLASS_NAME : SchemaClass.EDGE_CLASS_NAME;
    if (requestedClasses.isEmpty()) {
      return List.of(hierarchyRoot);
    }

    if (requestedClasses.size() == 1 && requestedClasses.iterator().next().size() == 1) {
      final var singleClass = requestedClasses.iterator().next().iterator().next();
      if (hierarchyAnalyzer.classExists(singleClass)) {
        return List.of(singleClass);
      } else {
        return List.of();
      }
    }

    return requestedClasses.stream()
        // 1. Apply "union" to each inner class set. At this point we just remove all classes whose
        // parents are also requested.
        .map(
            innerClasses -> innerClasses.stream()
                .filter(hierarchyAnalyzer::classExists) // ignoring non-existent classes
                .filter(name ->
                    // considering only classes who don't have parents also requested (union semantics)
                    innerClasses.stream().noneMatch(
                        maybeParent -> hierarchyAnalyzer.isSuperClassOf(maybeParent, name)
                    )
                )
                .collect(Collectors.toSet())
        )

        // 2. Apply "intersection" to the resulting sets.
        // Set A intersected with set B will
        // produce a set containing elements whose parents (or themselves) are in both sets A
        // and B. For instance, [dolphin,bird,insect] intersected with [mammal,ant] will produce
        // [dolphin,ant]
        .reduce(
            (classesOne, classesTwo) ->
                classesOne.stream().flatMap(c1 ->
                        classesTwo.stream().flatMap(c2 ->
                            hierarchyAnalyzer.selectChild(c1, c2).stream()
                        )
                    )
                    .collect(Collectors.toSet())
        )
        .orElseGet(() -> Set.of(hierarchyRoot))
        .stream()
        .toList();
  }

  @Nullable
  public YTDBGraphBaseQuery build(DatabaseSessionEmbedded session) {
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    assert schema != null;

    final var classes = buildClassList(new HierarchyAnalyzer(schema));

    if (classes.isEmpty()) {
      return new YTDBGraphEmptyQuery();
    }

    final var builder = new StringBuilder();

    final var parameters = new HashMap<String, Object>();
    final var where = buildWhere(parameters);

    if (classes.size() > 1) {
      builder.append("SELECT expand($union) ");
      final var lets =
          classes.stream()
              .map(c -> buildLetStatement(buildSingleQuery(c, where), classes.indexOf(c)))
              .collect(Collectors.joining(" , "));

      builder.append(
          String.format("%s , $union = UNIONALL(%s)", lets, buildVariables(classes.size())));
    } else {
      builder.append(buildSingleQuery(classes.getFirst(), where));
    }
    return new YTDBGraphQuery(builder.toString(), parameters, classes.size());
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

      final var sqlName = param.withValue ? "param" + i : null;
      appendCondition(
          where,
          param.name, sqlName, param.predicateStr, param.requiresNotNull
      );
      if (param.withValue) {
        parameters.put(sqlName, param.value);
      }
    }
    return where.toString();
  }

  private static String buildVariables(int count) {
    var builder = new StringBuilder();
    for (var i = 0; i < count; i++) {
      builder.append(String.format("$q%d", i));
      if (i < count - 1) {
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

  private static void appendCondition(
      StringBuilder builder,
      String name,
      @Nullable String sqlParamName,
      String predicateStr,
      boolean addNotNullCondition
  ) {
    if (T.id.getAccessor().equalsIgnoreCase(name)) {
      builder.append(" @rid ").append(predicateStr);
    } else if (addNotNullCondition) {
      builder.append("`").append(name).append("`").append(" IS NOT NULL ")
          .append("AND `").append(name).append("` ").append(predicateStr);
    } else {
      builder.append("`").append(name).append("` ").append(predicateStr);
    }

    if (sqlParamName != null) {
      builder.append(" :").append(sqlParamName);
    }
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
      boolean withValue,
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

  private static class HierarchyAnalyzer {

    private final ImmutableSchema schema;
    private final Map<String, Set<String>> superClassesCache = new HashMap<>();

    private HierarchyAnalyzer(ImmutableSchema schema) {
      this.schema = schema;
    }

    public boolean classExists(String name) {
      return schema.existsClass(name);
    }

    /// Return true if `superClass` is a super class of `childClass`.
    public boolean isSuperClassOf(String superClass, String childClass) {
      return getSuperClassesFor(childClass).contains(superClass);
    }

    /// Select the child class of the two provided classes. If classOne and classTwo are the same
    /// class, then just return this class. If the two provided classes have a parent-child
    /// relationship, then return the child class. Return empty optional otherwise.
    public Optional<String> selectChild(String classOne, String classTwo) {
      if (classOne.equals(classTwo)) {
        return Optional.of(classOne);
      } else if (isSuperClassOf(classOne, classTwo)) {
        return Optional.of(classTwo);
      } else if (isSuperClassOf(classTwo, classOne)) {
        return Optional.of(classOne);
      } else {
        return Optional.empty();
      }
    }

    private Set<String> getSuperClassesFor(String name) {
      final var cached = superClassesCache.get(name);
      if (cached != null) {
        return cached;
      }

      final var clazz = schema.getClass(name);
      final Set<String> superClasses;
      if (clazz == null) {
        superClasses = Set.of();
      } else {
        superClasses = clazz.getAllSuperClasses().stream()
            .map(SchemaClass::getName)
            .collect(Collectors.toSet());
      }

      superClassesCache.put(name, superClasses);
      return superClasses;
    }
  }
}
