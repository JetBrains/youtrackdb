package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Pins {@link MatchProjectionBuilder} output against the parser-emitted AST shape for aggregate
 * RETURN items (regression guard after removing SQL-text round-trips from Gremlin aggregates).
 */
public class MatchProjectionBuilderTest {

  @Test
  public void countStar_matchesParserShape() {
    assertSameProjectionShape(MatchProjectionBuilder.countStar(), "count(*)");
  }

  @Test
  public void listCurrentMatch_matchesParserShape() {
    assertSameProjectionShape(
        MatchProjectionBuilder.listCurrentMatch(), "list($currentMatch)");
  }

  /**
   * {@code avg} is no longer what the Gremlin translator emits for a property mean — it now emits
   * the {@code mean} aggregate, which divides in floating point — but hand-written SQL still
   * reaches {@code avg}, so the builder has to keep agreeing with the parser on it. Pinned beside
   * {@link #propertyAggregate_mean_matchesParserShape} so the pair states the distinction the new
   * function was created for.
   */
  @Test
  public void propertyAggregate_avg_matchesParserShape() {
    assertSameProjectionShape(
        MatchProjectionBuilder.propertyAggregate("avg", "age"), "avg(age)");
  }

  /**
   * {@code mean(age)} is the shape production emits for {@code values("age").mean()}, from both the
   * single-plan property aggregate and the group value-side accumulator. {@code mean} is a
   * brand-new SQL function name, so whether the builder and the parser agree on it is exactly the
   * question this class answers for every other shape.
   */
  @Test
  public void propertyAggregate_mean_matchesParserShape() {
    assertSameProjectionShape(
        MatchProjectionBuilder.propertyAggregate("mean", "age"), "mean(age)");
  }

  /**
   * {@code list(alias.name)} is what a bare {@code group()} after {@code values("name")} folds into
   * the value column — the projected property rather than the whole element. The expression form is
   * built from a resolved {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression},
   * so it has no {@code listAlias} spelling to borrow a pin from.
   */
  @Test
  public void listExpression_matchesParserShape() {
    assertSameProjectionShape(
        MatchProjectionBuilder.listExpression(parseReturnItem("v.name")), "list(v.name)");
  }

  private static void assertSameProjectionShape(
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression built, String itemSql) {
    assertThat(built.toString()).isEqualToIgnoringCase(parseReturnItem(itemSql).toString());
  }

  private static com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression parseReturnItem(
      String itemSql) {
    try {
      var sql = "SELECT " + itemSql + " FROM V";
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      var stmt = (SQLSelectStatement) parser.parse();
      var projection = stmt.getProjection();
      assertThat(projection).isNotNull();
      assertThat(projection.getItems()).isNotEmpty();
      var expr = projection.getItems().getFirst().getExpression();
      assertThat(expr).isNotNull();
      return expr;
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse return item: " + itemSql, e);
    }
  }
}
