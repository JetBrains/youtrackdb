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

  // --- Case A: RID extraction unit tests ---

  private SQLWhereClause parseWhere(String whereBody) throws ParseException {
    var sql = "SELECT FROM V WHERE " + whereBody;
    var parser = getParserFor(sql);
    var stm = (SQLSelectStatement) parser.parse();
    return stm.getWhereClause();
  }

  /** @rid = #23:1 → extracted, no remaining */
  @Test
  public void testExtractRidEquality_simple() throws ParseException {
    var where = parseWhere("@rid = #23:1");
    var result = where.extractAndRemoveRidEquality();
    assertNotNull("Should extract @rid equality", result);
    assertNotNull(result.ridExpression());
    assertNull("No remaining conditions expected", result.remainingWhere());
  }

  /** @rid = #23:1 AND name = 'x' → extracted, remaining = name = 'x' */
  @Test
  public void testExtractRidEquality_compound() throws ParseException {
    var where = parseWhere("@rid = #23:1 AND name = 'x'");
    var result = where.extractAndRemoveRidEquality();
    assertNotNull("Should extract @rid equality from compound", result);
    assertNotNull(result.ridExpression());
    assertNotNull("Should have remaining WHERE", result.remainingWhere());
  }

  /** name = 'x' → null (no @rid equality) */
  @Test
  public void testExtractRidEquality_noRid() throws ParseException {
    var where = parseWhere("name = 'x'");
    var result = where.extractAndRemoveRidEquality();
    assertNull("Should return null when no @rid equality", result);
  }

  /** out('HAS_CREATOR').@rid = #10:5 → extracted edge lookup */
  @Test
  public void testExtractEdgeRidLookup_simple() throws ParseException {
    var where = parseWhere("out('HAS_CREATOR').@rid = #10:5");
    var result = where.extractAndRemoveEdgeRidLookup();
    assertNotNull("Should extract edge RID lookup", result);
    assertEquals("HAS_CREATOR", result.edgeClassName());
    assertEquals("out", result.traversalDirection());
    assertNotNull(result.targetRidExpression());
    assertNull("No remaining conditions expected", result.remainingWhere());
  }

  /** in('KNOWS').@rid = #10:5 → extracted with direction=in */
  @Test
  public void testExtractEdgeRidLookup_inDirection() throws ParseException {
    var where = parseWhere("in('KNOWS').@rid = #10:5");
    var result = where.extractAndRemoveEdgeRidLookup();
    assertNotNull("Should extract edge RID lookup for IN", result);
    assertEquals("KNOWS", result.edgeClassName());
    assertEquals("in", result.traversalDirection());
  }

  /** out('HAS_CREATOR').@rid = #10:5 AND score > 3 → extracted, remaining */
  @Test
  public void testExtractEdgeRidLookup_compound() throws ParseException {
    var where = parseWhere("out('HAS_CREATOR').@rid = #10:5 AND score > 3");
    var result = where.extractAndRemoveEdgeRidLookup();
    assertNotNull("Should extract from compound", result);
    assertEquals("HAS_CREATOR", result.edgeClassName());
    assertNotNull("Should have remaining WHERE", result.remainingWhere());
  }

  /** name = 'x' → null (no edge RID lookup) */
  @Test
  public void testExtractEdgeRidLookup_noMatch() throws ParseException {
    var where = parseWhere("name = 'x'");
    var result = where.extractAndRemoveEdgeRidLookup();
    assertNull("Should return null when no edge RID lookup", result);
  }

  /** a > 5 AND out('X').@rid = $parent.$current.@rid → splits correctly */
  @Test
  public void testSplitByParentReference_mixed() throws ParseException {
    var where = parseWhere("a > 5 AND out('X').@rid = $parent.$current.@rid");
    var result = where.splitByParentReference();
    assertNotNull("Should split parent-referencing clause", result);
    assertNotNull("Should have parent-referencing part", result.parentReferencing());
    assertNotNull("Should have non-parent part", result.nonParentReferencing());
  }

  /** a > 5 → null (no $parent reference, no split needed) */
  @Test
  public void testSplitByParentReference_noParent() throws ParseException {
    var where = parseWhere("a > 5");
    var result = where.splitByParentReference();
    assertNull("Should return null when no $parent reference", result);
  }

  /**
   * out('X').@rid = $parent.$current.@rid → parentReferencing = whole clause,
   * nonParentReferencing = null
   */
  @Test
  public void testSplitByParentReference_allParent() throws ParseException {
    var where = parseWhere("out('X').@rid = $parent.$current.@rid");
    var result = where.splitByParentReference();
    assertNotNull("Should return split result", result);
    assertNotNull("Should have parent-referencing part", result.parentReferencing());
    assertNull(
        "Should have null non-parent part when all conditions reference $parent",
        result.nonParentReferencing());
  }

  // ====== splitByMatchedReference tests ======

  /**
   * creationDate > 5 AND @rid = $matched.person.@rid → splits into
   * matchedReferencing (@rid = $matched...) and nonMatchedReferencing (creationDate > 5)
   */
  @Test
  public void testSplitByMatchedReference_mixed() throws ParseException {
    var where = parseWhere("creationDate > 5 AND @rid = $matched.person.@rid");
    var result = where.splitByMatchedReference();
    assertNotNull("Should split matched-referencing clause", result);
    assertNotNull("Should have matched-referencing part", result.matchedReferencing());
    assertNotNull("Should have non-matched part", result.nonMatchedReferencing());
  }

  /** creationDate > 5 → null (no $matched reference) */
  @Test
  public void testSplitByMatchedReference_noMatched() throws ParseException {
    var where = parseWhere("creationDate > 5");
    var result = where.splitByMatchedReference();
    assertNull("Should return null when no $matched reference", result);
  }

  /**
   * @rid = $matched.person.@rid → matchedReferencing = whole clause,
   * nonMatchedReferencing = null
   */
  @Test
  public void testSplitByMatchedReference_allMatched() throws ParseException {
    var where = parseWhere("@rid = $matched.person.@rid");
    var result = where.splitByMatchedReference();
    assertNotNull("Should return split result", result);
    assertNotNull("Should have matched-referencing part", result.matchedReferencing());
    assertNull(
        "Should have null non-matched part when all conditions reference $matched",
        result.nonMatchedReferencing());
  }

  // ====== splitByLetDependency tests ======

  /**
   * Mixed AND: age > 25 is independent of LET $scores, birthday filter is also
   * independent → both go to independent half. name = $scores is dependent.
   * Result: independent = "age > 25 AND birthday > '2000-01-01'",
   *         dependent = "name = $scores".
   */
  @Test
  public void testSplitByLetDependency_mixedAndBlock() throws ParseException {
    var where = parseWhere(
        "age > 25 AND name = $scores AND birthday > '2000-01-01'");
    var result = where.splitByLetDependency(Set.of("scores"));
    assertNotNull("Should return split result for mixed dependencies", result);
    assertNotNull("Should have independent part", result.independent());
    assertNotNull("Should have dependent part", result.dependent());
    // Verify independent part does not reference $scores
    assertNull(
        "Independent part should not reference LET vars",
        result.independent().splitByLetDependency(Set.of("scores")));
  }

  /**
   * Fully independent: WHERE age > 25 AND name = 'Alice' with LET $x.
   * No conjunct references $x → returns null (caller pushes the whole WHERE).
   */
  @Test
  public void testSplitByLetDependency_fullyIndependent() throws ParseException {
    var where = parseWhere("age > 25 AND name = 'Alice'");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNull("Should return null when no LET variable is referenced", result);
  }

  /**
   * Fully dependent: WHERE name = $x AND age = $y with LET $x, $y.
   * All conjuncts reference LET vars → independent = null, dependent = this.
   */
  @Test
  public void testSplitByLetDependency_fullyDependent() throws ParseException {
    var where = parseWhere("name = $x AND age = $y");
    var result = where.splitByLetDependency(Set.of("x", "y"));
    assertNotNull("Should return split result", result);
    assertNull("Should have null independent part", result.independent());
    assertNotNull("Should have dependent part", result.dependent());
  }

  /**
   * Multi-OR all independent: WHERE (a > 5) OR (b < 10) with LET $x.
   * No branch references $x → returns null (push entire WHERE).
   */
  @Test
  public void testSplitByLetDependency_multiOrAllIndependent() throws ParseException {
    var where = parseWhere("a > 5 OR b < 10");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNull("Should return null for multi-OR all independent", result);
  }

  /**
   * Multi-OR mixed: WHERE (a > 5) OR (b = $x) with LET $x.
   * One branch references $x → entire WHERE stays dependent (cannot split OR).
   */
  @Test
  public void testSplitByLetDependency_multiOrMixed() throws ParseException {
    var where = parseWhere("a > 5 OR b = $x");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNotNull("Should return split result for mixed multi-OR", result);
    assertNull(
        "Should have null independent part (cannot split OR)",
        result.independent());
    assertNotNull("Should have dependent part (entire WHERE)", result.dependent());
  }

  /**
   * Single condition independent: WHERE age > 25 with LET $x.
   * Does not reference $x → returns null (push entire WHERE).
   */
  @Test
  public void testSplitByLetDependency_singleConditionIndependent()
      throws ParseException {
    var where = parseWhere("age > 25");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNull("Should return null for independent single condition", result);
  }

  /**
   * Single condition dependent: WHERE name = $x with LET $x.
   * References $x → independent = null, dependent = this.
   */
  @Test
  public void testSplitByLetDependency_singleConditionDependent()
      throws ParseException {
    var where = parseWhere("name = $x");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNotNull("Should return split result for dependent single condition", result);
    assertNull("Should have null independent part", result.independent());
    assertNotNull("Should have dependent part", result.dependent());
  }

  /**
   * $parent reference in conjunct: WHERE age > 25 AND out('X').@rid = $parent.$current.@rid
   * with LET $x. Neither conjunct references $x, but the $parent conjunct must
   * be classified as dependent (safety constraint).
   */
  @Test
  public void testSplitByLetDependency_parentRefClassifiedAsDependent()
      throws ParseException {
    var where = parseWhere(
        "age > 25 AND out('X').@rid = $parent.$current.@rid");
    var result = where.splitByLetDependency(Set.of("x"));
    assertNotNull("Should return split result due to $parent reference", result);
    assertNotNull("Should have independent part (age > 25)", result.independent());
    assertNotNull(
        "Should have dependent part ($parent conjunct)", result.dependent());
  }

  /**
   * Multiple LET variables: WHERE a = $x AND b > 5 AND c = $y with LET $x, $y.
   * a=$x and c=$y are dependent, b>5 is independent.
   */
  @Test
  public void testSplitByLetDependency_multipleLetVars() throws ParseException {
    var where = parseWhere("a = $x AND b > 5 AND c = $y");
    var result = where.splitByLetDependency(Set.of("x", "y"));
    assertNotNull("Should return split result", result);
    assertNotNull("Should have independent part (b > 5)", result.independent());
    assertNotNull("Should have dependent part (a=$x, c=$y)", result.dependent());
    // Independent part should not reference any LET vars
    assertNull(
        "Independent part should not reference LET vars",
        result.independent().splitByLetDependency(Set.of("x", "y")));
  }

  // ====== findRidEquality tests ======

  /**
   * Simple @rid = #23:1 in a standalone WHERE clause. findRidEquality should
   * detect it even though the parser wraps it in OrBlock(AndBlock(OrBlock(...))).
   */
  @Test
  public void testFindRidEquality_simple() throws ParseException {
    var where = parseWhere("@rid = #23:1");
    var result = where.findRidEquality();
    assertNotNull("findRidEquality should detect @rid = #23:1", result);
  }

  /** @rid = #23:1 AND name = 'x' → should still find the @rid equality */
  @Test
  public void testFindRidEquality_compound() throws ParseException {
    var where = parseWhere("@rid = #23:1 AND name = 'x'");
    var result = where.findRidEquality();
    assertNotNull("findRidEquality should detect @rid in compound WHERE", result);
  }

  /** name = 'x' → no @rid equality → null */
  @Test
  public void testFindRidEquality_noRid() throws ParseException {
    var where = parseWhere("name = 'x'");
    var result = where.findRidEquality();
    assertNull("findRidEquality should return null when no @rid equality", result);
  }

  /** @rid <> #23:1 → not-equals, not extracted */
  @Test
  public void testFindRidEquality_notEquals() throws ParseException {
    var where = parseWhere("@rid <> #23:1");
    var result = where.findRidEquality();
    assertNull("findRidEquality should return null for @rid <>", result);
  }

  /**
   * Tests the aliasFilter-style WHERE clause that the MATCH planner constructs.
   * The MATCH planner creates a WHERE clause with:
   *   baseExpression = SQLAndBlock { [filter.getBaseExpression()] }
   * where filter.getBaseExpression() is the parsed WHERE from a MATCH filter like
   * {@code where: (@rid = $matched.person.@rid)}.
   *
   * <p>The parsed expression has the structure:
   *   OrBlock(AndBlock(OrBlock(BinaryCondition)))
   * and the AND block wraps it in another layer, producing:
   *   AndBlock(OrBlock(AndBlock(OrBlock(BinaryCondition))))
   *
   * <p>findRidEquality must unwrap these nested wrapper blocks to find the
   * @rid = expr condition. This test verifies the fix for the bug where
   * findRidEquality failed on MATCH-style filters because it didn't unwrap
   * single-element OrBlock/AndBlock wrappers inside the top-level AndBlock.
   */
  @Test
  public void testFindRidEquality_matchStyleFilter() throws ParseException {
    // Parse a WHERE clause matching what the MATCH planner sees:
    // "where: (@rid = $matched.person.@rid)" is parsed as a full WHERE clause
    var innerWhere = parseWhere("@rid = $matched.person.@rid");

    // Simulate what addAliases does: wrap in a new AndBlock
    var outerWhere = new SQLWhereClause(-1);
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(innerWhere.getBaseExpression());
    outerWhere.setBaseExpression(andBlock);

    var result = outerWhere.findRidEquality();
    assertNotNull(
        "findRidEquality should detect @rid equality in MATCH-style nested filter",
        result);

    // Verify the extracted expression references the correct $matched alias
    var aliases = result.getMatchPatternInvolvedAliases();
    assertNotNull("Should have involved aliases", aliases);
    assertTrue("Should reference 'person' alias", aliases.contains("person"));
  }

  /**
   * Multi-condition MATCH-style filter: @rid = $matched.person.@rid AND score > 3.
   * When addAliases adds two separate filter conditions for the same alias
   * (from two MATCH references), each condition is added as a separate
   * sub-block in the outer AndBlock. This mimics the real MATCH planner
   * behavior where filters accumulate.
   */
  @Test
  public void testFindRidEquality_matchStyleCompound_separateFilters()
      throws ParseException {
    // Simulate addAliases adding two filters for the same alias:
    //   filter1: @rid = $matched.person.@rid
    //   filter2: score > 3
    var filter1 = parseWhere("@rid = $matched.person.@rid");
    var filter2 = parseWhere("score > 3");

    var outerWhere = new SQLWhereClause(-1);
    var andBlock = new SQLAndBlock(-1);
    andBlock.getSubBlocks().add(filter1.getBaseExpression());
    andBlock.getSubBlocks().add(filter2.getBaseExpression());
    outerWhere.setBaseExpression(andBlock);

    var result = outerWhere.findRidEquality();
    assertNotNull(
        "findRidEquality should detect @rid equality when filters are "
            + "added as separate sub-blocks",
        result);
  }

  /**
   * OR condition: @rid = #1:1 OR @rid = #2:2. Multi-element OrBlocks should
   * NOT be unwrapped — findRidEquality should return null for disjunctions.
   */
  @Test
  public void testFindRidEquality_orConditionNotUnwrapped() throws ParseException {
    var where = parseWhere("@rid = #1:1 OR @rid = #2:2");
    var result = where.findRidEquality();
    assertNull(
        "findRidEquality should return null for OR conditions", result);
  }

  /** Null baseExpression → findRidEquality returns null without NPE. */
  @Test
  public void testFindRidEquality_nullBaseExpression() {
    var where = new SQLWhereClause(-1);
    assertNull("Should return null for null baseExpression",
        where.findRidEquality());
  }

  /**
   * NOT (@rid = #23:1) → negated condition should NOT be detected as a
   * RID equality. Verifies the negate check in tryExtractRidFromTerm.
   */
  @Test
  public void testFindRidEquality_negatedRidCondition() throws ParseException {
    var where = parseWhere("NOT (@rid = #23:1)");
    var result = where.findRidEquality();
    assertNull("findRidEquality should return null for negated @rid condition",
        result);
  }

  /**
   * Deeply nested single-element wrappers (3+ alternating OrBlock/AndBlock
   * layers). Verifies the while-loop unwraps arbitrarily deep nesting.
   */
  @Test
  public void testFindRidEquality_deeplyNestedWrappers() throws ParseException {
    var innerWhere = parseWhere("@rid = #5:5");
    // Wrap in OrBlock(AndBlock(OrBlock(AndBlock(original))))
    var and1 = new SQLAndBlock(-1);
    and1.getSubBlocks().add(innerWhere.getBaseExpression());
    var or1 = new SQLOrBlock(-1);
    or1.getSubBlocks().add(and1);
    var and2 = new SQLAndBlock(-1);
    and2.getSubBlocks().add(or1);

    var outerWhere = new SQLWhereClause(-1);
    outerWhere.setBaseExpression(and2);

    var result = outerWhere.findRidEquality();
    assertNotNull("Should unwrap deeply nested single-element wrappers", result);
  }

  // =========================================================================
  // containsPositionalParameters — AST-based positional parameter detection
  // =========================================================================

  /**
   * A SELECT with a positional parameter (?) should be detected by the
   * AST walk, not by string matching.
   */
  @Test
  public void containsPositionalParameters_withParam_returnsTrue()
      throws ParseException {
    var parser = getParserFor("SELECT FROM V WHERE name = ?");
    var stm = parser.parse();
    assertTrue(stm.containsPositionalParameters());
  }

  /**
   * A SELECT without any positional parameters returns false.
   */
  @Test
  public void containsPositionalParameters_withoutParam_returnsFalse()
      throws ParseException {
    var parser = getParserFor("SELECT FROM V WHERE name = 'Alice'");
    var stm = parser.parse();
    assertFalse(stm.containsPositionalParameters());
  }

  /**
   * A '?' inside a string literal must NOT be detected as a positional
   * parameter. This is the false positive that the old
   * toString().contains("?") heuristic would trigger.
   */
  @Test
  public void containsPositionalParameters_questionMarkInStringLiteral_returnsFalse()
      throws ParseException {
    var parser = getParserFor("SELECT FROM V WHERE title = 'What?'");
    var stm = parser.parse();
    assertFalse(stm.containsPositionalParameters());
  }

  /**
   * Mixed: positional parameter AND a '?' in a string literal. The AST
   * correctly detects the real parameter.
   */
  @Test
  public void containsPositionalParameters_mixedLiteralAndParam_returnsTrue()
      throws ParseException {
    var parser = getParserFor(
        "SELECT FROM V WHERE title = 'What?' AND age > ?");
    var stm = parser.parse();
    assertTrue(stm.containsPositionalParameters());
  }

  private CommandContext getContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    return ctx;
  }
}
