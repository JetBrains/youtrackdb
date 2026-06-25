package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchStatement;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Runtime tests for {@link MatchWhereBuilder}-built predicates executed through the
 * GQL MATCH pipeline ({@link GqlMatchStatement} → {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner}).
 *
 * <p>Unit tests in {@link MatchWhereBuilderTest} pin AST shape only; this class pins
 * evaluation semantics on real data — especially the entity-presence view ({@code IS
 * DEFINED}) versus the value-layer view ({@code IS NULL}) that the Gremlin translator
 * relies on.
 */
@SuppressWarnings("resource")
public class MatchWhereBuilderRuntimeTest extends GraphBaseTest {

  private static final String CLASS = "MwbRuntime";

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(tx.getDatabaseSession());
  }

  /**
   * {@code IS DEFINED} is entity-presence: a stored property with literal {@code null}
   * still counts as defined. Only vertices that never carried the property match.
   */
  @Test
  public void isDefined_matchesPresentPropertyIncludingNull_notAbsent() {
    graph.addVertex(T.label, CLASS, "name", "Alice");
    graph.addVertex(T.label, CLASS, "name", null, "tag", "null-valued");
    graph.addVertex(T.label, CLASS, "tag", "absent-name");
    graph.tx().commit();

    var where =
        new MatchWhereBuilder().wrap(new MatchWhereBuilder().isDefined("name"));
    Assert.assertEquals(2, countMatches(where));
  }

  /**
   * {@code IS NULL} uses value execution ({@code expression.execute() == null}): both
   * absent properties and literal {@code null} values match; non-null values do not.
   */
  @Test
  public void isNull_matchesAbsentAndNullValue_notNonNull() {
    graph.addVertex(T.label, CLASS + "Null", "name", "Alice");
    graph.addVertex(T.label, CLASS + "Null", "name", null, "tag", "null-valued");
    graph.addVertex(T.label, CLASS + "Null", "tag", "absent-name");
    graph.tx().commit();

    var where = new MatchWhereBuilder().wrap(new MatchWhereBuilder().isNull("name"));
    Assert.assertEquals(2, countMatches(CLASS + "Null", where));
  }

  /**
   * {@code IS NOT DEFINED} matches only vertices that never stored the property — not
   * those carrying a literal {@code null} value.
   */
  @Test
  public void isNotDefined_matchesOnlyAbsentProperty_notNullValue() {
    graph.addVertex(T.label, CLASS + "NotDef", "name", "Alice");
    graph.addVertex(T.label, CLASS + "NotDef", "name", null, "tag", "null-valued");
    graph.addVertex(T.label, CLASS + "NotDef", "tag", "absent-name");
    graph.tx().commit();

    var where =
        new MatchWhereBuilder().wrap(new MatchWhereBuilder().isNotDefined("name"));
    Assert.assertEquals(1, countMatches(CLASS + "NotDef", where));
  }

  /**
   * Builder-built {@code IN} must execute correctly end-to-end — not only render and
   * pass {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition#
   * supportsBasicCalculation()}.
   */
  @Test
  public void in_literalCollection_matchesExpectedVertices() {
    graph.addVertex(T.label, CLASS + "In", "status", 1);
    graph.addVertex(T.label, CLASS + "In", "status", 2);
    graph.addVertex(T.label, CLASS + "In", "status", 3);
    graph.tx().commit();

    var wb = new MatchWhereBuilder();
    var where =
        wb.wrap(
            wb.in(
                "status",
                List.of(
                    MatchLiteralBuilder.toLiteral(1L),
                    MatchLiteralBuilder.toLiteral(2L))));
    Assert.assertEquals(2, countMatches(CLASS + "In", where));
  }

  private int countMatches(SQLWhereClause where) {
    return countMatches(CLASS, where);
  }

  private int countMatches(String className, SQLWhereClause where) {
    var filter = SQLMatchFilter.fromGqlNode("v", className);
    filter.setFilter(where);
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      return count;
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }
}
