package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromClassExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromRidsStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FilterStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.LimitExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.NativeStartEvidence;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SkipExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Facts;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.StartKind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBinaryCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNotNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsNullCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure strict-rule-A analysis of a completed, unstarted single MATCH plan.
 *
 * <p>Read set: pattern topology and path ASTs, alias classes and filters, return modes and
 * expressions, grouping, ordering and unwind inputs, exact physical step classes and order,
 * first/prefetch aliases and source plans, source class and WHERE, hop descriptors and direction,
 * boolean node roles and content, physical projection expressions, and skip/limit presence.
 * The baseline reads no bindings, schema, session, execution state or records. The optional
 * native source certificate compares equality operands in an invocation-local binding context. Root
 * choice and prefetch are estimate-dependent evidence exposed in the immutable decision facts.
 * Callers must provide the inputs that produced the plan and must not mutate either during analysis.
 *
 * <p>Only allowlisted owners expose nested content here. Unknown owners fail immediately, even
 * when getSubSteps is empty. In particular a probe and a prebuilt hash child are not a linear
 * continuation of the outer stream. Both keep MATCH, so neither transfers slice-after-filter state to that stream.
 */
public final class PlainPlanClassifier {

  private PlainPlanClassifier() {
  }

  public static PlainPlanDecision classify(InternalExecutionPlan plan, MatchPlanInputs inputs) {
    return classify(plan, inputs, null, null);
  }

  /** The optional source certificate is valid only for this invocation's parameter context. */
  public static PlainPlanDecision classify(InternalExecutionPlan plan, MatchPlanInputs inputs,
      NativeStartEvidence evidence, CommandContext bindings) {
    return classify(plan, inputs, evidence, bindings, false);
  }

  /** Structural gate only. A plain index candidate still requires invocation-local proof. */
  public static PlainPlanDecision indexCandidate(InternalExecutionPlan plan,
      MatchPlanInputs inputs) {
    return classify(plan, inputs, null, null, true);
  }

  private static PlainPlanDecision classify(InternalExecutionPlan plan, MatchPlanInputs inputs,
      NativeStartEvidence evidence, CommandContext bindings, boolean candidate) {
    var path = new ArrayList<PatternEdge>();
    var nodes = inputs.pattern().aliasToNode;
    if (nodes.isEmpty() || inputs.groupBy() != null || inputs.orderBy() != null
        || inputs.unwind() != null || inputs.returnDistinct() || inputs.returnElements()
        || inputs.returnPaths() || inputs.returnPatterns() || inputs.returnPathElements()
        || !inputs.notMatchExpressions().isEmpty() || !inputs.existsMatchExpressions().isEmpty()
        || inputs.returnNestedProjections().stream().anyMatch(x -> x != null)) {
      return PlainPlanDecision.rejected(Reason.INPUT_SHAPE, null);
    }
    var head = nodes.values().iterator().next();
    var node = head;
    var seen = new java.util.HashSet<String>();
    while (seen.add(node.alias)) {
      if (node.optional || node.out.size() > 1 || node.in.size() > (node == head ? 0 : 1)) {
        return PlainPlanDecision.rejected(Reason.INPUT_SHAPE, null);
      }
      if (node.out.isEmpty()) {
        break;
      }
      var edge = node.out.iterator().next();
      path.add(edge);
      node = edge.in;
    }
    if (seen.size() != nodes.size() || path.size() != nodes.size() - 1) {
      return PlainPlanDecision.rejected(Reason.INPUT_SHAPE, null);
    }
    var boundary = node.alias;
    if (!inputs.returnItems().stream().allMatch(x -> directRead(x, boundary))) {
      return PlainPlanDecision.rejected(Reason.PROJECTION, null);
    }
    var steps = plan.getSteps();
    int position = 0;
    boolean prefetched = false;
    StartKind kind = StartKind.UNKNOWN;
    Set<String> classes = Set.of();
    boolean filtered = false;
    if (!steps.isEmpty() && steps.getFirst().getClass() == MatchPrefetchStep.class) {
      var prefetch = (MatchPrefetchStep) steps.getFirst();
      if (!head.alias.equals(prefetch.getAlias())) {
        return PlainPlanDecision.rejected(Reason.ROOT_CHANGED, prefetch);
      }
      var start = start(prefetch.getSourcePlan(), inputs.aliasClasses().get(head.alias),
          "$g2m_v0".equals(head.alias) ? evidence : null, bindings,
          candidate && "$g2m_v0".equals(head.alias));
      if (!start.plain()) {
        return start;
      }
      // The planner builds this SELECT from the same class/RIDs and WHERE as a lazy start.
      // Materialization changes when records are read, not which records native start selects.
      prefetched = true;
      kind = start.facts().startKind();
      classes = start.facts().scannedClasses();
      position++;
    }
    if (position == steps.size() || steps.get(position).getClass() != MatchFirstStep.class) {
      return rejectedAt(steps, position, Reason.STEP_ORDER);
    }
    var first = (MatchFirstStep) steps.get(position++);
    if (!head.alias.equals(first.getAlias())) {
      return PlainPlanDecision.rejected(Reason.ROOT_CHANGED, first);
    }
    if (prefetched ? first.getSourcePlan() != null : first.getSourcePlan() == null) {
      return PlainPlanDecision.rejected(Reason.START_ACCESS, first);
    }
    if (!prefetched) {
      var start = start(first.getSourcePlan(), inputs.aliasClasses().get(head.alias),
          "$g2m_v0".equals(head.alias) ? evidence : null, bindings,
          candidate && "$g2m_v0".equals(head.alias));
      if (!start.plain()) {
        return start;
      }
      kind = start.facts().startKind();
      classes = start.facts().scannedClasses();
      filtered = kind == StartKind.CLASS_SCAN && first.getSourcePlan().getSteps().size() > 1;
    }
    for (var entry : inputs.aliasFilters().values()) {
      if (!local(entry)) {
        return PlainPlanDecision.rejected(Reason.PREDICATE, first);
      }
    }
    int vertexHops = 0;
    boolean edgePending = false;
    for (var expected : path) {
      if (position == steps.size() || steps.get(position).getClass() != MatchStep.class) {
        return rejectedAt(steps, position, Reason.STEP_ORDER);
      }
      var hop = (MatchStep) steps.get(position++);
      var edge = hop.edge;
      var item = edge.edge.item;
      if (!edge.out || !expected.out.alias.equals(edge.edge.out.alias)
          || !expected.in.alias.equals(edge.edge.in.alias) || !expected.item.equals(item)
          || item.getClass() != SQLMatchPathItem.class || edge.edge.in.optional
          || edge.getIntersectionDescriptor() != null || edge.getSemiJoinDescriptor() != null
          || item.getMethod() == null || !Set.of("out", "in", "both", "outE", "inE", "bothE",
              "outV", "inV", "bothV").contains(item.getMethod().getMethodNameString())
          || !item.getMethod().getParams().stream().allMatch(PlainPlanClassifier::literalLabel)
          || item.getFilter() == null || item.getFilter().getRid(null) != null
          || item.getFilter().getWhileCondition() != null
          || item.getFilter().getMaxDepth() != null || item.getFilter().getDepthAlias() != null
          || item.getFilter().getPathAlias() != null) {
        return PlainPlanDecision.rejected(Reason.HOP_ACCESS, hop);
      }
      if (!local(item.getFilter().getFilter())) {
        return PlainPlanDecision.rejected(Reason.PREDICATE, hop);
      }
      var method = item.getMethod().getMethodNameString();
      // An edge-as-node expansion and its endpoint projection form one vertex hop.
      boolean endpoint = Set.of("outV", "inV", "bothV").contains(method);
      if (!endpoint || !edgePending) {
        vertexHops++;
      }
      edgePending = Set.of("outE", "inE", "bothE").contains(method);
      filtered |= item.getFilter().getFilter() != null
          || narrows(item, item.getFilter().getClassName(null))
          || narrows(item, inputs.aliasClasses().get(expected.in.alias));
    }
    if (position < steps.size() && steps.get(position).getClass() == FilterBooleanMatchStep.class) {
      var check = booleanTree((FilterBooleanMatchStep) steps.get(position++), boundary);
      if (check != null) {
        return check;
      }
      filtered |= (kind == StartKind.CLASS_SCAN && !prefetched) || !path.isEmpty();
    }
    // The planner can place a direct projection before or after a root slice.
    ProjectionCalculationStep projection = null;
    if (position < steps.size()
        && steps.get(position).getClass() == ProjectionCalculationStep.class) {
      projection = (ProjectionCalculationStep) steps.get(position++);
    }
    boolean sliced = false;
    if (position < steps.size() && steps.get(position).getClass() == SkipExecutionStep.class) {
      sliced = true;
      position++;
    }
    if (position < steps.size() && steps.get(position).getClass() == LimitExecutionStep.class) {
      sliced = true;
      position++;
    }
    if (projection == null) {
      if (position == steps.size()
          || steps.get(position).getClass() != ProjectionCalculationStep.class) {
        return rejectedAt(steps, position, Reason.STEP_ORDER);
      }
      projection = (ProjectionCalculationStep) steps.get(position++);
    }
    for (var item : projection.getProjection().getItems()) {
      if (item.isAll() || item.isExclude() || !directRead(item.getExpression(), boundary)) {
        return PlainPlanDecision.rejected(Reason.PROJECTION, projection);
      }
    }
    var facts = new Facts(kind, prefetched, classes, vertexHops);
    if (position != steps.size()) {
      return new PlainPlanDecision(Reason.UNKNOWN_STEP, steps.get(position).getClass().getName(),
          facts);
    }
    if (sliced && kind == StartKind.INDEX) {
      // The equality certificate does not certify observable index order or early consumption.
      return PlainPlanDecision.rejected(Reason.START_ACCESS, first);
    }
    boolean sliceAfterFilter = sliced && filtered;
    boolean ridProtected = kind == StartKind.RID && vertexHops == 1;
    var reason = sliceAfterFilter ? Reason.SLICE_AFTER_FILTER
        : ridProtected ? Reason.RID_SINGLE_HOP_PROTECTED : Reason.PLAIN;
    return new PlainPlanDecision(reason, null, facts, sliceAfterFilter, ridProtected);
  }

  private static PlainPlanDecision rejectedAt(List<? extends ExecutionStep> steps, int position,
      Reason missing) {
    return position == steps.size() ? PlainPlanDecision.rejected(missing, null)
        : PlainPlanDecision.rejected(Reason.UNKNOWN_STEP, steps.get(position));
  }

  private static PlainPlanDecision start(InternalExecutionPlan plan, String expectedClass,
      NativeStartEvidence evidence, CommandContext bindings, boolean candidate) {
    if (candidate && !plan.getSteps().isEmpty()
        && plan.getSteps().getFirst().getClass()
            == com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromIndexStep.class) {
      return new PlainPlanDecision(Reason.PLAIN, null,
          new Facts(StartKind.INDEX, false, Set.of(), 0));
    }
    if (evidence != null && evidence.matches(plan, bindings, expectedClass)) {
      return new PlainPlanDecision(Reason.PLAIN, null,
          new Facts(StartKind.INDEX, false, Set.of(expectedClass), 0));
    }
    var steps = plan.getSteps();
    if (steps.isEmpty()) {
      return PlainPlanDecision.rejected(Reason.START_ACCESS, null);
    }
    var fetch = steps.getFirst();
    StartKind kind;
    Set<String> classes = Set.of();
    if (fetch.getClass() == FetchFromRidsStep.class) {
      kind = StartKind.RID;
    } else if (fetch.getClass() == FetchFromClassExecutionStep.class) {
      var name = ((FetchFromClassExecutionStep) fetch).getClassName();
      if (name == null || !name.equals(expectedClass)) {
        return PlainPlanDecision.rejected(Reason.START_ACCESS, fetch);
      }
      kind = StartKind.CLASS_SCAN;
      classes = Set.of(name);
    } else {
      // Index and known-endpoint starts need a separate native-access proof.
      return PlainPlanDecision.rejected(Reason.UNKNOWN_STEP, fetch);
    }
    if (steps.size() > 1) {
      var filter = steps.get(1);
      if (filter.getClass() != FilterStep.class) {
        return PlainPlanDecision.rejected(Reason.UNKNOWN_STEP, filter);
      }
      if (!local(((FilterStep) filter).getWhereClause())) {
        return PlainPlanDecision.rejected(Reason.PREDICATE, filter);
      }
    }
    if (steps.size() > 2) {
      return PlainPlanDecision.rejected(Reason.UNKNOWN_STEP, steps.get(2));
    }
    return new PlainPlanDecision(Reason.PLAIN, null, new Facts(kind, false, classes, 0));
  }

  private static PlainPlanDecision booleanTree(FilterBooleanMatchStep step, String boundary) {
    if (step.getKind() == FilterBooleanMatchStep.Kind.PROBE || step.hasProbeWork()) {
      return PlainPlanDecision.rejected(Reason.BOOLEAN_PROBE, step);
    }
    var children = step.getChildren();
    switch (step.getKind()) {
      case FILTER -> {
        if (!children.isEmpty() || !boundary.equals(step.getAlias()) || step.getCondition() == null
            || !local(step.getCondition())) {
          return PlainPlanDecision.rejected(Reason.PREDICATE, step);
        }
      }
      case NOT -> {
        if (children.size() != 1) {
          return PlainPlanDecision.rejected(Reason.BOOLEAN_SHAPE, step);
        }
      }
      case AND, OR -> {
        if (children.isEmpty()) {
          return PlainPlanDecision.rejected(Reason.BOOLEAN_SHAPE, step);
        }
      }
      default -> {
        return PlainPlanDecision.rejected(Reason.BOOLEAN_SHAPE, step);
      }
    }
    for (var child : children) {
      var failure = booleanTree(child, boundary);
      if (failure != null) {
        return failure;
      }
    }
    return null;
  }

  private static boolean local(SQLWhereClause where) {
    if (where == null) {
      return true;
    }
    return localBoolean(where.getBaseExpression());
  }

  // This deliberately small AST allowlist admits no functions or methods. Even pure-looking
  // calls need their own audit. Unknown expressions and subqueries fail closed.
  private static boolean localBoolean(SQLBooleanExpression expression) {
    if (expression == null) {
      return false;
    }
    if (expression.getClass() == SQLAndBlock.class) {
      return ((SQLAndBlock) expression).getSubBlocks().stream()
          .allMatch(PlainPlanClassifier::localBoolean);
    }
    if (expression.getClass() == SQLOrBlock.class) {
      return ((SQLOrBlock) expression).getSubBlocks().stream()
          .allMatch(PlainPlanClassifier::localBoolean);
    }
    if (expression.getClass() == SQLNotBlock.class) {
      return localBoolean(((SQLNotBlock) expression).getSub());
    }
    if (expression.getClass() == SQLBinaryCondition.class) {
      var binary = (SQLBinaryCondition) expression;
      return localExpression(binary.getLeft()) && localExpression(binary.getRight());
    }
    if (expression.getClass() == SQLInCondition.class) {
      var in = (SQLInCondition) expression;
      return localExpression(in.getLeft()) && in.getRightStatement() == null
          && (in.getRightParam() != null || localMath(in.getRightMathExpression()));
    }
    if (expression.getClass() == SQLIsDefinedCondition.class) {
      return localExpression(((SQLIsDefinedCondition) expression).getExpression());
    }
    if (expression.getClass() == SQLIsNullCondition.class) {
      return localExpression(((SQLIsNullCondition) expression).getExpression());
    }
    if (expression.getClass() == SQLIsNotNullCondition.class) {
      return localExpression(((SQLIsNotNullCondition) expression).getExpression());
    }
    return false;
  }

  private static boolean localExpression(SQLExpression expression) {
    return expression != null && expression.getClass() == SQLExpression.class
        && expression.getArrayConcatExpression() == null
        && (expression.getRid() != null
            ? expression.getRid().getCollection() != null
                && expression.getRid().getPosition() != null
            : localMath(expression.getMathExpression()));
  }

  private static boolean localMath(SQLMathExpression expression) {
    if (expression == null || expression.getClass() != SQLBaseExpression.class) {
      return false;
    }
    var base = (SQLBaseExpression) expression;
    if (base.getModifier() != null) {
      return false;
    }
    var identifier = base.getIdentifier();
    if (identifier == null) {
      return true; // Exact SQLBaseExpression has only scalar literals and parameters here.
    }
    var level = identifier.getLevelZero();
    if (level != null) {
      return level.getFunctionCall() == null && level.getCollection() != null
          && level.getCollection().getExpressions().stream()
              .allMatch(PlainPlanClassifier::localExpression);
    }
    var suffix = identifier.getSuffix();
    return suffix != null && (suffix.getRecordAttribute() != null
        || (suffix.getIdentifier() != null
            && !suffix.getIdentifier().getStringValue().startsWith("$")));
  }

  private static boolean literalLabel(SQLExpression expression) {
    return localExpression(expression)
        && expression.getMathExpression() instanceof SQLBaseExpression base
        && base.getStringLiteralValue() != null;
  }

  private static boolean narrows(SQLMatchPathItem item, String constraint) {
    if (constraint == null) {
      return false;
    }
    boolean edge = Set.of("outE", "inE", "bothE")
        .contains(item.getMethod().getMethodNameString());
    if ((edge ? "E" : "V").equalsIgnoreCase(constraint)) {
      return false;
    }
    // A single edge label already restricts outE/inE/bothE candidates to that edge class.
    var labels = item.getMethod().getParams();
    return !edge || labels.size() != 1 || !constraint.equalsIgnoreCase(
        ((SQLBaseExpression) labels.getFirst().getMathExpression()).getStringLiteralValue());
  }

  private static boolean directRead(SQLExpression expression, String alias) {
    if (expression == null || expression.getClass() != SQLExpression.class
        || expression.getMathExpression() == null
        || expression.getMathExpression().getClass() != SQLBaseExpression.class) {
      return false;
    }
    var base = (SQLBaseExpression) expression.getMathExpression();
    var identifier = base.getIdentifier();
    if (identifier == null || identifier.getSuffix() == null
        || identifier.getSuffix().getIdentifier() == null
        || !alias.equals(identifier.getSuffix().getIdentifier().getStringValue())) {
      return false;
    }
    var modifier = base.getModifier();
    return modifier == null || modifier.getSimpleSuffixPropertyName() != null
        || modifier.getSimpleSuffixRecordAttributeName() != null;
  }
}
