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

import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaClassEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;

public final class SchemaProxy implements Schema {

  @Nonnull
  private final DatabaseSessionEmbedded session;

  public SchemaProxy(@Nonnull final DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  @Nonnull
  public SchemaClass createClass(final @Nonnull String className) {
    return new SchemaClassProxy(SchemaManager.createClass(session, className), session);
  }

  @Nonnull
  @Override
  public SchemaClass createClass(@Nonnull final String className,
      @Nonnull final SchemaClass superClass) {
    var superImpl = superClass.getImplementation();

    return new SchemaClassProxy(SchemaManager.createClass(session, className, superImpl),
        session);
  }

  @Override
  public @Nonnull SchemaClass createClass(@Nonnull String className, SchemaClass... superClasses) {
    var superClassEntities = new SchemaClassEntity[superClasses.length];

    for (var i = 0; i < superClasses.length; i++) {
      superClassEntities[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(SchemaManager.createClass(session, className, superClassEntities),
        session);
  }

  @Override
  public @Nonnull SchemaClass createAbstractClass(final @Nonnull String className) {
    return new SchemaClassProxy(SchemaManager.createAbstractClass(session, className), session);
  }

  @Override
  public @Nonnull SchemaClass createAbstractClass(final @Nonnull String className,
      final @Nonnull SchemaClass superClass) {
    var superImpl = superClass.getImplementation();
    return new SchemaClassProxy(SchemaManager.createAbstractClass(session, className, superImpl),
        session);
  }

  @Override
  public @NonNull SchemaClass createAbstractClass(@Nonnull String className,
      final SchemaClass... superClasses) {
    var superClassEntities = new SchemaClassEntity[superClasses.length];

    for (var i = 0; i < superClasses.length; i++) {
      superClassEntities[i] = superClasses[i].getImplementation();
    }

    return new SchemaClassProxy(
        SchemaManager.createAbstractClass(session, className, superClassEntities),
        session);
  }

  @Override
  public @Nonnull SchemaClass getOrCreateClass(final @Nonnull String className) {
    return new SchemaClassProxy(SchemaManager.getOrCreateClass(session, className), session);
  }

  @Override
  public void dropClass(final @Nonnull String className) {
    SchemaManager.dropClass(session, className);
  }

  @Override
  public boolean existsClass(final @Nonnull String className) {
    return SchemaManager.existsClass(session, className);
  }

  @Override
  @Nullable
  public SchemaClass getClass(final @NonNull String className) {
    var cls = SchemaManager.getClass(session, className);

    return cls == null ? null : new SchemaClassProxy(cls, session);
  }

  @Override
  public @Nonnull Collection<? extends ImmutableSchemaClass> getClasses() {
    var classes = SchemaManager.getClasses(session);
    var result = new ArrayList<SchemaClass>(classes.size());

    for (var cls : classes) {
      result.add(new SchemaClassProxy(cls, session));
    }

    return result;
  }

  @Override
  public @Nonnull Collection<String> getIndexes() {
    var indexManager = session.getSharedContext().getIndexManager();
    var indexesInternal = indexManager.getIndexes();

    var indexes = new HashSet<String>(indexesInternal.size());
    for (var index : indexesInternal) {
      indexes.add(index.getName());
    }

    return indexes;
  }

  @Override
  public @Nonnull IndexDefinition getIndexDefinition(@Nonnull String indexName) {
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
        SchemaManager.INDEX_TYPE.valueOf(index.getType()), indexDefinition.isNullValuesIgnored(),
        indexDefinition.getCollate().getName(), metadata);
  }

  @Nullable
  @Override
  public SchemaClass getClassByCollectionId(int collectionId) {
    var cls = SchemaManager.getClassByCollectionId(session, collectionId);
    return cls != null ? new SchemaClassProxy(cls, session) : null;
  }


  @Override
  @Nullable
  public GlobalProperty getGlobalPropertyById(int id) {
    var globalProperty = SchemaManager.getGlobalPropertyById(session, id);
    if (globalProperty == null) {
      return null;
    }

    return new GlobalPropertyRecord(globalProperty.getName(), globalProperty.getType(),
        globalProperty.getId());
  }

  public String toString() {
    return "Schema: [" + session.getDatabaseName() + "]";
  }
}
