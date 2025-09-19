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
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.metadata.function.Function;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibrary;
import com.jetbrains.youtrackdb.internal.core.metadata.function.FunctionLibraryProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaSnapshot;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Identity;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Security;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityProxy;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityUserImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibrary;
import com.jetbrains.youtrackdb.internal.core.metadata.sequence.SequenceLibraryProxy;
import com.jetbrains.youtrackdb.internal.core.schedule.Scheduler;
import com.jetbrains.youtrackdb.internal.core.schedule.SchedulerProxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SessionMetadata {
  public static final String COLLECTION_INTERNAL_NAME = "internal";

  public static final String COLLECTION_NAME_SCHEMA_CLASS = "$schemaClassInternal";
  public static final String COLLECTION_NAME_SCHEMA_PROPERTY = "$schemaPropertyInternal";
  public static final String COLLECTION_NAME_GLOBAL_PROPERTY = "$globalPropertyInternal";

  public static Set<String> SYSTEM_COLLECTION =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  SecurityUserImpl.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Role.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Identity.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  Function.CLASS_NAME.toLowerCase(Locale.ENGLISH),
                  COLLECTION_INTERNAL_NAME, COLLECTION_NAME_SCHEMA_PROPERTY,
                  COLLECTION_NAME_GLOBAL_PROPERTY)));


  private SchemaProxy schema;
  private Security security;
  private FunctionLibraryProxy functionLibrary;
  private SchedulerProxy scheduler;
  private SequenceLibraryProxy sequenceLibrary;

  private SchemaSnapshot schemaSnapshot = null;
  private int immutableCount = 0;
  private final DatabaseSessionEmbedded database;

  public SessionMetadata(DatabaseSessionEmbedded databaseDocument) {
    this.database = databaseDocument;
  }

  public SchemaProxy getSlowMutableSchema() {
    return schema;
  }

  public ImmutableSchema getFastImmutableSchema() {
    var transaction = database.getTransactionInternal();

    if (transaction.isSchemaChanged()) {
      return schema;
    }

    return schemaSnapshot;
  }


  public void makeThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      if (schema != null) {
        this.schemaSnapshot = schema.makeSnapshot();
      }
    }
    this.immutableCount++;
  }

  public void clearThreadLocalSchemaSnapshot() {
    this.immutableCount--;
    if (this.immutableCount == 0) {
      this.schemaSnapshot = null;
    }
  }

  public void forceClearThreadLocalSchemaSnapshot() {
    if (this.immutableCount == 0) {
      this.schemaSnapshot = null;
    } else {
      throw new IllegalStateException("Attempted to force clear local schema snapshot for thread " +
          Thread.currentThread().getName() + " but the snapshot usage count is not zero: "
          + this.immutableCount);
    }
  }

  public Security getSecurity() {
    return security;
  }


  public SharedContext init(SharedContext shared) {
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

  public FunctionLibrary getFunctionLibrary() {
    return functionLibrary;
  }

  public SequenceLibrary getSequenceLibrary() {
    return sequenceLibrary;
  }

  public Scheduler getScheduler() {
    return scheduler;
  }
}
