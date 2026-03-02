package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A layered MATCH result row that wraps a parent {@link Result} and adds one new alias
 * without copying all upstream properties.
 * <p>
 * In the MATCH pipeline, each traversal step produces a new result row containing all
 * previously matched aliases plus the newly matched alias. The naive approach copies every
 * upstream property into a fresh {@link HashMap}. This class avoids that copy by storing
 * only the new alias/value pair and delegating lookups for other properties to the parent.
 * <p>
 * Additional properties (e.g. depth/path aliases from WHILE traversals) are stored in the
 * inherited {@code content} map, which is lazily created on the first such write.
 * <p>
 * Properties can be removed (e.g. by {@link ReturnMatchPatternsStep} stripping default
 * aliases). Removing a parent property stores a {@link #REMOVED_SENTINEL} in the local
 * {@code content} map to shadow the parent value. Removing the {@code newAlias} sets the
 * {@link #newAliasRemoved} flag.
 */
class MatchResultRow extends ResultInternal {

  /**
   * Sentinel stored in the local {@code content} map to indicate that a parent property
   * has been removed. Identity comparison is used.
   */
  private static final Object REMOVED_SENTINEL = new Object();

  private final Result parent;
  private final String newAlias;
  private Object newValue;
  private boolean newAliasRemoved;

  /**
   * Creates a layered result row.
   *
   * @param session  the database session
   * @param parent   the upstream result row (all previously matched aliases)
   * @param newAlias the alias being added by this traversal step
   * @param newValue the value for the new alias (will be normalized via
   *                 {@link #convertPropertyValue})
   */
  MatchResultRow(
      @Nullable DatabaseSessionEmbedded session,
      Result parent,
      String newAlias,
      Object newValue) {
    super(session, true);
    this.parent = parent;
    this.newAlias = newAlias;
    this.newValue = convertPropertyValue(newValue);
  }

  @Override
  public void setProperty(@Nonnull String name, Object value) {
    assert checkSession();
    value = convertPropertyValue(value);
    if (name.equals(newAlias)) {
      newValue = value;
      newAliasRemoved = false;
      return;
    }
    if (content == null) {
      content = new HashMap<>();
    }
    content.put(name, value);
  }

  @SuppressWarnings("TypeParameterUnusedInFormals")
  @Override
  public <T> T getProperty(@Nonnull String name) {
    assert checkSession();
    if (name.equals(newAlias)) {
      //noinspection unchecked
      return newAliasRemoved ? null : (T) newValue;
    }
    if (content != null && content.containsKey(name)) {
      var val = content.get(name);
      //noinspection unchecked
      return val == REMOVED_SENTINEL ? null : (T) val;
    }
    return parent.getProperty(name);
  }

  @Override
  public boolean hasProperty(@Nonnull String propName) {
    assert checkSession();
    if (propName.equals(newAlias)) {
      return !newAliasRemoved;
    }
    if (content != null && content.containsKey(propName)) {
      return content.get(propName) != REMOVED_SENTINEL;
    }
    return parent.hasProperty(propName);
  }

  @Override
  public @Nonnull List<String> getPropertyNames() {
    assert checkSession();
    var parentNames = parent.getPropertyNames();
    int extraCapacity = 1 + (content != null ? content.size() : 0);
    var result = new ArrayList<String>(parentNames.size() + extraCapacity);

    // Add parent names, skipping any that have been removed (via REMOVED_SENTINEL
    // in content, or via newAliasRemoved when the alias shadows a parent property)
    for (var name : parentNames) {
      if (name.equals(newAlias) && newAliasRemoved) {
        continue;
      }
      if (content != null && content.get(name) == REMOVED_SENTINEL) {
        continue;
      }
      result.add(name);
    }

    // Add the new alias if not already in parent and not removed
    if (!newAliasRemoved && !parentNames.contains(newAlias)) {
      result.add(newAlias);
    }

    // Add local override keys that are new (not in parent, not newAlias, not removed)
    if (content != null) {
      for (var entry : content.entrySet()) {
        var key = entry.getKey();
        if (entry.getValue() != REMOVED_SENTINEL
            && !key.equals(newAlias)
            && !parentNames.contains(key)) {
          result.add(key);
        }
      }
    }

    return Collections.unmodifiableList(result);
  }

  @Override
  public void removeProperty(String name) {
    assert checkSession();
    if (name.equals(newAlias)) {
      newAliasRemoved = true;
      return;
    }
    // If the property exists in the parent, shadow it with the sentinel
    if (parent.hasProperty(name)) {
      if (content == null) {
        content = new HashMap<>();
      }
      content.put(name, REMOVED_SENTINEL);
    } else if (content != null) {
      content.remove(name);
    }
  }

  // --- Typed accessor overrides ---
  // The base class implementations access `content` directly instead of using
  // getProperty(), so they bypass the layered lookup. Override each to delegate
  // the initial lookup through getProperty(), then apply the same type-specific
  // post-processing as the base class.

  @Override
  public Entity getEntity(@Nonnull String name) {
    assert checkSession();
    Object result = getProperty(name);

    if (result instanceof Identifiable id) {
      checkSessionForRecords();
      var transaction = session.getActiveTransaction();
      result = transaction.loadEntity(id);
    }
    if (result instanceof Entity entity) {
      return entity;
    }
    if (result == null) {
      return null;
    }
    throw new DatabaseException("Property " + name + " is not an entity");
  }

  @Nullable
  @Override
  public Result getResult(@Nonnull String name) {
    assert checkSession();
    Object result = getProperty(name);

    if (result instanceof Result res) {
      return res;
    }
    if (result == null) {
      return null;
    }
    throw new DatabaseException("Property " + name + " is not a result.");
  }

  @Override
  public Vertex getVertex(@Nonnull String name) {
    checkSessionForRecords();
    Object result = getProperty(name);

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadVertex(id);
    }
    if (result == null) {
      return null;
    }
    throw new DatabaseException("Property " + name + " is not a vertex");
  }

  @Override
  public Edge getEdge(@Nonnull String name) {
    checkSessionForRecords();
    Object result = getProperty(name);

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadEdge(id);
    }
    if (result instanceof Edge edge) {
      return edge;
    }
    if (result == null) {
      return null;
    }
    throw new DatabaseException("Property " + name + " is not an edge");
  }

  @Override
  public Blob getBlob(@Nonnull String name) {
    checkSessionForRecords();
    Object result = getProperty(name);

    if (result instanceof Identifiable id) {
      var transaction = session.getActiveTransaction();
      return transaction.loadBlob(id);
    }
    if (result == null) {
      return null;
    }
    throw new DatabaseException("Property " + name + " is not a blob");
  }

  @Nullable
  @Override
  public RID getLink(@Nonnull String name) {
    assert checkSession();
    Object result = getProperty(name);

    return switch (result) {
      case null -> null;
      case Identifiable id -> id.getIdentity();
      default ->
          throw new IllegalStateException("Property " + name + " is not a link");
    };
  }

  // --- Identity overrides ---

  @Override
  public boolean isProjection() {
    return true;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Result other)) {
      return false;
    }
    if (other.isIdentifiable() || other.isRelation()) {
      return false;
    }
    var myNames = getPropertyNames();
    var otherNames = other.getPropertyNames();
    if (myNames.size() != otherNames.size()) {
      return false;
    }
    for (var prop : myNames) {
      if (!Objects.equals(getProperty(prop), other.getProperty(prop))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    int h = 0;
    for (var prop : getPropertyNames()) {
      Object val = getProperty(prop);
      h += prop.hashCode() ^ (val == null ? 0 : val.hashCode());
    }
    return h;
  }

  // --- Serialization / materialization overrides ---

  @Override
  public @Nonnull Map<String, Object> toMap() {
    assert checkSession();
    var map = new HashMap<String, Object>();
    // Start with parent properties, skipping removed ones
    for (var prop : parent.getPropertyNames()) {
      if (prop.equals(newAlias) && newAliasRemoved) {
        continue;
      }
      if (content != null && content.get(prop) == REMOVED_SENTINEL) {
        continue;
      }
      map.put(prop, toMapValue(parent.getProperty(prop), false));
    }
    // Add/overwrite with local overrides (excluding sentinels)
    if (content != null) {
      for (var entry : content.entrySet()) {
        if (entry.getValue() != REMOVED_SENTINEL) {
          map.put(entry.getKey(), toMapValue(entry.getValue(), false));
        }
      }
    }
    // Add/overwrite with the new alias
    if (!newAliasRemoved) {
      map.put(newAlias, toMapValue(newValue, false));
    }
    return map;
  }

  @Override
  public @Nonnull Result detach() {
    assert checkSession();
    var detached = new ResultInternal(null);
    for (var prop : getPropertyNames()) {
      Object val = getProperty(prop);
      detached.setProperty(prop, toMapValue(val, false));
    }
    return detached;
  }

  @Override
  public String toString() {
    var sb = new StringBuilder("content:{\n");
    for (var prop : getPropertyNames()) {
      sb.append(prop).append(": ").append((Object) getProperty(prop)).append('\n');
    }
    sb.append("}\n");
    return sb.toString();
  }
}
