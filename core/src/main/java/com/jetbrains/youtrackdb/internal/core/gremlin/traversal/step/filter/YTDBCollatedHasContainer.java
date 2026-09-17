package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.step.filter;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

  private transient Map<Collate, Map<P<?>, Object>> transformedOperands = new HashMap<>();

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
    if (collate == null || "default".equals(collate.getName())) {
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

  private boolean evaluate(P<?> predicate, Object value, Collate collate) {
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

  private Object transformedOperand(P<?> predicate, Collate collate) {
    if (predicate.isParameterized()) {
      return transform(predicate.getValue(), collate);
    }
    return transformedOperands
        .computeIfAbsent(collate, ignored -> new HashMap<>())
        .computeIfAbsent(predicate, ignored -> transform(predicate.getValue(), collate));
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
    clone.transformedOperands = new HashMap<>();
    return clone;
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
