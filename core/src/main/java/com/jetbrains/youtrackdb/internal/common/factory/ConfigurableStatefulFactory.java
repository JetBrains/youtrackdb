/*
 *
 * Copyright YouTrackDB
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
 *
 */
package com.jetbrains.youtrackdb.internal.common.factory;

import com.jetbrains.youtrackdb.internal.common.exception.SystemException;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Configurable stateful factory. New instances are created when newInstance() is called, invoking
 * its default empty constructor.
 *
 * @param <K> Factory key
 * @param <V> Instance type
 */
public class ConfigurableStatefulFactory<K, V> {

  protected final Map<K, Class<? extends V>> registry = new LinkedHashMap<K, Class<? extends V>>();
  protected Class<? extends V> defaultClass;

  public Class<? extends V> get(final K iKey) {
    return registry.get(iKey);
  }

  public V newInstance(final K iKey) {
    if (iKey == null && defaultClass == null) {
      throw new IllegalArgumentException("Cannot create implementation for type null");
    }

    final var cls = registry.get(iKey);
    if (cls != null) {
      try {
        return cls.newInstance();
      } catch (Exception e) {
        final var exception =
            new SystemException(
                String.format(
                    "Error on creating new instance of class '%s' registered in factory with key"
                        + " '%s'",
                    cls, iKey));
        throw BaseException.wrapException(exception, e, (String) null);
      }
    }

    return newInstanceOfDefaultClass();
  }

  @Nullable
  public V newInstanceOfDefaultClass() {
    if (defaultClass != null) {
      try {
        return defaultClass.newInstance();
      } catch (Exception e) {
        throw BaseException.wrapException(
            new SystemException(
                String.format(
                    "Error on creating new instance of default class '%s'", defaultClass)),
            e, (String) null);
      }
    }
    return null;
  }

  public Set<K> getRegisteredNames() {
    return registry.keySet();
  }

  public ConfigurableStatefulFactory<K, V> register(
      final K iKey, final Class<? extends V> iValue) {
    registry.put(iKey, iValue);
    return this;
  }

  public ConfigurableStatefulFactory<K, V> unregister(final K iKey) {
    registry.remove(iKey);
    return this;
  }

  public ConfigurableStatefulFactory<K, V> unregisterAll() {
    registry.clear();
    return this;
  }

  public Class<? extends V> getDefaultClass() {
    return defaultClass;
  }

  public <C extends Class<? extends V>> ConfigurableStatefulFactory<K, V> setDefaultClass(
      final C defaultClass) {
    this.defaultClass = defaultClass;
    return this;
  }
}
