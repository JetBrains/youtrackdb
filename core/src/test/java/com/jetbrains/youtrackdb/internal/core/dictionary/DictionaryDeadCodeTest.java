/*
     *
     *  *
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
     *  *
     *
     *
     */
package com.jetbrains.youtrackdb.internal.core.dictionary;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.core.index.Index;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link Dictionary}.
 *
 * <p>PSI all-scope {@code ReferencesSearch} reports zero references anywhere in the project —
 * no production callers, no tests, no instantiation, no {@code instanceof} sites. The class
 * is also marked {@code @Deprecated} on the production side. It is fully production-dead and
 * a candidate for outright deletion in the deferred-cleanup track.
 *
 * <p>This pin captures:
 * <ul>
 *   <li>The public class shape ({@code @Deprecated}, generic single type-parameter {@code T},
 *       no super-interfaces);
 *   <li>The single public ctor signature;
 *   <li>Every public method's name and the fact that all four mutator/accessor methods throw
 *       {@link UnsupportedOperationException} (the only behavior in the implementation —
 *       {@code getIndex()} returns the wrapped index and is the only non-throwing method).
 * </ul>
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this test file in the same commit that
 * removes the {@code core/dictionary} package per the cluster-classification table row
 * "core/dictionary" in {@code track-22a.md}.
 */
public class DictionaryDeadCodeTest {

  private static final Set<String> EXPECTED_PUBLIC_DECLARED_METHOD_NAMES =
      new TreeSet<>(Set.of("get", "put", "remove", "size", "getIndex"));

  @Test
  public void classIsPublicDeprecatedGenericWithObjectSuperAndNoInterfaces() {
    var clazz = Dictionary.class;
    assertTrue("must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue(
        "must remain @Deprecated — production already marked it",
        clazz.isAnnotationPresent(Deprecated.class));
    assertSame("super must remain Object", Object.class, clazz.getSuperclass());
    assertEquals("must declare no super-interfaces", 0, clazz.getInterfaces().length);

    var typeParams = clazz.getTypeParameters();
    assertEquals("must declare exactly one type parameter", 1, typeParams.length);
    assertEquals("type parameter name must remain T", "T", typeParams[0].getName());
  }

  @Test
  public void publicDeclaredMethodNamesMatchPinnedSet() {
    var actual = new TreeSet<String>();
    for (Method m : Dictionary.class.getDeclaredMethods()) {
      if (m.isSynthetic()) {
        continue;
      }
      if (!Modifier.isPublic(m.getModifiers())) {
        continue;
      }
      actual.add(m.getName());
    }
    assertEquals(
        "public declared method-name set must match the pinned dead-code surface",
        EXPECTED_PUBLIC_DECLARED_METHOD_NAMES,
        actual);
  }

  @Test
  public void declaresExactlyOnePublicSingleArgConstructor() {
    var ctors = Dictionary.class.getDeclaredConstructors();
    assertEquals("must declare exactly one ctor", 1, ctors.length);
    var c = ctors[0];
    assertTrue("ctor must be public", Modifier.isPublic(c.getModifiers()));
    assertArrayEquals(
        "ctor signature must remain (Index)", new Class<?>[] {Index.class}, c.getParameterTypes());
  }

  @Test
  public void getOverloadsAndMutatorsThrowUnsupportedOperation() {
    var index = mock(Index.class);
    Dictionary<Object> dict = new Dictionary<>(index);

    assertThrows(
        "get(String) must throw — production stub", UnsupportedOperationException.class,
        () -> dict.get("k"));
    assertThrows(
        "get(String, String) must throw — production stub",
        UnsupportedOperationException.class,
        () -> dict.get("k", "fetch"));
    assertThrows(
        "put(String, Object) must throw — production stub",
        UnsupportedOperationException.class,
        () -> dict.put("k", "v"));
    assertThrows(
        "remove(String) must throw — production stub",
        UnsupportedOperationException.class,
        () -> dict.remove("k"));
    assertThrows(
        "size() must throw — production stub",
        UnsupportedOperationException.class,
        dict::size);
  }

  @Test
  public void getIndexReturnsTheConstructorWrappedIndex() {
    var index = mock(Index.class);
    Dictionary<Object> dict = new Dictionary<>(index);
    // The only non-throwing method on Dictionary — pin the wrapper-passthrough behavior so
    // a future refactor that drops the field is caught here in lockstep with the deletion.
    assertSame("getIndex must return the constructor-supplied Index", index, dict.getIndex());
  }
}
