package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.LiveQueryBatchResultListener;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHookV2.LiveQueryOp;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryListenerV2;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSelectStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 *
 */
public class LiveQueryListenerImpl implements LiveQueryListenerV2 {

  public static final String BEFORE_METADATA_KEY = "$$before$$";
  private final LiveQueryResultListener clientListener;
  private DatabaseSessionInternal execDb;

  private final SQLSelectStatement statement;
  private String className;
  private List<RecordId> rids;

  private final Map<Object, Object> params;

  private final int token;
  private static final Random random = new Random();

  public LiveQueryListenerImpl(
      LiveQueryResultListener clientListener, String query, DatabaseSessionInternal db,
      Object[] iArgs) {
    this(clientListener, query, db, toPositionalParams(iArgs));
  }

  public LiveQueryListenerImpl(
      LiveQueryResultListener clientListener,
      String query,
      DatabaseSessionInternal session,
      Map<Object, Object> iArgs) {
    this.clientListener = clientListener;
    this.params = iArgs;

    if (query.trim().toLowerCase().startsWith("live ")) {
      query = query.trim().substring(5);
    }
    var stm = SQLEngine.parse(query, session);
    if (!(stm instanceof SQLSelectStatement)) {
      throw new CommandExecutionException(session,
          "Only SELECT statement can be used as a live query: " + query);
    }
    this.statement = (SQLSelectStatement) stm;
    validateStatement(statement, session);
    if (statement.getTarget().getItem().getIdentifier() != null) {
      this.className = statement.getTarget().getItem().getIdentifier().getStringValue();
      if (!session
          .getMetadata()
          .getImmutableSchemaSnapshot()
          .existsClass(className)) {
        throw new CommandExecutionException(session,
            "Class " + className + " not found in the schema: " + query);
      }
    } else if (statement.getTarget().getItem().getRids() != null) {
      var context = new BasicCommandContext();
      context.setDatabaseSession(session);
      this.rids =
          statement.getTarget().getItem().getRids().stream()
              .map(x -> x.toRecordId(new ResultInternal(session), context))
              .collect(Collectors.toList());
    }
    execInSeparateDatabase(
        new CallableFunction() {
          @Override
          public Object call(Object iArgument) {
            return execDb = session.copy();
          }
        });

    synchronized (random) {
      token = random.nextInt(); // TODO do something better ;-)!
    }
    LiveQueryHookV2.subscribe(token, this, session);

    CommandContext ctx = new BasicCommandContext();
    if (iArgs != null)
    // BIND ARGUMENTS INTO CONTEXT TO ACCESS FROM ANY POINT (EVEN FUNCTIONS)
    {
      for (var arg : iArgs.entrySet()) {
        ctx.setVariable(arg.getKey().toString(), arg.getValue());
      }
    }
  }

  private void validateStatement(SQLSelectStatement statement, DatabaseSessionInternal session) {
    if (statement.getProjection() != null) {
      if (statement.getProjection().getItems().stream().anyMatch(x -> x.isAggregate(session))) {
        throw new CommandExecutionException(session,
            "Aggregate Projections cannot be used in live query " + statement);
      }
    }
    if (statement.getTarget().getItem().getIdentifier() == null
        && statement.getTarget().getItem().getRids() == null) {
      throw new CommandExecutionException(session,
          "Live queries can only be executed against a Class or on RIDs" + statement);
    }
    if (statement.getOrderBy() != null) {
      throw new CommandExecutionException(session,
          "Live queries do not support ORDER BY " + statement);
    }
    if (statement.getGroupBy() != null) {
      throw new CommandExecutionException(session,
          "Live queries do not support GROUP BY " + statement);
    }
    if (statement.getSkip() != null || statement.getLimit() != null) {
      throw new CommandExecutionException(session,
          "Live queries do not support SKIP/LIMIT " + statement);
    }
  }

  public int getToken() {
    return token;
  }

  @Override
  public void onLiveResults(List<LiveQueryOp> iRecords) {
    execDb.activateOnCurrentThread();

    for (var iRecord : iRecords) {
      ResultInternal record;
      if (iRecord.type == RecordOperation.CREATED || iRecord.type == RecordOperation.UPDATED) {
        record = copy(execDb, iRecord.after);
        if (iRecord.type == RecordOperation.UPDATED) {
          var before = copy(execDb, iRecord.before);
          record.setMetadata(BEFORE_METADATA_KEY, before);
        }
      } else {
        record = copy(execDb, iRecord.before);
        record.setMetadata(BEFORE_METADATA_KEY, record);
      }

      if (filter(execDb, record)) {
        switch (iRecord.type) {
          case RecordOperation.DELETED:
            record.setMetadata(BEFORE_METADATA_KEY, null);
            clientListener.onDelete(execDb, applyProjections(record));
            break;
          case RecordOperation.UPDATED:
            Result before =
                applyProjections((ResultInternal) record.getMetadata(BEFORE_METADATA_KEY));
            record.setMetadata(BEFORE_METADATA_KEY, null);
            clientListener.onUpdate(execDb, before, applyProjections(record));
            break;
          case RecordOperation.CREATED:
            clientListener.onCreate(execDb, applyProjections(record));
            break;
        }
      }
    }
    if (clientListener instanceof LiveQueryBatchResultListener) {
      ((LiveQueryBatchResultListener) clientListener).onBatchEnd(execDb);
    }
  }

  private ResultInternal applyProjections(ResultInternal record) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(execDb);

    if (statement.getProjection() != null) {
      var result =
          (ResultInternal)
              statement.getProjection().calculateSingle(ctx, record);
      return result;
    }
    return record;
  }

  private boolean filter(DatabaseSessionInternal db, Result record) {
    // filter by class
    if (className != null) {
      var filterClass = record.getProperty("@class");
      var recordClassName = String.valueOf(filterClass);
      if (filterClass == null) {
        return false;
      } else if (!(className.equalsIgnoreCase(recordClassName))) {
        var recordClass =
            this.execDb.getMetadata().getImmutableSchemaSnapshot().getClass(recordClassName);
        if (recordClass == null) {
          return false;
        }
        if (!recordClass.getName().equalsIgnoreCase(className)
            && !recordClass.isSubClassOf(className)) {
          return false;
        }
      }
    }
    if (rids != null && !rids.isEmpty()) {
      var found = false;
      for (var rid : rids) {
        if (rid.equals(record.getIdentity())) {
          found = true;
          break;
        }
        if (rid.equals(record.getProperty("@rid"))) {
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    // filter conditions
    var where = statement.getWhereClause();
    if (where == null) {
      return true;
    }
    var ctx = new BasicCommandContext();
    ctx.setInputParameters(params);
    return where.matchesFilters(record, ctx);
  }

  private ResultInternal copy(DatabaseSessionInternal db, Result item) {
    if (item == null) {
      return null;
    }
    var result = new ResultInternal(db);

    for (var prop : item.getPropertyNames()) {
      result.setProperty(prop, item.getProperty(prop));
    }
    return result;
  }

  private static Map<Object, Object> toPositionalParams(Object[] iArgs) {
    Map<Object, Object> result = new HashMap<>();
    for (var i = 0; i < iArgs.length; i++) {
      result.put(i, iArgs[i]);
    }
    return result;
  }

  @Override
  public void onLiveResultEnd() {
    clientListener.onEnd(execDb);
  }

  protected void execInSeparateDatabase(final CallableFunction iCallback) {
    iCallback.call(null);
  }

  public SQLSelectStatement getStatement() {
    return statement;
  }
}
