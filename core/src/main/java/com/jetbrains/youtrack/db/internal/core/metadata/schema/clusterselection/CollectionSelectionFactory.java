/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.metadata.schema.collectionselection;

import static com.jetbrains.youtrack.db.internal.common.util.ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader;

import com.jetbrains.youtrack.db.internal.common.factory.ConfigurableStatefulFactory;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.CollectionSelectionStrategy;

/**
 * Factory to get the collection selection strategy.
 */
public class CollectionSelectionFactory
    extends ConfigurableStatefulFactory<String, CollectionSelectionStrategy> {

  public CollectionSelectionFactory() {
    setDefaultClass(RoundRobinCollectionSelectionStrategy.class);
    this.registerStrategy();
  }

  private static final ClassLoader youTrackDbClassLoader =
      CollectionSelectionFactory.class.getClassLoader();

  private void registerStrategy() {
    final var ite =
        lookupProviderWithYouTrackDBClassLoader(CollectionSelectionStrategy.class,
            youTrackDbClassLoader);
    while (ite.hasNext()) {
      var strategy = ite.next();
      Class clz = strategy.getClass();
      try {
        var method = clz.getMethod("getName");
        if (method != null) {
          var key = (String) method.invoke(clz.newInstance());
          register(key, clz);
        } else {
          LogManager.instance().error(this, "getName() funciton missing", null);
        }
      } catch (Exception ex) {
        LogManager.instance().error(this, "failed to register class - " + clz.getName(), ex);
      }
    }
  }

  public CollectionSelectionStrategy getStrategy(final String iStrategy) {
    return newInstance(iStrategy);
  }
}
