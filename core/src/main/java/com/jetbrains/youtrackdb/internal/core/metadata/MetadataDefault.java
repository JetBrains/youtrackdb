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
package com.jetbrains.youtrackdb.internal.core.metadata;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibrary;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Security;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibrary;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrackdb.internal.core.schedule.Scheduler;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerProxy;
import java.io.IOException;
import javax.annotation.Nullable;

public class MetadataDefault implements MetadataInternal {

  public static final String COLLECTION_INTERNAL_NAME = "internal";

  public static final String COLLECTION_NAME_SCHEMA_CLASS = "$schemaClassInternal";
  public static final String COLLECTION_NAME_SCHEMA_PROPERTY = "$schemaPropertyInternal";
  public static final String COLLECTION_NAME_GLOBAL_PROPERTY = "$globalPropertyInternal";

  protected int schemaCollectionId;

  protected SchemaProxy schema;
  protected Security security;
  protected FunctionLibraryProxy functionLibrary;
  protected SchedulerProxy scheduler;
  protected SequenceLibraryProxy sequenceLibrary;

  private SchemaSnapshot schemaSnapshot = null;
  private int immutableCount = 0;
  private DatabaseSessionEmbedded database;

  public MetadataDefault() {
  }

  public MetadataDefault(DatabaseSessionEmbedded databaseDocument) {
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
  public void makeThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      if (schema != null) {
        this.schemaSnapshot = schema.makeSnapshot();
      }
    }
    this.immutableCount++;
  }

  @Override
  public void clearThreadLocalSchemaSnapshot() {
    this.immutableCount--;
    if (this.immutableCount == 0) {
      this.schemaSnapshot = null;
    }
  }

  @Override
  public void forceClearThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      this.schemaSnapshot = null;
    } else {
      throw new IllegalStateException("Attempted to force clear local schema snapshot for thread " +
          Thread.currentThread().getName() + " but the snapshot usage count is not zero: "
          + this.immutableCount);
    }
  }

  @Nullable
  @Override
  public ImmutableSchema getImmutableSchema(DatabaseSessionEmbedded session) {
    var currentTransaction = session.getTransactionInternal();
    if (currentTransaction.isSchemaChanged()) {
      return schema;
    }

    if (schemaSnapshot == null) {
      if (schema == null) {
        return null;
      }
      return schema.makeSnapshot();
    }

    return schemaSnapshot;
  }

  public Security getSecurity() {
    return security;
  }


  public SharedContext init(SharedContext shared) {
    schemaCollectionId = database.getCollectionIdByName(COLLECTION_INTERNAL_NAME);

    schema = new SchemaProxy(shared.getSchema(), database);
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
