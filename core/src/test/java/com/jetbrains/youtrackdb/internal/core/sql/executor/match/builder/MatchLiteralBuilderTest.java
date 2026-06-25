package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for {@link MatchLiteralBuilder}.
 *
 * <p>Coverage hits every branch of {@code toLiteral}: String, Number (Long, Integer, Double,
 * BigDecimal), Boolean, RecordIdInternal, Date, List, Set, Map, byte[], plus the unsupported-type
 * IllegalArgumentException branch.
 *
 * <p>Reflection is necessary because {@link SQLExpression} exposes setters but no getters for
 * {@code booleanValue} and {@code literalValue}, and {@link SQLBaseExpression} keeps its
 * {@code string} / {@code number} fields package-private. The tests verify the exact AST routing
 * each input type takes — that's the contract callers depend on for {@code SQLExpression.copy()}
 * to preserve the value through plan creation.
 */
public class MatchLiteralBuilderTest {

  // ── Strings ──

  /**
   * String literals route through {@code mathExpression → SQLBaseExpression.string}; no other
   * {@link SQLExpression} payload field is set.
   */
  @Test
  public void toLiteral_string_routesThroughSQLBaseExpressionStringField() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral("hello");

    var math = expr.getMathExpression();
    assertNotNull("string literal should populate mathExpression", math);
    assertTrue("math expression should be a SQLBaseExpression", math instanceof SQLBaseExpression);
    var stringField = readField(math, "string", String.class);
    assertNotNull("SQLBaseExpression.string should be populated for a String literal", stringField);
    assertTrue(
        "SQLBaseExpression(String) wraps the input in quotes; expected to contain 'hello'",
        stringField.contains("hello"));
    var numberField = readField(math, "number", Object.class);
    assertNull("number field must remain unset when the input is a String", numberField);
    assertNoOtherFieldSet(expr, "mathExpression");
  }

  /**
   * An empty input still triggers the String branch. {@link SQLBaseExpression} wraps it in quotes,
   * so the stored field is non-null even though the original input is {@code ""}.
   */
  @Test
  public void toLiteral_emptyString_isPreservedAsBaseExpressionString() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral("");
    var math = expr.getMathExpression();
    assertTrue(math instanceof SQLBaseExpression);
    assertNotNull(readField(math, "string", String.class));
  }

  // ── Numbers ──

  /**
   * {@code Long} literals route through {@code mathExpression → SQLBaseExpression → SQLInteger};
   * the Java value is preserved as {@link Number} inside {@link SQLInteger}.
   */
  @Test
  public void toLiteral_long_routesThroughSQLBaseExpressionWithSQLInteger() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral(42L);
    assertSQLBaseExpressionWithSQLIntegerValue(expr, 42L);
    assertNoOtherFieldSet(expr, "mathExpression");
  }

  /** {@code int} literals use the same {@link SQLInteger} AST routing as {@code Long}. */
  @Test
  public void toLiteral_integer_routesThroughSQLBaseExpressionWithSQLInteger() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral(7);
    assertSQLBaseExpressionWithSQLIntegerValue(expr, 7);
  }

  /** {@code double} literals use the same {@link SQLInteger} AST routing as integral types. */
  @Test
  public void toLiteral_double_routesThroughSQLBaseExpressionWithSQLInteger() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral(3.14);
    assertSQLBaseExpressionWithSQLIntegerValue(expr, 3.14);
  }

  /** {@link BigDecimal} literals use the same {@link SQLInteger} AST routing; value is preserved. */
  @Test
  public void toLiteral_bigDecimal_routesThroughSQLBaseExpressionWithSQLInteger() throws Exception {
    var bd = new BigDecimal("1234567890.12345");
    var expr = MatchLiteralBuilder.toLiteral(bd);
    assertSQLBaseExpressionWithSQLIntegerValue(expr, bd);
  }

  // ── Booleans ──

  /** {@code true} populates {@code booleanValue} and no other {@link SQLExpression} payload field. */
  @Test
  public void toLiteral_booleanTrue_setsBooleanValueAndNothingElse() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral(Boolean.TRUE);
    assertEquals(Boolean.TRUE, readField(expr, "booleanValue", Boolean.class));
    assertNoOtherFieldSet(expr, "booleanValue");
  }

  /** {@code false} populates {@code booleanValue} and no other {@link SQLExpression} payload field. */
  @Test
  public void toLiteral_booleanFalse_setsBooleanValueAndNothingElse() throws Exception {
    var expr = MatchLiteralBuilder.toLiteral(Boolean.FALSE);
    assertEquals(Boolean.FALSE, readField(expr, "booleanValue", Boolean.class));
    assertNoOtherFieldSet(expr, "booleanValue");
  }

  // ── RID ──

  /**
   * {@link RecordId} literals populate {@code rid} with legacy flag and collection/position
   * identifiers matching the input.
   */
  @Test
  public void toLiteral_recordId_setsRidWithLegacyFlagAndCorrectIdentifiers() throws Exception {
    var rid = new RecordId(12, 345);
    var expr = MatchLiteralBuilder.toLiteral(rid);

    var sqlRid = expr.getRid();
    assertNotNull("RID literal should populate rid field", sqlRid);
    assertTrue("legacy flag must be true so the printer emits #12:345", isLegacy(sqlRid));
    assertEquals(12, readSQLIntegerValue(sqlRid, "collection"));
    assertEquals(345, readSQLIntegerValue(sqlRid, "position"));
    assertNoOtherFieldSet(expr, "rid");
  }

  // ── Date / List / Set / Map / byte[] (literalValue branch) ──

  /** {@link Date} is stored opaquely in {@code literalValue} for {@code SQLExpression.copy()} parity. */
  @Test
  public void toLiteral_date_isStoredAsLiteralValueObject() throws Exception {
    var date = new Date(1_700_000_000_000L);
    var expr = MatchLiteralBuilder.toLiteral(date);
    assertSame(date, readField(expr, "literalValue", Object.class));
    assertNoOtherFieldSet(expr, "literalValue");
  }

  /** {@link List} is stored opaquely in {@code literalValue}. */
  @Test
  public void toLiteral_list_isStoredAsLiteralValueObject() throws Exception {
    var list = List.of(1, 2, 3);
    var expr = MatchLiteralBuilder.toLiteral(list);
    assertSame(list, readField(expr, "literalValue", Object.class));
    assertNoOtherFieldSet(expr, "literalValue");
  }

  /** {@link Set} is stored opaquely in {@code literalValue}. */
  @Test
  public void toLiteral_set_isStoredAsLiteralValueObject() throws Exception {
    Set<String> set = new HashSet<>();
    set.add("a");
    var expr = MatchLiteralBuilder.toLiteral(set);
    assertSame(set, readField(expr, "literalValue", Object.class));
  }

  /** {@link Map} is stored opaquely in {@code literalValue}. */
  @Test
  public void toLiteral_map_isStoredAsLiteralValueObject() throws Exception {
    Map<String, Object> map = new HashMap<>();
    map.put("k", "v");
    var expr = MatchLiteralBuilder.toLiteral(map);
    assertSame(map, readField(expr, "literalValue", Object.class));
  }

  /** {@code byte[]} is stored opaquely in {@code literalValue} with byte-for-byte equality. */
  @Test
  public void toLiteral_byteArray_isStoredAsLiteralValueObject() throws Exception {
    var bytes = new byte[] {1, 2, 3};
    var expr = MatchLiteralBuilder.toLiteral(bytes);
    var stored = readField(expr, "literalValue", Object.class);
    assertTrue(stored instanceof byte[]);
    assertArrayEquals(bytes, (byte[]) stored);
  }

  // ── Unsupported ──

  /**
   * Uses {@code this} (the test instance) as a non-supported Java type — guaranteed not to match
   * any of the {@code instanceof} branches.
   */
  @Test
  public void toLiteral_unsupportedType_throwsIAEWithDescriptiveMessage() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> MatchLiteralBuilder.toLiteral(this));
    assertTrue(
        "IAE message should describe the unsupported class",
        ex.getMessage().contains("Unsupported property value type"));
  }

  // ── Helpers ──

  /** Asserts numeric routing through {@code mathExpression → SQLBaseExpression → SQLInteger}. */
  private static void assertSQLBaseExpressionWithSQLIntegerValue(
      SQLExpression expr, Object expectedNumericValue) throws Exception {
    var math = expr.getMathExpression();
    assertNotNull("number literal should populate mathExpression", math);
    assertTrue(math instanceof SQLBaseExpression);
    var inner = readField(math, "number", Object.class);
    assertNotNull("SQLBaseExpression.number should carry an SQLInteger child", inner);
    assertTrue(
        "Number literals route through SQLInteger; got " + inner.getClass(),
        inner instanceof SQLInteger);
    assertEquals(expectedNumericValue, ((SQLInteger) inner).getValue());
    var stringField = readField(math, "string", String.class);
    assertNull("string field must remain unset when the input is a Number", stringField);
  }

  /** Reads a (possibly inherited) field by name from the given object. */
  private static <T> T readField(Object owner, String fieldName, Class<T> type) throws Exception {
    Class<?> c = owner.getClass();
    while (c != null) {
      try {
        Field f = c.getDeclaredField(fieldName);
        if (!f.trySetAccessible()) {
          throw new IllegalAccessException("Cannot access field " + fieldName + " on " + c);
        }
        return type.cast(f.get(owner));
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName + " on " + owner.getClass());
  }

  /** Reads an {@link SQLInteger} child field and returns its numeric value as {@code int}. */
  private static int readSQLIntegerValue(Object owner, String fieldName) throws Exception {
    var integer = readField(owner, fieldName, SQLInteger.class);
    assertNotNull(integer);
    assertNotNull(integer.getValue());
    return integer.getValue().intValue();
  }

  /** Returns whether the {@link SQLRid#legacy} flag is set on the given node. */
  private static boolean isLegacy(SQLRid rid) throws Exception {
    var legacy = readField(rid, "legacy", Boolean.class);
    return legacy != null && legacy;
  }

  /** Asserts that all {@link SQLExpression} payload fields except {@code expectedField} are unset. */
  private static void assertNoOtherFieldSet(SQLExpression expr, String expectedField)
      throws Exception {
    for (var name : payloadFieldNames()) {
      if (name.equals(expectedField)) {
        continue;
      }
      Object value = readField(expr, name, Object.class);
      assertNull(
          "expected field '"
              + name
              + "' to be unset when '"
              + expectedField
              + "' carries the value",
          value);
    }
  }

  /** Collects non-primitive, non-static field names on {@link SQLExpression} (literal payloads). */
  private static List<String> payloadFieldNames() {
    var names = new ArrayList<String>();
    for (var f : SQLExpression.class.getDeclaredFields()) {
      var mods = f.getModifiers();
      if (Modifier.isStatic(mods)) {
        continue;
      }
      if (f.getType().isPrimitive()) {
        // The parser-generated `id` int and similar bookkeeping primitives are
        // not literal payloads — skip.
        continue;
      }
      names.add(f.getName());
    }
    return names;
  }
}
