package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.LiveQueryResultListener;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabasePoolInternal;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class LiveQueryListenerImpl implements LiveQueryListenerV2 {

  public static final String BEFORE_METADATA_KEY = "$$before$$";
  private final LiveQueryResultListener clientListener;
  @Nonnull
  private final DatabasePoolInternal pool;

  private final SQLSelectStatement statement;
  private String className;
  private List<RecordId> rids;

  private final Map<Object, Object> params;

  private final int token;
  private static final Random random = new Random();

  public LiveQueryListenerImpl(
      LiveQueryResultListener clientListener, String query, @Nonnull DatabasePoolInternal pool,
      Object[] iArgs) {
    this(clientListener, query, pool, toPositionalParams(iArgs));
  }

  public LiveQueryListenerImpl(
      LiveQueryResultListener clientListener,
      String query,
      @Nonnull
      DatabasePoolInternal pool,
      Map<Object, Object> iArgs) {
    this.clientListener = clientListener;
    this.params = iArgs;
    this.pool = pool;

    if (query.trim().toLowerCase().startsWith("live ")) {
      query = query.trim().substring(5);
    }
    try (var session = (DatabaseSessionInternal) pool.acquire()) {
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
  }

  private static void validateStatement(SQLSelectStatement statement,
      DatabaseSessionInternal session) {
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
  public void onLiveResults(List<LiveQueryOp> liveQueryOps) {
    try (var session = (DatabaseSessionInternal) pool.acquire()) {

      for (var queryOp : liveQueryOps) {
        ResultInternal record;
        if (queryOp.type == RecordOperation.CREATED || queryOp.type == RecordOperation.UPDATED) {
          record = copy(session, queryOp.after);
          if (queryOp.type == RecordOperation.UPDATED) {
            var before = copy(session, queryOp.before);
            record.setMetadata(BEFORE_METADATA_KEY, before);
          }
        } else {
          record = copy(session, queryOp.before);
          record.setMetadata(BEFORE_METADATA_KEY, record);
        }

        if (filter(session, record)) {
          switch (queryOp.type) {
            case RecordOperation.DELETED:
              record.setMetadata(BEFORE_METADATA_KEY, null);
              clientListener.onDelete(session, applyProjections(record, session).detach());
              break;
            case RecordOperation.UPDATED:
              Result before =
                  applyProjections((ResultInternal) record.getMetadata(BEFORE_METADATA_KEY),
                      session);
              record.setMetadata(BEFORE_METADATA_KEY, null);
              clientListener.onUpdate(session, before, applyProjections(record, session).detach());
              break;
            case RecordOperation.CREATED:
              clientListener.onCreate(session, applyProjections(record, session).detach());
              break;
          }
        }
      }
      if (clientListener instanceof LiveQueryBatchResultListener) {
        ((LiveQueryBatchResultListener) clientListener).onBatchEnd(session);
      }
    }
  }

  private ResultInternal applyProjections(ResultInternal record, DatabaseSessionInternal session) {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    if (statement.getProjection() != null) {
      return (ResultInternal)
          statement.getProjection().calculateSingle(ctx, record);
    }
    return record;
  }

  private boolean filter(DatabaseSessionInternal session, Result record) {
    // filter by class
    if (className != null) {
      var filterClass = record.getProperty("@class");
      var recordClassName = String.valueOf(filterClass);
      if (filterClass == null) {
        return false;
      } else if (!(className.equalsIgnoreCase(recordClassName))) {
        var recordClass =
            session.getMetadata().getImmutableSchemaSnapshot().getClass(recordClassName);
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
    ctx.setDatabaseSession(session);

    ctx.setInputParameters(params);
    return where.matchesFilters(record, ctx);
  }

  @Nullable
  private static ResultInternal copy(DatabaseSessionInternal db, Result item) {
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
    try (var session = (DatabaseSessionInternal) pool.acquire()) {
      clientListener.onEnd(session);
    }
    pool.close();
  }

  public SQLSelectStatement getStatement() {
    return statement;
  }
}
