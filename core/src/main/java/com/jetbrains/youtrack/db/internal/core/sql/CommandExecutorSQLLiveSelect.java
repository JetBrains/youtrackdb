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
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.SecurityException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedAccessHook;
import com.jetbrains.youtrack.db.internal.core.metadata.security.RestrictedOperation;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryListener;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import java.util.Map;
import java.util.Random;

/**
 *
 */
public class CommandExecutorSQLLiveSelect extends CommandExecutorSQLSelect
    implements LiveQueryListener {

  public static final String KEYWORD_LIVE_SELECT = "LIVE SELECT";
  private DatabaseSessionInternal execDb;
  private int token;
  private static final Random random = new Random();

  public CommandExecutorSQLLiveSelect(DatabaseSessionInternal session) {
    super(session);
  }

  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    execInSeparateDatabase(
        iArgument -> execDb = session.copy());

    synchronized (random) {
      token = random.nextInt(); // TODO do something better ;-)!
    }

    subscribeToLiveQuery(token, session);
    bindDefaultContextVariables(session);

    if (iArgs != null)
    // BIND ARGUMENTS INTO CONTEXT TO ACCESS FROM ANY POINT (EVEN FUNCTIONS)
    {
      for (var arg : iArgs.entrySet()) {
        context.setVariable(arg.getKey().toString(), arg.getValue());
      }
    }

    if (timeoutMs > 0) {
      getContext().beginExecution(timeoutMs, timeoutStrategy);
    }

    var result = new EntityImpl(session);
    // TODO change this name...?
    result.setProperty("token", token);

    ((LegacyResultSet) getResult(session)).add(result);
    return getResult(session);
  }

  private void subscribeToLiveQuery(Integer token, DatabaseSessionInternal db) {
    LiveQueryHook.subscribe(token, this, db);
  }

  public void onLiveResult(final RecordOperation iOp) {
    final Identifiable value = iOp.record;

    if (!matchesTarget(value)) {
      return;
    }
    if (!matchesFilters(value)) {
      return;
    }
    if (!checkSecurity(value)) {
    }
  }

  protected static void execInSeparateDatabase(final CallableFunction iCallback) {
    iCallback.call(null);
  }

  private boolean checkSecurity(Identifiable value) {
    try {
      // TODO check this!
      var transaction = execDb.getActiveTransaction();
      execDb.checkSecurity(
          Rule.ResourceGeneric.CLASS,
          Role.PERMISSION_READ,
          ((EntityImpl) transaction.load(value)).getSchemaClassName());
    } catch (SecurityException ignore) {
      return false;
    }
    var security = execDb.getSharedContext().getSecurity();
    var transaction1 = execDb.getActiveTransaction();
    var allowedByPolicy = security.canRead(execDb, transaction1.load(value));
    if (!allowedByPolicy) {
      return false;
    }
    var transaction = execDb.getActiveTransaction();
    return RestrictedAccessHook.isAllowed(
        execDb, transaction.load(value), RestrictedOperation.ALLOW_READ, false);
  }

  private boolean matchesFilters(Identifiable value) {
    if (this.compiledFilter == null || this.compiledFilter.getRootCondition() == null) {
      return true;
    }

    var transaction = execDb.getActiveTransaction();
    return !(Boolean.FALSE.equals(
        compiledFilter.evaluate(transaction.loadEntity(value), (EntityImpl) value, getContext())));
  }

  private boolean matchesTarget(Identifiable value) {
    if (!(value instanceof EntityImpl)) {
      return false;
    }
    final var className = ((EntityImpl) value).getSchemaClassName();
    if (className == null) {
      return false;
    }
    final var docClass = execDb.getMetadata().getSchema().getClass(className);
    if (docClass == null) {
      return false;
    }

    if (this.parsedTarget.getTargetClasses() != null) {
      for (var clazz : parsedTarget.getTargetClasses().keySet()) {
        if (docClass.isSubClassOf(clazz)) {
          return true;
        }
      }
    }
    if (this.parsedTarget.getTargetRecords() != null) {
      for (var r : parsedTarget.getTargetRecords()) {
        if (r.getIdentity().equals(value.getIdentity())) {
          return true;
        }
      }
    }
    if (this.parsedTarget.getTargetClusters() != null) {
      final var clusterName = execDb.getClusterNameById(value.getIdentity().getClusterId());
      if (clusterName != null) {
        for (var cluster : parsedTarget.getTargetClusters().keySet()) {
          if (clusterName.equalsIgnoreCase(cluster)) { // make it case insensitive in 3.0?
            return true;
          }
        }
      }
    }
    return false;
  }

  public void onLiveResultEnd() {
    if (execDb != null) {
      execDb.close();
    }
  }

  @Override
  public CommandExecutorSQLSelect parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var requestText = (CommandRequestText) iRequest;
    final var originalText = requestText.getText();
    final var remainingText = requestText.getText().trim().substring(5).trim();
    requestText.setText(remainingText);
    try {
      return super.parse(session, iRequest);
    } finally {
      requestText.setText(originalText);
    }
  }
}
