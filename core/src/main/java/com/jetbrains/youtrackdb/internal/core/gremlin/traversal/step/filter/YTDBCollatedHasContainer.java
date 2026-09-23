package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.structure.Property;

/**
 * Applies an owning entity's declared property collation during native Gremlin filtering.
 *
 * <p>The inherited predicate remains unchanged so SQL folding and index recognition see the
 * original predicate. Only native value evaluation takes this collation-aware path.
 */
public final class YTDBCollatedHasContainer extends HasContainer {

  private static final Object UNCACHEABLE = new Object();
  private static final int MAX_PARAMETERIZED_ENTRIES = 8;
  private static final int MAX_PARAMETERIZED_POSITIONS = 1_024;
  private static final int MAX_CACHED_STRING_LENGTH = 128;

  private transient Map<Collate, Map<P<?>, CachedOperand>> transformedOperands;
  private transient Map<ParameterizedOperandKey, ParameterizedOperand> parameterizedOperands;
  private transient long parameterizedGeneration;

  public YTDBCollatedHasContainer(String key, P<?> predicate) {
    super(key, predicate);
  }

  /** Returns the original container when none of its predicate leaves support collation. */
  public static HasContainer wrap(HasContainer container) {
    if (container instanceof YTDBCollatedHasContainer
        || !supportsCollation(container.getPredicate())) {
      return container;
    }
    return new YTDBCollatedHasContainer(container.getKey(), container.getPredicate());
  }

  private static boolean supportsCollation(P<?> predicate) {
    if (predicate instanceof ConnectiveP<?> connective) {
      return connective.getPredicates().stream()
          .anyMatch(YTDBCollatedHasContainer::supportsCollation);
    }
    if (predicate instanceof NotP<?> not) {
      return supportsCollation(not.negate());
    }
    return isSupportedLeaf(predicate.getBiPredicate());
  }

  @Override
  protected boolean testValue(Property property) {
    var collate = declaredCollation(property);
    if (collate == null || DefaultCollate.NAME.equals(collate.getName())) {
      return super.testValue(property);
    }
    return evaluate(getPredicate(), property.value(), collate);
  }

  private static Collate declaredCollation(Property<?> property) {
    if (!(property.element() instanceof YTDBElementImpl element)) {
      return null;
    }
    var entity = element.getRawEntity();
    if (!(entity instanceof EntityImpl entityImpl)) {
      return null;
    }
    var schemaClass = entityImpl.getImmutableSchemaClass(entityImpl.getSession());
    if (schemaClass == null) {
      return null;
    }
    var schemaProperty = schemaClass.getPropertyInternal(property.key());
    return schemaProperty == null ? null : schemaProperty.getCollate();
  }

  boolean evaluate(P<?> predicate, Object value, Collate collate) {
    if (predicate instanceof AndP<?> and) {
      for (var child : and.getPredicates()) {
        if (!evaluate(child, value, collate)) {
          return false;
        }
      }
      return true;
    }
    if (predicate instanceof OrP<?> or) {
      for (var child : or.getPredicates()) {
        if (evaluate(child, value, collate)) {
          return true;
        }
      }
      return false;
    }
    if (predicate instanceof NotP<?> not) {
      return !evaluate(not.negate(), value, collate);
    }
    if (!isSupportedLeaf(predicate.getBiPredicate())) {
      return testRaw(predicate, value);
    }

    var transformedValue = transform(value, collate);
    var transformedOperand = transformedOperand(predicate, collate);
    if (predicate.getBiPredicate() == Compare.eq) {
      return valuesEqual(transformedValue, transformedOperand);
    }
    if (predicate.getBiPredicate() == Compare.neq) {
      return !valuesEqual(transformedValue, transformedOperand);
    }
    if (predicate.getBiPredicate() == Contains.within) {
      return contains(transformedValue, (Collection<?>) transformedOperand);
    }
    if (predicate.getBiPredicate() == Contains.without) {
      return !contains(transformedValue, (Collection<?>) transformedOperand);
    }
    return testLeaf(predicate.getBiPredicate(), transformedValue, transformedOperand);
  }

  /**
   * Parameterized membership retains bounded per-position strings. Other parameterized operands
   * stay lazy, while immutable nonparameterized shapes retain their existing snapshot cache.
   */
  Object transformedOperand(P<?> predicate, Collate collate) {
    var operand = predicate.getValue();
    if (eligibleParameterizedMembership(predicate, operand, collate)) {
      return parameterizedOperand(predicate, (List<?>) operand, collate);
    }
    clearParameterizedOperands(predicate);
    if (predicate.isParameterized()) {
      return transformUncachedOperand(predicate, operand, collate);
    }

    var operandsByPredicate = transformedOperands()
        .computeIfAbsent(collate, ignored -> new IdentityHashMap<>());
    var cached = operandsByPredicate.get(predicate);
    if (cached != null && stateMatches(cached.state(), operand)) {
      return cached.transformed();
    }

    var state = snapshotIfCacheable(operand);
    if (state == UNCACHEABLE) {
      return transformUncachedOperand(predicate, operand, collate);
    }
    cached = new CachedOperand(state, transformOperand(operand, collate));
    operandsByPredicate.put(predicate, cached);
    return cached.transformed();
  }

  private static boolean eligibleParameterizedMembership(
      P<?> predicate, Object operand, Collate collate) {
    return predicate.isParameterized()
        && predicate.getBiPredicate() instanceof Contains
        && operand instanceof List<?>
        && collate.getClass() == CaseInsensitiveCollate.class;
  }

  private Object parameterizedOperand(P<?> predicate, List<?> operand, Collate collate) {
    var key = new ParameterizedOperandKey(predicate, collate);
    var cached = findParameterizedOperand(key);
    if (cached != null) {
      cached.resize(operand.size());
    }
    return new CachedTransformingList(
        this, key, operand, collate, cached, parameterizedGeneration);
  }

  private ParameterizedOperand findParameterizedOperand(ParameterizedOperandKey key) {
    return parameterizedOperands == null ? null : parameterizedOperands.get(key);
  }

  private ParameterizedOperand retainParameterizedOperand(
      ParameterizedOperandKey key,
      int size,
      int index,
      String source,
      String normalized) {
    var cached = findParameterizedOperand(key);
    if (cached == null) {
      cached = new ParameterizedOperand(size, index, source, normalized);
      parameterizedOperands().put(key, cached);
    } else {
      cached.resize(size);
      cached.retain(index, source, normalized);
    }
    return cached;
  }

  private Map<ParameterizedOperandKey, ParameterizedOperand> parameterizedOperands() {
    if (parameterizedOperands == null) {
      parameterizedOperands = new LinkedHashMap<>(MAX_PARAMETERIZED_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            Map.Entry<ParameterizedOperandKey, ParameterizedOperand> eldest) {
          var remove = size() > MAX_PARAMETERIZED_ENTRIES;
          if (remove) {
            eldest.getValue().deactivate();
          }
          return remove;
        }
      };
    }
    return parameterizedOperands;
  }

  private void clearParameterizedOperands(P<?> predicate) {
    // Views capture this generation, so even an unconsumed view cannot restore obsolete state.
    parameterizedGeneration++;
    if (parameterizedOperands != null) {
      var iterator = parameterizedOperands.entrySet().iterator();
      while (iterator.hasNext()) {
        var entry = iterator.next();
        if (entry.getKey().predicate == predicate) {
          entry.getValue().deactivate();
          iterator.remove();
        }
      }
    }
  }

  private Map<Collate, Map<P<?>, CachedOperand>> transformedOperands() {
    if (transformedOperands == null) {
      transformedOperands = new IdentityHashMap<>();
    }
    return transformedOperands;
  }

  private static boolean stateMatches(Object state, Object value) {
    if (state instanceof Set<?> stateSet && value instanceof Set<?> valueSet) {
      return stateSet.equals(valueSet);
    }
    if (state instanceof Collection<?> stateCollection
        && value instanceof Collection<?> valueCollection) {
      if ((state instanceof Set<?>) != (value instanceof Set<?>)) {
        return false;
      }
      if (stateCollection.size() != valueCollection.size()) {
        return false;
      }
      var stateIterator = stateCollection.iterator();
      var valueIterator = valueCollection.iterator();
      while (stateIterator.hasNext()) {
        if (!stateMatches(stateIterator.next(), valueIterator.next())) {
          return false;
        }
      }
      return true;
    }
    return Objects.equals(state, value);
  }

  private static Object snapshotIfCacheable(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean
        || value instanceof Character || value instanceof Enum<?>) {
      return value;
    }
    if (value instanceof Collection<?> collection) {
      Collection<Object> snapshot = value instanceof Set<?> ? new HashSet<>() : new ArrayList<>();
      for (var member : collection) {
        var memberSnapshot = snapshotIfCacheable(member);
        if (memberSnapshot == UNCACHEABLE) {
          return UNCACHEABLE;
        }
        snapshot.add(memberSnapshot);
      }
      return snapshot;
    }
    return UNCACHEABLE;
  }

  /**
   * Membership only iterates its operand, so an uncached set can use a collection view safely.
   * Equality still materializes sets to preserve transformed uniqueness and cardinality.
   */
  private static Object transformUncachedOperand(P<?> predicate, Object value, Collate collate) {
    if (predicate.getBiPredicate() instanceof Contains && value instanceof Set<?> set
        && collate instanceof CaseInsensitiveCollate) {
      return new TransformingCollection(set, collate);
    }
    return transform(value, collate);
  }

  // Cacheable operand collections materialize eagerly so repeated candidate evaluation does not
  // repeat transformations. Uncached membership operands and property lists retain lazy views.
  private static Object transformOperand(Object value, Collate collate) {
    if (!(collate instanceof CaseInsensitiveCollate)) {
      return collate.transform(value);
    }
    if (value instanceof Set<?> set) {
      var transformed = new HashSet<>();
      for (var member : set) {
        transformed.add(transformOperand(member, collate));
      }
      return transformed;
    }
    if (value instanceof Collection<?> collection) {
      var transformed = new ArrayList<>();
      for (var member : collection) {
        transformed.add(transformOperand(member, collate));
      }
      return transformed;
    }
    return collate.transform(value);
  }

  private static boolean isSupportedLeaf(BiPredicate<?, ?> predicate) {
    return predicate instanceof Compare || predicate instanceof Contains
        || predicate instanceof Text;
  }

  @SuppressWarnings("unchecked")
  private static boolean testRaw(P<?> predicate, Object value) {
    return ((P<Object>) predicate).test(value);
  }

  static boolean valuesEqual(Object first, Object second) {
    // Compare.eq first checks list element compatibility, potentially scanning corresponding elements.
    // Recursion avoids that pre-scan and stops lazy transformations at the first unequal element.
    if (first instanceof List<?> firstList && second instanceof List<?> secondList) {
      if (firstList.size() != secondList.size()) {
        return false;
      }
      for (var index = 0; index < firstList.size(); index++) {
        if (!valuesEqual(firstList.get(index), secondList.get(index))) {
          return false;
        }
      }
      return true;
    }
    return Compare.eq.test(first, second);
  }

  private static boolean contains(Object value, Collection<?> candidates) {
    for (var candidate : candidates) {
      if (valuesEqual(value, candidate)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static boolean testLeaf(BiPredicate<?, ?> predicate, Object value, Object operand) {
    return ((BiPredicate<Object, Object>) predicate).test(value, operand);
  }

  /**
   * Uses lazy views for case-insensitive lists and membership collections.
   * Set transformation remains eager because case folding can collapse distinct members.
   */
  static Object transform(Object value, Collate collate) {
    if (!(collate instanceof CaseInsensitiveCollate)) {
      return collate.transform(value);
    }
    if (value instanceof Set<?> set) {
      var transformed = new HashSet<>();
      for (var member : set) {
        transformed.add(transform(member, collate));
      }
      return transformed;
    }
    if (value instanceof List<?> list) {
      return new TransformingList(list, collate);
    }
    if (value instanceof Collection<?> collection) {
      return new TransformingCollection(collection, collate);
    }
    return collate.transform(value);
  }

  @Override
  public YTDBCollatedHasContainer clone() {
    var clone = (YTDBCollatedHasContainer) super.clone();
    clone.transformedOperands = null;
    clone.parameterizedOperands = null;
    clone.parameterizedGeneration = 0;
    return clone;
  }

  private record CachedOperand(Object state, Object transformed) {
  }

  private static final class ParameterizedOperandKey {

    private final P<?> predicate;
    private final Collate collate;

    private ParameterizedOperandKey(P<?> predicate, Collate collate) {
      this.predicate = predicate;
      this.collate = collate;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ParameterizedOperandKey key
          && predicate == key.predicate
          && collate == key.collate;
    }

    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(predicate) + System.identityHashCode(collate);
    }
  }

  private static final class ParameterizedOperand {

    private Map<Integer, ParameterizedSlot> slots = new HashMap<>();
    private int observedSize;
    private boolean active = true;

    private ParameterizedOperand(
        int size, int index, String source, String normalized) {
      observedSize = size;
      retain(index, source, normalized);
    }

    private Object transform(int index, Object source, Collate collate) {
      if (index >= MAX_PARAMETERIZED_POSITIONS || !(source instanceof String sourceString)) {
        slots.remove(index);
        return YTDBCollatedHasContainer.transform(source, collate);
      }

      var slot = slots.get(index);
      if (slot != null && slot.source.equals(sourceString)) {
        return slot.normalized;
      }

      var normalized = YTDBCollatedHasContainer.transform(sourceString, collate);
      if (cacheable(sourceString, normalized)) {
        retain(index, sourceString, (String) normalized);
      } else {
        slots.remove(index);
      }
      return normalized;
    }

    private void retain(int index, String source, String normalized) {
      slots.put(index, new ParameterizedSlot(source, normalized));
    }

    private void resize(int size) {
      if (size < observedSize) {
        slots.keySet().removeIf(index -> index >= size);
      }
      observedSize = size;
    }

    private void deactivate() {
      active = false;
      slots.clear();
    }
  }

  private static boolean cacheable(String source, Object normalized) {
    return source.length() <= MAX_CACHED_STRING_LENGTH
        && normalized instanceof String normalizedString
        && normalizedString.length() <= MAX_CACHED_STRING_LENGTH;
  }

  private record ParameterizedSlot(String source, String normalized) {
  }

  private static final class CachedTransformingList extends AbstractList<Object> {

    private final YTDBCollatedHasContainer owner;
    private final ParameterizedOperandKey key;
    private final List<?> source;
    private final Collate collate;
    private final long generation;
    private ParameterizedOperand cached;

    private CachedTransformingList(
        YTDBCollatedHasContainer owner,
        ParameterizedOperandKey key,
        List<?> source,
        Collate collate,
        ParameterizedOperand cached,
        long generation) {
      this.owner = owner;
      this.key = key;
      this.source = source;
      this.collate = collate;
      this.cached = cached;
      this.generation = generation;
    }

    @Override
    public Object get(int index) {
      var member = source.get(index);
      if (generation != owner.parameterizedGeneration) {
        return YTDBCollatedHasContainer.transform(member, collate);
      }
      var size = source.size();
      if (cached != null && !cached.active) {
        cached = null;
      }
      if (cached == null) {
        cached = owner.findParameterizedOperand(key);
      }
      if (cached != null) {
        cached.resize(size);
        return cached.transform(index, member, collate);
      }

      var normalized = YTDBCollatedHasContainer.transform(member, collate);
      if (index < MAX_PARAMETERIZED_POSITIONS
          && member instanceof String sourceString
          && cacheable(sourceString, normalized)) {
        cached = owner.retainParameterizedOperand(
            key, size, index, sourceString, (String) normalized);
      }
      return normalized;
    }

    @Override
    public int size() {
      return source.size();
    }
  }

  private static final class TransformingList extends AbstractList<Object> {

    private final List<?> source;
    private final Collate collate;

    private TransformingList(List<?> source, Collate collate) {
      this.source = source;
      this.collate = collate;
    }

    @Override
    public Object get(int index) {
      return transform(source.get(index), collate);
    }

    @Override
    public int size() {
      return source.size();
    }
  }

  private static final class TransformingCollection extends AbstractCollection<Object> {

    private final Collection<?> source;
    private final Collate collate;

    private TransformingCollection(Collection<?> source, Collate collate) {
      this.source = source;
      this.collate = collate;
    }

    @Override
    public Iterator<Object> iterator() {
      var sourceIterator = source.iterator();
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return sourceIterator.hasNext();
        }

        @Override
        public Object next() {
          return transform(sourceIterator.next(), collate);
        }
      };
    }

    @Override
    public int size() {
      return source.size();
    }
  }
}
