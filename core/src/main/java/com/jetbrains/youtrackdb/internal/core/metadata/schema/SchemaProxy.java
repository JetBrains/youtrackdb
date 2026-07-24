/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.GlobalProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Proxy class to use the shared SchemaShared instance. Before to delegate each operations it sets
 * the current database in the thread local.
 *
 * <p>Reads route through {@link #resolve()} and writes through {@link #resolveForWrite()} (see
 * {@link SchemaProxedResource}), so during a schema transaction every method sees and mutates the
 * transaction's private tx-local {@link SchemaShared} copy rather than the committed shared
 * instance. The immutable snapshot read ({@link #makeSnapshot()}) is tx-aware too: during a schema
 * or index transaction it builds a session-private snapshot from the tx-local copy; outside one it
 * stays on the committed instance's shared cache.
 */
public final class SchemaProxy extends SchemaProxedResource<SchemaShared>
    implements SchemaInternal {

  public SchemaProxy(final SchemaShared iDelegate, final DatabaseSessionEmbedded iDatabase) {
    super(iDelegate, iDatabase);
  }

  @Override
  protected SchemaShared rebindToTxLocal(@Nonnull SchemaShared txLocalSchema) {
    // The proxy stands for the whole schema; its tx-local resolution is the copy itself.
    return txLocalSchema;
  }

  @Override
  protected void recordWriteTarget(@Nonnull TxSchemaState txState,
      @Nonnull SchemaShared resolved) {
    // A schema-level write records nothing here. Class create / drop / rename already record the
    // specific class(es) they touch through their own markClassChanged calls (recording the precise
    // name a create/drop/rename needs, which a whole-schema hook could not derive). Global-property
    // and blob-collection writes mutate only the root non-link payload, which the commit's
    // root-payload diff detects on its own. Blanket-recording every class on any schema-level write
    // would force the commit to rewrite every class's per-class record, defeating the selective
    // write's write-amplification win.
  }

  @Override
  public ImmutableSchema makeSnapshot() {
    assert session.assertIfNotActive();
    var txState = session.getTxSchemaState();
    if (txState != null) {
      // A schema- or index-changing transaction is in progress. Build a session-private, uncached
      // snapshot from the tx-local SchemaShared copy: its classes, property types, and constraint
      // rules reflect the transaction's own uncommitted schema (so validation and serialization
      // enforce a same-tx schema change), and its per-class index list resolves against this
      // session's index overlay through the index-manager routing seam. The snapshot is never
      // stored in the process-shared snapshot cache, so a concurrent session still reads the
      // committed view.
      //
      // Memoize the built snapshot on the transaction state for the lifetime of the current
      // tx-local schema generation. This branch is reached unpinned per record on the same-tx
      // DDL-then-DML path (getImmutableSchemaClass reads unpinned, executeReadRecord pins per
      // record), so without the memo an operation touching N records would rebuild the whole
      // ImmutableSchema up to N times. The memo stays session-scoped (it lives on TxSchemaState,
      // never in the shared cache) and is invalidated on every mid-tx schema or index change
      // through forceRebuildTxSchemaSnapshot, so a change still forces exactly one rebuild.
      var memoized = txState.getOverlaySnapshot();
      if (memoized != null) {
        return memoized;
      }
      var built = txState.getTxLocalSchema().makeUncachedSnapshot(session);
      txState.setOverlaySnapshot(built);
      return built;
    }
    // The committed fast path is strictly unchanged from the pre-tx-aware behavior: outside a
    // schema/index transaction the snapshot is taken from the committed instance's shared cache.
    return delegate.makeSnapshot(session);
  }

  public void create() {
    assert session.assertIfNotActive();
    resolveForWrite().create(session);
  }

  @Override
  public int countClasses() {
    assert session.assertIfNotActive();
    return resolve().countClasses(session);
  }

  @Override
  @Nonnull
  public SchemaClass createClass(final String iClassName) {
    assert session.assertIfNotActive();
    return new SchemaClassProxy(resolveForWrite().createClass(session, iClassName), session);
  }

  @Override
  public SchemaClass getOrCreateClass(final String iClassName) {
    return getOrCreateClass(iClassName, (SchemaClass) null);
  }

  @Override
  @Nullable public SchemaClass getOrCreateClass(final String iClassName,
      final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    if (iClassName == null) {
      return null;
    }

    var cls = resolve().getClass(iClassName);
    if (cls != null) {
      return new SchemaClassProxy(cls, session);
    }

    var schema = resolveForWrite();
    cls = schema.getOrCreateClass(session, iClassName,
        iSuperClass != null
            ? reresolveClassImpl(schema, ((SchemaClassInternal) iSuperClass).getImplementation())
            : null);

    return new SchemaClassProxy(cls, session);
  }

  @Override
  public SchemaClass getOrCreateClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();

    var schema = resolveForWrite();
    var superImpls = new SchemaClassImpl[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = reresolveClassImpl(schema,
          ((SchemaClassInternal) superClasses[i]).getImplementation());
    }

    return new SchemaClassProxy(schema.getOrCreateClass(session, iClassName, superImpls),
        session);
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull final String iClassName,
      @Nonnull final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpl = reresolveClassImpl(schema,
        ((SchemaClassInternal) iSuperClass).getImplementation());

    return new SchemaClassProxy(schema.createClass(session, iClassName, superImpl, null),
        session);
  }

  @Override
  public SchemaClass createClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpls = new SchemaClassImpl[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = reresolveClassImpl(schema,
          ((SchemaClassInternal) superClasses[i]).getImplementation());
    }

    return new SchemaClassProxy(schema.createClass(session, iClassName, superImpls), session);
  }

  @Override
  public SchemaClass createClass(final String iClassName, final SchemaClass iSuperClass,
      final int[] iCollectionIds) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpl =
        iSuperClass != null
            ? reresolveClassImpl(schema, ((SchemaClassInternal) iSuperClass).getImplementation())
            : null;
    return new SchemaClassProxy(
        schema.createClass(session, iClassName, superImpl, iCollectionIds),
        session);
  }

  @Override
  public SchemaClass createClass(String className, int[] collectionIds,
      SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpls = new SchemaClassImpl[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = reresolveClassImpl(schema,
          ((SchemaClassInternal) superClasses[i]).getImplementation());
    }

    return new SchemaClassProxy(schema.createClass(session, className, collectionIds, superImpls),
        session);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName) {
    assert session.assertIfNotActive();

    return new SchemaClassProxy(resolveForWrite().createAbstractClass(session, iClassName),
        session);
  }

  @Override
  public SchemaClass createAbstractClass(final String iClassName, final SchemaClass iSuperClass) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpl =
        iSuperClass != null
            ? reresolveClassImpl(schema, ((SchemaClassInternal) iSuperClass).getImplementation())
            : null;
    return new SchemaClassProxy(schema.createAbstractClass(session, iClassName, superImpl),
        session);
  }

  @Override
  public SchemaClass createAbstractClass(String iClassName, SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpls = new SchemaClassImpl[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = reresolveClassImpl(schema,
          ((SchemaClassInternal) superClasses[i]).getImplementation());
    }
    return new SchemaClassProxy(schema.createAbstractClass(session, iClassName, superImpls),
        session);
  }

  @Override
  public void dropClass(final String iClassName) {
    assert session.assertIfNotActive();
    resolveForWrite().dropClass(session, iClassName);
  }

  @Override
  public boolean existsClass(final String iClassName) {
    assert session.assertIfNotActive();
    if (iClassName == null) {
      return false;
    }

    return resolve().existsClass(iClassName);
  }

  @Override
  @Nullable public SchemaClass getClass(final Class<?> iClass) {
    assert session.assertIfNotActive();
    if (iClass == null) {
      return null;
    }

    var cls = resolve().getClass(iClass.getName());
    return cls == null ? null : new SchemaClassProxy(cls, session);
  }

  @Override
  @Nullable public SchemaClass getClass(final String iClassName) {
    if (iClassName == null) {
      return null;
    }

    assert session.assertIfNotActive();
    var cls = resolve().getClass(iClassName);
    return cls == null ? null : new SchemaClassProxy(cls, session);
  }

  @Override
  public Collection<SchemaClass> getClasses() {
    assert session.assertIfNotActive();
    var classes = resolve().getClasses(session);
    var result = new ArrayList<SchemaClass>(classes.size());
    for (var cls : classes) {
      result.add(new SchemaClassProxy(cls, session));
    }
    return result;
  }

  @Override
  public Collection<String> getIndexes() {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();

    var indexesInternal = indexManager.getIndexes();
    var indexes = new HashSet<String>(indexesInternal.size());
    for (var index : indexesInternal) {
      indexes.add(index.getName());
    }

    return indexes;
  }

  @Override
  public boolean indexExists(String indexName) {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();

    return indexManager.existsIndex(indexName);
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(String indexName) {
    assert session.assertIfNotActive();
    var indexManager = session.getSharedContext().getIndexManager();
    var index = indexManager.getIndex(indexName);

    if (index == null) {
      throw new IllegalArgumentException("Index '" + indexName + "' not found");
    }

    var indexDefinition = index.getDefinition();

    var metadata = index.getMetadata();

    if (metadata == null) {
      metadata = Collections.emptyMap();
    }

    return new IndexDefinition(indexName, indexDefinition.getClassName(),
        Collections.unmodifiableList(indexDefinition.getProperties()),
        SchemaClass.INDEX_TYPE.valueOf(index.getType()), indexDefinition.isNullValuesIgnored(),
        indexDefinition.getCollate().getName(), metadata);
  }

  @Deprecated
  public void load() {
    assert session.assertIfNotActive();
    delegate.load(session);
  }

  public Schema reload() {
    assert session.assertIfNotActive();
    delegate.reload(session);
    return this;
  }

  @Override
  public int getVersion() {
    return delegate.getVersion();
  }

  @Override
  public RecordIdInternal getIdentity() {
    return resolve().getIdentity();
  }

  @Deprecated
  public void close() {
    // DO NOTHING THE DELEGATE CLOSE IS MANAGED IN A DIFFERENT CONTEXT
  }

  @Override
  public String toString() {

    return delegate.toString();
  }

  @Override
  public Set<SchemaClass> getClassesRelyOnCollection(final String iCollectionName,
      DatabaseSessionEmbedded session) {
    assert this.session.assertIfNotActive();
    var classes = resolve().getClassesRelyOnCollection(this.session, iCollectionName);
    var result = new HashSet<SchemaClass>(classes.size());

    for (var cls : classes) {
      result.add(new SchemaClassProxy(cls, this.session));
    }
    return result;
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull String className, int collections,
      @Nonnull SchemaClass... superClasses) {
    assert session.assertIfNotActive();
    var schema = resolveForWrite();
    var superImpls = new SchemaClassImpl[superClasses.length];
    for (var i = 0; i < superClasses.length; i++) {
      superImpls[i] = reresolveClassImpl(schema,
          ((SchemaClassInternal) superClasses[i]).getImplementation());
    }

    return new SchemaClassProxy(schema.createClass(session, className, collections, superImpls),
        session);
  }

  @Nullable @Override
  public SchemaClass getClassByCollectionId(int collectionId) {
    assert session.assertIfNotActive();

    var cls = resolve().getClassByCollectionId(collectionId);
    return cls != null ? new SchemaClassProxy(cls, session) : null;
  }

  @Override
  public GlobalProperty getGlobalPropertyById(int id) {
    assert session.assertIfNotActive();
    return resolve().getGlobalPropertyById(id);
  }

  @Override
  public List<GlobalProperty> getGlobalProperties() {
    assert session.assertIfNotActive();
    return resolve().getGlobalProperties();
  }

  @Override
  public GlobalProperty createGlobalProperty(String name, PropertyType type, Integer id) {
    assert session.assertIfNotActive();
    return resolveForWrite().createGlobalProperty(session, name,
        PropertyTypeInternal.convertFromPublicType(type), id);
  }

  @Override
  public CollectionSelectionFactory getCollectionSelectionFactory() {
    assert session.assertIfNotActive();
    return resolve().getCollectionSelectionFactory();
  }

  @Nullable @Override
  public SchemaClassInternal getClassInternal(String iClassName) {
    assert session.assertIfNotActive();
    var cls = resolve().getClass(iClassName);

    return cls != null ? new SchemaClassProxy(cls, session) : null;
  }

  public IntSet getBlobCollections() {
    assert session.assertIfNotActive();
    return resolve().getBlobCollections();
  }

  public int addBlobCollection(final int collectionId) {
    assert session.assertIfNotActive();
    return resolveForWrite().addBlobCollection(session, collectionId);
  }

  public void removeBlobCollection(String collectionName) {
    assert session.assertIfNotActive();
    resolveForWrite().removeBlobCollection(session, collectionName);
  }
}
