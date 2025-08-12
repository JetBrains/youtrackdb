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

package com.jetbrains.youtrackdb.internal.lucene;

import static com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE.FULLTEXT;

import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexFactory;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.lucene.engine.LuceneFullTextIndexEngine;
import com.jetbrains.youtrackdb.internal.lucene.index.LuceneFullTextIndex;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneIndexFactory implements IndexFactory, DatabaseLifecycleListener {

  private static final Logger logger = LoggerFactory.getLogger(LuceneIndexFactory.class);

  public static final String LUCENE_ALGORITHM = "LUCENE";

  private static final Set<String> TYPES;
  private static final Set<String> ALGORITHMS;

  static {
    final Set<String> types = new HashSet<String>();
    types.add(FULLTEXT.toString());
    TYPES = Collections.unmodifiableSet(types);
  }

  static {
    final Set<String> algorithms = new HashSet<String>();
    algorithms.add(LUCENE_ALGORITHM);
    ALGORITHMS = Collections.unmodifiableSet(algorithms);
  }

  public LuceneIndexFactory() {
    this(false);
  }

  public LuceneIndexFactory(boolean manual) {
    if (!manual) {
      YouTrackDBEnginesManager.instance().addDbLifecycleListener(this);
    }
  }

  @Override
  public int getLastVersion(final String algorithm) {
    return 0;
  }

  @Override
  public Set<String> getTypes() {
    return TYPES;
  }

  @Override
  public Set<String> getAlgorithms() {
    return ALGORITHMS;
  }

  @Override
  public Index createIndex(String indexType, @Nonnull Storage storage)
      throws ConfigurationException {
    if (FULLTEXT.toString().equalsIgnoreCase(indexType)) {
      return new LuceneFullTextIndex(storage);
    }

    throw new ConfigurationException(storage.getName(), "Unsupported type : " + indexType);
  }

  @Override
  public Index createIndex(String indexType, @Nullable RID identity,
      @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) throws ConfigurationException {
    if (FULLTEXT.toString().equalsIgnoreCase(indexType)) {
      return new LuceneFullTextIndex(identity, transaction, storage);
    }

    throw new ConfigurationException(storage.getName(), "Unsupported type : " + indexType);
  }

  @Override
  public BaseIndexEngine createIndexEngine(Storage storage, IndexEngineData data) {
    return new LuceneFullTextIndexEngine(storage, data.getName(), data.getIndexId());
  }

  @Override
  public PRIORITY getPriority() {
    return PRIORITY.REGULAR;
  }

  @Override
  public void onCreate(@Nonnull DatabaseSessionInternal session) {
    LogManager.instance().debug(this, "onCreate", logger);
  }

  @Override
  public void onOpen(@Nonnull DatabaseSessionInternal session) {
    LogManager.instance().debug(this, "onOpen", logger);
  }

  @Override
  public void onClose(@Nonnull DatabaseSessionInternal session) {
    LogManager.instance().debug(this, "onClose", logger);
  }

  @Override
  public void onDrop(final @Nonnull DatabaseSessionInternal session) {
    try {
      if (session.isClosed()) {
        return;
      }

      LogManager.instance().debug(this, "Dropping Lucene indexes...", logger);

      session.getSharedContext().getIndexManager().getIndexes().stream()
          .filter(idx -> idx instanceof LuceneFullTextIndex)
          .peek(idx -> LogManager.instance().debug(this, "deleting index " + idx.getName(), logger))
          .forEach(idx -> session.executeInTxInternal(idx::delete));

    } catch (Exception e) {
      LogManager.instance().warn(this, "Error on dropping Lucene indexes", e);
    }
  }

}
