/*
 *
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.query;

import static org.mockito.Mockito.mock;

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

  /**
   * Leading-null pins the separator logic: {@code if (i++ > 0)} must NOT
   * emit a leading comma when position 0 is null. A mutation that added
   * the comma unconditionally would produce "[,null,x]".
   */
  @Test
  public void testToStringLeadingNull() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {null, "x"},
        Collections.emptyList());
    Assert.assertEquals("[null,x]", value.toString());
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
  public void testGetValuesReturnsStoredEmptyArray() {
    // Empty-array reference parity: getValues() must return the exact
    // stored reference, not null and not a fresh empty array. Callers
    // elsewhere branch on values == null, so this distinction matters.
    var arr = new Object[0];
    var value = new QueryRuntimeValueMulti(NULL_DEF, arr, Collections.emptyList());
    Assert.assertSame(arr, value.getValues());
  }

  @Test
  public void testGetDefinitionReturnsStoredReference() {
    // Use a non-null mock so the assertion is identity-sensitive (assertSame)
    // rather than null-shape-sensitive (assertNull). This catches a
    // mutation where getDefinition() hard-codes null, which the weaker
    // null-input test would have missed.
    var def = mock(SQLFilterItemFieldMultiAbstract.class);
    var value = new QueryRuntimeValueMulti(def, new Object[0], Collections.emptyList());
    Assert.assertSame(def, value.getDefinition());
  }

  @Test
  public void testGetDefinitionWithNullReturnsNull() {
    // Also pin the null-input path so getDefinition contract is fully
    // characterized.
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[0],
        Collections.emptyList());
    Assert.assertNull(value.getDefinition());
  }

  @Test
  public void testGetCollateReturnsByIndexDistinguishesPositions() {
    // Use two DISTINCT collates so a mutation that ignores the index and
    // always returns collates.get(0) is detectable.
    var c0 = Collate.defaultCollate();
    var c1 = Collate.caseInsensitiveCollate();
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"x", "y"},
        List.of(c0, c1));
    Assert.assertSame(c0, value.getCollate(0));
    Assert.assertSame(c1, value.getCollate(1));
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testGetCollateOutOfRangeThrows() {
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"x"},
        Collections.emptyList());
    // Backing list is empty — any index access must throw.
    value.getCollate(0);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testGetCollateShorterThanValuesThrows() {
    // Realistic schema-evolution case: values longer than the collates
    // list. Access past collates.size()-1 must throw, not return null or
    // the last valid collate.
    var value = new QueryRuntimeValueMulti(NULL_DEF, new Object[] {"a", "b"},
        List.of(Collate.defaultCollate()));
    value.getCollate(1);
  }
}
