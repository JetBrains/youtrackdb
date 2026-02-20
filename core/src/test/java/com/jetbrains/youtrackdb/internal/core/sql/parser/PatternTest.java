package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternEdge;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

  private CommandContext getContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    return ctx;
  }
}
