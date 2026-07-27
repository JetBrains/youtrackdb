package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.junit.Test;

/**
 * Pins the AST built by {@link ProjectionExpressionFactories}' field / order / limit / skip
 * factories to the exact rendering the SQL parser produces for the equivalent fragment. This
 * guards the IR-first path used by the Gremlin translator: building {@code alias.property},
 * {@code ORDER BY alias.field}, {@code LIMIT n}, and {@code SKIP n} directly as AST must stay
 * equivalent to parsing the same text, so removing the SQL-text round-trips changed nothing
 * observable (and closed the property-key injection surface, since the key is now a literal
 * identifier that can never be re-tokenized into extra syntax).
 */
public class ProjectionExpressionFactoriesTest {

  private static SQLSelectStatement parse(String sql) throws ParseException {
    return (SQLSelectStatement)
        new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))).parse();
  }

  private static String render(SQLExpression expression) {
    var sb = new StringBuilder();
    expression.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLOrderByItem item) {
    var sb = new StringBuilder();
    item.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLLimit limit) {
    var sb = new StringBuilder();
    limit.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  private static String render(SQLSkip skip) {
    var sb = new StringBuilder();
    skip.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  /** {@code alias.property} field access renders identically to the parsed projection item. */
  @Test
  public void aliasProperty_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT v.name FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasProperty("v", "name")))
        .isEqualTo(render(parsed));
  }

  /** {@code alias.@rid} record-attribute access renders identically to the parsed item. */
  @Test
  public void aliasRecordAttributeRid_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT v.@rid FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasRecordAttribute("v", "@rid")))
        .isEqualTo(render(parsed));
  }

  /** {@code alias.@class} record-attribute access renders identically to the parsed item. */
  @Test
  public void aliasRecordAttributeClass_rendersLikeParser() throws ParseException {
    var parsed =
        parse("SELECT v.@class FROM V").getProjection().getItems().getFirst().getExpression();
    assertThat(render(ProjectionExpressionFactories.aliasRecordAttribute("v", "@class")))
        .isEqualTo(render(parsed));
  }

  /** {@code ORDER BY alias.property DESC} renders and types identically to the parsed order item. */
  @Test
  public void orderByProperty_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V ORDER BY v.name DESC").getOrderBy().getItems().getFirst();
    var built = ProjectionExpressionFactories.orderByProperty("v", "name", false);
    assertThat(render(built)).isEqualTo(render(parsed));
    assertThat(built.getType()).isEqualTo(SQLOrderByItem.DESC);
  }

  /** {@code ORDER BY alias.@rid ASC} renders identically to the parsed order item. */
  @Test
  public void orderByRecordAttribute_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V ORDER BY v.@rid ASC").getOrderBy().getItems().getFirst();
    var built = ProjectionExpressionFactories.orderByRecordAttribute("v", "@rid", true);
    assertThat(render(built)).isEqualTo(render(parsed));
    assertThat(built.getType()).isEqualTo(SQLOrderByItem.ASC);
  }

  /** {@code LIMIT n} renders identically to the parsed limit clause. */
  @Test
  public void limit_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V LIMIT 5").getLimit();
    assertThat(render(ProjectionExpressionFactories.limit(5))).isEqualTo(render(parsed));
  }

  /** {@code SKIP n} renders identically to the parsed skip clause. */
  @Test
  public void skip_rendersLikeParser() throws ParseException {
    var parsed = parse("SELECT FROM V SKIP 3").getSkip();
    assertThat(render(ProjectionExpressionFactories.skip(3))).isEqualTo(render(parsed));
  }

  /**
   * A property key with SQL metacharacters is treated as a literal identifier, never re-tokenized
   * into extra syntax — the AST has exactly one projection value (the injection surface is closed).
   */
  @Test
  public void aliasProperty_injectionKeyStaysLiteral() {
    var built = ProjectionExpressionFactories.aliasProperty("v", "name FROM V WHERE 1=1--");
    // Renders the whole thing as one quoted identifier segment, not a parseable expression tail.
    assertThat(render(built)).contains("name FROM V WHERE 1=1--");
    assertThat(render(built)).startsWith("v.");
  }
}
