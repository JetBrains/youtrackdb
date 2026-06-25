package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInOperator;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Unit tests for {@link SqlInOperatorBinding} — the canonical access point for
 * setting {@code SQLInCondition.operator} via the cached, class-load-time-resolved
 * reflective handle.
 *
 * <p>Two correctness goals:
 *
 * <ol>
 *   <li>The helper must populate the (parser-private) operator field with a real
 *       {@link SQLInOperator} so {@code SQLInCondition.toString} renders the IN
 *       fragment without NPE-ing on a half-formed condition.
 *   <li>The reflective handle must be cached as a {@code static final Field} so the
 *       multi-ID translation hot path does not pay the {@code getDeclaredField} +
 *       {@code setAccessible} cost on every call.
 * </ol>
 */
public class SqlInOperatorBindingTest {

  /**
   * Verifies the helper writes a non-null {@link SQLInOperator} into the
   * {@code SQLInCondition.operator} field. The field is {@code protected} with no public
   * getter, so the test reflects on it directly.
   */
  @Test
  public void setOperator_populatesOperatorFieldWithSQLInOperator() throws Exception {
    var condition = new SQLInCondition(-1);

    SqlInOperatorBinding.setOperator(condition);

    var operatorField = SQLInCondition.class.getDeclaredField("operator");
    operatorField.setAccessible(true);
    Object actual = operatorField.get(condition);
    assertNotNull("operator field must be set", actual);
    assertTrue("operator must be a SQLInOperator instance", actual instanceof SQLInOperator);
  }

  /**
   * Locks in the cache contract: the helper must declare the resolved Field as
   * {@code private static final}. A regression that changes the reflective lookup back
   * to per-call {@code getDeclaredField} would have to remove this field, which the
   * test catches.
   */
  @Test
  public void operatorField_isStaticFinalCache() throws Exception {
    Field cached = SqlInOperatorBinding.class.getDeclaredField("OPERATOR_FIELD");
    int mods = cached.getModifiers();
    assertTrue("OPERATOR_FIELD must be static (resolved once at class load)",
        Modifier.isStatic(mods));
    assertTrue("OPERATOR_FIELD must be final (immutable cached handle)",
        Modifier.isFinal(mods));
    assertTrue("OPERATOR_FIELD must be private (encapsulated)", Modifier.isPrivate(mods));
    cached.setAccessible(true);
    Object value = cached.get(null);
    assertNotNull("cached field must be resolved at class load, not lazy", value);
    assertEquals("Field", value.getClass().getSimpleName());
  }

  /**
   * The helper is intended to be safe to call repeatedly. Each call writes a fresh
   * {@link SQLInOperator}; the underlying cached Field handle remains a single instance.
   * Doing 2k calls in a tight loop sanity-checks that no per-call resource leak is
   * introduced.
   */
  @Test
  public void setOperator_isSafeAcrossManyCalls() throws Exception {
    var operatorField = SQLInCondition.class.getDeclaredField("operator");
    operatorField.setAccessible(true);
    SQLInOperator previous = null;
    for (int i = 0; i < 2000; i++) {
      var condition = new SQLInCondition(-1);
      SqlInOperatorBinding.setOperator(condition);
      Object value = operatorField.get(condition);
      assertTrue(value instanceof SQLInOperator);
      // Each call yields a fresh operator instance — the helper does not share a
      // mutable singleton across conditions.
      if (previous != null) {
        org.junit.Assert.assertNotSame(previous, value);
      }
      previous = (SQLInOperator) value;
    }
  }

  /**
   * Defence in depth: calling the helper twice on the same condition leaves it with
   * the second operator (no exception, no double-wrap). Documents that the helper is
   * idempotent at the call-site level.
   */
  @Test
  public void setOperator_calledTwice_leavesLastWriterWins() throws Exception {
    var condition = new SQLInCondition(-1);
    SqlInOperatorBinding.setOperator(condition);
    var operatorField = SQLInCondition.class.getDeclaredField("operator");
    operatorField.setAccessible(true);
    Object first = operatorField.get(condition);

    SqlInOperatorBinding.setOperator(condition);
    Object second = operatorField.get(condition);

    assertNotNull(first);
    assertNotNull(second);
    org.junit.Assert.assertNotSame("each call writes a fresh operator instance", first, second);
  }

}
