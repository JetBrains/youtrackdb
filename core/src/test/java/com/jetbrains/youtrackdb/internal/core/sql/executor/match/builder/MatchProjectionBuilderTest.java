package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

  /**
   * {@code aliasColumn} builds the bare-alias RETURN item — the whole matched element rather than a
   * property of it — so it must render exactly as the parser's {@code SELECT v}. This is the shape
   * the Gremlin translator uses for the boundary entity column and for every {@code select(label)}
   * column, previously hand-assembled at four call sites.
   */
  @Test
  public void aliasColumn_matchesParserShape() {
    assertSameProjectionShape(MatchProjectionBuilder.aliasColumn("v"), "v");
  }

  /**
   * A translator-minted alias carries the reserved {@code $g2m_} prefix, which the parser also
   * accepts as an identifier. Pinned separately from the plain-name case because the whole point of
   * building the node rather than parsing text is that a caller-supplied name reaches the AST
   * verbatim, and a prefix the tokenizer treats specially is where that would break.
   */
  @Test
  public void aliasColumn_withTranslatorMintedAlias_matchesParserShape() {
    assertSameProjectionShape(MatchProjectionBuilder.aliasColumn("$g2m_v0"), "$g2m_v0");
  }

  /**
   * {@code columnAlias} builds the {@code AS name} identifier a RETURN column publishes under, so
   * it must equal the alias node the parser produces for {@code SELECT x AS myLabel}. The Gremlin
   * translator routes user {@code as(...)} labels through it, so the name has to survive unaltered.
   */
  @Test
  public void columnAlias_matchesParserShape() {
    assertThat(MatchProjectionBuilder.columnAlias("myLabel").toString())
        .isEqualTo(parseProjectionAlias("x AS myLabel").toString());
  }

  /**
   * {@code orderBy} wraps pre-built items in the clause container, matching
   * {@code ORDER BY v.name ASC}. The Gremlin order recogniser assembles its items first and then
   * needs exactly this container; before the factory it built the container by hand.
   */
  @Test
  public void orderBy_matchesParserShape() {
    var built =
        MatchProjectionBuilder.orderBy(
            List.of(ProjectionExpressionFactories.orderByProperty("v", "name", true)));

    assertThat(built.toString()).isEqualToIgnoringCase(parseOrderBy("v.name ASC").toString());
  }

  /**
   * Two items keep their declared order, which a container that appended in reverse or sorted would
   * lose. Ordering is the whole meaning of the clause, so the single-item case above cannot pin it.
   */
  @Test
  public void orderBy_multipleItems_keepsDeclaredOrder() {
    var built =
        MatchProjectionBuilder.orderBy(
            List.of(
                ProjectionExpressionFactories.orderByProperty("v", "name", true),
                ProjectionExpressionFactories.orderByProperty("v", "age", false)));

    assertThat(built.toString())
        .isEqualToIgnoringCase(parseOrderBy("v.name ASC, v.age DESC").toString());
  }

  /**
   * The container copies the caller's list rather than adopting it. {@link
   * com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy#setItems} stores the reference and
   * {@code addItem} appends to it, so adopting would let a later planner-side {@code addItem} write
   * back into a collection the caller still holds. Asserted by mutating the built clause and
   * showing the caller's list is untouched.
   */
  @Test
  public void orderBy_doesNotAliasTheCallerList() {
    var items =
        new ArrayList<SQLOrderByItem>(
            List.of(ProjectionExpressionFactories.orderByProperty("v", "name", true)));

    var built = MatchProjectionBuilder.orderBy(items);
    built.addItem(ProjectionExpressionFactories.orderByProperty("v", "age", false));

    assertThat(items).hasSize(1);
    assertThat(built.getItems()).hasSize(2);
  }

  /** An ORDER BY with no items is not a clause the planner can consume, so it is rejected. */
  @Test
  public void orderBy_rejectsAnEmptyItemList() {
    assertThatThrownBy(() -> MatchProjectionBuilder.orderBy(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * {@code groupBy} matches the parser's {@code GROUP BY v.name}. Both Gremlin grouping terminators
   * ({@code group}, {@code groupCount}) reach the planner through this container.
   */
  @Test
  public void groupBy_matchesParserShape() {
    var built = MatchProjectionBuilder.groupBy(ProjectionExpressionFactories.aliasProperty("v",
        "name"));

    assertThat(built.toString()).isEqualToIgnoringCase(parseGroupBy("v.name").toString());
  }

  /** A GROUP BY with no items cannot group anything, so it is rejected rather than emitted empty. */
  @Test
  public void groupBy_rejectsAnEmptyItemList() {
    assertThatThrownBy(() -> MatchProjectionBuilder.groupBy())
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A null item would render as a hole in the clause and NPE deep inside the planner, so it is
   * rejected at construction where the caller can still be identified.
   */
  @Test
  public void groupBy_rejectsANullItem() {
    assertThatThrownBy(() -> MatchProjectionBuilder.groupBy((SQLExpression) null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertSameProjectionShape(SQLExpression built, String itemSql) {
    assertThat(built.toString()).isEqualToIgnoringCase(parseReturnItem(itemSql).toString());
  }

  private static SQLExpression parseReturnItem(String itemSql) {
    var projection = parseSelect("SELECT " + itemSql + " FROM V").getProjection();
    assertThat(projection).isNotNull();
    assertThat(projection.getItems()).isNotEmpty();
    var expr = projection.getItems().getFirst().getExpression();
    assertThat(expr).isNotNull();
    return expr;
  }

  /** The {@code AS} identifier of a parsed {@code <expr> AS <name>} projection item. */
  private static SQLIdentifier parseProjectionAlias(String itemSql) {
    var projection = parseSelect("SELECT " + itemSql + " FROM V").getProjection();
    assertThat(projection).isNotNull();
    var alias = projection.getItems().getFirst().getAlias();
    assertThat(alias).isNotNull();
    return alias;
  }

  private static SQLOrderBy parseOrderBy(String orderBySql) {
    var orderBy = parseSelect("SELECT FROM V ORDER BY " + orderBySql).getOrderBy();
    assertThat(orderBy).isNotNull();
    return orderBy;
  }

  private static SQLGroupBy parseGroupBy(String groupBySql) {
    var groupBy = parseSelect("SELECT FROM V GROUP BY " + groupBySql).getGroupBy();
    assertThat(groupBy).isNotNull();
    return groupBy;
  }

  private static SQLSelectStatement parseSelect(String sql) {
    try {
      var parser =
          new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
      return (SQLSelectStatement) parser.parse();
    } catch (ParseException e) {
      throw new IllegalArgumentException("failed to parse: " + sql, e);
    }
  }
}
