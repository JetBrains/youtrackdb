/*
 * Copyright 2012 Geomatys.
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
package com.jetbrains.youtrack.db.internal.core.index;

import static com.jetbrains.youtrack.db.internal.common.util.ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader;

import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.Collections;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Utility class to create indexes. New IndexFactory can be registered
 *
 * <p>
 *
 * <p>In order to be detected, factories must implement the {@link IndexFactory} interface.
 *
 * <p>
 *
 * <p>In addition to implementing this interface datasources should have a services file:<br>
 * <code>META-INF/services/com.jetbrains.youtrack.db.internal.core.index.IndexFactory</code>
 *
 * <p>
 *
 * <p>The file should contain a single line which gives the full name of the implementing class.
 *
 * <p>
 *
 * <p>Example:<br>
 * <code>org.mycompany.index.MyIndexFactory</code>
 */
public final class Indexes {

  private static Set<IndexFactory> FACTORIES = null;
  private static final Set<IndexFactory> DYNAMIC_FACTORIES =
      java.util.Collections.synchronizedSet(new HashSet<>());
  private static final ClassLoader youTrackDbClassLoader = Indexes.class.getClassLoader();

  private Indexes() {
  }

  /**
   * Cache a set of all factories. we do not use the service loader directly since it is not
   * concurrent.
   *
   * @return Set<IndexFactory>
   */
  private static synchronized Set<IndexFactory> getFactories() {
    if (FACTORIES == null) {

      final var ite =
          lookupProviderWithYouTrackDBClassLoader(IndexFactory.class, youTrackDbClassLoader);

      final Set<IndexFactory> factories = new HashSet<>();
      while (ite.hasNext()) {
        factories.add(ite.next());
      }
      factories.addAll(DYNAMIC_FACTORIES);
      FACTORIES = java.util.Collections.unmodifiableSet(factories);
    }
    return FACTORIES;
  }

  /**
   * @return Iterator of all index factories
   */
  public static Iterator<IndexFactory> getAllFactories() {
    return getFactories().iterator();
  }

  /**
   * Iterates on all factories and append all index types.
   *
   * @return Set of all index types.
   */
  private static Set<String> getIndexTypes() {
    final Set<String> types = new HashSet<>();
    final var ite = getAllFactories();
    while (ite.hasNext()) {
      types.addAll(ite.next().getTypes());
    }
    return types;
  }

  public static IndexFactory getFactory(String indexType, String algorithm) {
    if (algorithm == null) {
      algorithm = chooseDefaultIndexAlgorithm(indexType);
    }

    if (algorithm != null) {
      algorithm = algorithm.toUpperCase(Locale.ENGLISH);
      final var ite = getAllFactories();

      while (ite.hasNext()) {
        final var factory = ite.next();
        if (factory.getTypes().contains(indexType) && factory.getAlgorithms().contains(algorithm)) {
          return factory;
        }
      }
    }

    throw new IndexException(
        "Index with type " + indexType + " and algorithm " + algorithm + " does not exist.");
  }

  public static Index createIndex(@Nonnull IndexMetadata metadata,
      @Nullable RID identity, @Nonnull IndexManagerAbstract indexManager, @Nonnull Storage storage)
      throws ConfigurationException, IndexException {
    var indexType = metadata.getType();
    var algorithm = metadata.getAlgorithm();

    return findFactoryByAlgorithmAndType(algorithm, indexType).createIndex(metadata, identity,
        indexManager, storage);
  }

  private static IndexFactory findFactoryByAlgorithmAndType(String algorithm, String indexType) {

    for (var factory : getFactories()) {
      if (indexType == null
          || indexType.isEmpty()
          || (factory.getTypes().contains(indexType))
          && factory.getAlgorithms().contains(algorithm)) {
        return factory;
      }
    }

    throw new IndexException(
        "Index type "
            + indexType
            + " with engine "
            + algorithm
            + " is not supported. Types are "
            + Collections.toString(getIndexTypes()));
  }

  public static BaseIndexEngine createIndexEngine(
      final Storage storage, final IndexEngineData metadata) {

    final var factory =
        findFactoryByAlgorithmAndType(metadata.getAlgorithm(), metadata.getIndexType());

    return factory.createIndexEngine(storage, metadata);
  }

  public static String chooseDefaultIndexAlgorithm(String type) {
    String algorithm = null;
    if (SchemaClass.INDEX_TYPE.NOTUNIQUE.name().equalsIgnoreCase(type)
        || SchemaClass.INDEX_TYPE.UNIQUE.name().equalsIgnoreCase(type)) {
      algorithm = DefaultIndexFactory.BTREE_ALGORITHM;
    }

    return algorithm;
  }

}
