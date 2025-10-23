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
package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBEmptyProperty;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.Property;

/// Extended version of TinkerPop [Property] with YouTrackDB specific methods.
public interface YTDBProperty<V> extends Property<V> {

  static <V> YTDBProperty<V> empty() {
    return YTDBEmptyProperty.instance();
  }

  /// Get the type of this property. Can be null if this information is not available.
  @Nullable
  PropertyType type();

  @Override
  YTDBElement element();
}
