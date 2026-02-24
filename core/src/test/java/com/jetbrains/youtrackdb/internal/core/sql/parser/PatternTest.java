package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternEdge;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Unit tests for Pattern, PatternNode, and PatternEdge graph representation classes. */
public class PatternTest extends ParserTestAbstract {

  /** Verifies that a single-node MATCH pattern produces one node, zero edges. */
  @Test
  public void testSimplePattern() throws ParseException {
    var query = "MATCH {as:a, class:Person} return a";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
    var pattern = stm.pattern;
    assertEquals(0, pattern.getNumOfEdges());
    assertEquals(1, pattern.getAliasToNode().size());
    assertNotNull(pattern.getAliasToNode().get("a"));
    assertEquals(1, pattern.getDisjointPatterns().size());
  }

  /** Verifies that two disconnected nodes produce two disjoint sub-patterns. */
  @Test
  public void testCartesianProduct() throws ParseException {
    var query = "MATCH {as:a, class:Person}, {as:b, class:Person} return a, b";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
    var pattern = stm.pattern;
    assertEquals(0, pattern.getNumOfEdges());
    assertEquals(2, pattern.getAliasToNode().size());
    assertNotNull(pattern.getAliasToNode().get("a"));
    var subPatterns = pattern.getDisjointPatterns();
    assertEquals(2, subPatterns.size());
    assertEquals(0, subPatterns.get(0).getNumOfEdges());
    assertEquals(1, subPatterns.get(0).getAliasToNode().size());
    assertEquals(0, subPatterns.get(1).getNumOfEdges());
    assertEquals(1, subPatterns.get(1).getAliasToNode().size());

    Set<String> aliases = new HashSet<>();
    aliases.add("a");
    aliases.add("b");
    aliases.remove(subPatterns.get(0).getAliasToNode().keySet().iterator().next());
    aliases.remove(subPatterns.get(1).getAliasToNode().keySet().iterator().next());
    assertEquals(0, aliases.size());
  }

  /**
   * Verifies that a complex multi-component pattern (two connected sub-graphs sharing
   * node 'd') is split into exactly two disjoint patterns with correct alias distribution.
   */
  @Test
  public void testComplexCartesianProduct() throws ParseException {
    var query =
        "MATCH {as:a, class:Person}-->{as:b}, {as:c, class:Person}-->{as:d}-->{as:e}, {as:d,"
            + " class:Foo}-->{as:f} return a, b";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
    var pattern = stm.pattern;
    assertEquals(4, pattern.getNumOfEdges());
    assertEquals(6, pattern.getAliasToNode().size());
    assertNotNull(pattern.getAliasToNode().get("a"));
    var subPatterns = pattern.getDisjointPatterns();
    assertEquals(2, subPatterns.size());

    Set<String> aliases = new HashSet<>();
    aliases.add("a");
    aliases.add("b");
    aliases.add("c");
    aliases.add("d");
    aliases.add("e");
    aliases.add("f");
    aliases.removeAll(subPatterns.get(0).getAliasToNode().keySet());
    aliases.removeAll(subPatterns.get(1).getAliasToNode().keySet());
    assertEquals(0, aliases.size());
  }

  // -- PatternNode tests --

  /** Verifies that isOptionalNode() returns false by default and true after setting optional. */
  @Test
  public void testPatternNodeOptionalFlag() {
    var node = new PatternNode();
    node.alias = "x";
    assertFalse(node.isOptionalNode());

    node.optional = true;
    assertTrue(node.isOptionalNode());
  }

  /** Verifies that addEdge() creates the edge and registers it in both nodes' adjacency sets. */
  @Test
  public void testPatternNodeAddEdge() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";

    var pathItem = new SQLMatchPathItem(-1);
    int added = nodeA.addEdge(pathItem, nodeB);

    assertEquals(1, added);
    assertEquals(1, nodeA.out.size());
    assertEquals(0, nodeA.in.size());
    assertEquals(0, nodeB.out.size());
    assertEquals(1, nodeB.in.size());

    // The same edge instance should be in both adjacency sets
    PatternEdge edge = nodeA.out.iterator().next();
    assertSame(edge, nodeB.in.iterator().next());
    assertSame(nodeA, edge.out);
    assertSame(nodeB, edge.in);
    assertSame(pathItem, edge.item);
  }

  /** Verifies that copy() preserves alias, optional flag, out edges, and leaves in set empty. */
  @Test
  public void testPatternNodeCopy() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    nodeA.optional = true;

    var nodeB = new PatternNode();
    nodeB.alias = "b";

    var pathItem = new SQLMatchPathItem(-1);
    nodeA.addEdge(pathItem, nodeB);

    var copy = nodeA.copy();
    assertEquals("a", copy.alias);
    assertTrue(copy.optional);
    assertEquals(1, copy.out.size());
    // in set of the copy is not populated (see PatternNode.copy() javadoc)
    assertEquals(0, copy.in.size());

    // The copied edge should point to the original nodeB (shallow copy)
    PatternEdge copiedEdge = copy.out.iterator().next();
    assertSame(nodeB, copiedEdge.in);
    assertSame(pathItem, copiedEdge.item);
    // But the source should be the copy, not the original
    assertSame(copy, copiedEdge.out);
  }

  /** Verifies that copy() of a node with no edges produces an empty copy. */
  @Test
  public void testPatternNodeCopyNoEdges() {
    var node = new PatternNode();
    node.alias = "solo";
    node.optional = false;

    var copy = node.copy();
    assertEquals("solo", copy.alias);
    assertFalse(copy.optional);
    assertTrue(copy.out.isEmpty());
    assertTrue(copy.in.isEmpty());
  }

  // -- PatternEdge tests --

  /** Verifies that PatternEdge.toString() formats as "{as: <target>}<method>". */
  @Test
  public void testPatternEdgeToString() throws ParseException {
    // Parse a MATCH statement to get real PatternEdge instances with proper toString
    var query = "MATCH {as:a, class:Person}-->{as:b} return a";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();

    var nodeA = stm.pattern.get("a");
    assertNotNull(nodeA);
    assertEquals(1, nodeA.out.size());

    PatternEdge edge = nodeA.out.iterator().next();
    String str = edge.toString();
    // Should contain the target alias "b"
    assertTrue("toString should contain target alias 'b': " + str,
        str.contains("{as: b}"));
  }

  // -- Pattern validation tests --

  /**
   * Verifies that validate() rejects optional nodes with outgoing edges
   * (optional nodes must be right-terminal).
   */
  @Test(expected = CommandSQLParsingException.class)
  public void testValidateOptionalNodeWithOutgoingEdges() throws ParseException {
    var query = "MATCH {as:a, class:Person, optional:true}-->{as:b} return a";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
    stm.pattern.validate();
  }

  /** Verifies that validate() accepts optional nodes that are right-terminal. */
  @Test
  public void testValidateOptionalNodeRightTerminal() throws ParseException {
    var query = "MATCH {as:a, class:Person}-->{as:b, optional:true} return a";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
    // Should not throw
    stm.pattern.validate();
  }

  /** Verifies that validate() rejects an isolated optional node (no incoming edges). */
  @Test(expected = CommandSQLParsingException.class)
  public void testValidateIsolatedOptionalNode() {
    var pattern = new Pattern();
    var node = new PatternNode();
    node.alias = "lonely";
    node.optional = true;
    pattern.aliasToNode.put("lonely", node);
    pattern.validate();
  }

  /** Verifies Pattern setters work correctly for numOfEdges and aliasToNode. */
  @Test
  public void testPatternSetters() {
    var pattern = new Pattern();
    assertEquals(0, pattern.getNumOfEdges());

    pattern.setNumOfEdges(5);
    assertEquals(5, pattern.getNumOfEdges());

    var nodeMap = new LinkedHashMap<String, PatternNode>();
    var node = new PatternNode();
    node.alias = "test";
    nodeMap.put("test", node);
    pattern.setAliasToNode(nodeMap);
    assertSame(node, pattern.getAliasToNode().get("test"));
  }

  // -- getLowerSubclass tests --

  /**
   * Verifies that getLowerSubclass returns the more specific class when class1
   * is a subclass of class2 in the schema hierarchy.
   */
  @Test
  public void testGetLowerSubclassReturnsChildWhenFirstIsSubclass() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Animal");
    schema.createClass("Dog", schema.getClass("Animal"));

    var result = SQLMatchStatement.getLowerSubclass(session, "Dog", "Animal");
    assertEquals("Dog", result);
  }

  /**
   * Verifies that getLowerSubclass returns the more specific class when class2
   * is a subclass of class1 (reversed argument order).
   */
  @Test
  public void testGetLowerSubclassReturnsChildWhenSecondIsSubclass() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Vehicle");
    schema.createClass("Car", schema.getClass("Vehicle"));

    var result = SQLMatchStatement.getLowerSubclass(session, "Vehicle", "Car");
    assertEquals("Car", result);
  }

  /**
   * Verifies that getLowerSubclass returns null when neither class is a subclass
   * of the other (they are in separate hierarchies).
   */
  @Test
  public void testGetLowerSubclassReturnsNullForUnrelatedClasses() {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Planet");
    schema.createClass("Star");

    var result = SQLMatchStatement.getLowerSubclass(session, "Planet", "Star");
    assertNull("unrelated classes should return null", result);
  }

  /**
   * Verifies that getLowerSubclass throws CommandExecutionException when the first
   * class name does not exist in the schema.
   */
  @Test(expected = CommandExecutionException.class)
  public void testGetLowerSubclassThrowsForMissingFirstClass() {
    session.getMetadata().getSchema().createClass("Existing");
    SQLMatchStatement.getLowerSubclass(session, "NonExistent", "Existing");
  }

  /**
   * Verifies that getLowerSubclass throws CommandExecutionException when the second
   * class name does not exist in the schema.
   */
  @Test(expected = CommandExecutionException.class)
  public void testGetLowerSubclassThrowsForMissingSecondClass() {
    session.getMetadata().getSchema().createClass("AlsoExisting");
    SQLMatchStatement.getLowerSubclass(session, "AlsoExisting", "DoesNotExist");
  }

  // -- buildPatterns / addAliases filter merging tests --

  /**
   * Verifies that buildPatterns correctly merges WHERE filters when the same alias
   * appears in multiple MATCH expressions, combining them into an AND block.
   */
  @Test
  public void testBuildPatternsMergesFiltersForSameAlias() throws ParseException {
    // Alias 'a' appears twice with different WHERE conditions
    var query = "MATCH {as:a, class:V, where:(name = 'foo')}.out(){as:b},"
        + " {as:a, class:V, where:(age > 10)} RETURN a, b";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();

    // Both filters should be merged into a single SQLWhereClause for alias 'a'
    assertNotNull("alias filter map should be populated", stm.aliasFilters);
    var mergedFilter = stm.aliasFilters.get("a");
    assertNotNull("alias 'a' should have a merged filter", mergedFilter);

    // The merged filter should be an AND block containing both sub-conditions
    var andBlock = (SQLAndBlock) mergedFilter.baseExpression;
    assertEquals("merged filter should combine both conditions",
        2, andBlock.subBlocks.size());
  }

  /**
   * Verifies that buildPatterns succeeds when the same alias is referenced with a
   * superclass (Creature) and a subclass (Human). This is the success-path
   * complement to {@link #testBuildPatternsThrowsForIncompatibleClasses()}.
   * The class resolution internally picks the more specific class, but since the
   * resolved class map is local to buildPatterns(), we verify the observable outcome:
   * pattern built without exception and the alias node exists.
   */
  @Test
  public void testBuildPatternsResolvesClassHierarchyForSameAlias() throws ParseException {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Creature");
    schema.createClass("Human", schema.getClass("Creature"));

    // Alias 'a' referenced with parent class 'Creature' and child class 'Human'
    var query = "MATCH {as:a, class:Creature}.out(){as:b},"
        + " {as:a, class:Human} RETURN a, b";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    // Should NOT throw — Creature and Human are in the same hierarchy
    stm.buildPatterns();

    assertNotNull("pattern should be built", stm.pattern);
    assertNotNull("alias 'a' should exist in pattern", stm.pattern.get("a"));
    assertEquals("pattern should have one edge (a→b)", 1, stm.pattern.getNumOfEdges());
    assertEquals("pattern should have two nodes (a, b)", 2,
        stm.pattern.getAliasToNode().size());
  }

  /**
   * Verifies that buildPatterns throws when the same alias references two classes
   * that are not in the same hierarchy (incompatible classes).
   */
  @Test(expected = CommandExecutionException.class)
  public void testBuildPatternsThrowsForIncompatibleClasses() throws ParseException {
    var schema = session.getMetadata().getSchema();
    schema.createClass("Fish");
    schema.createClass("Rock");

    var query = "MATCH {as:a, class:Fish}.out(){as:b},"
        + " {as:a, class:Rock} RETURN a, b";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    stm.setContext(getContext());
    stm.buildPatterns();
  }

  /**
   * Verifies that assignDefaultAliases correctly assigns default alias names to
   * path items that have no explicit alias, including creating a SQLMatchFilter
   * when none exists.
   */
  @Test
  public void testAssignDefaultAliasesToItemsWithoutFilter() throws ParseException {
    // out(){} has no alias and may have a null filter internally
    var query = "MATCH {as:a, class:V}.out().in() RETURN a";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();
    var expressions = stm.getMatchExpressions();

    // Call assignDefaultAliases and verify all nodes get aliases
    SQLMatchStatement.assignDefaultAliases(expressions);

    // The origin should keep its explicit alias
    assertEquals("a", expressions.get(0).origin.getAlias());

    // Path items without aliases should get default aliases
    for (var item : expressions.get(0).items) {
      assertNotNull("each path item should have a filter after assignment", item.filter);
      assertNotNull("each path item should have an alias after assignment",
          item.filter.getAlias());
      assertTrue("default alias should start with the expected prefix",
          item.filter.getAlias().startsWith(SQLMatchStatement.DEFAULT_ALIAS_PREFIX));
    }
  }

  // -- execute with Map params test --

  /**
   * Verifies that execute(session, Map, parentCtx, usePlanCache) correctly processes
   * named parameters and returns results. This tests the Map-parameter execution path.
   */
  @Test
  public void testExecuteWithMapParams() throws ParseException {
    // Set up test data: create a simple vertex class with data
    session.execute("CREATE class MapTestVertex extends V").close();
    session.begin();
    session.execute("CREATE VERTEX MapTestVertex set name = 'alice'").close();
    session.execute("CREATE VERTEX MapTestVertex set name = 'bob'").close();
    session.commit();

    var query = "MATCH {class: MapTestVertex, as: a, where: (name = :name)} RETURN a.name as name";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();

    Map<Object, Object> params = new java.util.HashMap<>();
    params.put("name", "alice");

    // Execute with Map params, no parent context, with plan cache
    session.begin();
    try {
      var resultSet = stm.execute(session, params, null, true);
      assertTrue("should have at least one result", resultSet.hasNext());
      var result = resultSet.next();
      assertEquals("alice", result.getProperty("name"));
      assertFalse("should have exactly one result", resultSet.hasNext());
      resultSet.close();
    } finally {
      session.rollback();
    }
  }

  /**
   * Verifies that execute(session, Map, parentCtx, usePlanCache) works correctly
   * without plan cache and with a parent context.
   */
  @Test
  public void testExecuteWithMapParamsNoCacheWithParentCtx() throws ParseException {
    session.execute("CREATE class NoCacheVertex extends V").close();
    session.begin();
    session.execute("CREATE VERTEX NoCacheVertex set val = 42").close();
    session.commit();

    var query = "MATCH {class: NoCacheVertex, as: a} RETURN a.val as val";
    var parser = getParserFor(query);
    var stm = (SQLMatchStatement) parser.parse();

    var parentCtx = getContext();
    Map<Object, Object> params = new java.util.HashMap<>();

    // Execute with Map params, with parent context, without plan cache
    session.begin();
    try {
      var resultSet = stm.execute(session, params, parentCtx, false);
      assertTrue("should have at least one result", resultSet.hasNext());
      var result = resultSet.next();
      assertEquals(42, result.<Object>getProperty("val"));
      resultSet.close();
    } finally {
      session.rollback();
    }
  }

  @Test
  public void addNode_singleAlias() {
    var pattern = new Pattern();
    pattern.addNode("x");
    Assert.assertEquals(1, pattern.getAliasToNode().size());
    Assert.assertNotNull(pattern.getAliasToNode().get("x"));
    Assert.assertEquals("x", pattern.getAliasToNode().get("x").alias);
    Assert.assertEquals(0, pattern.getNumOfEdges());
  }

  @Test
  public void addNode_multipleDistinct() {
    var pattern = new Pattern();
    pattern.addNode("a");
    pattern.addNode("b");
    pattern.addNode("c");
    Assert.assertEquals(3, pattern.getAliasToNode().size());
  }

  @Test
  public void addNode_nullAlias_ignored() {
    var pattern = new Pattern();
    pattern.addNode(null);
    Assert.assertTrue(pattern.getAliasToNode().isEmpty());
  }

  @Test
  public void addNode_duplicateAlias_ignored() {
    var pattern = new Pattern();
    pattern.addNode("dup");
    pattern.addNode("dup");
    Assert.assertEquals(1, pattern.getAliasToNode().size());
  }

  @Test
  public void addNode_preservesInsertionOrder() {
    var pattern = new Pattern();
    pattern.addNode("z");
    pattern.addNode("a");
    pattern.addNode("m");
    var keys = pattern.getAliasToNode().keySet().stream().toList();
    Assert.assertEquals("z", keys.get(0));
    Assert.assertEquals("a", keys.get(1));
    Assert.assertEquals("m", keys.get(2));
  }

  private CommandContext getContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    return ctx;
  }
}
