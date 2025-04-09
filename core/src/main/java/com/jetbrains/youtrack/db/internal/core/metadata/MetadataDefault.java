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
package com.jetbrains.youtrack.db.internal.core.metadata;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.SharedContext;
import com.jetbrains.youtrack.db.internal.core.index.IndexManager;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerAbstract;
import com.jetbrains.youtrack.db.internal.core.index.IndexManagerProxy;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibrary;
import com.jetbrains.youtrack.db.internal.core.metadata.function.FunctionLibraryProxy;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaProxy;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Security;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityProxy;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibrary;
import com.jetbrains.youtrack.db.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrack.db.internal.core.schedule.Scheduler;
import com.jetbrains.youtrack.db.internal.core.schedule.SchedulerProxy;
import java.io.IOException;
import javax.annotation.Nullable;

public class MetadataDefault implements MetadataInternal {

  public static final String COLLECTION_INTERNAL_NAME = "internal";
  protected int schemaCollectionId;

  protected SchemaProxy schema;
  protected Security security;
  protected IndexManagerProxy indexManager;
  protected FunctionLibraryProxy functionLibrary;
  protected SchedulerProxy scheduler;
  protected SequenceLibraryProxy sequenceLibrary;

  private ImmutableSchema immutableSchema = null;
  private int immutableCount = 0;
  private DatabaseSessionInternal database;

  public MetadataDefault() {
  }

  public MetadataDefault(DatabaseSessionInternal databaseDocument) {
    this.database = databaseDocument;
  }

  @Deprecated
  public void load() {
  }

  @Deprecated
  public void create() throws IOException {
  }

  public SchemaProxy getSchema() {
    return schema;
  }

  @Override
  public SchemaInternal getSchemaInternal() {
    return schema;
  }

  @Override
  public void makeThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      if (schema != null) {
        this.immutableSchema = schema.makeSnapshot();
      }
    }
    this.immutableCount++;
  }

  @Override
  public void clearThreadLocalSchemaSnapshot() {
    this.immutableCount--;
    if (this.immutableCount == 0) {
      this.immutableSchema = null;
    }
  }

  @Override
  public void forceClearThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      this.immutableSchema = null;
    } else {
      throw new IllegalStateException("Attempted to force clear local schema snapshot for thread " +
          Thread.currentThread().getName() + " but the snapshot usage count is not zero: "
          + this.immutableCount);
    }
  }

  @Nullable
  @Override
  public ImmutableSchema getImmutableSchemaSnapshot() {
    if (immutableSchema == null) {
      if (schema == null) {
        return null;
      }
      return schema.makeSnapshot();
    }
    return immutableSchema;
  }

  public Security getSecurity() {
    return security;
  }

  /**
   * {@inheritDoc}
   */
  public IndexManager getIndexManager() {
    return indexManager;
  }

  @Override
  public IndexManagerAbstract getIndexManagerInternal() {
    return indexManager.delegate();
  }

  public SharedContext init(SharedContext shared) {
    schemaCollectionId = database.getCollectionIdByName(COLLECTION_INTERNAL_NAME);

    schema = new SchemaProxy(shared.getSchema(), database);
    indexManager = new IndexManagerProxy(shared.getIndexManager(), database);

    security = new SecurityProxy(shared.getSecurity(), database);
    functionLibrary = new FunctionLibraryProxy(shared.getFunctionLibrary(), database);
    sequenceLibrary = new SequenceLibraryProxy(shared.getSequenceLibrary(), database);
    scheduler = new SchedulerProxy(shared.getScheduler(), database);
    return shared;
  }

  /**
   * Reloads the internal objects.
   */
  public void reload() {
    // RELOAD ALL THE SHARED CONTEXT
    database.getSharedContext().reload(database);
    // ADD HERE THE RELOAD OF A PROXY OBJECT IF NEEDED
  }

  /**
   * Closes internal objects
   */
  @Deprecated
  public void close() {
    // DO NOTHING BECAUSE THE PROXY OBJECT HAVE NO DIRECT STATE
    // ADD HERE THE CLOSE OF A PROXY OBJECT IF NEEDED
  }

  protected DatabaseSessionInternal getDatabase() {
    return database;
  }

  public FunctionLibrary getFunctionLibrary() {
    return functionLibrary;
  }

  @Override
  public SequenceLibrary getSequenceLibrary() {
    return sequenceLibrary;
  }

  public Scheduler getScheduler() {
    return scheduler;
  }
}
