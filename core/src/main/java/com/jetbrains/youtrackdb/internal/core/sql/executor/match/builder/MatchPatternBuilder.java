package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Stateful accumulator for the unified MATCH IR that MatchExecutionPlanner consumes.
///
/// Produces a [Pattern] (topology), a `Map<alias, className>` (`aliasClasses`), and a
/// `Map<alias, SQLWhereClause>` (`aliasFilters`). The three artifacts are returned together
/// from [#build()] as a [PatternIR] record.
///
/// Aliases are caller-managed: the builder requires non-null aliases on every call and
/// never auto-generates them. Front-ends choose their own naming scheme — GQL today uses
/// `$c<N>`; the Gremlin translator will use a different prefix. Decoupling alias
/// generation from the builder lets each consumer keep its tests stable.
///
/// `addEdge` reuses [Pattern#addExpression], which performs implicit `getOrCreateNode`
/// for both endpoints, so it is safe to call without registering the endpoints via
/// [#addNode] first. Conversely, registering an endpoint through [#addNode] before
/// the edge attaches a class / where clause to it.
public final class MatchPatternBuilder {

  /// Direction of an edge hop. Maps onto the parser's `out` / `in` / `both` method-call
  /// vocabulary that [SQLMatchPathItem] understands.
  public enum Direction {
    OUT, IN, BOTH
  }

  /// Immutable triple returned by [#build()].
  public record PatternIR(
      Pattern pattern,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters) {
  }

  private final Pattern pattern = new Pattern();
  private final Map<String, String> aliasClasses = new LinkedHashMap<>();
  private final Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
  private boolean built;

  /// Registers (or updates) a node identified by `alias`. The node is created in the
  /// underlying [Pattern] if it does not already exist; otherwise the existing node is
  /// reused.
  ///
  /// Repeated registration of the same alias **merges** rather than replaces:
  /// - `className` overwrites the existing class when non-null/non-blank; passing
  ///   null or blank leaves a previously-registered class in place. This lets a
  ///   later edge-target call re-register the alias without erasing class info
  ///   that an earlier `addNode` attached.
  /// - `where` overwrites the existing where-clause when non-null; passing null
  ///   leaves the previously-registered clause in place.
  /// - `optional` is **monotonic**: passing `true` upgrades the node to optional;
  ///   passing `false` does **not** clear an already-optional flag. This matches
  ///   MATCH semantics where two paths converging on the same alias make it
  ///   optional if any path declares it so.
  ///
  /// @param alias    non-null alias for the node
  /// @param className class to attach to the alias (overwrites when non-null/non-blank)
  /// @param where    where clause to attach to the alias (overwrites when non-null)
  /// @param optional when true, marks the node as optional (monotonic — never cleared)
  /// @return this builder for fluent chaining
  /// @throws IllegalStateException if [#build()] has already been called on this builder
  public MatchPatternBuilder addNode(
      String alias, String className, SQLWhereClause where, boolean optional) {
    checkNotBuilt();
    Objects.requireNonNull(alias, "alias must not be null");
    var node =
        pattern.aliasToNode.computeIfAbsent(
            alias,
            a -> {
              var n = new PatternNode();
              n.alias = a;
              return n;
            });
    if (optional) {
      node.optional = true;
    }
    if (className != null && !className.isBlank()) {
      aliasClasses.put(alias, className);
    }
    if (where != null) {
      aliasFilters.put(alias, where);
    }
    return this;
  }

  /// Registers an edge `fromAlias` → `toAlias` with the given direction and (optional)
  /// edge label. Either alias is implicitly created via [Pattern#addExpression]'s
  /// internal `getOrCreateNode` if it hasn't been registered yet.
  ///
  /// `edgeFilter` is attached as the path item's target-vertex filter — i.e. the
  /// `WHERE` of the `{…}` block that follows `.out('E')` in MATCH grammar — and is
  /// not stored in `aliasFilters` (which is reserved for nodes registered via
  /// [#addNode]). Callers that want the filter to participate in plan-level
  /// selectivity inference should also call [#addNode] for the target alias.
  ///
  /// `whileCondition` and `maxDepth` are not yet supported because their parser
  /// setters are package-private; an [UnsupportedOperationException] is thrown so
  /// the gap is loud rather than silent. The Gremlin translator does not exercise
  /// variable-depth traversals in Phase 1.
  public MatchPatternBuilder addEdge(
      String fromAlias,
      String toAlias,
      Direction dir,
      String edgeLabel,
      SQLWhereClause edgeFilter,
      SQLWhereClause whileCondition,
      Integer maxDepth) {
    checkNotBuilt();
    Objects.requireNonNull(fromAlias, "fromAlias must not be null");
    Objects.requireNonNull(toAlias, "toAlias must not be null");
    Objects.requireNonNull(dir, "dir must not be null");
    if (whileCondition != null || maxDepth != null) {
      throw new UnsupportedOperationException(
          "whileCondition / maxDepth are not yet supported by MatchPatternBuilder; "
              + "wire them through when the translator's variable-depth handler arrives");
    }

    var fromFilter = SQLMatchFilter.fromGqlNode(fromAlias, null);
    var toFilter = SQLMatchFilter.fromGqlNode(toAlias, null);
    if (edgeFilter != null) {
      toFilter.setFilter(edgeFilter);
    }

    var pathItem = new SQLMatchPathItem(-1);
    var edgeIdent =
        edgeLabel != null && !edgeLabel.isBlank() ? new SQLIdentifier(edgeLabel) : null;
    switch (dir) {
      case OUT -> pathItem.outPath(edgeIdent);
      case IN -> pathItem.inPath(edgeIdent);
      case BOTH -> pathItem.bothPath(edgeIdent);
    }
    pathItem.setFilter(toFilter);

    var expr = new SQLMatchExpression(-1);
    expr.setOrigin(fromFilter);
    expr.addItem(pathItem);

    pattern.addExpression(expr);
    return this;
  }

  /// Returns {@code true} iff {@code alias} has been registered in the underlying
  /// pattern — either via a prior [#addNode] call or via [Pattern#addExpression]'s
  /// implicit {@code getOrCreateNode} from a prior [#addEdge]. Callers that need
  /// to validate cross-references against the in-progress pattern (e.g. the
  /// pattern-form NOT recogniser checking that its origin alias matches an
  /// already-registered node) use this before constructing detached AST that
  /// references the alias.
  ///
  /// Read-only; idempotent. Routes through the public [Pattern#get] accessor
  /// rather than the underlying field so a future [Pattern] internal-structure
  /// change cannot break this lookup silently. Safe to call at any time —
  /// after [#build()] the builder no longer owns the pattern, but the read
  /// itself stays consistent.
  public boolean hasAlias(String alias) {
    return alias != null && pattern.get(alias) != null;
  }

  /// Returns the accumulated IR and locks the builder. The [Pattern] is handed over
  /// by reference (the planner is its new sole owner); the alias maps are defensively
  /// copied so future inspection of the returned [PatternIR] is stable. After
  /// `build()`, any further [#addNode] / [#addEdge] / [#build()] call throws
  /// [IllegalStateException] — the one-shot contract makes the ownership transfer
  /// explicit and prevents the half-snapshotted state where a re-built IR would
  /// share the live [Pattern] but a stale copy of the maps.
  public PatternIR build() {
    checkNotBuilt();
    built = true;
    return new PatternIR(
        pattern, new LinkedHashMap<>(aliasClasses), new LinkedHashMap<>(aliasFilters));
  }

  private void checkNotBuilt() {
    if (built) {
      throw new IllegalStateException(
          "MatchPatternBuilder is one-shot: build() has already been called. "
              + "Create a new builder instance for each plan.");
    }
  }
}
