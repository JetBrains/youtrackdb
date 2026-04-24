package com.jetbrains.youtrackdb.internal.core.query;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemFieldMultiAbstract;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Standalone tests for {@link QueryRuntimeValueMulti}. The class is a plain
 * holder for a multi-value filter operand — constructor, three getters, and
 * {@link #toString} — so the tests avoid database setup.
 *
 * <p>{@code QueryRuntimeValueMulti} is live: it is constructed in
 * {@link SQLFilterItemFieldMultiAbstract#getValue} and returned from filter
 * evaluation whenever a dotted field expression refers to multiple values.
 * Regressions in its {@code toString} format surface in error messages and
 * logging; regressions in {@code getValues} break downstream operator-level
 * comparisons.
 */
public class QueryRuntimeValueMultiTest {

  // Null definition is safe — QueryRuntimeValueMulti only stores the
  // reference. No method exercised here dereferences it.
  private static final SQLFilterItemFieldMultiAbstract NULL_DEF = null;

  @Test
  public void testToStringEmptyArray() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[0], Collections.emptyList());
    Assert.assertEquals("[]", value.toString());
  }

  @Test
  public void testToStringSingleValue() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"a"},
        Collections.emptyList());
    Assert.assertEquals("[a]", value.toString());
  }

  @Test
  public void testToStringMultipleValuesCommaSeparated() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"a", "b", "c"},
        Collections.emptyList());
    Assert.assertEquals("[a,b,c]", value.toString());
  }

  @Test
  public void testToStringMixedTypesDelegatesToToString() {
    // toString appends values via StringBuilder.append(Object), which calls
    // Object.toString(). Null values yield the literal "null".
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {42, null, "x"},
        Collections.emptyList());
    Assert.assertEquals("[42,null,x]", value.toString());
  }

  @Test
  public void testToStringNullValuesArrayReturnsEmptyString() {
    // Guarded path: values == null → "". Documents the contract for callers
    // that may serialize before initialization.
    var value = new QueryRuntimeValueMulti(NULL_DEF, null, Collections.emptyList());
    Assert.assertEquals("", value.toString());
  }

  @Test
  public void testGetValuesReturnsStoredArray() {
    var arr = new Object[] {1, 2, 3};
    var value = new QueryRuntimeValueMulti(NULL_DEF, arr, Collections.emptyList());
    Assert.assertSame("getValues must return the stored reference", arr,
        value.getValues());
  }

  @Test
  public void testGetDefinitionReturnsStoredReference() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[0],
        Collections.emptyList());
    Assert.assertNull(value.getDefinition());
  }

  @Test
  public void testGetCollateReturnsByIndex() {
    var collate = Collate.defaultCollate();
    List<Collate> collates = List.of(collate);
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"x"}, collates);
    Assert.assertSame(collate, value.getCollate(0));
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testGetCollateOutOfRangeThrows() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"x"},
        Collections.emptyList());
    // Backing list is empty — any index access must throw.
    value.getCollate(0);
  }
}
