package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;

/**
 * Pins {@link MatchWhereBuilder#matchedAccess(String, String...)} (SF3): the {@code
 * $matched.<alias>.<segments…>} cross-alias accessor is now built as an AST modifier chain via
 * {@link
 * com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories#matchedVariable}
 * — production no longer concatenates a {@code SELECT} string and reparses it. These tests assert
 * both that the built expression renders the expected {@code $matched.a.…} text and that its render
 * is identical to the shape {@link YouTrackDBSql} produces for the same source (a round-trip pin
 * proving the dropped parse path was equivalent). A plain segment becomes a property modifier; a
 * segment starting with {@code @} becomes a record-attribute modifier.
 */
public class MatchWhereBuilderMatchedAccessTest {

  /**
   * {@code matchedAccess("a", "name")} renders {@code $matched.a.name} — a plain segment maps to a
   * property modifier. Both {@code toGenericStatement} and {@code toString} render identically
   * (positional-param map is empty; there are no parameters in a pure identifier chain).
   */
  @Test
  public void matchedAccess_property_rendersMatchedAliasDotProperty() {
    var expr = new MatchWhereBuilder().matchedAccess("a", "name");

    var generic = new StringBuilder();
    expr.toGenericStatement(generic);
    assertThat(generic.toString()).isEqualTo("$matched.a.name");

    var stringForm = new StringBuilder();
    expr.toString(Collections.emptyMap(), stringForm);
    assertThat(stringForm.toString()).isEqualTo("$matched.a.name");
  }

  /**
   * {@code matchedAccess("a", "@rid")} renders {@code $matched.a.@rid} — a {@code @}-prefixed
   * segment maps to a record-attribute modifier rather than a plain property.
   */
  @Test
  public void matchedAccess_recordAttribute_rendersMatchedAliasDotRid() {
    var expr = new MatchWhereBuilder().matchedAccess("a", "@rid");

    var generic = new StringBuilder();
    expr.toGenericStatement(generic);
    assertThat(generic.toString()).isEqualTo("$matched.a.@rid");

    var stringForm = new StringBuilder();
    expr.toString(Collections.emptyMap(), stringForm);
    assertThat(stringForm.toString()).isEqualTo("$matched.a.@rid");
  }

  /**
   * Round-trip pin: the AST built for a property access renders identically to the AST {@link
   * YouTrackDBSql} parses from the equivalent SQL source. This is the equivalence the SF3 change
   * relies on when it drops the parse-then-render path — the directly-built modifier chain must be
   * indistinguishable from the parsed one.
   */
  @Test
  public void matchedAccess_property_roundTripsWithParsedExpression() {
    var built = new MatchWhereBuilder().matchedAccess("a", "name");
    assertSameRender(built, "$matched.a.name");
  }

  /** Round-trip pin for the record-attribute form ({@code $matched.a.@rid}). */
  @Test
  public void matchedAccess_recordAttribute_roundTripsWithParsedExpression() {
    var built = new MatchWhereBuilder().matchedAccess("a", "@rid");
    assertSameRender(built, "$matched.a.@rid");
  }

  /** Asserts {@code built} renders the same generic statement as the parser's AST for {@code source}. */
  private static void assertSameRender(SQLExpression built, String source) {
    var builtRendered = new StringBuilder();
    built.toGenericStatement(builtRendered);

    var parsedRendered = new StringBuilder();
    parseProjectionExpression(source).toGenericStatement(parsedRendered);

    assertThat(builtRendered.toString()).isEqualTo(parsedRendered.toString());
  }

  /** Parses {@code SELECT <source> FROM V} and returns the single projection item's expression. */
  private static SQLExpression parseProjectionExpression(String source) {
    try {
      var sql = "SELECT " + source + " FROM V";
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
      throw new IllegalArgumentException("failed to parse projection expression: " + source, e);
    }
  }
}
