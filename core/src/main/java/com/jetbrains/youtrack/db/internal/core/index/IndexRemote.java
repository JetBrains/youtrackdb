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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Proxied abstract index.
 */
public class IndexRemote implements Index {

  protected final String databaseName;
  private final String wrappedType;
  private final String algorithm;
  private final RID rid;
  protected IndexDefinition indexDefinition;
  protected String name;
  protected Map<String, Object> configuration;

  private final int version;
  protected final Map<String, Object> metadata;
  protected Set<String> clustersToIndex;

  public IndexRemote(
      final String iName,
      final String iWrappedType,
      final String algorithm,
      final RID iRid,
      final IndexDefinition iIndexDefinition,
      final Map<String, Object> iConfiguration,
      final Set<String> clustersToIndex,
      String database) {
    this.name = iName;
    this.wrappedType = iWrappedType;
    this.algorithm = algorithm;
    this.rid = iRid;
    this.indexDefinition = iIndexDefinition;
    this.configuration = iConfiguration;

    @SuppressWarnings("unchecked")
    var metadata = (Map<String, Object>) iConfiguration.get("metadata");
    this.metadata = Collections.unmodifiableMap(metadata);

    this.clustersToIndex = new HashSet<>(clustersToIndex);
    this.databaseName = database;

    if (configuration == null) {
      version = -1;
    } else {
      final var version = (Integer) configuration.get(Index.INDEX_VERSION);
      this.version = Objects.requireNonNullElse(version, -1);
    }
  }

  public IndexRemote create(
      final IndexMetadata indexMetadata) {
    this.name = indexMetadata.getName();
    return this;
  }

  public IndexRemote delete(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public String getDatabaseName() {
    return databaseName;
  }

  @Override
  public long getRebuildVersion() {
    throw new UnsupportedOperationException();
  }

  public long count(DatabaseSessionInternal session, final Object iKey) {
    throw new UnsupportedOperationException();
  }

  public IndexRemote put(DatabaseSessionInternal session, final Object key,
      final Identifiable value) {
    throw new UnsupportedOperationException();
  }

  public boolean remove(DatabaseSessionInternal session, final Object key) {
    throw new UnsupportedOperationException();
  }

  public boolean remove(DatabaseSessionInternal session, final Object key,
      final Identifiable rid) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getVersion() {
    return version;
  }

  public long rebuild(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public IndexRemote clear(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public long getSize(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  public long getKeySize() {
    throw new UnsupportedOperationException();
  }

  public boolean isAutomatic() {
    return indexDefinition != null && indexDefinition.getClassName() != null;
  }

  @Override
  public boolean isUnique() {
    return false;
  }

  @Override
  public Object getCollatingValue(Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean loadFromConfiguration(DatabaseSessionInternal session, Map<String, ?> config) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, ?> updateConfiguration(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Index addCluster(DatabaseSessionInternal session, String iClusterName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeCluster(DatabaseSessionInternal session, String iClusterName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean canBeUsedInEqualityOperators() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexMetadata loadMetadata(DatabaseSessionInternal session, Map<String, ?> config) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {

  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long size(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RID> getRids(DatabaseSessionInternal session, Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<Object> keyStream() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesBetween(DatabaseSessionInternal session,
      Object fromKey, boolean fromInclusive, Object toKey, boolean toInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntries(DatabaseSessionInternal session,
      Collection<?> keys, boolean ascSortOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMajor(DatabaseSessionInternal session,
      Object fromKey, boolean fromInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RawPair<Object, RID>> streamEntriesMinor(DatabaseSessionInternal session,
      Object toKey, boolean toInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isNativeTxSupported() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void doPut(DatabaseSessionInternal session, AbstractPaginatedStorage storage, Object key,
      RID rid) throws InvalidIndexEngineIdException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean doRemove(DatabaseSessionInternal session, AbstractPaginatedStorage storage,
      Object key, RID rid) throws InvalidIndexEngineIdException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean doRemove(AbstractPaginatedStorage storage, Object key,
      DatabaseSessionInternal session)
      throws InvalidIndexEngineIdException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<RID> getRidsIgnoreTx(DatabaseSessionInternal session, Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Index create(DatabaseSessionInternal session, IndexMetadata metadata, boolean rebuild,
      ProgressListener progressListener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getIndexId() {
    throw new UnsupportedOperationException();
  }

  public String getName() {
    return name;
  }

  @Override
  public void flush() {
  }

  public String getType() {
    return wrappedType;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public Map<String, ?> getConfiguration(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  @Override
  public boolean supportsOrderedIterations() {
    throw new UnsupportedOperationException();
  }

  public RID getIdentity() {
    return rid;
  }

  public long rebuild(DatabaseSessionInternal session,
      final ProgressListener iProgressListener) {
    return rebuild(session);
  }

  public PropertyTypeInternal[] getKeyTypes() {
    if (indexDefinition != null) {
      return indexDefinition.getTypes();
    }
    return new PropertyTypeInternal[0];
  }

  @Override
  public Object get(DatabaseSessionInternal session, Object key) {
    return null;
  }

  public IndexDefinition getDefinition() {
    return indexDefinition;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (IndexRemote) o;

    return name.equals(that.name);
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  public Set<String> getClusters() {
    return Collections.unmodifiableSet(clustersToIndex);
  }

  @Override
  public boolean isRebuilding() {
    return false;
  }

  @Override
  public Object getFirstKey() {
    throw new UnsupportedOperationException("getFirstKey");
  }

  @Override
  public Object getLastKey(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException("getLastKey");
  }

  @Override
  public IndexCursor iterateEntriesBetween(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException("iterateEntriesBetween");
  }

  @Override
  public IndexCursor iterateEntriesMajor(DatabaseSessionInternal session, Object fromKey,
      boolean fromInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException("iterateEntriesMajor");
  }

  @Override
  public IndexCursor iterateEntriesMinor(DatabaseSessionInternal session, Object toKey,
      boolean toInclusive, boolean ascOrder) {
    throw new UnsupportedOperationException("iterateEntriesMinor");
  }

  @Override
  public IndexCursor iterateEntries(DatabaseSessionInternal session, Collection<?> keys,
      boolean ascSortOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexCursor cursor(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexCursor descCursor(DatabaseSessionInternal session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexKeyCursor keyCursor() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int compareTo(Index index) {
    final var name = index.getName();
    return this.name.compareTo(name);
  }
}
