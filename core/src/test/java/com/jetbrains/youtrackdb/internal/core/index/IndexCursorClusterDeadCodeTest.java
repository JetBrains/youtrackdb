package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.iterator.IndexCursorStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Reflection-only shape pin for the {@code IndexCursor} / {@code IndexAbstractCursor} /
 * {@code IndexCursorStream} lockstep cluster and the standalone {@code IndexKeyCursor}
 * interface.
 *
 * <p>PSI {@code ReferencesSearch} (all-scope) confirms that the only references to
 * {@code IndexCursor} are inside {@code IndexAbstractCursor} (the {@code implements} clause
 * and its forwarding methods). The only references to {@code IndexAbstractCursor} are inside
 * {@code IndexCursorStream} (the {@code extends} clause). {@code IndexCursorStream} itself
 * has zero references anywhere. {@code IndexKeyCursor} also has zero references anywhere.
 * All four are therefore production-dead and forwarded to the deferred-cleanup track.
 *
 * <p>Tests are reflection-only so that no live call site is manufactured. Pinning the public
 * surface, constructor signatures, super-type vectors, and implemented interfaces guards
 * against silent rename / drop / type-hierarchy drift between now and the deferred-cleanup
 * work.
 *
 * <p>WHEN-FIXED: pending dead-code cleanup — delete this test file in lockstep with
 * {@code IndexCursor}, {@code IndexAbstractCursor}, {@code IndexCursorStream}, and
 * {@code IndexKeyCursor}.
 */
public class IndexCursorClusterDeadCodeTest {

  // ========================================================================
  //  IndexCursor — public interface
  // ========================================================================

  /**
   * {@code IndexCursor} is a public interface extending {@link java.util.Iterator}.
   * It declares the four public methods that form the cursor contract.
   */
  @Test
  public void indexCursor_isPublicInterfaceExtendingIterator() {
    var clazz = IndexCursor.class;
    assertTrue("IndexCursor must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("IndexCursor must be an interface", clazz.isInterface());

    // Must extend Iterator<Identifiable>.
    var supers = clazz.getInterfaces();
    boolean foundIterator = false;
    for (Class<?> s : supers) {
      if (Iterator.class.equals(s)) {
        foundIterator = true;
        break;
      }
    }
    assertTrue("IndexCursor must extend Iterator<Identifiable>", foundIterator);
  }

  /**
   * The four contract methods of {@code IndexCursor} must be present with correct return
   * types so that implementers downstream of the deferred-cleanup track can verify the
   * removal was safe.
   */
  @Test
  public void indexCursor_contractMethodsHaveCorrectReturnTypes() throws Exception {
    // nextEntry() → Map.Entry<Object, Identifiable>
    var nextEntry = IndexCursor.class.getDeclaredMethod("nextEntry");
    assertSame("nextEntry must return Map.Entry", Map.Entry.class, nextEntry.getReturnType());

    // toValues() → Set<Identifiable>
    var toValues = IndexCursor.class.getDeclaredMethod("toValues");
    assertSame("toValues must return Set", Set.class, toValues.getReturnType());

    // toEntries() → Set<Map.Entry<Object, Identifiable>>
    var toEntries = IndexCursor.class.getDeclaredMethod("toEntries");
    assertSame("toEntries must return Set", Set.class, toEntries.getReturnType());

    // toKeys() → Set<Object>
    var toKeys = IndexCursor.class.getDeclaredMethod("toKeys");
    assertSame("toKeys must return Set", Set.class, toKeys.getReturnType());

    // setPrefetchSize(int)
    var setPrefetch = IndexCursor.class.getDeclaredMethod("setPrefetchSize", int.class);
    assertSame("setPrefetchSize must return void", void.class, setPrefetch.getReturnType());
  }

  // ========================================================================
  //  IndexAbstractCursor — abstract class that implements IndexCursor
  // ========================================================================

  /**
   * {@code IndexAbstractCursor} is a public abstract class that implements
   * {@link IndexCursor}.
   */
  @Test
  public void indexAbstractCursor_isPublicAbstractImplementsIndexCursor() {
    var clazz = IndexAbstractCursor.class;
    assertTrue("IndexAbstractCursor must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("IndexAbstractCursor must be abstract", Modifier.isAbstract(clazz.getModifiers()));

    boolean implementsIndexCursor = false;
    for (Class<?> iface : clazz.getInterfaces()) {
      if (IndexCursor.class.equals(iface)) {
        implementsIndexCursor = true;
        break;
      }
    }
    assertTrue("IndexAbstractCursor must implement IndexCursor", implementsIndexCursor);
  }

  /**
   * Pins the declared public method names on {@code IndexAbstractCursor} so that a rename
   * or addition is caught before deletion.
   */
  @Test
  public void indexAbstractCursor_publicDeclaredMethodSurface() {
    var expected = new TreeSet<>(Set.of(
        "toValues",
        "toEntries",
        "toKeys",
        "hasNext",
        "next",
        "remove",
        "setPrefetchSize",
        "getPrefetchSize"));
    var actual = new TreeSet<String>();
    for (Method m : IndexAbstractCursor.class.getDeclaredMethods()) {
      if (!m.isSynthetic() && Modifier.isPublic(m.getModifiers())) {
        actual.add(m.getName());
      }
    }
    assertEquals("public declared method-name set must match the pinned surface",
        expected, actual);
  }

  // ========================================================================
  //  IndexCursorStream — concrete subclass of IndexAbstractCursor
  // ========================================================================

  /**
   * {@code IndexCursorStream} is a public concrete (non-abstract) class that extends
   * {@link IndexAbstractCursor}.
   */
  @Test
  public void indexCursorStream_isPublicConcreteExtendsIndexAbstractCursor() {
    var clazz = IndexCursorStream.class;
    assertTrue("IndexCursorStream must be public", Modifier.isPublic(clazz.getModifiers()));
    assertSame("IndexCursorStream must extend IndexAbstractCursor",
        IndexAbstractCursor.class, clazz.getSuperclass());
  }

  /**
   * {@code IndexCursorStream} declares a single public one-arg constructor accepting
   * {@code Stream<RawPair<Object, RID>>} so callers can wrap a raw stream in a cursor.
   */
  @Test
  public void indexCursorStream_singlePublicConstructorAcceptsStream() {
    var ctors = IndexCursorStream.class.getDeclaredConstructors();
    assertEquals("IndexCursorStream must declare exactly one constructor", 1, ctors.length);
    var ctor = ctors[0];
    assertTrue("ctor must be public", Modifier.isPublic(ctor.getModifiers()));
    assertEquals("ctor must accept exactly one parameter (Stream)", 1, ctor.getParameterCount());
    assertSame("ctor parameter must be Stream",
        Stream.class, ctor.getParameterTypes()[0]);
  }

  /**
   * {@code IndexCursorStream.nextEntry()} must be declared public, non-static, and return
   * {@link Map.Entry}. This is the only method {@code IndexCursorStream} adds on top of the
   * {@link IndexAbstractCursor} base — everything else is inherited.
   */
  @Test
  public void indexCursorStream_nextEntryDeclaredPublicReturnsMapEntry() throws Exception {
    var m = IndexCursorStream.class.getDeclaredMethod("nextEntry");
    assertTrue("nextEntry must be public", Modifier.isPublic(m.getModifiers()));
    assertSame("nextEntry must return Map.Entry", Map.Entry.class, m.getReturnType());
  }

  // ========================================================================
  //  IndexKeyCursor — standalone dead interface (separate lockstep)
  // ========================================================================

  /**
   * {@code IndexKeyCursor} is a public interface with a single {@code next(int)} method
   * that returns {@code Object}. It has zero production references and is forwarded to the
   * deferred-cleanup track as a standalone dead interface.
   *
   * <p>WHEN-FIXED: pending dead-code cleanup — delete this interface when no production
   * caller exists for {@code IndexKeyCursor.next(int)}.
   */
  @Test
  public void indexKeyCursor_isPublicInterfaceWithSingleNextMethod() throws Exception {
    var clazz = IndexKeyCursor.class;
    assertTrue("IndexKeyCursor must be public", Modifier.isPublic(clazz.getModifiers()));
    assertTrue("IndexKeyCursor must be an interface", clazz.isInterface());
    assertEquals("IndexKeyCursor must extend no other interface",
        0, clazz.getInterfaces().length);

    // Single method: Object next(int prefetchSize).
    var methods = clazz.getDeclaredMethods();
    assertEquals("IndexKeyCursor must declare exactly one method", 1, methods.length);
    var next = methods[0];
    assertEquals("the method must be named 'next'", "next", next.getName());
    assertSame("next must return Object", Object.class, next.getReturnType());
    assertArrayEquals("next must accept a single int parameter",
        new Class<?>[] {int.class}, next.getParameterTypes());
  }
}
