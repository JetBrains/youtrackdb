package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.sql.CommandExecutorSQLAbstract;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFromClause;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLFromItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInputParameter;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMetadataIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLTraverseProjectionItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLTraverseStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 */
public class TraverseExecutionPlanner {

  private List<SQLTraverseProjectionItem> projections = null;
  private final SQLFromClause target;

  private final SQLWhereClause whileClause;

  private final SQLTraverseStatement.Strategy strategy;
  private final SQLInteger maxDepth;

  private final SQLSkip skip;
  private final SQLLimit limit;

  public TraverseExecutionPlanner(SQLTraverseStatement statement) {
    // copying the content, so that it can be manipulated and optimized
    if (statement.getProjections() == null) {
      this.projections = null;
    } else {
      this.projections =
          statement.getProjections().stream().map(SQLTraverseProjectionItem::copy)
              .collect(Collectors.toList());
    }

    this.target = statement.getTarget();
    this.whileClause =
        statement.getWhileClause() == null ? null : statement.getWhileClause().copy();

    this.strategy =
        statement.getStrategy() == null
            ? SQLTraverseStatement.Strategy.DEPTH_FIRST
            : statement.getStrategy();
    this.maxDepth = statement.getMaxDepth() == null ? null : statement.getMaxDepth().copy();

    this.skip = statement.getSkip();
    this.limit = statement.getLimit();
  }

  public InternalExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    var result = new SelectExecutionPlan(ctx);

    handleFetchFromTarger(result, ctx, enableProfiling);

    handleTraversal(result, ctx, enableProfiling);

    if (skip != null) {
      result.chain(new SkipExecutionStep(skip, ctx, enableProfiling));
    }
    if (limit != null) {
      result.chain(new LimitExecutionStep(limit, ctx, enableProfiling));
    }

    return result;
  }

  private void handleTraversal(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    switch (strategy) {
      case BREADTH_FIRST:
        result.chain(
            new BreadthFirstTraverseStep(
                this.projections, this.whileClause, maxDepth, ctx, profilingEnabled));
        break;
      case DEPTH_FIRST:
        result.chain(
            new DepthFirstTraverseStep(
                this.projections, this.whileClause, maxDepth, ctx, profilingEnabled));
        break;
    }
    // TODO
  }

  private void handleFetchFromTarger(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {

    var target = this.target == null ? null : this.target.getItem();
    if (target == null) {
      handleNoTarget(result, ctx, profilingEnabled);
    } else if (target.getIdentifier() != null) {
      handleClassAsTarget(result, this.target, ctx, profilingEnabled);
    } else if (target.getStatement() != null) {
      handleSubqueryAsTarget(result, target.getStatement(), ctx, profilingEnabled);
    } else if (target.getFunctionCall() != null) {
      //        handleFunctionCallAsTarget(result, target.getFunctionCall(), ctx);//TODO
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "function call as target is not supported yet");
    } else if (target.getInputParam() != null) {
      handleInputParamAsTarget(result, target.getInputParam(), ctx, profilingEnabled);
    } else if (target.getMetadata() != null) {
      handleMetadataAsTarget(result, target.getMetadata(), ctx, profilingEnabled);
    } else if (target.getRids() != null && !target.getRids().isEmpty()) {
      handleRidsAsTarget(result, target.getRids(), ctx, profilingEnabled);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private static void handleInputParamAsTarget(
      SelectExecutionPlan result,
      SQLInputParameter inputParam,
      CommandContext ctx,
      boolean profilingEnabled) {
    var paramValue = inputParam.getValue(ctx.getInputParameters());
    switch (paramValue) {
      case null -> result.chain(new EmptyStep(ctx, profilingEnabled)); // nothing to return
      case SchemaClass schemaClass -> {
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(
            new SQLIdentifier(schemaClass.getName()));
        handleClassAsTarget(result, from, ctx, profilingEnabled);
      }
      case String propertyName -> {
        // strings are treated as classes
        var from = new SQLFromClause(-1);
        var item = new SQLFromItem(-1);
        from.setItem(item);
        item.setIdentifier(new SQLIdentifier(propertyName));
        handleClassAsTarget(result, from, ctx, profilingEnabled);
      }
      case Identifiable identifiable -> {
        var orid = identifiable.getIdentity();

        var rid = new SQLRid(-1);
        var collection = new SQLInteger(-1);
        collection.setValue(orid.getCollectionId());
        var position = new SQLInteger(-1);
        position.setValue(orid.getCollectionPosition());
        rid.setLegacy(true);
        rid.setCollection(collection);
        rid.setPosition(position);

        handleRidsAsTarget(result, Collections.singletonList(rid), ctx, profilingEnabled);
      }
      case Iterable<?> iterable -> {
        // try list of RIDs
        List<SQLRid> rids = new ArrayList<>();
        for (var x : iterable) {
          if (!(x instanceof Identifiable)) {
            throw new CommandExecutionException(ctx.getDatabaseSession(),
                "Cannot use colleciton as target: " + paramValue);
          }
          var orid = ((Identifiable) x).getIdentity();

          var rid = new SQLRid(-1);
          var collection = new SQLInteger(-1);
          collection.setValue(orid.getCollectionId());
          var position = new SQLInteger(-1);
          position.setValue(orid.getCollectionPosition());
          rid.setCollection(collection);
          rid.setPosition(position);

          rids.add(rid);
        }
        handleRidsAsTarget(result, rids, ctx, profilingEnabled);
      }
      default -> throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Invalid target: " + paramValue);
    }
  }

  private static void handleNoTarget(
      SelectExecutionPlan result, CommandContext ctx, boolean profilingEnabled) {
    result.chain(new EmptyDataGeneratorStep(1, ctx, profilingEnabled));
  }

  private static void handleMetadataAsTarget(
      SelectExecutionPlan plan,
      SQLMetadataIdentifier metadata,
      CommandContext ctx,
      boolean profilingEnabled) {
    var db = ctx.getDatabaseSession();
    String schemaRecordIdAsString;
    if (metadata.getName().equalsIgnoreCase(CommandExecutorSQLAbstract.METADATA_SCHEMA)) {
      schemaRecordIdAsString = db.getStorageInfo().getConfiguration().getSchemaRecordId();
    } else if (metadata.getName().equalsIgnoreCase(CommandExecutorSQLAbstract.METADATA_INDEXMGR)) {
      schemaRecordIdAsString = db.getStorageInfo().getConfiguration().getIndexMgrRecordId();
    } else {
      throw new UnsupportedOperationException("Invalid metadata: " + metadata.getName());
    }
    var schemaRid = new RecordId(schemaRecordIdAsString);
    plan.chain(new FetchFromRidsStep(Collections.singleton(schemaRid), ctx, profilingEnabled));
  }

  private static void handleRidsAsTarget(
      SelectExecutionPlan plan, List<SQLRid> rids, CommandContext ctx, boolean profilingEnabled) {
    List<RecordId> actualRids = new ArrayList<>();
    for (var rid : rids) {
      actualRids.add(rid.toRecordId((Result) null, ctx));
    }
    plan.chain(new FetchFromRidsStep(actualRids, ctx, profilingEnabled));
  }

  private static void handleClassAsTarget(
      SelectExecutionPlan plan,
      SQLFromClause queryTarget,
      CommandContext ctx,
      boolean profilingEnabled) {
    var identifier = queryTarget.getItem().getIdentifier();

    Boolean orderByRidAsc = null; // null: no order. true: asc, false:desc
    var fetcher =
        new FetchFromClassExecutionStep(
            identifier.getStringValue(), null, ctx, orderByRidAsc, profilingEnabled);
    plan.chain(fetcher);
  }

  private static void handleSubqueryAsTarget(
      SelectExecutionPlan plan,
      SQLStatement subQuery,
      CommandContext ctx,
      boolean profilingEnabled) {
    var subCtx = new BasicCommandContext();
    subCtx.setDatabaseSession(ctx.getDatabaseSession());
    subCtx.setParent(ctx);
    var subExecutionPlan =
        subQuery.createExecutionPlan(subCtx, profilingEnabled);
    plan.chain(new SubQueryStep(subExecutionPlan, ctx, subCtx, profilingEnabled));
  }
}
