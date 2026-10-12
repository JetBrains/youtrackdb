package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Chooses a native start before either branch can prefetch or emit a row. */
final class NativeKnownEndpointStep extends AbstractExecutionStep {

  enum Path {
    AUTO, RID, SOURCE
  }

  // This scoped hook is test-only. It has no configuration or command-context surface.
  private static final ThreadLocal<Path> TEST_PATH = ThreadLocal.withInitial(() -> Path.AUTO);

  static AutoCloseable forcePath(Path path) {
    var previous = TEST_PATH.get();
    TEST_PATH.set(path);
    return () -> TEST_PATH.set(previous);
  }

  private final KnownEndpointExistsAccess access;
  private final SelectExecutionPlan current;
  private final SelectExecutionPlan source;
  private final Set<String> aliases;
  private final SQLLimit limit;
  private final SQLSkip skip;
  private final boolean fullInput;
  private SelectExecutionPlan running;
  private String path = "not started";
  private String reason = "not started";

  NativeKnownEndpointStep(CommandContext ctx, KnownEndpointExistsAccess access,
      SelectExecutionPlan current, SelectExecutionPlan source, Set<String> aliases,
      SQLLimit limit, SQLSkip skip, boolean fullInput, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.access = access;
    this.current = current;
    this.source = source;
    this.aliases = Set.copyOf(aliases);
    this.limit = limit == null ? null : limit.copy();
    this.skip = skip == null ? null : skip.copy();
    this.fullInput = fullInput;
  }

  String path() {
    return path;
  }

  String reason() {
    return reason;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) {
    path = "not started";
    reason = "not started";
    var selected = TEST_PATH.get();
    running = selected == Path.RID ? null : attemptSource(ctx, selected);
    path = running == null ? "rid" : "source";
    if (selected == Path.RID) {
      reason = "test selector";
    }
    // A reset or cached-plan copy must not inherit the other branch's prefetch bindings.
    for (var alias : aliases) {
      ctx.setVariable(MatchPrefetchStep.PREFETCHED_MATCH_ALIAS_PREFIX + alias, null);
    }
    if (running == null) {
      running = (SelectExecutionPlan) current.copy(ctx);
    }
    // Starting the retained query and all reads after the decision are outside the guard.
    return running.start().onClose(context -> running.close());
  }

  static void discardAttempt(@Nullable SelectExecutionPlan partial, RuntimeException failure,
      DatabaseSessionEmbedded session, boolean txWasActive) {
    // Secured loads can wrap timeout and activation errors. Do not treat their wrappers as a
    // recoverable optimization failure. The shared guard also handles interruption and tx loss.
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof TimeoutException || cause instanceof SessionNotActivatedException) {
        throw failure;
      }
    }
    KnownEndpointExistsStep.discardAttempt(partial, failure, session, txWasActive);
  }

  @Nullable private SelectExecutionPlan attemptSource(CommandContext ctx, Path selected) {
    var session = ctx.getDatabaseSession();
    boolean txWasActive = session.isTxActive();
    SelectExecutionPlan partial = null;
    try {
      KnownEndpointExistsStep.checkpoint("native decision");
      // Class estimates adjust counts by scanning pending operations. This O(1) gate prevents
      // a native decision from doing work proportional to the transaction's write-set size.
      if (session.getTransactionInternal().getEntryCount() != 0) {
        return fallback("pending transaction writes");
      }
      var schema = session.getMetadata().getImmutableSchemaSnapshot();
      var sourceClass = schema.getClass(access.sourceClass);
      if (sourceClass == null || !sourceClass.isVertexType()) {
        return fallback("source class unavailable");
      }
      for (int collection : sourceClass.getPolymorphicCollectionIds()) {
        if (!KnownEndpointExistsStep.canReadCollection(session, collection)) {
          return fallback("source collection denied");
        }
      }
      var targets = access.targets(ctx, new KnownEndpointExistsCost.WorkBudget(false, 0));
      var labelsByHop = access.labels(ctx);
      if (targets == null || targets.size() != 1 || labelsByHop == null) {
        return fallback("unknown target or edge label");
      }
      var rid = targets.getFirst();
      if (!KnownEndpointExistsStep.canReadCollection(session, rid.getCollectionId())) {
        return fallback("target collection denied");
      }
      var record = session.executeReadRecord(rid, null, false);
      if (!(record instanceof VertexEntityImpl vertex)) {
        return fallback("target is missing, hidden or not a vertex");
      }
      var targetFilter = access.hops.getLast().vertex().getFilter();
      var targetClass = targetFilter.getClassName(ctx);
      // The RID root does not enforce a rid: slot's class, but the forward hop does.
      // Retain that root if switching would add a class constraint.
      if (targetClass != null
          && !vertex.getImmutableSchemaClass(session).isSubClassOf(targetClass)) {
        return fallback("target class mismatch");
      }
      // Prefetch evaluates the pinned target predicate even when no source reaches it.
      // Evaluate it inside the guard too. A failure leaves the RID branch to raise its error.
      var targetWhere = targetFilter.getFilter();
      if (targetWhere != null
          && !targetWhere.matchesFilters(new ResultInternal(session, vertex), ctx)) {
        return fallback("target predicate rejected");
      }
      var labels = labelsByHop.getFirst();
      var reverse = access.forward == Direction.OUT ? Direction.IN : Direction.OUT;
      var policies = new KnownEndpointExistsStep.FieldPolicies(session);
      if (policies.has(access.sourceClass, access.forward, labels)
          || policies.has(vertex.getSchemaClassName(), reverse, labels)) {
        return fallback("edge-list read policy");
      }
      long degree = degree(vertex, reverse, labels, ctx);
      if (degree < 0) {
        return fallback("edge-list storage form");
      }
      long demand = KnownEndpointExistsCost.requiredRows(limit, skip, fullInput, ctx);
      long skipped = skip == null ? 0 : Math.max(0, skip.getValue(ctx));
      boolean streamingLimit = !fullInput && limit != null && limit.getValue(ctx) >= 0;
      // Paging follows the cost comparison. Without a unique ORDER BY, either start may
      // produce a different valid slice because its natural input order can differ.
      double ridWork = demand < 0 ? degree : Math.min(degree, demand);
      // A streaming LIMIT does not require the caller to consume every requested row.
      // Compare first-row work too, including skipped rows. Full-input consumers must finish
      // both branches before their first row. Unpaged queries retain the full-work comparison.
      double ridFirst = streamingLimit ? Math.min(degree, skipped + 1.0) : ridWork;
      double comparisonWork = Math.min(ridWork, ridFirst);
      // Even a zero-record source costs a scan start. Read degree before any exact count.
      if (selected != Path.SOURCE && comparisonWork <= KnownEndpointExistsCost.SAFETY_MARGIN) {
        return fallback("degree cannot meet margin");
      }
      if (session.getTransactionInternal().getEntryCount() != 0) {
        return fallback("pending transaction writes");
      }
      // Estimates can only reject a switch. They never establish the winning path.
      long approximateSource = session.approximateCountClass(access.sourceClass);
      if (selected != Path.SOURCE && (approximateSource < 0
          || (1 + 2.0 * approximateSource) * KnownEndpointExistsCost.SAFETY_MARGIN
              >= comparisonWork)) {
        return fallback("approximate source margin");
      }
      KnownEndpointExistsStep.checkpoint("native counts");
      // A low live count need not imply a cheap count: tombstones still occupy map positions.
      // Charge every visited position across all exact counts, including optimistic retries.
      long[] remainingPositions = {selected == Path.SOURCE ? Long.MAX_VALUE
          : (long) (comparisonWork / KnownEndpointExistsCost.SAFETY_MARGIN)};
      Runnable countPosition = () -> {
        KnownEndpointExistsStep.checkpoint("native count position");
        if (remainingPositions[0] <= 0) {
          throw new CountWorkExceededException();
        }
        remainingPositions[0]--;
      };
      long sourceCount = exactCount(sourceClass.getPolymorphicCollectionIds(), countPosition, ctx);
      if (sourceCount < 0) {
        return fallback("source count unavailable");
      }
      if (selected != Path.SOURCE
          && (1 + 2.0 * sourceCount) * KnownEndpointExistsCost.SAFETY_MARGIN >= comparisonWork) {
        return fallback("exact count margin");
      }
      int edgeWork = access.hops.getFirst().edge() == null ? 1 : 2;
      double maxEdges = selected == Path.SOURCE ? Double.POSITIVE_INFINITY
          : (comparisonWork / KnownEndpointExistsCost.SAFETY_MARGIN - 1 - 2.0 * sourceCount)
              / edgeWork;
      long edges = edgeBound(sourceClass, labels, maxEdges, countPosition, ctx, selected);
      if (edges < 0) {
        return fallback("edge bound or margin unavailable");
      }
      // Charge both the secured storage-form preflight and the later plain source scan.
      // This full source-work upper bound also includes all preparation before its first row.
      // RID work is bounded below by the requested edge reads, or the full degree for a
      // full-input consumer. Edge-record hops load each edge before following its endpoint.
      double sourceWork = 1 + 2.0 * sourceCount
          + (double) edges * edgeWork;
      if (selected != Path.SOURCE
          && (sourceWork * KnownEndpointExistsCost.SAFETY_MARGIN >= ridWork
              || sourceWork * KnownEndpointExistsCost.SAFETY_MARGIN >= ridFirst)) {
        return fallback("exact count margin");
      }
      // Inspect actual source values too. A LINKLIST can outlive its LINKBAG declaration.
      // Complete this secured scan before emitting a row. Counts and preflight work stay private.
      long forwardEdges = 0;
      try (var records = session.browseClass(access.sourceClass)) {
        while (records.hasNext()) {
          var sourceRecord = records.next();
          if (!(sourceRecord instanceof VertexEntityImpl sourceVertex)) {
            return fallback("source is not a vertex");
          }
          long sourceDegree = degree(sourceVertex, access.forward, labels, ctx);
          if (sourceDegree < 0) {
            return fallback("edge-list storage form");
          }
          forwardEdges = Math.addExact(forwardEdges, sourceDegree);
          // Lightweight relationships and old endpoint values need not be covered by the
          // schema-derived edge counts. Verify the bound on every visible source before use.
          if (forwardEdges > edges) {
            return fallback("forward edge bound unavailable");
          }
        }
      }
      // Read policies can have side effects. Never use counts from before a pending write.
      if (session.getTransactionInternal().getEntryCount() != 0) {
        return fallback("pending transaction writes");
      }
      partial = (SelectExecutionPlan) source.copy(ctx);
      KnownEndpointExistsStep.checkpoint("native source copy");
      reason = selected == Path.SOURCE ? "test selector" : "exact count margin";
      return partial;
    } catch (CountWorkExceededException exhausted) {
      return fallback("exact count work budget");
    } catch (RuntimeException failure) {
      discardAttempt(partial, failure, session, txWasActive);
      return fallback("source attempt failed: " + failure.getClass().getSimpleName());
    }
  }

  private long degree(VertexEntityImpl vertex, Direction direction, List<String> labels,
      CommandContext ctx) {
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    var labelArray = labels.toArray(String[]::new);
    var properties = labels.isEmpty() ? vertex.getEdgeNames(direction)
        : VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, direction, labelArray);
    long result = 0;
    for (var property : properties) {
      if (VertexEntityImpl.getConnection(schema, direction, property, labelArray) == null) {
        continue;
      }
      var value = vertex.getPropertyInternal(property);
      if (value == null) {
        continue;
      }
      if (!(value instanceof LinkBag bag) || !bag.isSizeable()) {
        return -1;
      }
      result = Math.addExact(result, bag.size());
    }
    return result;
  }

  // This internal signal aborts only decision work. It never escapes to the query caller.
  // The marker uses storage's existing expected-failure path without hiding genuine errors.
  private static final class CountWorkExceededException extends RuntimeException
      implements HighLevelException {

  }

  /** Counts the permitted collection set under the storage count path's state protection. */
  private long exactCount(int[] ids, Runnable countPosition, CommandContext ctx) {
    var session = ctx.getDatabaseSession();
    return session.getStorage().count(session, ids, false, countPosition);
  }

  /** Counts each concrete edge class once, including every type whose source could match. */
  private long edgeBound(SchemaClass sourceClass, List<String> labels, double maxEdges,
      Runnable countPosition, CommandContext ctx, Path selected) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var classes = new LinkedHashSet<SchemaClass>();
    for (var label : labels.isEmpty() ? List.of("E") : labels) {
      var edgeClass = schema.getClass(label);
      if (edgeClass == null || !edgeClass.isEdgeType()) {
        continue;
      }
      classes.add(edgeClass);
      classes.addAll(edgeClass.getAllSubclasses());
    }
    var sourceTypes = new ArrayList<>(sourceClass.getAllSubclasses());
    sourceTypes.add(sourceClass);
    long count = 0;
    String endpoint = access.forward == Direction.OUT ? "out" : "in";
    for (var clazz : classes) {
      if (clazz.isAbstract()) {
        continue;
      }
      var property = clazz.getProperty(endpoint);
      var linked = property == null ? null : property.getLinkedClass();
      // Multiple inheritance can create a common subtype of otherwise unrelated classes.
      // Test every source subtype rather than assuming unrelated parents are disjoint.
      if (linked != null && sourceTypes.stream().noneMatch(type -> type.isSubClassOf(linked))) {
        continue;
      }
      for (int collection : clazz.getCollectionIds()) {
        if (!KnownEndpointExistsStep.canReadCollection(session, collection)) {
          return -1;
        }
      }
      long approximate = session.approximateCountClass(clazz.getName(), false);
      if (selected != Path.SOURCE
          && (approximate < 0 || count + (double) approximate >= maxEdges)) {
        return -1;
      }
      KnownEndpointExistsStep.checkpoint("native edge counts");
      long classCount = exactCount(clazz.getCollectionIds(), countPosition, ctx);
      if (classCount < 0) {
        return -1;
      }
      count = Math.addExact(count, classCount);
      // Stop before counting another class once this partial upper bound already loses.
      if (count >= maxEdges) {
        return -1;
      }
    }
    return count;
  }

  @Nullable private SelectExecutionPlan fallback(String why) {
    reason = why;
    return null;
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    return running == null ? List.of(current, source)
        : path.equals("source") ? List.of(current, running) : List.of(running, source);
  }

  @Override
  public Result toResult(DatabaseSessionEmbedded session) {
    var result = (ResultInternal) super.toResult(session);
    // Exact counts include policy-hidden records. Publish only the chosen path and reason.
    result.setProperty("path", path);
    result.setProperty("reason", reason);
    result.setProperty("subExecutionPlans", getSubExecutionPlans().stream()
        .map(plan -> plan.toResult(session)).toList());
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ NATIVE KNOWN-ENDPOINT CHOICE (exact counts, margin "
        + KnownEndpointExistsCost.SAFETY_MARGIN + ")"
        + (profilingEnabled ? " [path=" + path + ", reason=" + reason + "]" : "")
        + "\n" + spaces + "  RID:\n"
        + getSubExecutionPlans().getFirst().prettyPrint(depth + 2, indent)
        + "\n" + spaces + "  SOURCE:\n"
        + getSubExecutionPlans().get(1).prettyPrint(depth + 2, indent);
  }

  @Override
  public boolean canBeCached() {
    return current.canBeCached() && source.canBeCached();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new NativeKnownEndpointStep(ctx, access.copy(), (SelectExecutionPlan) current.copy(ctx),
        (SelectExecutionPlan) source.copy(ctx), aliases, limit, skip, fullInput, profilingEnabled);
  }

  @Override
  public void close() {
    if (running != null) {
      running.close();
    }
    super.close();
  }
}
