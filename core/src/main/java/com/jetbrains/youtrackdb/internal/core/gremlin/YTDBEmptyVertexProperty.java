/*
 *
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
package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBEmptyVertexProperty<V> implements YTDBVertexProperty<V> {

  private static final YTDBEmptyVertexProperty<?> INSTANCE = new YTDBEmptyVertexProperty<>();

  @SuppressWarnings("unchecked")
  public static <V> YTDBVertexProperty<V> instance() {
    return (YTDBVertexProperty<V>) INSTANCE;
  }

  private YTDBEmptyVertexProperty() {
  }

  @Override
  public YTDBVertex element() {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public YTDBVertexPropertyId id() {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public YTDBGraph graph() {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public <U> YTDBProperty<U> property(String key) {
    return YTDBProperty.empty();
  }

  @Override
  public <U> YTDBProperty<U> property(String key, U value) {
    return YTDBProperty.empty();
  }

  @Override
  public boolean hasProperty(String key) {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public boolean removeProperty(String key) {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public PropertyType type() {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public String key() {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public V value() throws NoSuchElementException {
    throw Property.Exceptions.propertyDoesNotExist();
  }

  @Override
  public boolean isPresent() {
    return false;
  }

  @Override
  public void remove() {
  }

  @Override
  public String toString() {
    return StringFactory.propertyString(this);
  }

  @Override
  public <U> Iterator<Property<U>> properties(final String... propertyKeys) {
    return Collections.emptyIterator();
  }
}
