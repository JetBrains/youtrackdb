package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.Test;

/**
 * Unit tests for {@link ExpandStep#prettyPrint(int, int)} covering every
 * combination of push-down filter descriptors.
 */
public class ExpandStepPrettyPrintTest {

  private final BasicCommandContext ctx = new BasicCommandContext();

  // =========================================================================
  // No filters — base case
  // =========================================================================

  @Test
  public void prettyPrint_noFilters_showsExpandOnly() {
    var step = new ExpandStep(ctx, false, null);
    var output = step.prettyPrint(0, 2);

    assertThat(output).isEqualTo("+ EXPAND");
  }

  // =========================================================================
  // Class filter only
  // =========================================================================

  @Test
  public void prettyPrint_classFilterOnly() {
    IntSet ids = new IntOpenHashSet(new int[] {10, 11, 12});
    var step = new ExpandStep(ctx, false, null, null, ids);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ EXPAND");
    assertThat(output).contains("(class filter: 3 collection(s))");
  }

  // =========================================================================
  // Direct RID filter
  // =========================================================================

  @Test
  public void prettyPrint_directRidFilter() {
    var ridExpr = mock(com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression.class);
    var desc = new RidFilterDescriptor.DirectRid(ridExpr);
    var step = new ExpandStep(ctx, false, null, null, null, desc, null);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("(rid filter: direct)");
  }

  // =========================================================================
  // Edge RID lookup filter
  // =========================================================================

  @Test
  public void prettyPrint_edgeRidLookupFilter() {
    var ridExpr = mock(com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression.class);
    var desc = new RidFilterDescriptor.EdgeRidLookup("HAS_CREATOR", "out", ridExpr, false);
    var step = new ExpandStep(ctx, false, null, null, null, desc, null);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("(rid filter: out('HAS_CREATOR'))");
  }

  // =========================================================================
  // Index pre-filter
  // =========================================================================

  @Test
  public void prettyPrint_indexPreFilter() {
    var indexDesc = mock(IndexSearchDescriptor.class);
    var index = mock(Index.class);
    when(indexDesc.getIndex()).thenReturn(index);
    when(index.getName()).thenReturn("Message.creationDate");

    var step = new ExpandStep(ctx, false, null, null, null, null, indexDesc);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("(index pre-filter: Message.creationDate)");
  }

  // =========================================================================
  // Push-down filter
  // =========================================================================

  @Test
  public void prettyPrint_pushDownFilter() {
    var whereClause = new SQLWhereClause(-1);
    var step = new ExpandStep(ctx, false, null, whereClause, null, null, null);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("(push-down filter:");
  }

  // =========================================================================
  // All filters combined
  // =========================================================================

  @Test
  public void prettyPrint_allFiltersCombined() {
    IntSet ids = new IntOpenHashSet(new int[] {5, 6});

    var ridExpr = mock(com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression.class);
    var ridDesc = new RidFilterDescriptor.EdgeRidLookup("KNOWS", "in", ridExpr, false);

    var indexDesc = mock(IndexSearchDescriptor.class);
    var index = mock(Index.class);
    when(indexDesc.getIndex()).thenReturn(index);
    when(index.getName()).thenReturn("Person.name");

    var whereClause = new SQLWhereClause(-1);

    var step = new ExpandStep(ctx, false, null, whereClause, ids, ridDesc, indexDesc);
    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ EXPAND");
    assertThat(output).contains("(class filter: 2 collection(s))");
    assertThat(output).contains("(rid filter: in('KNOWS'))");
    assertThat(output).contains("(index pre-filter: Person.name)");
    assertThat(output).contains("(push-down filter:");
  }

  // =========================================================================
  // Depth / indent
  // =========================================================================

  @Test
  public void prettyPrint_withDepthAndIndent_addsSpaces() {
    var step = new ExpandStep(ctx, false, null);
    var output = step.prettyPrint(2, 3);

    assertThat(output).startsWith("      + EXPAND");
  }

  // =========================================================================
  // copy() — preserves all filter descriptors
  // =========================================================================

  @Test
  public void copy_preservesClassFilter() {
    IntSet classFilter = IntOpenHashSet.of(10, 20);
    var step = new ExpandStep(ctx, false, "alias",
        null, classFilter, null, null);
    var copy = (ExpandStep) step.copy(ctx);
    var output = copy.prettyPrint(0, 2);
    assertThat(output).contains("class filter: 2 collection(s)");
  }

  @Test
  public void copy_preservesRidFilter() {
    var ridFilter = new RidFilterDescriptor.DirectRid(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression(-1));
    var step = new ExpandStep(ctx, false, "alias",
        null, null, ridFilter, null);
    var copy = (ExpandStep) step.copy(ctx);
    var output = copy.prettyPrint(0, 2);
    assertThat(output).contains("rid filter: direct");
  }

  @Test
  public void copy_preservesIndexDescriptor() {
    var index = mock(Index.class);
    when(index.getName()).thenReturn("Person.name");
    var indexDesc = mock(IndexSearchDescriptor.class);
    when(indexDesc.getIndex()).thenReturn(index);
    var step = new ExpandStep(ctx, false, "alias",
        null, null, null, indexDesc);
    var copy = (ExpandStep) step.copy(ctx);
    var output = copy.prettyPrint(0, 2);
    assertThat(output).contains("index pre-filter: Person.name");
  }

  @Test
  public void copy_preservesPushDownFilter() {
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(
        new com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock(-1));
    var step = new ExpandStep(ctx, false, "alias",
        where, null, null, null);
    var copy = (ExpandStep) step.copy(ctx);
    var output = copy.prettyPrint(0, 2);
    assertThat(output).contains("push-down filter:");
  }

  @Test
  public void canBeCached_returnsTrue() {
    var step = new ExpandStep(ctx, false, null);
    assertThat(step.canBeCached()).isTrue();
  }
}
