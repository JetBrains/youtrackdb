package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FetchFromClassExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FilterByClassStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.FilterStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBaseExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMathExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLParenthesisExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/** Owns built-source eligibility and fixed endpoints for a detached candidate alternative. */
final class KnownEndpointExistsAccess {

  static final int UNBUDGETED_TARGETS = 8;

  record Hop(Direction forward, SQLMatchPathItem edge, SQLMatchPathItem vertex) {
  }

  final String alias;
  final String sourceClass;
  final SQLWhereClause sourceFilter;
  final SQLMatchExpression check;
  final Direction forward;
  final List<Hop> hops;
  final SQLExpression ridExpression;
  final SQLRid ridSlot;
  final SQLInCondition ridIn;
  final boolean equality;

  private KnownEndpointExistsAccess(String alias, String sourceClass,
      SQLWhereClause sourceFilter, SQLMatchExpression check, Direction forward,
      SQLExpression ridExpression, SQLRid ridSlot, SQLInCondition ridIn, boolean equality) {
    this.alias = alias;
    this.sourceClass = sourceClass;
    this.sourceFilter = sourceFilter == null ? null : sourceFilter.copy();
    this.check = check.copy();
    this.forward = forward;
    this.hops = hops(this.check, null);
    this.ridExpression = ridExpression == null ? null : ridExpression.copy();
    this.ridSlot = ridSlot == null ? null : ridSlot.copy();
    this.ridIn = ridIn == null ? null : ridIn.copy();
    this.equality = equality;
  }

  @Nullable static KnownEndpointExistsAccess find(SelectExecutionPlan built, Pattern pattern,
      List<SQLMatchExpression> checks, Map<String, String> classes,
      Map<String, SQLWhereClause> filters, CommandContext ctx) {
    if (built.getSteps().isEmpty() || !(built.getSteps().getFirst() instanceof MatchFirstStep root)
        || !(root.getSourcePlan() instanceof SelectExecutionPlan scan)
        || scan.getSteps().isEmpty()
        || scan.getSteps().getFirst().getClass() != FetchFromClassExecutionStep.class
        || scan.getOrderReport().fullOrderCovered()) {
      return null;
    }
    // Inspect the actual SELECT chain. A subclass-index union, RID fetch or LET start is not a scan.
    if (scan.getSteps().stream().skip(1)
        .anyMatch(step -> !(step instanceof FilterStep || step instanceof FilterByClassStep))) {
      return null;
    }
    var fetch = ((ExecutionStepInternal) scan.getSteps().getFirst())
        .serialize(ctx.getDatabaseSession());
    if (Boolean.TRUE.equals(fetch.getProperty("orderByRidDesc"))) {
      return null;
    }
    String alias = root.getAlias();
    String sourceClass = classes.get(alias);
    if (sourceClass == null || !sourceClass.equals(fetch.getProperty("className"))
        || pattern.aliasToNode.values().stream().anyMatch(PatternNode::isOptionalNode)
        || built.getSteps().stream().skip(1).anyMatch(step -> !(step instanceof MatchStep))) {
      return null;
    }
    for (var check : checks) {
      if (!alias.equals(check.getOrigin().getAlias()) || check.getOrigin().isOptional()
          || check.getItems().isEmpty() || MatchExecutionPlanner.notPatternDependsOnMatched(check)
          || MatchExecutionPlanner.findSharedAliases(check, pattern).size() != 1) {
        continue;
      }
      var hops = hops(check, ctx);
      if (hops == null || (check.getOrigin().getFilter() != null
          && check.getOrigin().getFilter().toString().contains("$"))) {
        continue;
      }
      SQLMatchPathItem item = check.getItems().getLast();
      Direction direction = hops.getFirst().forward();
      var slot = item.getFilter().getRid(ctx);
      if (slot != null && !resolvableSlot(slot, ctx)) {
        continue;
      }
      var where = item.getFilter().getFilter();
      SQLExpression expression = where == null ? null : where.findRidEquality();
      boolean equality = expression != null;
      var ridIn = expression == null && where != null ? where.findRidInList() : null;
      boolean staticIn = ridIn != null && ridIn.getRightStatement() == null
          && (ridIn.getRightParam() != null || (ridIn.getRightMathExpression() != null
              && fixedMath(ridIn.getRightMathExpression(), ctx)));
      if (slot == null && !staticIn
          && (expression == null || !fixedExpression(expression, ctx))) {
        continue;
      }
      var access = new KnownEndpointExistsAccess(alias, sourceClass, filters.get(alias), check,
          direction, expression, slot, ridIn, equality);
      // Bindings never decide plan shape. Each execution resolves comparison values independently.
      return access;
    }
    return null;
  }

  /** Collapses a canonical edge-record step and its endpoint step into one vertex hop. */
  @Nullable private static List<Hop> hops(SQLMatchExpression check, CommandContext ctx) {
    var result = new ArrayList<Hop>();
    var items = check.getItems();
    for (int i = 0; i < items.size(); i++) {
      var item = items.get(i);
      if (!ordinary(item, ctx)) {
        return null;
      }
      String name = item.getMethod().getMethodNameString().toLowerCase(Locale.ROOT);
      Direction direction = switch (name) {
        case "out", "oute" -> Direction.OUT;
        case "in", "ine" -> Direction.IN;
        case "both" -> Direction.BOTH;
        default -> null;
      };
      if (direction == null) {
        return null;
      }
      SQLMatchPathItem edge = null;
      if (name.endsWith("e")) {
        edge = item;
        if (++i >= items.size()) {
          return null;
        }
        item = items.get(i);
        if (!ordinary(item, ctx) || !item.getMethod().getParams().isEmpty()
            || !item.getMethod().getMethodNameString()
                .equalsIgnoreCase(direction == Direction.OUT ? "inV" : "outV")) {
          return null;
        }
      }
      result.add(new Hop(direction, edge, item));
    }
    return result;
  }

  private static boolean ordinary(SQLMatchPathItem item, CommandContext ctx) {
    return item.getClass() == SQLMatchPathItem.class && item.getMethod() != null
        && item.getFilter() != null && !item.getFilter().isOptional()
        && item.getFilter().getWhileCondition() == null && item.getFilter().getMaxDepth() == null
        && item.getFilter().getDepthAlias() == null && item.getFilter().getPathAlias() == null
        && (ctx == null || item.getMethod().getParams().stream()
            .allMatch(param -> fixedExpression(param, ctx)));
  }

  private static boolean resolvableSlot(SQLRid slot, CommandContext ctx) {
    var text = slot.toString();
    if (!text.startsWith("{")) {
      return true;
    }
    // RID has no expression getter. Parse its expression text to inspect row dependencies.
    var expression = text.substring(text.indexOf(':') + 1, text.length() - 1);
    try {
      var parsed = new YouTrackDBSql(
          new ByteArrayInputStream(expression.getBytes(StandardCharsets.UTF_8)))
          .Expression();
      return fixedExpression(parsed, ctx);
    } catch (ParseException unsupported) {
      return false;
    }
  }

  // Early calculation also accepts functions that inspect the current record. Only these AST
  // forms prove independence. Serialization exposes the tree without changing generated classes.
  static boolean fixedExpression(SQLExpression expression, CommandContext ctx) {
    try {
      return fixedValue(expression.serialize(ctx.getDatabaseSession()), ctx);
    } catch (RuntimeException unsupported) {
      // Some unsupported parser nodes cannot serialize. They cannot prove a fixed target.
      return false;
    }
  }

  private static boolean fixedMath(SQLMathExpression expression, CommandContext ctx) {
    try {
      return fixedMath(expression.serialize(ctx.getDatabaseSession()), ctx);
    } catch (RuntimeException unsupported) {
      return false;
    }
  }

  private static boolean fixedValue(Result expression, CommandContext ctx) {
    if (expression.getProperty("json") != null
        || expression.getProperty("booleanExpression") != null
        || expression.getProperty("arrayConcatExpression") != null) {
      return false;
    }
    Result rid = expression.getProperty("rid");
    if (rid != null) {
      Result value = rid.getProperty("expression");
      return value == null || fixedValue(value, ctx);
    }
    Result math = expression.getProperty("mathExpression");
    return math == null || fixedMath(math, ctx);
  }

  private static boolean fixedMath(Result math, CommandContext ctx) {
    String type = math.getProperty("__class");
    if (SQLBaseExpression.class.getName().equals(type)) {
      if (math.getProperty("modifier") != null) {
        return false;
      }
      Result identifier = math.getProperty("identifier");
      if (identifier == null) {
        return true; // Literal or input parameter.
      }
      Result level = identifier.getProperty("levelZero");
      if (identifier.getProperty("suffix") != null || level == null
          || level.getProperty("functionCall") != null
          || Boolean.TRUE.equals(level.getProperty("self"))) {
        return false;
      }
      Result collection = level.getProperty("collection");
      List<Result> values = collection == null ? null : collection.getProperty("expressions");
      return values != null && values.stream().allMatch(value -> fixedValue(value, ctx));
    }
    if (SQLParenthesisExpression.class.getName().equals(type)) {
      Result expression = math.getProperty("expression");
      return math.getProperty("statement") == null && expression != null
          && fixedValue(expression, ctx);
    }
    if (!SQLMathExpression.class.getName().equals(type)) {
      return false;
    }
    List<Result> children = math.getProperty("childExpressions");
    return children != null && children.stream().allMatch(child -> fixedMath(child, ctx));
  }

  KnownEndpointExistsAccess copy() {
    return new KnownEndpointExistsAccess(alias, sourceClass, sourceFilter, check, forward,
        ridExpression, ridSlot, ridIn, equality);
  }

  /** Resolves fixed scalar labels once. Unsupported values retain the forward path's meaning. */
  @Nullable List<List<String>> labels(CommandContext ctx) {
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    var result = new ArrayList<List<String>>();
    for (var hop : hops) {
      var labels = new ArrayList<String>();
      var method = (hop.edge() == null ? hop.vertex() : hop.edge()).getMethod();
      for (var expression : method.getParams()) {
        Object value = expression.execute((Result) null, ctx);
        if (!(value instanceof String)) {
          // Collections and null have special forward conversion rules. Do not stringify them.
          return null;
        }
        var label = IOUtils.getStringContent(value);
        var clazz = schema.getClass(label);
        labels.add(clazz == null ? label : clazz.getName());
      }
      result.add(List.copyOf(labels));
    }
    return result;
  }

  // Resolution runs inside the guarded decision phase. The retained check owns query errors.
  @Nullable List<RecordIdInternal> targets(CommandContext ctx,
      KnownEndpointExistsCost.WorkBudget budget) {
    if (ridSlot != null) {
      try {
        var rid = ridSlot.toRecordId((Result) null, ctx);
        return rid == null ? null : List.of(rid);
      } catch (IllegalArgumentException | DatabaseException invalid) {
        return null;
      }
    }
    Object value = ridIn == null ? ridExpression.execute((Result) null, ctx)
        : ridIn.evaluateRight((Result) null, ctx);
    if (equality && value instanceof Collection<?> collection) {
      // QueryOperatorEquals unwraps exactly one collection member, not arrays or longer lists.
      if (collection.size() != 1) {
        return null;
      }
      value = collection.iterator().next();
    }
    var result = new LinkedHashSet<RecordIdInternal>();
    if (!equality) {
      // Do not consume one-shot iterables. Long finite lists are bounded during preparation.
      if (!(value instanceof Collection<?> collection)) {
        return null;
      }
      budget.enable(collection.size() > UNBUDGETED_TARGETS);
      for (var element : collection) {
        budget.charge(1);
        var rid = asRid(element);
        if (rid == null) {
          return null;
        }
        result.add(rid);
      }
    } else {
      var rid = asRid(value);
      if (rid == null) {
        return null;
      }
      result.add(rid);
    }
    return result.isEmpty() ? null : new ArrayList<>(result);
  }

  @Nullable private static RecordIdInternal asRid(Object value) {
    if (value instanceof Identifiable identifiable) {
      return identifiable.getIdentity() instanceof RecordIdInternal rid ? rid : null;
    }
    if (value instanceof String string) {
      try {
        return RecordIdInternal.fromString(string, false);
      } catch (IllegalArgumentException | DatabaseException invalid) {
        return null;
      }
    }
    return null;
  }
}
