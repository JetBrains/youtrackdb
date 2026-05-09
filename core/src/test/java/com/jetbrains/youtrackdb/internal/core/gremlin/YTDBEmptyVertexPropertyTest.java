package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.junit.Test;

/**
 * Tests for the {@link YTDBEmptyVertexProperty} singleton sentinel — the parallel of
 * {@link YTDBEmptyProperty} for vertex-properties. The sentinel implements
 * {@link YTDBVertexProperty} but every accessor that would dereference a real backing element
 * throws {@link Property.Exceptions#propertyDoesNotExist}; the only methods that succeed
 * silently are the ones that should be no-ops on an absent property
 * ({@code remove}, {@code isPresent}, {@code property(key)}, {@code property(key, value)},
 * {@code properties}, {@code toString}).
 *
 * <p>The original {@link YTDBEmptyPropertyTest} only covers the {@link YTDBEmptyProperty}
 * variant; this class fills the symmetric gap for the vertex-property singleton.
 */
public class YTDBEmptyVertexPropertyTest {

  /**
   * The {@code instance()} factory always returns the same singleton — pin it so a future
   * refactor that allocates a new instance per call surfaces here (the singleton is a
   * load-bearing invariant for {@link Object#equals} reflexivity in collection use).
   */
  @Test
  public void instanceReturnsSameSingleton() {
    YTDBVertexProperty<String> a = YTDBEmptyVertexProperty.instance();
    YTDBVertexProperty<Integer> b = YTDBEmptyVertexProperty.instance();
    assertSame(a, b);
  }

  /** {@code isPresent()} on the empty vertex property must be {@code false}. */
  @Test
  public void isPresentReturnsFalse() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    assertFalse(prop.isPresent());
  }

  /** {@code remove()} is a no-op (no exception, no state change). */
  @Test
  public void removeIsNoOp() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    prop.remove();
    // still absent
    assertFalse(prop.isPresent());
  }

  /**
   * {@code property(key)} returns the {@link YTDBProperty#empty} sentinel — accessing a
   * meta-property of an absent vertex-property is itself absent, not an exception. Pin the
   * non-throwing branch.
   */
  @Test
  public void propertyByKeyReturnsEmptyProperty() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    var sub = prop.property("any");
    assertNotNull(sub);
    assertFalse(sub.isPresent());
  }

  /** {@code property(key, value)} also returns the empty sentinel — no insertion happens. */
  @Test
  public void propertyByKeyValueReturnsEmptyProperty() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    var sub = prop.property("any", "value");
    assertNotNull(sub);
    assertFalse(sub.isPresent());
  }

  /**
   * {@code properties()} on the empty sentinel returns an empty iterator — calling code can
   * safely iterate without first checking {@code isPresent()}.
   */
  @Test
  public void propertiesReturnsEmptyIterator() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    var iter = prop.properties("a", "b", "c");
    assertNotNull(iter);
    assertFalse(iter.hasNext());
  }

  /** {@code toString()} delegates to TinkerPop's StringFactory; non-null and non-empty. */
  @Test
  public void toStringIsNonEmpty() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    var s = prop.toString();
    assertNotNull(s);
    assertFalse(s.isEmpty());
  }

  /** {@code key()} throws because the absent property carries no key. */
  @Test
  public void keyThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.key();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code value()} throws because there is no value. */
  @Test
  public void valueThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.value();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code element()} throws because the empty property is not bound to a vertex. */
  @Test
  public void elementThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.element();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code id()} throws because no underlying record id exists. */
  @Test
  public void idThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.id();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code graph()} throws because the property is not attached to a graph. */
  @Test
  public void graphThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.graph();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code hasProperty(key)} throws — there is no underlying entity to consult. */
  @Test
  public void hasPropertyThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.hasProperty("any");
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code removeProperty(key)} throws — symmetric to {@code hasProperty}. */
  @Test
  public void removePropertyThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.removeProperty("any");
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /** {@code type()} throws — the property carries no schema metadata. */
  @Test
  public void typeThrows() {
    YTDBVertexProperty<String> prop = YTDBEmptyVertexProperty.instance();
    try {
      prop.type();
      fail("Expected propertyDoesNotExist");
    } catch (IllegalStateException expected) {
      // ok
    }
  }

  /**
   * The factory route — {@link YTDBVertexProperty#empty} — also returns the same singleton.
   * This pins the indirection layer used by client code.
   */
  @Test
  public void factoryEmptyReturnsSingleton() {
    assertEquals(YTDBEmptyVertexProperty.instance(), YTDBVertexProperty.empty());
  }
}
