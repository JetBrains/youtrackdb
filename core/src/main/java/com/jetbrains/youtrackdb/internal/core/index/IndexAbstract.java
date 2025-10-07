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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysGreaterKey;
import com.jetbrains.youtrackdb.internal.core.index.comparator.AlwaysLessKey;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaIndex;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Handles indexing when records change. The underlying lock manager for keys can be the
/// [PartitionedLockManager], the default one.
///
public abstract class IndexAbstract implements Index {
  private static final AlwaysLessKey ALWAYS_LESS_KEY = new AlwaysLessKey();
  private static final AlwaysGreaterKey ALWAYS_GREATER_KEY = new AlwaysGreaterKey();

  @Nonnull
  protected final AbstractStorage storage;
  @Nonnull
  protected final SchemaIndex schemaIndex;

  public IndexAbstract(@Nonnull SchemaIndex schemaIndex,
      @Nonnull final AbstractStorage storage) {
    this.storage = storage;
    this.schemaIndex = schemaIndex;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rebuild(DatabaseSessionEmbedded session) {
    var indexDefinition = schemaIndex.getIndexDefinition();
    var classToRebuild = indexDefinition.getClassName();
    var classSize = session.countClass(classToRebuild);

    var reportInterval = session.getConfiguration()
        .getValueAsInteger(GlobalConfiguration.INDEX_REPORT_INTERVAL);
    if (reportInterval > 0) {
      LogManager.instance().info(this, "Re-indexing class: %s", classToRebuild);
    }

    if (classSize > 0) {
      LogManager.instance().info(this, "Removing entries from class: %s. %d entries to remove.",
          classToRebuild, classSize);
      var classIterator = new RecordIteratorClass(session, classToRebuild,
          true, true);
      var removed = 0;

      var transaction = session.getActiveTransaction();

      while (classIterator.hasNext()) {
        var entity = classIterator.next();
        ClassIndexManager.deleteIndexEntry(transaction, entity, this);
        removed++;

        if (reportInterval > 0 && (removed % reportInterval) == 0) {
          LogManager.instance()
              .info(this, "Removing %d index entries out of %d.", removed, classSize);
        }
      }

      LogManager.instance()
          .info(this, "Adding new entries to index %s. %d entries to add.",
              classToRebuild);
      var added = 0;
      classIterator = new RecordIteratorClass(session, classToRebuild, true,
          true);
      while (classIterator.hasNext()) {
        var entity = classIterator.next();
        ClassIndexManager.addIndexEntry(transaction, entity, this);
        added++;

        if (reportInterval > 0 && (added % reportInterval) == 0) {
          LogManager.instance().info(this, "Adding %d index entries out of %d.", added, classSize);
        }
      }
    }

    if (reportInterval > 0) {
      LogManager.instance().info(this, "Re-indexing of class %s is completed.", classToRebuild);
    }
  }


  @Override
  public void doRemove(DatabaseSessionInternal session, AbstractStorage storage,
      Object key, RID rid) {
    doRemove(storage, key, session);
  }

  @Override
  public void remove(FrontendTransaction transaction, Object key, final Identifiable rid) {
    key = getCollatingValue(key);
    transaction.addIndexEntry(this, getName(), OPERATION.REMOVE, key, rid);
  }

  @Override
  public void remove(FrontendTransaction transaction, Object key) {
    key = getCollatingValue(key);

    transaction.addIndexEntry(this, getName(), OPERATION.REMOVE, key, null);
  }

  @Override
  public void doRemove(AbstractStorage storage, Object key,
      DatabaseSessionInternal session) {
    storage.removeKeyFromIndex(schemaIndex.getId(), key);
  }

  @Override
  public String getName() {
    return schemaIndex.getName();
  }

  @Override
  public IndexType getType() {
    return schemaIndex.getType();
  }

  @Override
  public String toString() {
    return "Index [ " + getName() + "]";
  }

  /**
   * Interprets transaction index changes for a certain key. Override it to customize index
   * behaviour on interpreting index changes. This may be viewed as an optimization, but in some
   * cases this is a requirement. For example, if you put multiple values under the same key during
   * the transaction for single-valued/unique index, but remove all of them except one before
   * commit, there is no point in throwing {@link RecordDuplicatedException} while applying index
   * changes.
   *
   * @param changes the changes to interpret.
   * @return the interpreted index key changes.
   */
  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    return changes.getEntriesAsList();
  }


  @Override
  public abstract boolean isUnique();


  @Override
  public CloseableIterator<Object> keys() {
    return storage.getIndexKeys(schemaIndex.getId());
  }

  @Override
  public IndexDefinition getDefinition() {
    return schemaIndex.getIndexDefinition();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (IndexAbstract) o;

    return schemaIndex.getName().equals(that.schemaIndex.getName());
  }

  @Override
  public int hashCode() {
    return schemaIndex.getName().hashCode();
  }

  @Override
  public int getIndexId() {
    return schemaIndex.getId();
  }


  @Override
  public Object getCollatingValue(final Object key) {
    if (key != null) {
      return schemaIndex.getIndexDefinition().getCollate().transform(key);
    }
    return key;
  }

  @Override
  public int compareTo(Index index) {
    final var name = index.getName();
    return this.schemaIndex.getName().compareTo(name);
  }

  /**
   * Indicates search behavior in case of {@link CompositeKey} keys that have less amount of
   * internal keys are used, whether lowest or highest partially matched key should be used. Such
   * keys is allowed to use only in
   */
  public enum PartialSearchMode {
    /**
     * Any partially matched key will be used as search result.
     */
    NONE,
    /**
     * The biggest partially matched key will be used as search result.
     */
    HIGHEST_BOUNDARY,

    /**
     * The smallest partially matched key will be used as search result.
     */
    LOWEST_BOUNDARY
  }

  private static Object enhanceCompositeKey(
      Object key, PartialSearchMode partialSearchMode, IndexDefinition definition) {
    if (!(key instanceof CompositeKey compositeKey)) {
      return key;
    }

    final var keySize = definition.getParamCount();

    if (!(keySize == 1
        || compositeKey.getKeys().size() == keySize
        || partialSearchMode.equals(PartialSearchMode.NONE))) {
      final var fullKey = new CompositeKey(compositeKey);
      var itemsToAdd = keySize - fullKey.getKeys().size();

      final Comparable<?> keyItem;
      if (partialSearchMode.equals(PartialSearchMode.HIGHEST_BOUNDARY)) {
        keyItem = ALWAYS_GREATER_KEY;
      } else {
        keyItem = ALWAYS_LESS_KEY;
      }

      for (var i = 0; i < itemsToAdd; i++) {
        fullKey.addKey(keyItem);
      }

      return fullKey;
    }

    return key;
  }

  public Object enhanceToCompositeKeyBetweenAsc(Object keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo, getDefinition());
    return keyTo;
  }

  public Object enhanceFromCompositeKeyBetweenAsc(Object keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom, getDefinition());
    return keyFrom;
  }

  public Object enhanceToCompositeKeyBetweenDesc(Object keyTo, boolean toInclusive) {
    PartialSearchMode partialSearchModeTo;
    if (toInclusive) {
      partialSearchModeTo = PartialSearchMode.HIGHEST_BOUNDARY;
    } else {
      partialSearchModeTo = PartialSearchMode.LOWEST_BOUNDARY;
    }

    keyTo = enhanceCompositeKey(keyTo, partialSearchModeTo, getDefinition());
    return keyTo;
  }

  public Object enhanceFromCompositeKeyBetweenDesc(Object keyFrom, boolean fromInclusive) {
    PartialSearchMode partialSearchModeFrom;
    if (fromInclusive) {
      partialSearchModeFrom = PartialSearchMode.LOWEST_BOUNDARY;
    } else {
      partialSearchModeFrom = PartialSearchMode.HIGHEST_BOUNDARY;
    }

    keyFrom = enhanceCompositeKey(keyFrom, partialSearchModeFrom, getDefinition());
    return keyFrom;
  }
}
