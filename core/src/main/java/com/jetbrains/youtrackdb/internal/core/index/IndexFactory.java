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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface IndexFactory {

  int getLastVersion(final String algorithm);

  /**
   * @return List of supported indexes of this factory
   */
  Set<String> getTypes();

  /**
   * @return List of supported algorithms of this factory
   */
  Set<String> getAlgorithms();

  /**
   * Creates an index.
   */
  Index createIndex(String indexType, @Nonnull Storage storage) throws ConfigurationException;

  /**
   * Creates an index.
   */
  Index createIndex(String indexType, @Nullable RID identity,
      @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage)
      throws ConfigurationException;

  BaseIndexEngine createIndexEngine(Storage storage, IndexEngineData data);
}
