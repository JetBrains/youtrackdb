package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder.Direction;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for {@link MatchPatternBuilder}.
 *
 * <p>Verifies topology and class/filter map population for: single-node patterns; out / in /
 * both edges; multi-hop chains; optional nodes; implicit-fromAlias creation; class-name and
 * where-clause registration; alias-prefix preservation (the builder must not generate
 * default aliases — that's the caller's responsibility); rejection of unsupported
 * variable-depth parameters.
 */
public class MatchPatternBuilderTest {

  // ── addNode ──

  @Test
  public void addNode_singleNode_populatesAliasToNodeAndAliasClasses() {
    var ir = new MatchPatternBuilder().addNode("a", "Person", null, false).build();

    assertEquals(1, ir.pattern().aliasToNode.size());
    var node = ir.pattern().aliasToNode.get("a");
    assertNotNull("node 'a' should be registered", node);
    assertEquals("a", node.alias);
    assertFalse("optional flag should default to false", node.optional);
    assertEquals("Person", ir.aliasClasses().get("a"));
    assertTrue("no where clause provided", ir.aliasFilters().isEmpty());
    assertEquals(0, ir.pattern().getNumOfEdges());
  }

  @Test
  public void addNode_withWhereClause_populatesAliasFilters() {
    var b = new MatchPatternBuilder();
    var b2 = new MatchWhereBuilder();
    var where = b2.wrap(b2.eq("age", MatchLiteralBuilder.toLiteral(30L)));

    var ir = b.addNode("p", "Person", where, false).build();

    assertSame(where, ir.aliasFilters().get("p"));
  }

  @Test
  public void addNode_optionalTrue_marksNodeOptional() {
    var ir = new MatchPatternBuilder().addNode("p", "Person", null, true).build();
    assertTrue(ir.pattern().aliasToNode.get("p").isOptionalNode());
  }

  @Test
  public void addNode_idempotentByAlias_reusesExistingNode() {
    var b = new MatchPatternBuilder();
    var ir = b.addNode("p", "Person", null, false).addNode("p", "Person", null, true).build();

    assertEquals(1, ir.pattern().aliasToNode.size());
    assertTrue(
        "second addNode call should upgrade the node to optional",
        ir.pattern().aliasToNode.get("p").isOptionalNode());
  }

  @Test
  public void addNode_blankClassName_doesNotPopulateAliasClasses() {
    var ir = new MatchPatternBuilder().addNode("a", "", null, false).build();
    assertTrue("blank className must be ignored", ir.aliasClasses().isEmpty());
    assertNotNull("the node itself is still registered", ir.pattern().aliasToNode.get("a"));
  }

  /**
   * Caller is responsible for default-alias generation; the builder must not rewrite.
   */
  @Test
  public void addNode_anonymousAlias_isPreservedExactly() {
    var ir = new MatchPatternBuilder().addNode("$c0", "V", null, false).build();
    assertNotNull(ir.pattern().aliasToNode.get("$c0"));
    assertEquals("$c0", ir.pattern().aliasToNode.get("$c0").alias);
  }

  /**
   * Pins the documented merge contract: a non-null/non-blank {@code className}
   * overwrites the previously-registered class for the same alias.
   */
  @Test
  public void addNode_repeatedWithDifferentClass_overwritesAliasClass() {
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "Person", null, false)
            .addNode("a", "Employee", null, false)
            .build();

    assertEquals("Employee", ir.aliasClasses().get("a"));
  }

  /**
   * Pins the documented merge contract: a non-null where-clause overwrites
   * the previously-registered clause for the same alias.
   */
  @Test
  public void addNode_repeatedWithDifferentWhere_overwritesAliasFilter() {
    var b2 = new MatchWhereBuilder();
    var w1 = b2.wrap(b2.eq("a", MatchLiteralBuilder.toLiteral(1L)));
    var w2 = b2.wrap(b2.eq("a", MatchLiteralBuilder.toLiteral(2L)));
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "P", w1, false)
            .addNode("a", "P", w2, false)
            .build();

    assertSame("second where clause must win on overwrite", w2, ir.aliasFilters().get("a"));
  }

  /**
   * The skip-on-blank guard keeps a previously-registered class in place when a later
   * {@link MatchPatternBuilder#addNode} call passes null/blank — this lets edge-target
   * re-registration upgrade {@code optional} without erasing class info.
   */
  @Test
  public void addNode_repeatedWithBlankClassNameSecond_preservesOriginalClass() {
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "Person", null, false)
            .addNode("a", null, null, true)
            .build();

    assertEquals(
        "blank className must not erase the previously-registered class",
        "Person",
        ir.aliasClasses().get("a"));
    assertTrue(
        "but the optional flag should still upgrade",
        ir.pattern().aliasToNode.get("a").isOptionalNode());
  }

  /** Documented monotonic-upgrade contract for optional: once true, never cleared. */
  @Test
  public void addNode_optionalIsMonotonic_falseDoesNotClearTrue() {
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "Person", null, true)
            .addNode("a", "Person", null, false)
            .build();
    assertTrue(
        "second addNode with optional=false must NOT clear the existing optional flag",
        ir.pattern().aliasToNode.get("a").isOptionalNode());
  }

  // ── addEdge ──

  @Test
  public void addEdge_outDirection_createsEdgeBetweenAliases() {
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "Person", null, false)
            .addNode("b", "Person", null, false)
            .addEdge("a", "b", Direction.OUT, "Knows", null, null, null)
            .build();

    assertEquals(2, ir.pattern().aliasToNode.size());
    assertEquals(1, ir.pattern().getNumOfEdges());
    var aNode = ir.pattern().aliasToNode.get("a");
    var bNode = ir.pattern().aliasToNode.get("b");
    assertEquals("a should have one outgoing edge", 1, aNode.out.size());
    assertEquals("b should have one incoming edge", 1, bNode.in.size());
    var edge = aNode.out.iterator().next();
    assertSame(aNode, edge.out);
    assertSame(bNode, edge.in);
  }

  /**
   * Direction-encoding lives inside {@code SQLMatchPathItem.method.methodName}
   * ({@code "in"} / {@code "out"} / {@code "both"}), not in topology counts.
   * Render the path item to verify the right method name landed.
   */
  @Test
  public void addEdge_outDirection_pathItemRendersAsOutMethodCall() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, "Knows", null, null, null)
            .build();
    var rendered = renderPathItemFor(ir);
    assertTrue(
        "OUT direction must produce '.out(...)' method call: " + rendered,
        rendered.startsWith(".out("));
    assertTrue("path item must capture the edge label 'Knows': " + rendered,
        rendered.contains("Knows"));
  }

  @Test
  public void addEdge_inDirection_pathItemRendersAsInMethodCall() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.IN, "Wrote", null, null, null)
            .build();
    var rendered = renderPathItemFor(ir);
    assertTrue(
        "IN direction must produce '.in(...)' method call: " + rendered,
        rendered.startsWith(".in("));
    assertTrue("path item must capture the edge label 'Wrote': " + rendered,
        rendered.contains("Wrote"));
    assertEquals(2, ir.pattern().aliasToNode.size());
    assertEquals(1, ir.pattern().getNumOfEdges());
  }

  @Test
  public void addEdge_bothDirection_pathItemRendersAsBothMethodCall() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.BOTH, "Friend", null, null, null)
            .build();
    var rendered = renderPathItemFor(ir);
    assertTrue(
        "BOTH direction must produce '.both(...)' method call: " + rendered,
        rendered.startsWith(".both("));
    assertTrue("path item must capture the edge label 'Friend': " + rendered,
        rendered.contains("Friend"));
    assertEquals(2, ir.pattern().aliasToNode.size());
    assertEquals(1, ir.pattern().getNumOfEdges());
  }

  /**
   * When {@code edgeLabel} is null, {@code SQLMatchPathItem.graphPath} substitutes the literal
   * class name {@code "E"}. Asserting {@code .out(E){...}} pins the exact default rather than
   * any string containing the letter {@code E} — a future change to e.g. {@code "Edge"} would
   * render {@code .out(Edge){...}} and fail.
   */
  @Test
  public void addEdge_nullEdgeLabel_pathItemRendersWithDefaultEdgeClass() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, null, null, null, null)
            .build();
    var rendered = renderPathItemFor(ir);
    // SQLBaseExpression string-quoting (see graphPath) wraps the class name in quotes.
    assertEquals(".out(\"E\"){as: b}", rendered);
  }

  /**
   * Neither alias was registered via {@link MatchPatternBuilder#addNode}; {@link
   * MatchPatternBuilder#addEdge} must implicitly create both.
   */
  @Test
  public void addEdge_implicitlyCreatesUnregisteredEndpoints() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("fresh1", "fresh2", Direction.OUT, "E", null, null, null)
            .build();

    assertNotNull(ir.pattern().aliasToNode.get("fresh1"));
    assertNotNull(ir.pattern().aliasToNode.get("fresh2"));
    assertTrue("implicit endpoints have no class registered", ir.aliasClasses().isEmpty());
  }

  @Test
  public void addEdge_withEdgeFilter_attachesFilterToTargetPathItem() {
    var b2 = new MatchWhereBuilder();
    var where = b2.wrap(b2.eq("name", MatchLiteralBuilder.toLiteral("foo")));

    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, "E", where, null, null)
            .build();

    assertEquals(1, ir.pattern().getNumOfEdges());
    var edge = ir.pattern().aliasToNode.get("a").out.iterator().next();
    assertSame(
        "edge-filter clause should be the target path item's filter",
        where,
        edge.item.getFilter().getFilter());
  }

  @Test
  public void addEdge_multiHopChain_accumulatesEdges() {
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, "E1", null, null, null)
            .addEdge("b", "c", Direction.OUT, "E2", null, null, null)
            .addEdge("c", "d", Direction.OUT, "E3", null, null, null)
            .build();

    assertEquals(4, ir.pattern().aliasToNode.size());
    assertEquals(3, ir.pattern().getNumOfEdges());
    assertEquals(1, ir.pattern().aliasToNode.get("a").out.size());
    assertEquals(1, ir.pattern().aliasToNode.get("b").in.size());
    assertEquals(1, ir.pattern().aliasToNode.get("b").out.size());
    assertEquals(1, ir.pattern().aliasToNode.get("d").in.size());
  }

  @Test
  public void addEdge_whileConditionPresent_throwsUnsupported() {
    var b2 = new MatchWhereBuilder();
    var whileC = b2.wrap(b2.eq("k", MatchLiteralBuilder.toLiteral(1L)));

    var b = new MatchPatternBuilder();
    assertThrows(
        UnsupportedOperationException.class,
        () -> b.addEdge("a", "b", Direction.OUT, "E", null, whileC, null));
  }

  @Test
  public void addEdge_maxDepthPresent_throwsUnsupported() {
    var b = new MatchPatternBuilder();
    assertThrows(
        UnsupportedOperationException.class,
        () -> b.addEdge("a", "b", Direction.OUT, "E", null, null, 5));
  }

  @Test
  public void addEdge_nullDirection_throwsNPE() {
    var b = new MatchPatternBuilder();
    assertThrows(
        NullPointerException.class,
        () -> b.addEdge("a", "b", null, "E", null, null, null));
  }

  // ── hasAlias ──

  /**
   * Pins the explicit-registration branch of {@link MatchPatternBuilder#hasAlias(String)}.
   * Production callers (the pattern-form NOT recogniser's origin-presence check) rely on
   * {@code hasAlias} returning true once an alias has been registered via
   * {@link MatchPatternBuilder#addNode}, and false for any alias the builder has never seen.
   */
  @Test
  public void hasAlias_returnsTrueAfterAddNode_falseForUnknown() {
    var b = new MatchPatternBuilder().addNode("v0", "Person", null, false);
    assertTrue("alias registered via addNode must be reported as present", b.hasAlias("v0"));
    assertFalse(
        "unknown alias must be reported as absent", b.hasAlias("phantom"));
  }

  /**
   * Pins the implicit-registration branch of {@link MatchPatternBuilder#hasAlias(String)}.
   * {@link MatchPatternBuilder#addEdge} calls {@code Pattern.addExpression} internally,
   * which performs {@code getOrCreateNode} for both endpoints.
   */
  @Test
  public void hasAlias_returnsTrueAfterAddEdgeImplicitRegistration() {
    var b =
        new MatchPatternBuilder()
            .addNode("origin", "Person", null, false)
            .addEdge("origin", "target", Direction.OUT, "Knows", null, null, null);
    assertTrue(
        "addEdge's implicit getOrCreateNode must register the target alias",
        b.hasAlias("target"));
  }

  /**
   * Pins the documented null-input guard: {@link MatchPatternBuilder#hasAlias(String)}
   * returns false for a null alias instead of throwing.
   */
  @Test
  public void hasAlias_nullAlias_returnsFalse() {
    assertFalse("null alias must be reported as absent", new MatchPatternBuilder().hasAlias(null));
  }

  // ── build() one-shot contract ──

  /**
   * {@link MatchPatternBuilder#build()} must hand back defensive copies of the alias
   * maps so callers can mutate the returned {@link MatchPatternBuilder.PatternIR}
   * without corrupting the builder's internal accumulator state.
   */
  @Test
  public void build_defensiveCopy_aliasMapsIsolatedFromBuilderState() throws Exception {
    var wb = new MatchWhereBuilder();
    var where = wb.wrap(wb.eq("age", MatchLiteralBuilder.toLiteral(30L)));
    var b = new MatchPatternBuilder().addNode("p", "Person", where, false);
    var ir = b.build();

    assertNotSame(
        "aliasClasses must be copied out of the builder",
        readField(b, "aliasClasses"),
        ir.aliasClasses());
    assertNotSame(
        "aliasFilters must be copied out of the builder",
        readField(b, "aliasFilters"),
        ir.aliasFilters());

    ir.aliasClasses().put("p", "Hacked");
    ir.aliasFilters().put("p", where);

    Map<String, String> internalClasses = readField(b, "aliasClasses");
    Map<String, SQLWhereClause> internalFilters = readField(b, "aliasFilters");
    assertEquals("Person", internalClasses.get("p"));
    assertSame(where, internalFilters.get("p"));
  }

  /**
   * The one-shot contract makes the ownership transfer to the planner explicit: any further
   * mutation after {@link MatchPatternBuilder#build()} must fail loudly rather than half-update
   * the previously-returned IR (whose {@code Pattern} is shared by reference).
   */
  @Test
  public void build_oneShot_subsequentAddNodeThrows() {
    var b = new MatchPatternBuilder().addNode("a", "Person", null, false);
    b.build();
    assertThrows(IllegalStateException.class, () -> b.addNode("b", "Place", null, false));
  }

  @Test
  public void build_oneShot_subsequentAddEdgeThrows() {
    var b = new MatchPatternBuilder().addNode("a", "Person", null, false);
    b.build();
    assertThrows(
        IllegalStateException.class,
        () -> b.addEdge("a", "b", Direction.OUT, "E", null, null, null));
  }

  @Test
  public void build_oneShot_secondBuildThrows() {
    var b = new MatchPatternBuilder().addNode("a", "Person", null, false);
    b.build();
    assertThrows(IllegalStateException.class, b::build);
  }

  // ── helpers ──

  /** Renders the outgoing path item for {@code fromAlias} (direction + edge label). */
  private static String renderPathItemFor(MatchPatternBuilder.PatternIR ir) {
    var node = ir.pattern().aliasToNode.get("a");
    assertNotNull("node '" + "a" + "' must exist", node);
    assertEquals(1, node.out.size());
    var edge = node.out.iterator().next();
    var sb = new StringBuilder();
    edge.item.toString(new HashMap<>(), sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static <T> T readField(Object owner, String fieldName) throws Exception {
    Class<?> c = owner.getClass();
    while (c != null) {
      try {
        Field f = c.getDeclaredField(fieldName);
        if (!f.trySetAccessible()) {
          throw new IllegalAccessException("Cannot access field " + fieldName + " on " + c);
        }
        return ((Class<T>) Map.class).cast(f.get(owner));
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName + " on " + owner.getClass());
  }
}
