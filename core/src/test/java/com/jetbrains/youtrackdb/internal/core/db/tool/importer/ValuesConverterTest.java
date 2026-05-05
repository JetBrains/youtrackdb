/*
 *
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

/**
 * Standalone interface-contract pin for {@link ValuesConverter}. The interface declares a single
 * generic-typed {@code convert(session, value)} method that every importer converter implements.
 * Pinning the method shape here makes any future contract change (rename, parameter reorder,
 * additional method) a build-time failure across all 9 implementations rather than a silent
 * compile-time pickup that could mask a behavioural drift.
 *
 * <p>The test also pins a tiny no-op anonymous implementation to confirm the interface is usable
 * without inheritance gymnastics — converters elsewhere in this package extend
 * {@link AbstractCollectionConverter} or implement the interface directly, and a third-party
 * extension (e.g., a future binary-blob converter) must remain expressible as a plain anonymous
 * class.
 */
public class ValuesConverterTest {

  /**
   * The interface exposes exactly one method, {@code convert}, taking a session and a value of
   * the bound generic type and returning a value of the same type. Any future contract change
   * (additional method, signature drift) flips this assertion.
   */
  @Test
  public void testInterfaceDeclaresOnlyConvertMethod() {
    var declared = ValuesConverter.class.getDeclaredMethods();
    assertEquals(
        "ValuesConverter must declare exactly one method (convert)",
        1, declared.length);

    var method = declared[0];
    assertEquals("convert", method.getName());
    assertEquals(2, method.getParameterCount());
    assertEquals(DatabaseSessionEmbedded.class, method.getParameterTypes()[0]);
    // The second parameter is the generic T, which is erased to Object at runtime — the contract
    // here is only that the impls bind T at the class level, not that the interface itself
    // restricts T.
    assertEquals(Object.class, method.getParameterTypes()[1]);
    assertEquals(Object.class, method.getReturnType());
  }

  /**
   * The interface itself must be public and abstract — third-party importer converters
   * (a hypothetical binary-blob converter, e.g.) would be unable to implement it from outside the
   * package otherwise.
   */
  @Test
  public void testInterfaceIsPublicAndAbstract() {
    var modifiers = ValuesConverter.class.getModifiers();
    assertTrue("ValuesConverter must be public", Modifier.isPublic(modifiers));
    assertTrue("ValuesConverter must be an interface (i.e., abstract)",
        ValuesConverter.class.isInterface());
  }

  /**
   * A trivial anonymous implementation must compile and behave as expected. Pins that
   * {@link ValuesConverter} stays implementable without ceremony — useful when extending the
   * importer with a new value type at the dispatch level.
   */
  @Test
  public void testAnonymousImplementationCompilesAndRoundTripsValue() throws Exception {
    ValuesConverter<String> identity = (session, value) -> value;

    // Use reflection to invoke without needing a real session — the lambda doesn't touch it.
    Method convert = ValuesConverter.class.getDeclaredMethod(
        "convert", DatabaseSessionEmbedded.class, Object.class);

    var input = "hello";
    var result = convert.invoke(identity, null, input);
    assertSame("identity converter must return the input by reference", input, result);

    var nullResult = convert.invoke(identity, null, (Object) null);
    assertNull("identity converter must propagate null without coercion", nullResult);
  }
}
