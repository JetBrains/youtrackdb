package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Test;

/**
 * Direct tests for {@link YTDBPropertyFactory} static accessors. The factory is also exercised
 * indirectly by every {@link YTDBPropertyImpl}/{@link YTDBVertexPropertyImpl} construction site
 * inside the gremlin engine; this class pins the four typed-cast helpers
 * ({@code ytdbProps}, {@code stdProps}, {@code ytdbVectorProps}, {@code stdVectorProps}) and the
 * {@code empty()} factory method on both shared instances so the wide-cast helpers cannot
 * silently change which singleton they return.
 */
public class YTDBPropertyFactoryTest {

  /**
   * The {@code ytdbProps()} accessor returns the same {@link YTDBPropertyFactory#PROPERTY}
   * singleton. The cast is unchecked but the call site documents the contract — pin it.
   */
  @Test
  public void ytdbPropsReturnsSharedPropertySingleton() {
    var factory = YTDBPropertyFactory.<String>ytdbProps();
    assertSame(YTDBPropertyFactory.PROPERTY, factory);
  }

  /**
   * The {@code stdProps()} accessor returns the same shared instance as {@code ytdbProps()};
   * the only difference between the two helpers is the declared bound on the result type. Pin
   * the equivalence so a future refactor that distinguishes them surfaces here.
   */
  @Test
  public void stdPropsReturnsSharedPropertySingleton() {
    YTDBPropertyFactory<String, ? extends Property<String>> factory =
        YTDBPropertyFactory.stdProps();
    assertSame(YTDBPropertyFactory.PROPERTY, factory);
  }

  /**
   * The {@code ytdbVectorProps()} accessor returns the same
   * {@link YTDBPropertyFactory#VERTEX_PROPERTY} singleton.
   */
  @Test
  public void ytdbVectorPropsReturnsSharedVertexPropertySingleton() {
    var factory = YTDBPropertyFactory.<String>ytdbVectorProps();
    assertSame(YTDBPropertyFactory.VERTEX_PROPERTY, factory);
  }

  /**
   * The {@code stdVectorProps()} accessor returns the same shared instance as
   * {@code ytdbVectorProps()}; same reasoning as {@code stdProps}/{@code ytdbProps}.
   */
  @Test
  public void stdVectorPropsReturnsSharedVertexPropertySingleton() {
    YTDBPropertyFactory<String, ? extends VertexProperty<String>> factory =
        YTDBPropertyFactory.stdVectorProps();
    assertSame(YTDBPropertyFactory.VERTEX_PROPERTY, factory);
  }

  /**
   * {@link YTDBPropertyFactory#PROPERTY}'s {@code empty()} method returns a {@link YTDBProperty}
   * sentinel that reports {@link Property#isPresent() isPresent() == false}. The factory
   * delegates to {@link YTDBProperty#empty()} which itself routes to
   * {@link YTDBEmptyProperty#instance()}; pin the {@code isPresent} contract so a regression
   * that swaps the empty sentinel for a present-but-null property is caught here.
   */
  @Test
  public void propertyFactoryEmptyReturnsAbsentProperty() {
    var empty = YTDBPropertyFactory.PROPERTY.empty();
    assertNotNull(empty);
    assertTrue("empty property must report isPresent=false", !empty.isPresent());
  }

  /**
   * {@link YTDBPropertyFactory#VERTEX_PROPERTY}'s {@code empty()} method returns a
   * {@link YTDBVertexProperty} sentinel that reports
   * {@link Property#isPresent() isPresent() == false}.
   */
  @Test
  public void vertexPropertyFactoryEmptyReturnsAbsentVertexProperty() {
    var empty = YTDBPropertyFactory.VERTEX_PROPERTY.empty();
    assertNotNull(empty);
    assertTrue("empty vertex property must report isPresent=false", !empty.isPresent());
  }
}
