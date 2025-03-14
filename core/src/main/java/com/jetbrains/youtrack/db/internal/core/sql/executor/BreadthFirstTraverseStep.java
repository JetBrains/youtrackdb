package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLInteger;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLTraverseProjectionItem;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class BreadthFirstTraverseStep extends AbstractTraverseStep {

  public BreadthFirstTraverseStep(
      List<SQLTraverseProjectionItem> projections,
      SQLWhereClause whileClause,
      SQLInteger maxDepth,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(projections, whileClause, maxDepth, ctx, profilingEnabled);
  }

  @Override
  protected void fetchNextEntryPoints(
      ExecutionStream nextN, CommandContext ctx, List<Result> entryPoints,
      Set<RID> traversed) {
    // Doing max batch of 100 entry points for now
    while (nextN.hasNext(ctx) && entryPoints.size() < 100) {
      var item = toTraverseResult(ctx.getDatabaseSession(), nextN.next(ctx));
      if (item != null) {
        List<RID> stack = new ArrayList<>();
        var identity = item.getIdentity();
        stack.add(identity);
        ((ResultInternal) item).setMetadata("$stack", stack);

        List<Identifiable> path = new ArrayList<>();
        path.add(identity);

        ((ResultInternal) item).setMetadata("$path", path);
        if (item.isEntity() && !traversed.contains(item.asEntity().getIdentity())) {
          tryAddEntryPoint(item, ctx, entryPoints, traversed);
        }
      }
    }
  }

  private Result toTraverseResult(DatabaseSessionInternal db, Result item) {
    TraverseResult res = null;
    if (item instanceof TraverseResult) {
      res = (TraverseResult) item;
    } else if (item.isEntity() && item.asEntity().getIdentity().isPersistent()) {
      res = new TraverseResult(db, item.asEntity());
      res.depth = 0;
      res.setMetadata("$depth", 0);
    } else if (item.getPropertyNames().size() == 1) {
      var val = item.getProperty(item.getPropertyNames().iterator().next());
      if (val instanceof Identifiable) {
        res = new TraverseResult(db, (Identifiable) val);
        res.depth = 0;
        res.setMetadata("$depth", 0);
      }
    } else {
      res = new TraverseResult(db);
      for (var key : item.getPropertyNames()) {
        res.setProperty(key, item.getProperty(key));
      }
      if (item instanceof ResultInternal resultInternal) {
        for (var md : resultInternal.getMetadataKeys()) {
          res.setMetadata(md, resultInternal.getMetadata(md));
        }
      }
    }

    return res;
  }

  @Override
  protected void fetchNextResults(
      CommandContext ctx, List<Result> results, List<Result> entryPoints,
      Set<RID> traversed) {
    if (!entryPoints.isEmpty()) {
      var item = (TraverseResult) entryPoints.remove(0);
      results.add(item);
      for (var proj : projections) {
        var nextStep = proj.execute(item, ctx);
        if (this.maxDepth == null || this.maxDepth.getValue().intValue() > item.depth) {
          addNextEntryPoints(
              nextStep,
              item.depth + 1,
              (List<Identifiable>) item.getMetadata("$path"),
              ctx,
              entryPoints,
              traversed);
        }
      }
    }
  }

  private void addNextEntryPoints(
      Object nextStep,
      int depth,
      List<Identifiable> path,
      CommandContext ctx,
      List<Result> entryPoints,
      Set<RID> traversed) {
    if (nextStep instanceof Identifiable) {
      addNextEntryPoints(((Identifiable) nextStep), depth, path, ctx, entryPoints, traversed);
    } else if (nextStep instanceof Iterable) {
      addNextEntryPoints(
          ((Iterable) nextStep).iterator(), depth, path, ctx, entryPoints, traversed);
    } else if (nextStep instanceof Map) {
      addNextEntryPoints(
          ((Map) nextStep).values().iterator(), depth, path, ctx, entryPoints, traversed);
    } else if (nextStep instanceof Result) {
      addNextEntryPoints(((Result) nextStep), depth, path, ctx, entryPoints, traversed);
    }
  }

  private void addNextEntryPoints(
      Iterator nextStep,
      int depth,
      List<Identifiable> path,
      CommandContext ctx,
      List<Result> entryPoints,
      Set<RID> traversed) {
    while (nextStep.hasNext()) {
      addNextEntryPoints(nextStep.next(), depth, path, ctx, entryPoints, traversed);
    }
  }

  private void addNextEntryPoints(
      Identifiable nextStep,
      int depth,
      List<Identifiable> path,
      CommandContext ctx,
      List<Result> entryPoints,
      Set<RID> traversed) {
    if (traversed.contains(nextStep.getIdentity())) {
      return;
    }
    var res = new TraverseResult(ctx.getDatabaseSession(), nextStep);
    res.depth = depth;
    res.setMetadata("$depth", depth);

    List<Identifiable> newPath = new ArrayList<>();
    newPath.addAll(path);
    newPath.add(res.getIdentity());
    res.setMetadata("$path", newPath);

    List reverseStack = new ArrayList();
    reverseStack.addAll(newPath);
    Collections.reverse(reverseStack);
    List newStack = new ArrayList();
    newStack.addAll(reverseStack);
    res.setMetadata("$stack", newStack);

    tryAddEntryPoint(res, ctx, entryPoints, traversed);
  }

  private void addNextEntryPoints(
      Result nextStep,
      int depth,
      List<Identifiable> path,
      CommandContext ctx,
      List<Result> entryPoints,
      Set<RID> traversed) {
    if (!nextStep.isEntity()) {
      return;
    }
    if (traversed.contains(nextStep.getIdentity())) {
      return;
    }
    if (nextStep instanceof TraverseResult) {
      ((TraverseResult) nextStep).depth = depth;
      ((TraverseResult) nextStep).setMetadata("$depth", depth);

      List<Identifiable> newPath = new ArrayList<>();
      newPath.addAll(path);
      newPath.add(nextStep.getIdentity());
      ((TraverseResult) nextStep).setMetadata("$path", newPath);

      List reverseStack = new ArrayList();
      reverseStack.addAll(newPath);
      Collections.reverse(reverseStack);
      List newStack = new ArrayList();
      newStack.addAll(reverseStack);
      ((TraverseResult) nextStep).setMetadata("$stack", newStack);

      tryAddEntryPoint(nextStep, ctx, entryPoints, traversed);
    } else {
      var res = new TraverseResult(ctx.getDatabaseSession(), nextStep.asEntity());
      res.depth = depth;
      res.setMetadata("$depth", depth);

      List<Identifiable> newPath = new ArrayList<>();
      newPath.addAll(path);
      newPath.add(nextStep.getIdentity());
      ((TraverseResult) nextStep).setMetadata("$path", newPath);

      List reverseStack = new ArrayList();
      reverseStack.addAll(newPath);
      Collections.reverse(reverseStack);
      var newStack = new ArrayDeque();
      newStack.addAll(reverseStack);
      ((TraverseResult) nextStep).setMetadata("$stack", newStack);

      tryAddEntryPoint(res, ctx, entryPoints, traversed);
    }
  }

  private void tryAddEntryPoint(
      Result res, CommandContext ctx, List<Result> entryPoints, Set<RID> traversed) {
    if (whileClause == null || whileClause.matchesFilters(res, ctx)) {
      entryPoints.add(res);
    }
    traversed.add(res.getIdentity());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ BREADTH-FIRST TRAVERSE \n");
    if (whileClause != null) {
      result.append(spaces);
      result.append("WHILE " + whileClause);
    }
    return result.toString();
  }
}
