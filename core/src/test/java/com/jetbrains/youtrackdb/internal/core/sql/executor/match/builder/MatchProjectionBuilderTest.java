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
   * The Gremlin translator maps {@code mean()} to the SQL aggregate {@code avg} (there is no {@code
   * mean} SQL function — see {@code PropertyAggregateStepRecogniser}), so {@code avg(age)} is the
   * shape production actually emits for a property mean. Pin the builder's {@code avg} output
   * against the parser-emitted {@code avg(age)} AST; a raw {@code mean(...)} projection is never
   * produced by the pipeline and would fail plan-build, so it is not worth pinning.
   */
  @Test
  public void propertyAggregate_avg_matchesParserShape() {
    assertSameProjectionShape(
        MatchProjectionBuilder.propertyAggregate("avg", "age"), "avg(age)");
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
