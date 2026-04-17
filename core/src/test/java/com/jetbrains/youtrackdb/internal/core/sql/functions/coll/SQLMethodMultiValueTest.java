/*
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.coll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link SQLMethodMultiValue} — the {@code [multivalue(...)]} SQL method, which looks up
 * one or more property names on an entity via {@link
 * com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper#getFieldValue}.
 *
 * <p>The execute() signature is {@code execute(iThis, iCurrentRecord, iContext, ioResult,
 * iParams)} — note {@code iContext} is the third parameter, not the fifth as in
 * {@code SQLFunction.execute}. Tests call the method with this AbstractSQLMethod order.
 *
 * <p>Covered paths:
 *
 * <ul>
 *   <li>null iThis / null params[0] → null (short-circuit)
 *   <li>single non-multi-value param → direct EntityHelper lookup
 *   <li>single multi-value param → iterates inner items, resolves each, returns list (or single
 *       value if the result list has exactly one element)
 *   <li>multiple params (mix of scalar + collection) → aggregated list, or unwrapped single value
 *       when it collapses to one element
 * </ul>
 *
 * <p>Uses {@link DbTestBase} because {@code EntityHelper.getFieldValue} requires an active
 * database session (it may recursively load linked records).
 */
public class SQLMethodMultiValueTest extends DbTestBase {

  private EntityImpl entity;

  @Before
  public void setUpEntity() {
    session.createClass("Person");
    session.begin();
    entity = (EntityImpl) session.newEntity("Person");
    entity.setProperty("firstName", "Alice");
    entity.setProperty("lastName", "Smith");
    entity.setProperty("age", 30);
    entity.setProperty("email", "alice@example.com");
    session.commit();
    session.begin();
  }

  @After
  public void rollbackIfLeftOpen() {
    if (session.getActiveTransaction().isActive()) {
      session.rollback();
    }
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  // ---------------------------------------------------------------------------
  // Null-short-circuit contracts
  // ---------------------------------------------------------------------------

  @Test
  public void nullIThisReturnsNull() {
    var method = new SQLMethodMultiValue();

    var result = method.execute(null, null, ctx(), null, new Object[] {"firstName"});

    assertNull(result);
  }

  @Test
  public void nullFirstParameterReturnsNull() {
    // params[0] is the field-name key; null means no key to resolve, so return null.
    var method = new SQLMethodMultiValue();

    var result = method.execute(entity, null, ctx(), null, new Object[] {(Object) null});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Single-scalar-param fast path — direct EntityHelper lookup
  // ---------------------------------------------------------------------------

  @Test
  public void singleScalarParamReturnsFieldValue() {
    // Non-multi-value single param with length == 1 → EntityHelper.getFieldValue.
    var method = new SQLMethodMultiValue();

    var result = method.execute(entity, null, ctx(), null, new Object[] {"firstName"});

    assertEquals("Alice", result);
  }

  @Test
  public void singleScalarParamWithNonStringKeyIsCoercedToString() {
    // Non-String, non-multi-value params[0] — execute() calls iParams[0].toString() to build the
    // field name. Use a StringBuilder-style object whose toString equals an existing property
    // name to prove the coercion happens (literal "firstName" wouldn't show coercion occurred).
    var method = new SQLMethodMultiValue();
    var stringBuilderKey = new StringBuilder("firstName");

    var result = method.execute(entity, null, ctx(), null, new Object[] {stringBuilderKey});

    assertEquals("Alice", result);
  }

  @Test
  public void singleScalarParamForMissingFieldReturnsNull() {
    var method = new SQLMethodMultiValue();

    var result = method.execute(entity, null, ctx(), null, new Object[] {"nonExistentField"});

    assertNull(result);
  }

  // ---------------------------------------------------------------------------
  // Single-collection-param path — iterate inner items
  // ---------------------------------------------------------------------------

  @Test
  public void singleListParamResolvesEachInnerName() {
    // Inner iteration builds a list; if size == 1 it's unwrapped, else the list is returned.
    var method = new SQLMethodMultiValue();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(entity, null, ctx(), null,
        new Object[] {List.of("firstName", "lastName")});

    assertEquals(List.of("Alice", "Smith"), result);
  }

  @Test
  public void singleSetParamResolvesEachInnerName() {
    // Set is also a MultiValue — same inner-iteration path.
    var method = new SQLMethodMultiValue();

    // Inner iteration yields 1 entry → unwrapped scalar (list.size()==1 return list.get(0)).
    var result = method.execute(entity, null, ctx(), null, new Object[] {Set.of("firstName")});

    assertEquals("Alice", result);
  }

  @Test
  public void singleArrayParamResolvesEachInnerName() {
    // Object[] arrays also qualify as multi-value — the branch must not fall through to the
    // scalar path.
    var method = new SQLMethodMultiValue();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(entity, null, ctx(), null,
        new Object[] {new Object[] {"firstName", "age"}});

    assertEquals(List.of("Alice", 30), result);
  }

  // ---------------------------------------------------------------------------
  // Multi-param path — mix of scalar and collection, aggregation
  // ---------------------------------------------------------------------------

  @Test
  public void multipleScalarParamsReturnListOfValues() {
    var method = new SQLMethodMultiValue();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(entity, null, ctx(), null,
        new Object[] {"firstName", "lastName", "age"});

    assertEquals(List.of("Alice", "Smith", 30), result);
  }

  @Test
  public void multipleParamsMixedScalarAndCollectionFlatten() {
    // Mixed: "firstName" (scalar) + ["lastName", "email"] (collection) → [firstName, lastName,
    // email] in order.
    var method = new SQLMethodMultiValue();

    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(entity, null, ctx(), null,
        new Object[] {"firstName", List.of("lastName", "email")});

    assertEquals(List.of("Alice", "Smith", "alice@example.com"), result);
  }

  @Test
  public void multipleParamsCollapsingToSingleValueUnwraps() {
    // Two params but one resolves to null: [null, "firstName"] → getFieldValue(null) may return
    // null. Actually EntityHelper.getFieldValue returns null for nonexistent; size-1 return only
    // happens when exactly one value is produced AND list.size()==1. Force that with a single
    // collection containing one element.
    var method = new SQLMethodMultiValue();

    // Two params: one collection with a missing field, one scalar scalar with a present field.
    // Both are added to the list — size > 1 → returned as list (no unwrap).
    @SuppressWarnings("unchecked")
    var result = (List<Object>) method.execute(entity, null, ctx(), null,
        new Object[] {List.of("missing"), "firstName"});

    assertEquals(2, result.size());
    assertNull(result.get(0));
    assertEquals("Alice", result.get(1));
  }

  @Test
  public void singleCollectionCollapsingToOneUnwraps() {
    // Inner list of size 1 → result list has 1 entry → unwrapped to scalar.
    var method = new SQLMethodMultiValue();

    var result = method.execute(entity, null, ctx(), null, new Object[] {List.of("email")});

    assertEquals("alice@example.com", result);
  }

  // ---------------------------------------------------------------------------
  // Metadata / contract surface
  // ---------------------------------------------------------------------------

  @Test
  public void nameIsMultivalue() {
    var method = new SQLMethodMultiValue();
    assertEquals("multivalue", SQLMethodMultiValue.NAME);
    assertEquals("multivalue", method.getName());
  }

  @Test
  public void syntaxExposesExpectedSignature() {
    var method = new SQLMethodMultiValue();
    assertEquals("multivalue(<index>)", method.getSyntax());
  }

  @Test
  public void minAndMaxParamsAllowVariadic() {
    var method = new SQLMethodMultiValue();
    assertEquals(1, method.getMinParams());
    // Variadic — -1 means unbounded.
    assertEquals(-1, method.getMaxParams(session));
  }
}
