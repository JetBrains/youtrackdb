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

  @Test
  public void addNode_anonymousAlias_isPreservedExactly() {
    // Caller is responsible for default-alias generation; the builder must not rewrite.
    var ir = new MatchPatternBuilder().addNode("$c0", "V", null, false).build();
    assertNotNull(ir.pattern().aliasToNode.get("$c0"));
    assertEquals("$c0", ir.pattern().aliasToNode.get("$c0").alias);
  }

  @Test
  public void addNode_nullAlias_throwsNPE() {
    assertThrows(
        NullPointerException.class,
        () -> new MatchPatternBuilder().addNode(null, "V", null, false));
  }

  @Test
  public void addNode_repeatedWithDifferentClass_overwritesAliasClass() {
    // Pins the documented merge contract: a non-null/non-blank className
    // overwrites the previously-registered class for the same alias.
    var ir =
        new MatchPatternBuilder()
            .addNode("a", "Person", null, false)
            .addNode("a", "Employee", null, false)
            .build();

    assertEquals("Employee", ir.aliasClasses().get("a"));
  }

  @Test
  public void addNode_repeatedWithDifferentWhere_overwritesAliasFilter() {
    // Pins the documented merge contract: a non-null where-clause overwrites
    // the previously-registered clause for the same alias.
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

  @Test
  public void addNode_repeatedWithBlankClassNameSecond_preservesOriginalClass() {
    // The "skip-on-blank" guard keeps a previously-registered class in place
    // when a later addNode call passes null/blank — this lets edge-target
    // re-registration upgrade `optional` without erasing class info.
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

  @Test
  public void addNode_optionalIsMonotonic_falseDoesNotClearTrue() {
    // Documented monotonic-upgrade contract for optional: once true, never cleared.
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

  @Test
  public void addEdge_outDirection_pathItemRendersAsOutMethodCall() {
    // Direction-encoding lives inside SQLMatchPathItem.method.methodName ("in"/"out"/"both"),
    // not in topology counts. Render the path item to verify the right method name landed.
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, "Knows", null, null, null)
            .build();
    var rendered = renderPathItemFor(ir, "a");
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
    var rendered = renderPathItemFor(ir, "a");
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
    var rendered = renderPathItemFor(ir, "a");
    assertTrue(
        "BOTH direction must produce '.both(...)' method call: " + rendered,
        rendered.startsWith(".both("));
    assertTrue("path item must capture the edge label 'Friend': " + rendered,
        rendered.contains("Friend"));
    assertEquals(2, ir.pattern().aliasToNode.size());
    assertEquals(1, ir.pattern().getNumOfEdges());
  }

  @Test
  public void addEdge_nullEdgeLabel_pathItemRendersWithDefaultEdgeClass() {
    // When edgeLabel is null, SQLMatchPathItem.graphPath substitutes the literal
    // class name "E". Asserting `.out(E){...` (with the trailing closing paren)
    // pins the exact default rather than any string containing the letter "E" —
    // a future change to e.g. "Edge" would render `.out(Edge){...` and fail.
    var ir =
        new MatchPatternBuilder()
            .addEdge("a", "b", Direction.OUT, null, null, null, null)
            .build();
    var rendered = renderPathItemFor(ir, "a");
    // The class name is rendered with surrounding quotes by SQLBaseExpression's
    // string-quoting (see graphPath); pin the exact rendered shape so a change
    // to a different default class (e.g. "Edge") would render '.out("Edge")'
    // and fail this assertion.
    assertEquals(".out(\"E\"){as: b}", rendered);
  }

  @Test
  public void addEdge_implicitlyCreatesUnregisteredEndpoints() {
    // Neither alias was registered via addNode; addEdge must implicitly create both.
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
  public void addEdge_nullFromAlias_throwsNPE() {
    var b = new MatchPatternBuilder();
    assertThrows(
        NullPointerException.class,
        () -> b.addEdge(null, "b", Direction.OUT, "E", null, null, null));
  }

  @Test
  public void addEdge_nullToAlias_throwsNPE() {
    var b = new MatchPatternBuilder();
    assertThrows(
        NullPointerException.class,
        () -> b.addEdge("a", null, Direction.OUT, "E", null, null, null));
  }

  @Test
  public void addEdge_nullDirection_throwsNPE() {
    var b = new MatchPatternBuilder();
    assertThrows(
        NullPointerException.class,
        () -> b.addEdge("a", "b", null, "E", null, null, null));
  }

  // ── hasAlias ──

  @Test
  public void hasAlias_returnsTrueAfterAddNode_falseForUnknown() {
    // Pins the explicit-registration branch of hasAlias. Production callers
    // (the pattern-form NOT recogniser's origin-presence check) rely on
    // hasAlias returning true once an alias has been registered via addNode,
    // and false for any alias the builder has never seen. A regression that
    // narrowed the lookup to only edge-registered aliases would silently
    // accept an in-progress pattern that has no positive-pattern node for the
    // NOT origin.
    var b = new MatchPatternBuilder().addNode("v0", "Person", null, false);
    assertTrue("alias registered via addNode must be reported as present", b.hasAlias("v0"));
    assertFalse(
        "unknown alias must be reported as absent", b.hasAlias("phantom"));
  }

  @Test
  public void hasAlias_returnsTrueAfterAddEdgeImplicitRegistration() {
    // Pins the implicit-registration branch of hasAlias. addEdge calls
    // Pattern.addExpression internally which performs getOrCreateNode for
    // both endpoints, so the target alias becomes pattern-registered even
    // without an explicit addNode call. The pattern-form NOT recogniser's
    // origin-presence check must observe this branch — a regression that
    // routed hasAlias around Pattern.get (e.g. against a private addNode-only
    // map) would miss the implicit-registration case and incorrectly decline.
    var b =
        new MatchPatternBuilder()
            .addNode("origin", "Person", null, false)
            .addEdge("origin", "target", Direction.OUT, "Knows", null, null, null);
    assertTrue(
        "addEdge's implicit getOrCreateNode must register the target alias",
        b.hasAlias("target"));
  }

  @Test
  public void hasAlias_nullAlias_returnsFalse() {
    // Pins the documented null-input guard: hasAlias(null) returns false
    // instead of throwing. Callers that funnel a possibly-null boundary
    // alias through hasAlias (e.g. the pattern-form NOT recogniser's
    // defensive null-boundary check) rely on the guard so they do not have
    // to pre-filter; a regression that removed the null check would turn
    // every such call into a NullPointerException at runtime.
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

    @SuppressWarnings("unchecked")
    Map<String, String> internalClasses = readField(b, "aliasClasses");
    @SuppressWarnings("unchecked")
    Map<String, SQLWhereClause> internalFilters = readField(b, "aliasFilters");
    assertEquals("Person", internalClasses.get("p"));
    assertSame(where, internalFilters.get("p"));
  }

  @Test
  public void build_oneShot_subsequentAddNodeThrows() {
    var b = new MatchPatternBuilder().addNode("a", "Person", null, false);
    b.build();
    // The one-shot contract makes the ownership-transfer to the planner explicit:
    // any further mutation after build() must fail loudly rather than half-update
    // the previously-returned IR (whose Pattern is shared by reference).
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

  /// Renders the single outgoing path item attached to the node bound to `fromAlias`.
  /// Path-item rendering carries the direction-encoding method call ("in"/"out"/"both")
  /// plus the edge label, which is what these tests need to verify.
  private static String renderPathItemFor(MatchPatternBuilder.PatternIR ir, String fromAlias) {
    var node = ir.pattern().aliasToNode.get(fromAlias);
    assertNotNull("node '" + fromAlias + "' must exist", node);
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
        f.setAccessible(true);
        return (T) f.get(owner);
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName + " on " + owner.getClass());
  }
}
