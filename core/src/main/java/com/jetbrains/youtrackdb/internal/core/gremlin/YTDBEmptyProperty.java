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

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBEmptyProperty<V> implements YTDBProperty<V> {

  private static final YTDBEmptyProperty<?> INSTANCE = new YTDBEmptyProperty<>();

  @SuppressWarnings("unchecked")
  public static <V> YTDBProperty<V> instance() {
    return (YTDBProperty<V>) INSTANCE;
  }

  private YTDBEmptyProperty() {
  }

  @Override
  public String key() {
    throw Exceptions.propertyDoesNotExist();
  }

  @Override
  public V value() throws NoSuchElementException {
    throw Exceptions.propertyDoesNotExist();
  }

  @Override
  public boolean isPresent() {
    return false;
  }

  @Override
  public YTDBElement element() {
    throw Exceptions.propertyDoesNotExist();
  }

  @Override
  public void remove() {
  }

  @Override
  public PropertyType type() {
    throw Exceptions.propertyDoesNotExist();
  }

  @Override
  public String toString() {
    return StringFactory.propertyString(this);
  }

  @Override
  public boolean equals(final Object object) {
    return object instanceof YTDBEmptyProperty;
  }

  @Override
  public int hashCode() {
    return 0;
  }

}
