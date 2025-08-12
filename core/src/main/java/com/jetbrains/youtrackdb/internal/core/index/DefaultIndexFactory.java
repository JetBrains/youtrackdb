/*
 *
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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.index.engine.RemoteIndexEngine;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Default YouTrackDB index factory for indexes based on SBTree.<br> Supports index types:
 *
 * <ul>
 *   <li>UNIQUE
 *   <li>NOTUNIQUE
 *   <li>FULLTEXT
 *   <li>DICTIONARY
 * </ul>
 */
public class DefaultIndexFactory implements IndexFactory {

  static final String BTREE_ALGORITHM = "BTREE";

  private static final Set<String> TYPES;
  private static final Set<String> ALGORITHMS;

  static {
    final Set<String> types = new HashSet<>();
    types.add(SchemaClass.INDEX_TYPE.UNIQUE.toString());
    types.add(SchemaClass.INDEX_TYPE.NOTUNIQUE.toString());
    TYPES = Collections.unmodifiableSet(types);
  }

  static {
    final Set<String> algorithms = new HashSet<>();
    algorithms.add(BTREE_ALGORITHM);

    ALGORITHMS = Collections.unmodifiableSet(algorithms);
  }


  public Set<String> getTypes() {
    return TYPES;
  }

  public Set<String> getAlgorithms() {
    return ALGORITHMS;
  }

  @Override
  public Index createIndex(String indexType, @Nonnull Storage storage)
      throws ConfigurationException {
    if (SchemaClass.INDEX_TYPE.UNIQUE.toString().equals(indexType)) {
      return new IndexUnique(storage);
    } else if (SchemaClass.INDEX_TYPE.NOTUNIQUE.toString().equals(indexType)) {
      return new IndexNotUnique(storage);
    }

    throw new ConfigurationException(storage.getName(), "Unsupported type: " + indexType);
  }

  @Override
  public Index createIndex(@Nonnull String indexType, @Nullable RID identity,
      @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage)
      throws ConfigurationException {
    if (SchemaClass.INDEX_TYPE.UNIQUE.toString().equals(indexType)) {
      return new IndexUnique(identity, transaction, storage);
    } else if (SchemaClass.INDEX_TYPE.NOTUNIQUE.toString().equals(indexType)) {
      return new IndexNotUnique(identity, transaction, storage);
    }

    throw new ConfigurationException(storage.getName(), "Unsupported type: " + indexType);
  }

  @Override
  public int getLastVersion(final String algorithm) {
    if (algorithm.equals(BTREE_ALGORITHM)) {
      return BTreeIndexEngine.VERSION;
    }

    throw new IllegalStateException("Invalid algorithm name " + algorithm);
  }

  @Override
  public BaseIndexEngine createIndexEngine(Storage storage, IndexEngineData data) {

    if (data.getAlgorithm() == null) {
      throw new IndexException(storage.getName(), "Name of algorithm is not specified");
    }
    final BaseIndexEngine indexEngine;
    var storageType = storage.getType();

    if (storageType.equals("distributed")) {
      storageType = storage.getType();
    }

    var version = data.getVersion();
    if (version < 0) {
      version = getLastVersion(data.getAlgorithm());
    }

    switch (storageType) {
      case "memory":
      case "disk":
        var realStorage = (AbstractStorage) storage;
        if (data.getAlgorithm().equals(BTREE_ALGORITHM)) {
          if (data.isMultivalue()) {
            indexEngine =
                new BTreeMultiValueIndexEngine(
                    data.getIndexId(), data.getName(), realStorage, version);
          } else {
            indexEngine =
                new BTreeSingleValueIndexEngine(
                    data.getIndexId(), data.getName(), realStorage, version);
          }
        } else {
          throw new IllegalStateException("Invalid name of algorithm :'" + "'");
        }
        break;
      case "remote":
        indexEngine = new RemoteIndexEngine(data.getIndexId(), data.getName());
        break;
      default:
        throw new IndexException(storage.getName(), "Unsupported storage type: " + storageType);
    }

    return indexEngine;
  }
}
