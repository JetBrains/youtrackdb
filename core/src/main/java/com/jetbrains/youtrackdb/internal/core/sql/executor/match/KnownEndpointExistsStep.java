package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.exception.CommandInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.LiveQueryInterruptedException;
import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.exception.SessionNotActivatedException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.index.engine.SelectivityEstimator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Rule.ResourceGeneric;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Guards target-only preparation before choosing a branch. Stream reads remain unguarded. */
final class KnownEndpointExistsStep extends AbstractExecutionStep {

  private static final Logger logger = LoggerFactory.getLogger(KnownEndpointExistsStep.class);

  enum Path {
    AUTO, SOURCE, TARGET
  }

  // This hook has no configuration or command-context surface. Tests scope it to their thread.
  private static final ThreadLocal<Path> TEST_PATH = ThreadLocal.withInitial(() -> Path.AUTO);

  static AutoCloseable forcePath(Path path) {
    var previous = TEST_PATH.get();
    TEST_PATH.set(path);
    return () -> TEST_PATH.set(previous);
  }

  private record InjectedFailure(String stage, RuntimeException failure) {
  }

  private static final ThreadLocal<InjectedFailure> TEST_FAILURE = new ThreadLocal<>();

  static AutoCloseable injectFailure(String stage, RuntimeException failure) {
    var previous = TEST_FAILURE.get();
    TEST_FAILURE.set(new InjectedFailure(stage, failure));
    return () -> {
      if (previous == null) {
        TEST_FAILURE.remove();
      } else {
        TEST_FAILURE.set(previous);
      }
    };
  }

  static void checkpoint(String stage) {
    var injected = TEST_FAILURE.get();
    if (injected != null && injected.stage.equals(stage)) {
      throw injected.failure;
    }
  }

  static void rethrowIfNotRecoverable(RuntimeException failure,
      DatabaseSessionEmbedded session, boolean txWasActive) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    boolean queryInterrupted = false;
    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof ThreadInterruptedException || cause instanceof InterruptedException) {
        // Blocking waits can clear the flag before storage wraps the interrupt. Restore it just
        // as resource acquisition and write-cache flush do, and retain the original exception.
        Thread.currentThread().interrupt();
        throw failure;
      }
      // Query cancellation does not itself imply a consumed JVM thread interrupt.
      queryInterrupted |= cause instanceof CommandInterruptedException
          || cause instanceof LiveQueryInterruptedException;
    }
    if (queryInterrupted || failure instanceof TimeoutException
        || failure instanceof SessionNotActivatedException || session.isClosed()
        || (txWasActive && !session.isTxActive())) {
      throw failure;
    }
  }

  static void discardAttempt(@Nullable SelectExecutionPlan partial, RuntimeException failure,
      DatabaseSessionEmbedded session, boolean txWasActive) {
    rethrowIfNotRecoverable(failure, session, txWasActive);
    // Report only the class. A policy exception message can contain protected record data.
    LogManager.instance().debug(KnownEndpointExistsStep.class,
        "Known-endpoint target attempt failed: %s", logger, failure.getClass().getSimpleName());
    if (partial != null) {
      try {
        partial.close();
      } catch (RuntimeException closing) {
        rethrowIfNotRecoverable(closing, session, txWasActive);
        LogManager.instance().debug(KnownEndpointExistsStep.class,
            "Known-endpoint target attempt cleanup failed: %s", logger,
            closing.getClass().getSimpleName());
      }
    }
  }

  static final class Counters {
    String path = "not started";
    String reason = "not started";
    long candidates;
    long edgeReads;
    long targetLoads;
    long sourceRecordsRead;
    boolean budgetSwitch;
    // Allowance and spent work stay internal. After a switch, discovery counters also depend on
    // the allowance derived from unsecured class statistics. Keep real values for internal use.
    double workBudget;
    double discoveryWork;

    Map<String, Object> published() {
      var values = new LinkedHashMap<String, Object>();
      values.put("path", path);
      values.put("reason", reason);
      values.put("candidates", candidates);
      if (!budgetSwitch) {
        values.put("edgeReads", edgeReads);
        values.put("targetLoads", targetLoads);
      }
      values.put("sourceRecordsRead", sourceRecordsRead);
      values.put("budgetSwitch", budgetSwitch);
      return values;
    }
  }

  private final KnownEndpointExistsAccess access;
  private final SelectExecutionPlan current;
  private final SelectExecutionPlan target;
  private final double probeWork;
  private final double laterWork;
  private final SQLLimit limit;
  private final SQLSkip skip;
  private final boolean fullInput;
  private Counters counters = new Counters();
  @Nullable private SelectExecutionPlan running;

  KnownEndpointExistsStep(CommandContext ctx, KnownEndpointExistsAccess access,
      SelectExecutionPlan current, SelectExecutionPlan target, double probeWork, double laterWork,
      SQLLimit limit, SQLSkip skip, boolean fullInput, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.access = access;
    this.current = current;
    this.target = target;
    this.probeWork = probeWork;
    this.laterWork = laterWork;
    this.limit = limit == null ? null : limit.copy();
    this.skip = skip == null ? null : skip.copy();
    this.fullInput = fullInput;
  }

  Counters counters() {
    return counters;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) {
    counters = new Counters();
    var selected = TEST_PATH.get();
    running = selected == Path.SOURCE ? null : attemptTarget(ctx, selected);
    counters.path = running == null ? "source" : "target";
    if (selected == Path.SOURCE) {
      counters.reason = "test selector";
    }
    // Neither template runs. Each execution owns both its step state and its context bindings.
    // The current branch and all stream consumption are outside the optimization-only guard.
    if (running == null) {
      running = (SelectExecutionPlan) current.copy(ctx);
      bind(running, List.of());
    }
    return running.start().onClose(context -> running.close());
  }

  @Nullable private SelectExecutionPlan attemptTarget(CommandContext ctx, Path selected) {
    var session = ctx.getDatabaseSession();
    boolean txWasActive = session.isTxActive();
    SelectExecutionPlan partial = null;
    try {
      var candidates = discover(ctx, selected);
      if (candidates == null) {
        return null;
      }
      partial = (SelectExecutionPlan) target.copy(ctx);
      checkpoint("target copy");
      bind(partial, candidates);
      return partial;
    } catch (KnownEndpointExistsCost.BudgetExceeded exhausted) {
      counters.budgetSwitch = true;
      counters.reason = "work budget exceeded";
      return null;
    } catch (RuntimeException failure) {
      discardAttempt(partial, failure, session, txWasActive);
      counters.candidates = 0;
      counters.reason = "target attempt failed: " + failure.getClass().getSimpleName();
      return null;
    }
  }

  private void bind(SelectExecutionPlan plan, List<RecordIdInternal> candidates) {
    var root = (MatchFirstStep) plan.getSteps().getFirst();
    var sourcePlan = (SelectExecutionPlan) root.getSourcePlan();
    var source = (KnownEndpointSourceStep) sourcePlan.getSteps().getFirst();
    source.bind(candidates, counters);
  }

  @Nullable private List<RecordIdInternal> discover(CommandContext ctx, Path selected) {
    checkpoint("decision");
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var sourceClass = schema.getClassInternal(access.sourceClass);
    double count = sourceClass == null ? 0 : sourceClass.approximateCount(session);
    double filterShare = filterShare(ctx);
    long demand = limit == null || fullInput ? -1 : limit.getValue(ctx);
    if (demand >= 0 && skip != null) {
      long skipped = skip.getValue(ctx);
      demand = skipped < 0 || Long.MAX_VALUE - demand < skipped ? -1 : demand + skipped;
    }
    // Until visible degree is known, estimate a complete source scan. A sparse LIMIT can still
    // need that whole scan. Use the same model as the later degree-based refinement.
    var initialCosts = KnownEndpointExistsCost.estimate(count, 0, 0, 1, filterShare,
        probeWork, laterWork, demand, fullInput);
    var budget = new KnownEndpointExistsCost.WorkBudget(access.check.getItems().size() > 1,
        initialCosts.sourceFull());
    try {
      return discover(ctx, selected, sourceClass, count, filterShare, demand, budget);
    } finally {
      counters.workBudget = budget.limit();
      counters.discoveryWork = budget.spent();
    }
  }

  @Nullable private List<RecordIdInternal> discover(CommandContext ctx, Path selected,
      SchemaClassInternal sourceClass,
      double count, double filterShare, long demand, KnownEndpointExistsCost.WorkBudget budget) {
    var session = ctx.getDatabaseSession();
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var rids = access.targets(ctx, budget);
    if (rids == null || sourceClass == null) {
      return fallback("unknown targets");
    }
    for (int collection : sourceClass.getPolymorphicCollectionIds()) {
      if (!canReadCollection(session, collection)) {
        return fallback("source collection denied");
      }
    }
    var labelsByHop = access.labels(ctx);
    if (labelsByHop == null) {
      return fallback("unknown edge label");
    }
    var fieldPolicies = new FieldPolicies(session);
    var farHop = access.hops.getLast();
    var labels = labelsByHop.getLast();
    var labelArray = labels.toArray(String[]::new);
    var reverse = switch (farHop.forward()) {
      case OUT -> Direction.IN;
      case IN -> Direction.OUT;
      case BOTH -> Direction.BOTH;
    };
    var fixedProperties = labels.isEmpty() ? List.<String>of()
        : VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, reverse, labelArray);
    String upstreamClass = access.hops.size() == 1 ? access.sourceClass : "V";
    var bags = new ArrayList<LinkBag>();
    double degree = 0;
    for (var rid : rids) {
      // Check collection access separately so target discovery never adds a permission error.
      if (!canReadCollection(session, rid.getCollectionId())
          || (!rid.isPersistent() && session.getTransactionInternal().getRecord(rid) == null)) {
        return fallback("target unavailable");
      }
      budget.charge(1);
      var record = session.executeReadRecord(rid, null, false);
      if (!(record instanceof VertexEntityImpl vertex)) {
        return fallback("target is missing, hidden or not a vertex");
      }
      counters.targetLoads++;
      var properties = labels.isEmpty() ? vertex.getEdgeNames(reverse) : fixedProperties;
      if (fieldPolicies.has(upstreamClass, farHop.forward(), labels)
          || fieldPolicies.has(vertex.getSchemaClassName(), reverse, labels)) {
        return fallback("edge-list read policy");
      }
      for (var property : properties) {
        budget.charge(1);
        if (VertexEntityImpl.getConnection(schema, reverse, property, labelArray) == null) {
          continue;
        }
        var value = vertex.getPropertyInternal(property);
        if (value == null) {
          continue;
        }
        // Stored records can retain lists after schema changes. Inspect the value, not its type.
        if (!(value instanceof LinkBag bag) || !bag.isSizeable()) {
          return fallback("edge-list storage form");
        }
        bags.add(bag);
        degree += bag.size();
      }
    }
    double walkedDegree = degree;
    // Each earlier hop adds its estimated reverse fan-out and secured middle-record load.
    for (int i = access.hops.size() - 2; i >= 0; i--) {
      var hop = access.hops.get(i);
      var method = (hop.edge() == null ? hop.vertex() : hop.edge()).getMethod();
      double fanOut = 0;
      String middleClass = hop.vertex().getFilter().getClassName(ctx);
      if (middleClass == null) {
        middleClass = "V";
      }
      for (var label : labelsByHop.get(i)) {
        fanOut += EdgeFanOutEstimator.estimateFanOut(session, label, middleClass,
            hop.forward() == Direction.OUT ? Direction.IN : Direction.OUT, null, null);
      }
      degree *= method.getParams().isEmpty() ? EdgeFanOutEstimator.defaultFanOut() : fanOut;
      walkedDegree += degree;
    }
    var vertices = schema.getClassInternal("V");
    double vertexCount = vertices == null ? count : vertices.approximateCount(session);
    double typeShare = vertexCount <= 0 ? 0 : Math.min(1, count / vertexCount);
    // A linked endpoint proves the type only if every selected edge subtype declares that endpoint.
    if (provenSourceType(ctx, labelsByHop.getFirst())) {
      typeShare = 1;
    }
    var costs = KnownEndpointExistsCost.estimate(count, degree, counters.targetLoads, typeShare,
        filterShare, probeWork, laterWork, demand, fullInput);
    if (access.check.getItems().size() > 1
        || rids.size() > KnownEndpointExistsAccess.UNBUDGETED_TARGETS || budget.spent() > 0) {
      // Include already-spent preparation, all estimated deduplication work, middle loads and
      // edge-record loads. Statistics estimate discovery only. The running budget bounds skew.
      double additional = budget.spent() - counters.targetLoads + walkedDegree;
      if (access.hops.size() > 1) {
        additional += walkedDegree - degree;
      }
      if (access.hops.stream().anyMatch(hop -> hop.edge() != null)) {
        additional += walkedDegree;
      }
      costs = new KnownEndpointExistsCost.Estimate(costs.sourceFull(),
          costs.targetFull() + additional, costs.sourceFirst(), costs.targetFirst() + additional);
    }
    budget.refine(costs.sourceFull());
    // LIMIT bounds output. It does not prove that a lazy caller consumes that many rows.
    // Full-input plans already include all required work in their first-row estimate.
    if (selected != Path.TARGET && !costs.targetWins()) {
      return fallback("cost or first-row margin");
    }
    checkpoint("reverse walk");
    var identities = new KnownEndpointExistsWalk(access, counters, budget, fieldPolicies,
        labelsByHop).collect(ctx, bags);
    if (identities == null) {
      return null;
    }
    budget.charge(identities.size() + KnownEndpointExistsCost.sortWork(identities.size()));
    var ordered = new ArrayList<>(identities);
    ordered.sort(Comparator.comparingInt(RecordIdInternal::getCollectionId)
        .thenComparingLong(RecordIdInternal::getCollectionPosition));
    counters.reason = selected == Path.TARGET ? "test selector" : "cost and first-row margin";
    return ordered;
  }

  double filterShare(CommandContext ctx) {
    if (access.sourceFilter == null) {
      return 1;
    }
    var session = ctx.getDatabaseSession();
    var clazz = session.getMetadata().getImmutableSchemaSnapshot()
        .getClassInternal(access.sourceClass);
    var blocks = access.sourceFilter.flatten(ctx, clazz);
    if (blocks.size() == 1 && blocks.getFirst().getSubBlocks().size() == 1
        && blocks.getFirst().getSubBlocks().getFirst() instanceof SQLBinaryCondition binary
        && KnownEndpointExistsAccess.fixedExpression(binary.getRight(), ctx)) {
      // Index selection can also evaluate the RHS. Any failure discards the target attempt.
      var descriptor = TraversalPreFilterHelper.findIndexForFilter(
          access.sourceFilter, access.sourceClass, ctx);
      if (descriptor != null) {
        var index = descriptor.getIndex();
        var statistics = index.getStatistics(session);
        if (statistics != null && statistics.totalCount() > 0) {
          var value = binary.getRight().execute((Result) null, ctx);
          double share =
              SelectivityEstimator.estimateForOperator(binary.getOperator(), statistics,
                  index.getHistogram(session), value);
          if (Double.isFinite(share) && share >= 0) {
            return Math.min(1, share);
          }
        }
      }
    }
    // Unindexed fields and unsupported predicates have no measured cardinality estimate.
    // A neutral share avoids inventing a 50% filter benefit and is identical on both paths.
    return 1;
  }

  private boolean provenSourceType(CommandContext ctx, List<String> labels) {
    // Only the hop next to the original source can prove its endpoint type. The far hop can
    // have a different edge class and direction in a multi-step path.
    var first = access.hops.getFirst();
    if (labels.isEmpty() || first.forward() == Direction.BOTH) {
      return false;
    }
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    String endpoint = first.forward() == Direction.OUT ? "out" : "in";
    for (var label : labels) {
      var edgeClass = schema.getClass(label);
      if (edgeClass == null || !edgeClass.isEdgeType()) {
        return false;
      }
      var classes = new ArrayList<>(edgeClass.getAllSubclasses());
      classes.add(edgeClass);
      for (var clazz : classes) {
        var property = clazz.getProperty(endpoint);
        if (property == null || property.getLinkedClass() == null
            || !property.getLinkedClass().isSubClassOf(access.sourceClass)) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean canReadCollection(DatabaseSessionEmbedded session, int collection) {
    try {
      session.checkSecurity(ResourceGeneric.COLLECTION, Role.PERMISSION_READ,
          session.getCollectionNameById(collection));
      return true;
    } catch (SecurityException denied) {
      return false;
    }
  }

  /** A decision owns its policy cache. Record loops never repeat a schema-wide policy scan. */
  static final class FieldPolicies {
    private record Key(String className, Direction direction, List<String> labels) {
      Key {
        labels = List.copyOf(labels);
      }
    }

    private final DatabaseSessionEmbedded session;
    private final Map<Key, Boolean> results = new HashMap<>();

    FieldPolicies(DatabaseSessionEmbedded session) {
      this.session = session;
    }

    boolean has(String className, Direction direction, List<String> labels) {
      var key = new Key(className, direction, labels);
      return results.computeIfAbsent(key, k -> hasFieldPolicy(session, k.className(),
          k.direction(), k.labels().toArray(String[]::new)));
    }

    int size() {
      return results.size();
    }
  }

  static boolean hasFieldPolicy(DatabaseSessionEmbedded session, String className,
      Direction direction, String[] labels) {
    var schema = session.getMetadata().getImmutableSchemaSnapshot();
    var clazz = schema.getClass(className);
    var classes = new ArrayList<>(clazz.getAllSubclasses());
    classes.add(clazz);
    // With no label, every edge class can supply an adjacency field, including schemaless fields.
    var edgeLabels = labels.length == 0
        ? schema.getClass("E").getAllSubclasses().stream().map(c -> c.getName())
            .toArray(String[]::new)
        : labels;
    var properties = new LinkedHashSet<>(
        VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, direction, edgeLabels));
    properties.addAll(VertexEntityImpl.getAllPossibleEdgePropertyNames(schema, direction, "E"));
    // Exact policies can name schemaless fields that no edge class declares.
    session.getSharedContext().getSecurity().getAllFilteredProperties(session).stream()
        .map(policy -> policy.getPropertyName())
        .filter(property -> VertexEntityImpl.isConnectionToEdge(direction, property))
        .filter(property -> labels.length == 0 || properties.contains(property))
        .forEach(properties::add);
    for (var c : classes) {
      c.getProperties().stream().map(p -> p.getName())
          .filter(p -> VertexEntityImpl.isConnectionToEdge(direction, p))
          .filter(p -> labels.length == 0 || properties.contains(p)).forEach(properties::add);
      for (var property : properties) {
        if (session.getSharedContext().getSecurity().isReadRestrictedBySecurityPolicy(session,
            "database.class." + c.getName() + "." + property)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable private List<RecordIdInternal> fallback(String reason) {
    counters.reason = reason;
    return null;
  }

  @Override
  public List<ExecutionPlan> getSubExecutionPlans() {
    if (running == null) {
      return List.of(current, target);
    }
    return counters.path.equals("source") ? List.of(running, target) : List.of(current, running);
  }

  @Override
  public Result toResult(DatabaseSessionEmbedded session) {
    var result = (ResultInternal) super.toResult(session);
    result.setProperty("subExecutionPlans", getSubExecutionPlans().stream()
        .map(plan -> plan.toResult(session)).toList());
    counters.published().forEach(result::setProperty);
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ KNOWN-ENDPOINT EXISTS CHOICE (margin "
        + KnownEndpointExistsCost.SAFETY_MARGIN + ", full and first-row cost)"
        + (profilingEnabled ? counters.published().entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", ", " [", "]")) : "")
        + "\n" + spaces + "  CURRENT:\n"
        + getSubExecutionPlans().getFirst().prettyPrint(depth + 2, indent)
        + "\n" + spaces + "  TARGET:\n"
        + getSubExecutionPlans().get(1).prettyPrint(depth + 2, indent);
  }

  @Override
  public boolean canBeCached() {
    return current.canBeCached() && target.canBeCached();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new KnownEndpointExistsStep(ctx, access.copy(), (SelectExecutionPlan) current.copy(ctx),
        (SelectExecutionPlan) target.copy(ctx), probeWork, laterWork, limit, skip, fullInput,
        profilingEnabled);
  }

  @Override
  public void close() {
    if (running != null) {
      running.close();
    }
    super.close();
  }
}
