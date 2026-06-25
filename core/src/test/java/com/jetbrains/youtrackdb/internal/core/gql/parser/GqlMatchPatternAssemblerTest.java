package com.jetbrains.youtrackdb.internal.core.gql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for {@link GqlMatchPatternAssembler}.
 *
 * Verifies GQL-specific alias minting ({@code $c<N>}) and label defaults ({@code "V"})
 * when converting visitor-produced {@link SQLMatchFilter} lists into shared MATCH IR.
 */
public class GqlMatchPatternAssemblerTest {

  @Test
  public void fromFilters_singleAnonymous_mintsC0AndDefaultsLabelToV() {
    var ir = GqlMatchPatternAssembler.fromFilters(List.of(filter(null, null)));

    assertNotNull(ir.pattern().aliasToNode.get("$c0"));
    assertEquals("$c0", ir.pattern().aliasToNode.get("$c0").alias);
    assertEquals("V", ir.aliasClasses().get("$c0"));
    assertNull(ir.aliasFilters().get("$c0"));
  }

  @Test
  public void fromFilters_blankAlias_mintsC0() {
    var ir = GqlMatchPatternAssembler.fromFilters(List.of(filter("", "Person")));

    assertNotNull(ir.pattern().aliasToNode.get("$c0"));
    assertEquals("Person", ir.aliasClasses().get("$c0"));
  }

  @Test
  public void fromFilters_namedAlias_preservesAlias() {
    var ir = GqlMatchPatternAssembler.fromFilters(List.of(filter("p", "Person")));

    assertNotNull(ir.pattern().aliasToNode.get("p"));
    assertEquals("Person", ir.aliasClasses().get("p"));
    assertNull(ir.pattern().aliasToNode.get("$c0"));
  }

  @Test
  public void fromFilters_multipleAnonymous_incrementsCounter() {
    var ir =
        GqlMatchPatternAssembler.fromFilters(
            List.of(filter(null, "A"), filter(null, "B"), filter("named", "C")));

    assertNotNull(ir.pattern().aliasToNode.get("$c0"));
    assertNotNull(ir.pattern().aliasToNode.get("$c1"));
    assertNotNull(ir.pattern().aliasToNode.get("named"));
    assertEquals("A", ir.aliasClasses().get("$c0"));
    assertEquals("B", ir.aliasClasses().get("$c1"));
    assertEquals("C", ir.aliasClasses().get("named"));
  }

  @Test
  public void fromFilters_blankLabel_defaultsToV() {
    var ir = GqlMatchPatternAssembler.fromFilters(List.of(filter("v", "")));

    assertEquals("V", ir.aliasClasses().get("v"));
  }

  @Test
  public void fromFilters_withNodeWhereClause_populatesAliasFilters() {
    var matchFilter = SQLMatchFilter.fromGqlNode("p", "Person");
    var where = GqlMatchStatement.buildWhereClause(Map.of("age", 30L));
    matchFilter.setFilter(where);

    var ir = GqlMatchPatternAssembler.fromFilters(List.of(matchFilter));

    assertSame(where, ir.aliasFilters().get("p"));
    assertEquals("Person", ir.aliasClasses().get("p"));
    assertNotNull(ir.pattern().aliasToNode.get("p"));
  }

  @Test
  public void incrementalAdd_matchesFromFilters() {
    var assembler = new GqlMatchPatternAssembler();
    assembler.add(filter(null, "Person"));
    assembler.add(filter("x", "Thing"));
    var ir = assembler.build();

    assertTrue(ir.pattern().aliasToNode.containsKey("$c0"));
    assertTrue(ir.pattern().aliasToNode.containsKey("x"));
    assertEquals("Person", ir.aliasClasses().get("$c0"));
    assertEquals("Thing", ir.aliasClasses().get("x"));
  }

  private static SQLMatchFilter filter(String alias, String label) {
    return SQLMatchFilter.fromGqlNode(alias, label);
  }
}
