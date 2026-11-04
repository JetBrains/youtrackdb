package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.common.comparator.GremlinOrderComparator;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.ResolvedOrderByNullsPlacement;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One item of an {@code ORDER BY} clause, together with the comparison it performs.
 *
 * <h2>Text order follows the declared collation of the property</h2>
 *
 * The collation of the ordered property is the single authority for text order. Three sources feed
 * it, in this precedence: an explicit {@code COLLATE} clause on the item, the collation declared on
 * the property in the schema, and the default collation. The default collation is plain
 * case-sensitive comparison, which is also the TinkerPop rule, so a property that declares nothing
 * orders identically here and on the native Gremlin pipeline.
 *
 * <p>The declared collation is set by the planner once per plan build (see
 * {@link OrderByCollationResolver}), never read per comparison. Reading it per record would let two
 * records of different subclasses answer two different collations inside one sort, which makes the
 * comparison non-transitive.
 */
public class SQLOrderByItem {

  /** Plain case-sensitive comparison — what an item with no declaration compares with. */
  private static final Collate DEFAULT_COLLATE = Collate.defaultCollate();

  public static final String ASC = "ASC";
  public static final String DESC = "DESC";
  /** Explicit {@code NULLS FIRST} on this ORDER BY item (absolute placement). */
  public static final String NULLS_FIRST = "NULLS FIRST";
  /** Explicit {@code NULLS LAST} on this ORDER BY item (absolute placement). */
  public static final String NULLS_LAST = "NULLS LAST";

  protected String alias;
  protected SQLModifier modifier;
  protected String recordAttr;
  protected SQLRid rid;
  protected String type = ASC;
  /**
   * Explicit null ordering from the grammar ({@link #NULLS_FIRST}, {@link #NULLS_LAST}), or
   * {@code null} when omitted. The direction-specific configuration then applies.
   */
  @Nullable protected String nullOrdering;
  protected SQLExpression collate;

  // calculated at run time
  private Collate collateStrategy;
  private boolean isEdge;

  /** True only for sort items constructed by the Gremlin-to-MATCH translator. */
  private boolean gremlinToMatchTranslatorProduced;

  /**
   * The collation the ordered property declares in the schema, or {@code null} when the item is not
   * a plain property, the property declares nothing, or two classes of one polymorphic query declare
   * different collations for the name. Written once per plan build by the planner.
   */
  @Nullable
  private Collate declaredCollate;

  public String getAlias() {
    return alias;
  }

  public void setAlias(String alias) {
    this.alias = alias;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  @Nullable public String getNullOrdering() {
    return nullOrdering;
  }

  public void setNullOrdering(@Nullable String nullOrdering) {
    this.nullOrdering = nullOrdering;
  }

  public String getRecordAttr() {
    return recordAttr;
  }

  public void setRecordAttr(String recordAttr) {
    this.recordAttr = recordAttr;
  }

  public SQLRid getRid() {
    return rid;
  }

  public void setRid(SQLRid rid) {
    this.rid = rid;
  }

  /**
   * Resolves whether nulls should sort before non-nulls for this item using the runtime global
   * default only. Prefer {@link #resolveNullsFirst(ContextConfiguration)} when a session or storage
   * configuration is available.
   */
  public boolean resolveNullsFirst() {
    return resolveNullsFirst((ContextConfiguration) null);
  }

  /**
   * Resolves whether nulls should sort before non-nulls for this item.
   *
   * <p>An explicit clause wins. Otherwise the direction-specific setting is read from {@code
   * config} when present, then from the runtime global.
   */
  public boolean resolveNullsFirst(@Nullable ContextConfiguration config) {
    return OrderByNullsUtil.resolveNullsFirst(nullOrdering, !DESC.equals(type), config);
  }

  /**
   * Resolves whether nulls should sort before non-nulls for this item from a default that the caller
   * already resolved for the whole sort. No configuration is read here, so every comparison of one
   * sort sees the same placement.
   *
   * <p>The name differs from {@link #resolveNullsFirst(ContextConfiguration)} on purpose. Two
   * single-argument overloads would make a {@code null} literal argument ambiguous for callers.
   *
   * @param nullsDefault the default resolved once at the start of the sort
   */
  public boolean nullsFirstFor(ResolvedOrderByNullsPlacement nullsDefault) {
    return OrderByNullsUtil.composeNullsFirst(nullOrdering, !DESC.equals(type), nullsDefault);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {

    if (alias != null) {
      builder.append(alias);
      if (modifier != null) {
        modifier.toString(params, builder);
      }
    } else if (recordAttr != null) {
      builder.append(recordAttr);
    } else if (rid != null) {
      rid.toString(params, builder);
    }
    if (type != null) {
      builder.append(" ").append(type);
    }
    if (nullOrdering != null) {
      builder.append(" ").append(nullOrdering);
    }
    if (collate != null) {
      builder.append(" COLLATE ");
      collate.toString(params, builder);
    }
  }

  /**
   * Compares two rows by this sort key.
   *
   * @param nullsDefault the null-placement default resolved once for the whole sort (see {@link
   *     OrderByNullsUtil#resolvePlacementsForSort}). It is a parameter rather than a per-comparison
   *     read for two reasons. A change in the middle of a sort would break the comparator contract,
   *     and the read takes a storage lock.
   */
  public int compare(Result a, Result b, CommandContext ctx, ResolvedOrderByNullsPlacement nullsDefault) {
    // Direct reads avoid stored-key dispatch overhead. Equivalence tests guard both paths.
    Object aVal = null;
    Object bVal = null;
    if (rid != null) {
      throw new UnsupportedOperationException("ORDER BY " + rid + " is not supported yet");
    }
    if (recordAttr != null) {
      aVal = a.getProperty(recordAttr);
      bVal = b.getProperty(recordAttr);
    } else if (alias != null) {
      if (isEdge) {
        aVal = readRaw(a);
        bVal = readRaw(b);
      } else {
        if (a.hasProperty(alias)) {
          aVal = a.getProperty(alias);
        }
        if (b.hasProperty(alias)) {
          bVal = b.getProperty(alias);
        }
      }
    }
    if (aVal == null && bVal == null) {
      aVal = ((ResultInternal) a).getMetadata(alias);
      bVal = ((ResultInternal) b).getMetadata(alias);
    }
    if (modifier != null) {
      aVal = modifier.execute(a, aVal, ctx);
      bVal = modifier.execute(b, bVal, ctx);
    }
    return compareValues(aVal, bVal, ctx, nullsDefault);
  }

  /** Only modifier execution and edge traversal benefit from execution-local key storage. */
  public boolean needsStoredKey() {
    return modifier != null || isEdge;
  }

  private Object readRaw(Result row) {
    if (rid != null) {
      throw new UnsupportedOperationException("ORDER BY " + rid + " is not supported yet");
    }
    if (recordAttr != null) {
      return row.getProperty(recordAttr);
    }
    if (alias == null) {
      return null;
    }
    if (isEdge) {
      var element = (Vertex) row.asEntityOrNull();
      var iter = element == null ? null : element.getVertices(Direction.OUT, alias).iterator();
      return iter != null && iter.hasNext() ? iter.next() : null;
    }
    return row.hasProperty(alias) ? row.getProperty(alias) : null;
  }

  /** Execution-local state for one row occurrence and one item, never stored on an AST node. */
  public static final class SortKey {
    private boolean rawRead;
    private boolean rawNull;
    private Object raw;
    private Object metadata;
    private boolean metadataRead;
    private boolean rawComputed;
    private boolean metadataComputed;

    private void read(SQLOrderByItem item, Result row) {
      if (!rawRead) {
        raw = item.readRaw(row);
        rawNull = raw == null;
        rawRead = true;
      }
    }

    private void compact(SQLOrderByItem item) {
      // Resolve explicit collation at its original comparison boundary before choosing storage.
      // Only the exact built-in policies guarantee identity comparison for a graph record.
      var policy = item.comparisonCollate().getClass();
      if (item.isEdge && item.modifier == null && raw instanceof Vertex vertex
          && (item.collate == null || item.collateStrategy != null)
          && (policy == DefaultCollate.class || policy == CaseInsensitiveCollate.class)) {
        raw = vertex.getIdentity();
      }
    }

    /** Releases values before this transient slot is reused for another row occurrence. */
    public void reset() {
      rawRead = false;
      rawNull = false;
      raw = null;
      metadata = null;
      metadataRead = false;
      rawComputed = false;
      metadataComputed = false;
    }

    private void readMetadata(SQLOrderByItem item, Result row) {
      if (!metadataRead) {
        metadata = ((ResultInternal) row).getMetadata(item.alias);
        metadataRead = true;
      }
    }

    private Object value(SQLOrderByItem item, Result row, boolean useMetadata,
        CommandContext ctx) {
      if (useMetadata) {
        if (!metadataComputed) {
          metadata = item.modifier == null ? metadata : item.modifier.execute(row, metadata, ctx);
          metadataComputed = true;
        }
        return metadata;
      }
      if (!rawComputed) {
        raw = item.modifier == null ? raw : item.modifier.execute(row, raw, ctx);
        rawComputed = true;
      }
      return raw;
    }
  }

  /** Keeps pairwise metadata selection and acquisition order identical to direct comparison. */
  public int compareKeys(Result a, SortKey aKey, Result b, SortKey bKey,
      CommandContext ctx, ResolvedOrderByNullsPlacement nullsDefault) {
    // Null slots are direct reads. Keep one acquisition sequence for both caller contracts.
    var aVal = rawValue(a, aKey);
    var bVal = rawValue(b, bKey);
    boolean metadata = (aKey == null ? aVal == null : aKey.rawNull)
        && (bKey == null ? bVal == null : bKey.rawNull);
    if (metadata) {
      aVal = metadataValue(a, aKey);
      bVal = metadataValue(b, bKey);
    }
    aVal = aKey == null ? applyModifier(a, aVal, ctx) : aKey.value(this, a, metadata, ctx);
    bVal = bKey == null ? applyModifier(b, bVal, ctx) : bKey.value(this, b, metadata, ctx);
    int result = compareValues(aVal, bVal, ctx, nullsDefault);
    if (aKey != null) {
      aKey.compact(this);
    }
    if (bKey != null) {
      bKey.compact(this);
    }
    return result;
  }

  private Object rawValue(Result row, SortKey key) {
    if (key == null) {
      return readRaw(row);
    }
    key.read(this, row);
    return key.raw;
  }

  private Object metadataValue(Result row, SortKey key) {
    if (key == null) {
      return ((ResultInternal) row).getMetadata(alias);
    }
    key.readMetadata(this, row);
    return key.metadata;
  }

  private Object applyModifier(Result row, Object value, CommandContext ctx) {
    return modifier == null ? value : modifier.execute(row, value, ctx);
  }

  // Keep cold collation lookup separate so the shared comparison fits the JIT inline limit.
  private void resolveCollateStrategy(CommandContext ctx) {
    var collateVal = collate.execute(new ResultInternal(ctx.getDatabaseSession()), ctx);
    if (collateVal == null) {
      collateVal = collate.toString();
      if (collateVal.equals("null")) {
        collateVal = null;
      }
    }
    if (collateVal != null) {
      collateStrategy = SQLEngine.getCollate(String.valueOf(collateVal));
      if (collateStrategy == null) {
        collateStrategy =
            SQLEngine.getCollate(String.valueOf(collateVal).toUpperCase(Locale.ENGLISH));
      }
      if (collateStrategy == null) {
        collateStrategy =
            SQLEngine.getCollate(String.valueOf(collateVal).toLowerCase(Locale.ENGLISH));
      }
      if (collateStrategy == null) {
        throw new CommandExecutionException(ctx.getDatabaseSession(),
            "Invalid collate for ORDER BY: " + collateVal);
      }
    }
  }

  private int compareValues(Object aVal, Object bVal, CommandContext ctx,
      ResolvedOrderByNullsPlacement nullsDefault) {
    var result = 0;
    if (collate != null && collateStrategy == null) {
      resolveCollateStrategy(ctx);
    }

    // Null placement is absolute once resolved (explicit clause or global default composed with
    // ASC/DESC). Apply it before ASC/DESC flipping of non-null comparisons so NULLS FIRST/LAST
    // stay independent of direction, and so COLLATE agrees with the non-collate path.
    if (aVal == null || bVal == null) {
      if (aVal == null && bVal == null) {
        return 0;
      }
      var nullsFirst = nullsFirstFor(nullsDefault);
      if (aVal == null) {
        return nullsFirst ? -1 : 1;
      }
      return nullsFirst ? 1 : -1;
    }

    // One comparison rule for every value class, text included: the collation that governs this
    // item. Text used to take a session-locale Collator instead, which made an undeclared property
    // order by locale rules here and by code point on the native Gremlin pipeline and in every
    // index, so one query answered up to three different sequences.
    var comparison = comparisonCollate();
    if (gremlinToMatchTranslatorProduced && collateStrategy == null
        && declaredCollate == null) {
      result = GremlinOrderComparator.INSTANCE.compare(
          aVal, bVal, ctx == null ? null : ctx.getDatabaseSession());
    } else if ((aVal instanceof Comparable && bVal instanceof Comparable)
        || collateStrategy != null) {
      // A value that is not Comparable still has an order whenever the query states a COLLATE
      // clause: the collation routes it through the comparator registry, which knows byte arrays and
      // the other non-Comparable stored classes. Gating that on Comparable turned every such value
      // into a tie, which is a stated ordering silently dropped.
      try {
        result = comparison.compareForOrderBy(aVal, bVal);
      } catch (Exception e) {
        // Two values of incompatible types reach this, e.g. a String against an Integer in one
        // schema-less column. Reporting them equal keeps the sort from failing the whole query.
        LogManager.instance().error(this, "Error during comparision", e);
        result = 0;
      }
    }
    if (type == DESC) {
      result = -1 * result;
    }
    return result;
  }

  /**
   * The collation this item compares with: the explicit {@code COLLATE} clause when the query states
   * one, the declared collation of the property otherwise, and the default collation when the
   * property declares none.
   */
  @Nonnull
  private Collate comparisonCollate() {
    if (collateStrategy != null) {
      return collateStrategy;
    }
    return declaredCollate == null ? DEFAULT_COLLATE : declaredCollate;
  }

  /**
   * Records the collation the ordered property declares in the schema. Called by the planner once
   * per plan build; {@code null} means the item compares with the default collation.
   */
  public void setDeclaredCollate(@Nullable Collate declaredCollate) {
    this.declaredCollate = declaredCollate;
  }

  public void setGremlinToMatchTranslatorProduced(boolean produced) {
    gremlinToMatchTranslatorProduced = produced;
  }

  public boolean isGremlinToMatchTranslatorProduced() {
    return gremlinToMatchTranslatorProduced;
  }

  @Nullable
  public Collate getDeclaredCollate() {
    return declaredCollate;
  }

  public SQLOrderByItem copy() {
    var result = new SQLOrderByItem();
    result.alias = alias;
    result.modifier = modifier == null ? null : modifier.copy();
    result.recordAttr = recordAttr;
    result.rid = rid == null ? null : rid.copy();
    result.type = type;
    result.nullOrdering = nullOrdering;
    result.collate = this.collate == null ? null : collate.copy();
    result.isEdge = this.isEdge;
    result.gremlinToMatchTranslatorProduced = this.gremlinToMatchTranslatorProduced;
    // Carried like isEdge: the SELECT planner copies the clause after resolution (the synthetic
    // ORDER BY projections rebuild it), and a lost collation would silently fall back to default.
    result.declaredCollate = this.declaredCollate;
    return result;
  }

  public void extractSubQueries(SubQueryCollector collector) {
    if (modifier != null) {
      modifier.extractSubQueries(collector);
    }
  }

  public boolean refersToParent() {
    if (alias != null && alias.equalsIgnoreCase("$parent")) {
      return true;
    }
    if (modifier != null && modifier.refersToParent()) {
      return true;
    }
    return collate != null && collate.refersToParent();
  }

  public SQLModifier getModifier() {
    return modifier;
  }

  public void setModifier(SQLModifier modifier) {
    this.modifier = modifier;
  }

  public Result serialize(DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    result.setProperty("alias", alias);
    if (modifier != null) {
      result.setProperty("modifier", modifier.serialize(session));
    }
    result.setProperty("recordAttr", recordAttr);
    if (rid != null) {
      result.setProperty("rid", rid.serialize(session));
    }
    result.setProperty("type", type);
    result.setProperty("nullOrdering", nullOrdering);
    if (collate != null) {
      result.setProperty("collate", collate.serialize(session));
    }
    return result;
  }

  public void deserialize(Result fromResult) {
    alias = fromResult.getProperty("alias");
    if (fromResult.getProperty("modifier") != null) {
      modifier = new SQLModifier(-1);
      modifier.deserialize(fromResult.getProperty("modifier"));
    }
    recordAttr = fromResult.getProperty("recordAttr");
    if (fromResult.getProperty("rid") != null) {
      rid = new SQLRid(-1);
      rid.deserialize(fromResult.getProperty("rid"));
    }
    type = DESC.equals(fromResult.getProperty("type")) ? DESC : ASC;
    var storedNullOrdering = fromResult.<String>getProperty("nullOrdering");
    if (NULLS_FIRST.equals(storedNullOrdering) || NULLS_LAST.equals(storedNullOrdering)) {
      nullOrdering = storedNullOrdering;
    } else {
      nullOrdering = null;
    }
    if (fromResult.getProperty("collate") != null) {
      collate = new SQLExpression(-1);
      collate.deserialize(fromResult.getProperty("collate"));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (SQLOrderByItem) o;

    if (!Objects.equals(alias, that.alias)) {
      return false;
    }
    if (!Objects.equals(modifier, that.modifier)) {
      return false;
    }
    if (!Objects.equals(recordAttr, that.recordAttr)) {
      return false;
    }
    if (!Objects.equals(rid, that.rid)) {
      return false;
    }
    if (!Objects.equals(type, that.type)) {
      return false;
    }
    if (!Objects.equals(nullOrdering, that.nullOrdering)) {
      return false;
    }
    return Objects.equals(collate, that.collate);
  }

  @Override
  public int hashCode() {
    var result = alias != null ? alias.hashCode() : 0;
    result = 31 * result + (modifier != null ? modifier.hashCode() : 0);
    result = 31 * result + (recordAttr != null ? recordAttr.hashCode() : 0);
    result = 31 * result + (rid != null ? rid.hashCode() : 0);
    result = 31 * result + (type != null ? type.hashCode() : 0);
    result = 31 * result + (nullOrdering != null ? nullOrdering.hashCode() : 0);
    result = 31 * result + (collate != null ? collate.hashCode() : 0);
    return result;
  }

  public SQLExpression getCollate() {
    return collate;
  }

  public void toGenericStatement(StringBuilder builder) {

    if (alias != null) {
      builder.append(alias);
      if (modifier != null) {
        modifier.toGenericStatement(builder);
      }
    } else if (recordAttr != null) {
      builder.append(recordAttr);
    } else if (rid != null) {
      rid.toGenericStatement(builder);
    }
    if (type != null) {
      builder.append(" ").append(type);
    }
    if (nullOrdering != null) {
      builder.append(" ").append(nullOrdering);
    }
    if (collate != null) {
      builder.append(" COLLATE ");
      collate.toGenericStatement(builder);
    }
  }

  public void setEdge(boolean isEdge) {
    this.isEdge = isEdge;
  }
}
