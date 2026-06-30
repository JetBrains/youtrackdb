package com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Stateful accumulator for the unified MATCH IR that {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner} consumes.
 *
 * <p>Produces a {@link Pattern} (topology), a {@code Map<alias, className>} ({@code aliasClasses}),
 * and a {@code Map<alias, SQLWhereClause>} ({@code aliasFilters}). The three artifacts are
 * returned together from {@link #build()} as a {@link PatternIR} record.
 *
 * <p>Aliases are caller-managed: the builder requires non-null aliases on every call and never
 * auto-generates them. Front-ends choose their own naming scheme — GQL today uses {@code $c<N>};
 * the Gremlin translator will use a different prefix. Decoupling alias generation from the builder
 * lets each consumer keep its tests stable.
 *
 * <p>{@code addEdge} reuses {@link Pattern#addExpression}, which performs implicit
 * {@code getOrCreateNode} for both endpoints, so it is safe to call without registering the
 * endpoints via {@link #addNode} first. Conversely, registering an endpoint through
 * {@link #addNode} before the edge attaches a class / where clause to it.
 */
public final class MatchPatternBuilder {

  /**
   * Direction of an edge hop. Maps onto the parser's {@code out} / {@code in} / {@code both}
   * method-call vocabulary that {@link SQLMatchPathItem} understands.
   */
  public enum Direction {
    OUT, IN, BOTH
  }

  /** Triple returned by {@link #build()}. {@code pattern} is a deep copy; {@code aliasClasses}
   * and {@code aliasFilters} are unmodifiable views. */
  public record PatternIR(
      Pattern pattern,
      Map<String, String> aliasClasses,
      Map<String, SQLWhereClause> aliasFilters) {
  }

  private final Pattern pattern = new Pattern();
  private final Map<String, String> aliasClasses = new LinkedHashMap<>();
  private final Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
  private boolean built;

  /**
   * Registers (or updates) a node identified by {@code alias}. The node is created in the
   * underlying {@link Pattern} if it does not already exist; otherwise the existing node is
   * reused.
   *
   * <p>Repeated registration of the same alias merges rather than replaces:
   *
   * <ul>
   *   <li>{@code className} overwrites the existing class when non-null/non-blank; passing null or
   *       blank leaves a previously-registered class in place. This lets a later edge-target call
   *       re-register the alias without erasing class info that an earlier {@code addNode} attached.
   *   <li>{@code where} overwrites the existing where-clause when non-null; passing null leaves
   *       the previously-registered clause in place.
   *   <li>{@code optional} is monotonic: passing {@code true} upgrades the node to optional;
   *       passing {@code false} does not clear an already-optional flag. This matches MATCH
   *       semantics where two paths converging on the same alias make it optional if any path
   *       declares it so.
   * </ul>
   *
   * @param alias non-null alias for the node
   * @param className class to attach to the alias (overwrites when non-null/non-blank)
   * @param where where clause to attach to the alias (overwrites when non-null)
   * @param optional when true, marks the node as optional (monotonic — never cleared)
   * @return this builder for fluent chaining
   * @throws IllegalStateException if {@link #build()} has already been called on this builder
   */
  public MatchPatternBuilder addNode(
      @Nonnull String alias, String className, SQLWhereClause where, boolean optional) {
    checkNotBuilt();
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

  /**
   * Registers an edge {@code fromAlias} → {@code toAlias} with the given direction and (optional)
   * edge label. Either alias is implicitly created via {@link Pattern#addExpression}'s internal
   * {@code getOrCreateNode} if it hasn't been registered yet.
   *
   * <p>{@code edgeFilter} is attached as the path item's target-vertex filter — i.e. the
   * {@code WHERE} of the {@code {…}} block that follows {@code .out('E')} in MATCH grammar — and
   * is not stored in {@code aliasFilters} (which is reserved for nodes registered via
   * {@link #addNode}). Callers that want the filter to participate in plan-level selectivity
   * inference should also call {@link #addNode} for the target alias.
   *
   * <p>{@code whileCondition} and {@code maxDepth} are not yet supported because their parser
   * setters are package-private; an {@link UnsupportedOperationException} is thrown so the gap is
   * loud rather than silent. The Gremlin translator does not exercise variable-depth traversals in
   * Phase 1.
   */
  public MatchPatternBuilder addEdge(
      @Nonnull String fromAlias,
      @Nonnull String toAlias,
      @Nonnull Direction dir,
      String edgeLabel,
      SQLWhereClause edgeFilter,
      SQLWhereClause whileCondition,
      Integer maxDepth) {
    checkNotBuilt();
    if (whileCondition != null || maxDepth != null) {
      throw new UnsupportedOperationException(
          "whileCondition / maxDepth are not yet supported by MatchPatternBuilder; "
              + "wire them through when the translator's variable-depth handler arrives");
    }

    var fromFilter = SQLMatchFilter.fromAliasAndClass(fromAlias, null);
    var toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null);
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

  /**
   * Returns {@code true} iff {@code alias} has been registered in the underlying pattern — either
   * via a prior {@link #addNode} call or via {@link Pattern#addExpression}'s implicit
   * {@code getOrCreateNode} from a prior {@link #addEdge}. Callers that need to validate
   * cross-references against the in-progress pattern (e.g. the pattern-form NOT recogniser
   * checking that its origin alias matches an already-registered node) use this before
   * constructing detached AST that references the alias.
   *
   * <p>Read-only; idempotent. Routes through the public {@link Pattern#get} accessor rather than
   * the underlying field so a future {@link Pattern} internal-structure change cannot break this
   * lookup silently. Safe to call at any time — after {@link #build()} the builder no longer owns
   * the pattern, but the read itself stays consistent.
   */
  public boolean hasAlias(String alias) {
    return alias != null && pattern.get(alias) != null;
  }

  /**
   * Returns the accumulated IR and locks the builder. {@code pattern} is deep-copied; the alias
   * maps are exposed as {@link Collections#unmodifiableMap(Map) unmodifiable views} so callers
   * cannot mutate {@link PatternIR} after {@code build()}. After {@code build()}, any further
   * {@link #addNode} / {@link #addEdge} / {@link #build()} call throws
   * {@link IllegalStateException} — the one-shot contract makes the ownership transfer explicit.
   */
  public PatternIR build() {
    checkNotBuilt();
    built = true;
    return new PatternIR(
        pattern.copy(),
        Collections.unmodifiableMap(aliasClasses),
        Collections.unmodifiableMap(aliasFilters));
  }

  private void checkNotBuilt() {
    if (built) {
      throw new IllegalStateException(
          "MatchPatternBuilder is one-shot: build() has already been called. "
              + "Create a new builder instance for each plan.");
    }
  }
}
