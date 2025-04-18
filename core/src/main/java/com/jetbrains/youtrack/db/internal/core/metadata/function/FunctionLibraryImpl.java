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
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionImpl;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Manages stored functions.
 */
public class FunctionLibraryImpl {

  public static final String CLASSNAME = "OFunction";
  private static final String DROPPED_FUNCTIONS_MAP = "droppedFunctionsMap";
  protected final ConcurrentHashMap<String, Function> functions = new ConcurrentHashMap<>();
  private final AtomicBoolean needReload = new AtomicBoolean(false);

  public FunctionLibraryImpl() {
  }

  public static void create(DatabaseSessionInternal session) {
    init(session);
  }

  public void load(DatabaseSessionInternal session) {
    // COPY CALLBACK IN RAM
    final Map<String, CallableFunction<Object, Map<Object, Object>>> callbacks =
        new HashMap<>();
    for (var entry : functions.entrySet()) {
      if (entry.getValue().getCallback() != null) {
        callbacks.put(entry.getKey(), entry.getValue().getCallback());
      }
    }

    functions.clear();

    // LOAD ALL THE FUNCTIONS IN MEMORY
    if (session.getMetadata().getImmutableSchemaSnapshot().existsClass("OFunction")) {
      try (var result = session.query("select from OFunction order by name")) {
        while (result.hasNext()) {
          var res = result.next();
          var d = (EntityImpl) res.asEntity();
          // skip the function records which do not contain real data
          if (d.getPropertiesCount() == 0) {
            continue;
          }

          final var f = new Function(d);

          // RESTORE CALLBACK IF ANY
          f.setCallback(callbacks.get(f.getName()));

          functions.put(d.getProperty("name").toString().toUpperCase(Locale.ENGLISH), f);
        }
      }
    }
  }

  public static void onAfterFunctionDropped(FrontendTransactionImpl currentTx,
      EntityImpl functionEntity) {
    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_FUNCTIONS_MAP);
    if (droppedSequencesMap == null) {
      droppedSequencesMap = new HashMap<>();
    }

    currentTx.setCustomData(DROPPED_FUNCTIONS_MAP, droppedSequencesMap);
    droppedSequencesMap.put(functionEntity.getIdentity(),
        functionEntity.getProperty("name"));
  }


  public void onFunctionDropped(@Nonnull DatabaseSessionInternal session, @Nonnull RID rid) {
    var currentTx = (FrontendTransactionImpl) session.getTransactionInternal();

    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_FUNCTIONS_MAP);
    var functionName = droppedSequencesMap.get(rid);

    functions.remove(functionName);
    onFunctionsChanged(session);
  }

  public void createdFunction(DatabaseSessionEmbedded session, EntityImpl function) {
    final var f = new Function(session, function.getIdentity());
    functions.put(function.getProperty("name").toString().toUpperCase(Locale.ENGLISH), f);
    onFunctionsChanged(session);
  }

  public Set<String> getFunctionNames() {
    return Collections.unmodifiableSet(functions.keySet());
  }

  public Function getFunction(DatabaseSessionInternal session, final String iName) {
    reloadIfNeeded(session);
    return functions.get(iName.toUpperCase(Locale.ENGLISH));
  }

  public synchronized Function createFunction(
      DatabaseSessionEmbedded session, final String iName) {
    init(session);
    reloadIfNeeded(session);

    session.begin();
    final var f = new Function(session).setName(iName);
    try {
      f.save(session);
      functions.put(iName.toUpperCase(Locale.ENGLISH), f);
      session.commit();
    } catch (RecordDuplicatedException ex) {
      LogManager.instance().error(this, "Exception is suppressed, original exception is ", ex);

      //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
      throw BaseException.wrapException(
          new FunctionDuplicatedException("Function with name '" + iName + "' already exist"),
          null, session.getDatabaseName());
    }

    return f;
  }

  public void close() {
    functions.clear();
  }

  protected static void init(final DatabaseSessionInternal session) {
    if (session.getMetadata().getSchema().existsClass("OFunction")) {
      var f = session.getMetadata().getSchema().getClassInternal("OFunction");
      var prop = f.getPropertyInternal("name");

      if (prop.getAllIndexes().isEmpty()) {
        prop.createIndex(SchemaClass.INDEX_TYPE.UNIQUE);
      }
      return;
    }

    var f = (SchemaClassInternal) session.getMetadata().getSchema().createClass("OFunction");
    var prop = f.createProperty("name", PropertyTypeInternal.STRING, (PropertyTypeInternal) null,
        true);
    prop.createIndex(SchemaClass.INDEX_TYPE.UNIQUE);

    f.createProperty("code", PropertyTypeInternal.STRING, (PropertyTypeInternal) null, true);
    f.createProperty("language", PropertyTypeInternal.STRING, (PropertyTypeInternal) null, true);
    f.createProperty("idempotent", PropertyTypeInternal.BOOLEAN, (PropertyTypeInternal) null, true);
    f.createProperty("parameters", PropertyTypeInternal.EMBEDDEDLIST, PropertyTypeInternal.STRING,
        true);
  }

  public synchronized void dropFunction(DatabaseSessionInternal session, Function function) {
    reloadIfNeeded(session);
    var name = function.getName();
    function.delete(session);
    functions.remove(name.toUpperCase(Locale.ENGLISH));
  }

  public synchronized void dropFunction(DatabaseSessionInternal session, String iName) {
    reloadIfNeeded(session);

    session.executeInTx(
        transaction -> {
          var function = getFunction(session, iName);
          function.delete(session);
          functions.remove(iName.toUpperCase(Locale.ENGLISH));
        });
  }

  public void updatedFunction(DatabaseSessionEmbedded session, EntityImpl function) {
    reloadIfNeeded(session);
    var oldName = (String) function.getOriginalValue("name");
    if (oldName != null) {
      functions.remove(oldName.toUpperCase(Locale.ENGLISH));
    }
    CallableFunction<Object, Map<Object, Object>> callBack = null;
    var oldFunction = functions.get(function.getProperty("name").toString());
    if (oldFunction != null) {
      callBack = oldFunction.getCallback();
    }

    final var f = new Function(session, function.getIdentity());
    if (callBack != null) {
      f.setCallback(callBack);
    }

    functions.put(function.getProperty("name").toString().toUpperCase(Locale.ENGLISH), f);
    onFunctionsChanged(session);
  }

  private void reloadIfNeeded(DatabaseSessionInternal session) {
    if (needReload.get()) {
      load(session);
      needReload.set(false);
    }
  }

  private static void onFunctionsChanged(DatabaseSessionInternal session) {
    for (var listener : session.getSharedContext().browseListeners()) {
      listener.onFunctionLibraryUpdate(session, session.getDatabaseName());
    }
    session.getSharedContext().getYouTrackDB().getScriptManager()
        .close(session.getDatabaseName());
  }

  public synchronized void update() {
    needReload.set(true);
  }

  public static void validateFunctionRecord(EntityImpl entity) throws DatabaseException {
    String name = entity.getProperty("name");
    if (!Pattern.compile("[A-Za-z][A-Za-z0-9_]*").matcher(name).matches()) {
      var session = entity.getBoundedToSession();
      throw new DatabaseException(session != null ? session.getDatabaseName() : null,
          "Invalid function name: " + name);
    }
  }
}
