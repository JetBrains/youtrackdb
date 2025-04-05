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
package com.jetbrains.youtrack.db.internal.core.metadata.function;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.MetadataUpdateListener;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Manages stored functions.
 */
public class FunctionLibraryImpl {

  public static final String CLASSNAME = "OFunction";
  protected final Map<String, Function> functions = new ConcurrentHashMap<String, Function>();
  private final AtomicBoolean needReload = new AtomicBoolean(false);

  public FunctionLibraryImpl() {
  }

  public void create(DatabaseSessionInternal db) {
    init(db);
  }

  public void load() {
    throw new UnsupportedOperationException();
  }

  public void load(DatabaseSessionInternal db) {
    // COPY CALLBACK IN RAM
    final Map<String, CallableFunction<Object, Map<Object, Object>>> callbacks =
        new HashMap<String, CallableFunction<Object, Map<Object, Object>>>();
    for (Map.Entry<String, Function> entry : functions.entrySet()) {
      if (entry.getValue().getCallback() != null) {
        callbacks.put(entry.getKey(), entry.getValue().getCallback());
      }
    }

    functions.clear();

    // LOAD ALL THE FUNCTIONS IN MEMORY
    if (db.getMetadata().getImmutableSchemaSnapshot().existsClass("OFunction")) {
      try (ResultSet result = db.query("select from OFunction order by name")) {
        while (result.hasNext()) {
          Result res = result.next();
          EntityImpl d = (EntityImpl) res.getEntity().get();
          // skip the function records which do not contain real data
          if (d.fields() == 0) {
            continue;
          }

          final Function f = new Function(d);

          // RESTORE CALLBACK IF ANY
          f.setCallback(callbacks.get(f.getName(db)));

          functions.put(d.field("name").toString().toUpperCase(Locale.ENGLISH), f);
        }
      }
    }
  }

  public void droppedFunction(EntityImpl function) {
    functions.remove(function.field("name").toString());
    onFunctionsChanged(DatabaseRecordThreadLocal.instance().get());
  }

  public void createdFunction(EntityImpl function) {
    EntityImpl metadataCopy = function.copy();
    final Function f = new Function(metadataCopy);
    functions.put(metadataCopy.field("name").toString().toUpperCase(Locale.ENGLISH), f);
    onFunctionsChanged(DatabaseRecordThreadLocal.instance().get());
  }

  public Set<String> getFunctionNames() {
    return Collections.unmodifiableSet(functions.keySet());
  }

  public Function getFunction(final String iName) {
    reloadIfNeeded(DatabaseRecordThreadLocal.instance().get());
    return functions.get(iName.toUpperCase(Locale.ENGLISH));
  }

  public Function createFunction(final String iName) {
    throw new UnsupportedOperationException("Use Create function with database on internal api");
  }

  public synchronized Function createFunction(
      DatabaseSessionInternal database, final String iName) {
    init(database);
    reloadIfNeeded(DatabaseRecordThreadLocal.instance().get());

    database.begin();
    final Function f = new Function(database).setName(database, iName);
    try {
      f.save(database);
      functions.put(iName.toUpperCase(Locale.ENGLISH), f);
      database.commit();
    } catch (RecordDuplicatedException ex) {
      LogManager.instance().error(this, "Exception is suppressed, original exception is ", ex);

      //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
      throw BaseException.wrapException(
          new FunctionDuplicatedException("Function with name '" + iName + "' already exist"),
          null);
    }

    return f;
  }

  public void close() {
    functions.clear();
  }

  protected void init(final DatabaseSessionInternal db) {
    if (db.getMetadata().getSchema().existsClass("OFunction")) {
      final SchemaClass f = db.getMetadata().getSchema().getClass("OFunction");
      SchemaProperty prop = f.getProperty(db, "name");
      if (prop.getAllIndexes(db).isEmpty()) {
        prop.createIndex(db, SchemaClass.INDEX_TYPE.UNIQUE_HASH_INDEX);
      }
      return;
    }

    var f = (SchemaClassInternal) db.getMetadata().getSchema().createClass("OFunction");
    SchemaProperty prop = f.createProperty(db, "name", PropertyType.STRING, (PropertyType) null,
        true);
    prop.createIndex(db, SchemaClass.INDEX_TYPE.UNIQUE_HASH_INDEX);

    f.createProperty(db, "code", PropertyType.STRING, (PropertyType) null, true);
    f.createProperty(db, "language", PropertyType.STRING, (PropertyType) null, true);
    f.createProperty(db, "idempotent", PropertyType.BOOLEAN, (PropertyType) null, true);
    f.createProperty(db, "parameters", PropertyType.EMBEDDEDLIST, PropertyType.STRING, true);
  }

  public synchronized void dropFunction(DatabaseSessionInternal session, Function function) {
    reloadIfNeeded(session);
    String name = function.getName(session);
    EntityImpl entity = function.getDocument(session);
    entity.delete();
    functions.remove(name.toUpperCase(Locale.ENGLISH));
  }

  public synchronized void dropFunction(DatabaseSessionInternal session, String iName) {
    reloadIfNeeded(session);

    session.executeInTx(
        () -> {
          Function function = getFunction(iName);
          EntityImpl entity = function.getDocument(session);
          entity.delete();
          functions.remove(iName.toUpperCase(Locale.ENGLISH));
        });
  }

  public void updatedFunction(EntityImpl function) {
    DatabaseSessionInternal database = DatabaseRecordThreadLocal.instance().get();
    reloadIfNeeded(database);
    String oldName = (String) function.getOriginalValue("name");
    if (oldName != null) {
      functions.remove(oldName.toUpperCase(Locale.ENGLISH));
    }
    EntityImpl metadataCopy = function.copy();
    CallableFunction<Object, Map<Object, Object>> callBack = null;
    Function oldFunction = functions.get(metadataCopy.field("name").toString());
    if (oldFunction != null) {
      callBack = oldFunction.getCallback();
    }
    final Function f = new Function(metadataCopy);
    if (callBack != null) {
      f.setCallback(callBack);
    }
    functions.put(metadataCopy.field("name").toString().toUpperCase(Locale.ENGLISH), f);
    onFunctionsChanged(database);
  }

  private void reloadIfNeeded(DatabaseSessionInternal database) {
    if (needReload.get()) {
      load(database);
      needReload.set(false);
    }
  }

  private void onFunctionsChanged(DatabaseSessionInternal database) {
    for (MetadataUpdateListener listener : database.getSharedContext().browseListeners()) {
      listener.onFunctionLibraryUpdate(database, database.getName());
    }
    database.getSharedContext().getYouTrackDB().getScriptManager().close(database.getName());
  }

  public synchronized void update() {
    needReload.set(true);
  }

  public static void validateFunctionRecord(EntityImpl entity) throws DatabaseException {
    String name = entity.getProperty("name");
    if (!Pattern.compile("[A-Za-z][A-Za-z0-9_]*").matcher(name).matches()) {
      throw new DatabaseException("Invalid function name: " + name);
    }
  }
}
