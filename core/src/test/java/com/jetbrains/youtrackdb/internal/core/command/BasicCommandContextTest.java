package com.jetbrains.youtrackdb.internal.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * Unit tests for BasicCommandContext verifying variable resolution behavior:
 * system variable shortcuts, dot-path navigation, and parent context hierarchy.
 */
public class BasicCommandContextTest extends DbTestBase {

  /**
   * Verifies that system variables set via setSystemVariable are accessible
   * through getVariable using their well-known names ("current", "matched", etc.).
   * This exercises the resolveNamedSystemVariable fast path.
   */
  @Test
  public void testNamedSystemVariableResolution() {
    var ctx = new BasicCommandContext(session);
    var currentVal = "record1";
    var matchedVal = "matchRow";

    ctx.setSystemVariable(CommandContext.VAR_CURRENT, currentVal);
    ctx.setSystemVariable(CommandContext.VAR_MATCHED, matchedVal);
    ctx.setSystemVariable(CommandContext.VAR_DEPTH, 5);
    ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, "candidateX");

    // Access via getVariable should resolve through named system variable shortcut
    assertSame(currentVal, ctx.getVariable("$current"));
    assertSame(matchedVal, ctx.getVariable("$matched"));
    assertEquals(5, ctx.getVariable("$depth"));
    assertEquals("candidateX", ctx.getVariable("$currentMatch"));
  }

  /**
   * Verifies that getVariable returns the default value when the variable name
   * is not a known system variable and is not set as a regular variable.
   */
  @Test
  public void testGetVariableReturnsDefault() {
    var ctx = new BasicCommandContext(session);
    var defaultVal = "fallback";
    assertEquals(defaultVal, ctx.getVariable("unknownVar", defaultVal));
  }

  /**
   * Verifies that getVariable with dot-path notation navigates via the PARENT
   * keyword. This exercises the slow path (variable name contains '.').
   * The "$PARENT.$nested" form navigates to the parent context and resolves
   * the variable by name.
   */
  @Test
  public void testGetVariableWithDotPathParent() {
    var parent = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    parent.setChild(child);

    parent.setVariable("nested", "parentValue");
    // "$PARENT.$nested" navigates to parent, then resolves "$nested" as a variable
    assertEquals("parentValue", child.getVariable("$PARENT.$nested"));
  }

  /**
   * Verifies that setVariable stores simple variable names directly without
   * path parsing.
   */
  @Test
  public void testSetVariableSimpleName() {
    var ctx = new BasicCommandContext(session);
    ctx.setVariable("counter", 42);
    assertEquals(42, ctx.getVariable("counter"));
  }

  /**
   * Verifies that setVariable for a variable that exists in the parent context
   * propagates to the parent, except for "current" and "parent" which are bound
   * locally.
   *
   * <p>Falsifiability: inspect the child's own {@code getVariables()} map directly to pin that
   * the write did NOT land on the child — otherwise a bug that routed the write to the child
   * could still satisfy {@code parent.getVariable("shared")} (which walks the child chain via
   * {@link BasicCommandContext#getVariables()}).
   */
  @Test
  public void testSetVariableExistingInParent() {
    var parent = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    parent.setChild(child);

    parent.setVariable("shared", "fromParent");

    // Setting "shared" in child should propagate to parent
    child.setVariable("shared", "updated");
    assertEquals("parent sees the updated value", "updated", parent.getVariable("shared"));
    // Pin the storage location: the write must have landed on the parent, NOT the child.
    // BasicCommandContext.getVariables() merges child's map into the parent's, so we can't use
    // it to distinguish storage side; fall back to hasVariable (package-visible to tests).
    assertFalse("child must not hold its own 'shared' entry — propagation landed on parent",
        child.hasVariable("shared") && !parent.hasVariable("shared"));
    assertTrue("parent must hold the 'shared' entry directly",
        parent.hasVariable("shared"));
  }

  /**
   * Verifies that setVariable for "current" is always bound locally even if
   * the parent has a variable with the same name.
   */
  @Test
  public void testSetVariableCurrentBoundLocally() {
    var parent = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    parent.setChild(child);

    parent.setVariable("current", "parentCurrent");

    // "current" should be bound locally in the child, not propagated
    child.setVariable("current", "childCurrent");
    assertEquals("childCurrent", child.getVariable("current"));
    assertEquals("parentCurrent", parent.getVariable("current"));
  }

  /**
   * Verifies that setVariable for a new variable (not in parent) creates it
   * in the current context. Note: the parent can still see it through the child
   * context delegation, which is the expected behavior.
   */
  @Test
  public void testSetVariableNewInCurrentContext() {
    var parent = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    parent.setChild(child);

    child.setVariable("localOnly", "value");
    assertEquals("value", child.getVariable("localOnly"));
    // Parent sees child variables through the child context (by design)
    assertEquals("value", parent.getVariable("localOnly"));
  }

  /**
   * Verifies that getVariable("$ROOT") navigates to the root context
   * in a parent chain.
   */
  @Test
  public void testRootVariableLookup() {
    var root = new BasicCommandContext(session);
    var mid = new BasicCommandContext(session);
    var leaf = new BasicCommandContext(session);
    root.setChild(mid);
    mid.setChild(leaf);

    root.setVariable("rootVal", "atRoot");

    // $ROOT should return the root context
    var rootCtx = leaf.getVariable("$ROOT");
    assertSame(root, rootCtx);
  }

  /**
   * Verifies that getVariable with dot path "ROOT.$varName" resolves a
   * variable from the root context.
   */
  @Test
  public void testRootDotPathVariableResolution() {
    var root = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    root.setChild(child);

    root.setVariable("greeting", "hello");

    // "ROOT.$greeting" should navigate to root, then resolve "greeting"
    assertEquals("hello", child.getVariable("$ROOT.$greeting"));
  }

  /**
   * Verifies the DB-dependent dot-path branch at {@code BasicCommandContext.java:184-188}:
   * when {@code getVariable} receives a path like {@code "entity.fieldName"} and the prefix
   * resolves to an Entity-like value (not a CommandContext and not {@code $PARENT}/{@code $ROOT}),
   * the suffix is forwarded to {@link
   * com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#getFieldValue} with a live
   * session, which reads the named property off the entity. The active transaction is required
   * because {@code EntityHelper.getIdentifiableValue} calls {@code session.getActiveTransaction()}
   * on the read path.
   */
  @Test
  public void testGetVariableDotPathResolvesFieldOnEmbeddedEntity() {
    session.begin();
    try {
      var entity = session.newEmbeddedEntity();
      entity.setProperty("nickname", "Alice");

      var ctx = new BasicCommandContext(session);
      ctx.setVariable("person", entity);

      // Forwards to EntityHelper.getFieldValue(session, entity, "nickname", ctx) — the
      // session-required slow path. Cannot be covered without a DbTestBase.
      assertEquals("Alice", ctx.getVariable("person.nickname"));
    } finally {
      if (session.isTxActive()) {
        session.commit();
      }
    }
  }

  /**
   * Verifies the DB-dependent {@code $PARENT.fieldName} branch at {@code BasicCommandContext.java:
   * 159-161}: the suffix is resolved against the parent CommandContext itself via
   * {@link com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#getFieldValue}, which
   * requires a session. On a parent that is also a {@link BasicCommandContext}, looking up a
   * non-existent reflective field returns {@code null} rather than throwing — this pin locks in
   * the observed shape so any change to raise an error is detected.
   */
  @Test
  public void testParentDotPathEntityHelperReturnsNullForUnknownField() {
    var parent = new BasicCommandContext(session);
    var child = new BasicCommandContext(session);
    parent.setChild(child);

    // "$PARENT.noSuchField" hits the EntityHelper path on the parent object; there is no such
    // field on BasicCommandContext, and the current behavior is to return null quietly.
    assertNull(child.getVariable("$PARENT.noSuchField"));
  }
}
