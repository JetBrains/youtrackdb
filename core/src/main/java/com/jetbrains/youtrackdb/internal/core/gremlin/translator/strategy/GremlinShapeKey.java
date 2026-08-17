package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.YTDBStrategyUtil;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.ProductiveByStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.EdgeLabelVerificationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.structure.T;

/**
 * Cheap structural key for the Gremlin-to-MATCH translation cache, plus a binding harvest that
 * follows the walker's bind order. Values stay out of the key (only their runtime class) so
 * {@code has("age", 30)} and {@code has("age", 40)} share an entry; class names, {@code as()}
 * labels, limit/skip literals, and comparability-block classes stay in so distinct plans cannot
 * collide. Every variable-length token is length-prefixed, matching {@link GremlinPlanFingerprint}.
 *
 * <p>Harvest walks the same L→R step list and {@link TraversalParent} children the walker does,
 * and binds through {@link GremlinPredicateAdapter} so {@code startingWith} on a declared STRING
 * still allocates two slots. A harvested size that disagrees with the cached template's
 * {@code bindingCount} is a safety valve: {@code apply} falls through to a full walk.
 */
final class GremlinShapeKey {

  private static final String LABEL_KEY = T.label.getAccessor();

  private static final String ID_KEY = T.id.getAccessor();

  private static final Set<Class<?>> TRANSPARENT_STEPS = Set.of(NoOpBarrierStep.class);

  private GremlinShapeKey() {
    // Static helper — no instances.
  }

  /**
   * One pass over {@code traversal}: the structural key and the positional bindings this invocation
   * would install on a translation-cache hit.
   */
  static Extraction extract(
      @Nonnull Traversal.Admin<?, ?> traversal, @Nonnull DatabaseSessionEmbedded session) {
    var encoder = new Encoder(session.getSchema());
    Boolean polymorphic = YTDBStrategyUtil.isPolymorphic(traversal);
    encoder.appendToken("poly", polymorphic == null ? "n" : (polymorphic ? "1" : "0"));
    encoder.appendToken(
        "elv",
        traversal.getStrategies().getStrategy(EdgeLabelVerificationStrategy.class).isPresent()
            ? "1"
            : "0");
    var productiveKeys =
        traversal
            .getStrategies()
            .getStrategy(ProductiveByStrategy.class)
            .map(ProductiveByStrategy::getProductiveKeys)
            .orElse(null);
    if (productiveKeys == null) {
      encoder.appendToken("pb", "-");
    } else {
      encoder.sb.append("pb:");
      encoder.appendToken(Integer.toString(productiveKeys.size()));
      for (String key : new TreeSet<>(productiveKeys)) {
        encoder.appendToken(key);
      }
    }
    encoder.visit(traversal);
    return new Extraction(encoder.sb.toString(), Map.copyOf(encoder.bindings));
  }

  record Extraction(@Nonnull String key, @Nonnull Map<Object, Object> bindings) {
  }

  private static final class Encoder {

    private final StringBuilder sb = new StringBuilder(256);

    private final LinkedHashMap<Object, Object> bindings = new LinkedHashMap<>();

    @Nullable private final Schema schema;

    private final ParamSink sink = this::bindParam;

    Encoder(@Nullable Schema schema) {
      this.schema = schema;
    }

    void visit(Traversal.Admin<?, ?> traversal) {
      sb.append("T:");
      sb.append(traversal.getSteps().size()).append(':');
      for (Step<?, ?> step : traversal.getSteps()) {
        if (TRANSPARENT_STEPS.contains(step.getClass())) {
          continue;
        }
        appendStep(step);
        if (step instanceof TraversalParent parent) {
          for (var child : parent.getLocalChildren()) {
            visit(child.asAdmin());
          }
          for (var child : parent.getGlobalChildren()) {
            visit(child.asAdmin());
          }
        }
      }
    }

    private void appendStep(Step<?, ?> step) {
      sb.append("S:");
      appendToken(step.getClass().getName());
      var labels = GremlinStepLabels.userLabels(step);
      sb.append("L:").append(labels.size()).append(':');
      for (String label : labels) {
        appendToken(label);
      }
      switch (step) {
        case GraphStep<?, ?> graphStep -> appendGraphStep(graphStep);
        case HasStep<?> hasStep -> appendHasStep(hasStep);
        case VertexStepContract<?> vertexStep -> appendVertexStep(vertexStep);
        case RangeGlobalStepContract<?> range -> appendRange(range);
        case OrderGlobalStep<?, ?> orderStep -> appendOrder(orderStep);
        case FoldStep<?, ?> foldStep -> appendToken("fold", foldStep.isListFold() ? "1" : "0");
        case PropertiesStep<?> propertiesStep -> appendProperties(propertiesStep);
        default -> {
          // Class name + labels + child traversals already distinguish the remaining registered
          // steps (count, group, select, union, not, …). Unregistered steps decline; sharing one
          // Decline entry per class is correct.
        }
      }
    }

    private void appendGraphStep(GraphStep<?, ?> graphStep) {
      appendToken("gv", graphStep.returnsVertex() ? "1" : "0");
      var ids = graphStep.getIds();
      appendToken("ids", Integer.toString(ids == null ? 0 : ids.length));
    }

    private void appendVertexStep(VertexStepContract<?> vertexStep) {
      appendToken("dir", vertexStep.getDirection().name());
      appendToken("re", vertexStep.returnsEdge() ? "1" : "0");
      var labels = vertexStep.getEdgeLabels();
      sb.append("el:").append(labels.length).append(':');
      for (String label : labels) {
        appendToken(label);
      }
    }

    private void appendRange(RangeGlobalStepContract<?> range) {
      appendToken("lo", String.valueOf(range.getLowRange()));
      appendToken("hi", String.valueOf(range.getHighRange()));
    }

    private void appendOrder(OrderGlobalStep<?, ?> orderStep) {
      var comparators = orderStep.getComparators();
      sb.append("ord:").append(comparators.size()).append(':');
      for (var pair : comparators) {
        appendToken(String.valueOf(pair.getValue1()));
      }
    }

    private void appendProperties(PropertiesStep<?> propertiesStep) {
      appendToken("rt", propertiesStep.getReturnType().name());
      var keys = propertiesStep.getPropertyKeys();
      sb.append("pk:").append(keys.length).append(':');
      for (String key : keys) {
        appendToken(key);
      }
    }

    private void appendHasStep(HasStep<?> hasStep) {
      var containers = hasStep.getHasContainers();
      sb.append("H:").append(containers.size()).append(':');
      String typeClass = null;
      for (HasContainer container : containers) {
        if (LABEL_KEY.equals(container.getKey()) && container.getValue() instanceof String name) {
          typeClass = name;
        }
      }
      final var labelClass = typeClass;
      GremlinPredicateAdapter.PropertyTypeGate typeGate =
          key -> isDeclaredString(schema, labelClass, key);
      for (HasContainer container : containers) {
        var key = container.getKey();
        if (LABEL_KEY.equals(key)) {
          appendToken("lab");
          appendStructuralValue(container.getValue());
          continue;
        }
        if (ID_KEY.equals(key)) {
          appendToken("id");
          appendToken(Integer.toString(idCardinality(container.getValue())));
          continue;
        }
        appendToken(key == null ? "" : key);
        appendPredicate(container.getPredicate());
        GremlinPredicateAdapter.INSTANCE.toFilter(container, typeGate, sink, true);
      }
    }

    private void appendPredicate(@Nullable P<?> predicate) {
      if (predicate == null) {
        appendToken("P", "-");
        return;
      }
      if (predicate instanceof NotP<?> notP) {
        // NotP has no public getter for the wrapped predicate; negate() returns it (see
        // GremlinPredicateAdapter.translate).
        sb.append("NOT:");
        appendPredicate(notP.negate());
        return;
      }
      if (predicate instanceof AndP<?> andP) {
        sb.append("AND:");
        appendPredicateList(andP.getPredicates());
        return;
      }
      if (predicate instanceof OrP<?> orP) {
        sb.append("OR:");
        appendPredicateList(orP.getPredicates());
        return;
      }
      var bi = predicate.getBiPredicate();
      appendToken("bi", bi == null ? "-" : bi.getClass().getName());
      if (bi != null) {
        appendToken(String.valueOf(bi));
      }
      appendValueClass(predicate.getValue());
    }

    private void appendPredicateList(List<? extends P<?>> children) {
      sb.append(children.size()).append(':');
      for (var child : children) {
        appendPredicate(child);
      }
    }

    /**
     * Label strings (and similar structural tokens) stay verbatim in the key. Predicate comparison
     * values do not go through here — only {@link #appendValueClass}.
     */
    private void appendStructuralValue(@Nullable Object value) {
      if (value instanceof Collection<?> collection) {
        sb.append("C:").append(collection.size()).append(':');
        for (var element : collection) {
          appendToken(String.valueOf(element));
        }
        return;
      }
      appendToken(String.valueOf(value));
    }

    private void appendValueClass(@Nullable Object value) {
      if (value == null) {
        appendToken("vc", "N");
        return;
      }
      if (value instanceof Collection<?> collection) {
        sb.append("VC:").append(collection.size()).append(':');
        for (var element : collection) {
          appendToken(element == null ? "N" : element.getClass().getName());
        }
        return;
      }
      appendToken("vc", value.getClass().getName());
    }

    private SQLPositionalParameter bindParam(Object value) {
      var slot = bindings.size();
      bindings.put(slot, value);
      return SQLPositionalParameter.forSlot(slot);
    }

    void appendToken(String token) {
      sb.append(token.length()).append(':').append(token);
    }

    void appendToken(String label, String token) {
      sb.append(label).append(':');
      appendToken(token);
    }
  }

  private static int idCardinality(@Nullable Object value) {
    if (value instanceof Collection<?> collection) {
      return collection.size();
    }
    return value == null ? 0 : 1;
  }

  private static boolean isDeclaredString(
      @Nullable Schema schema, @Nullable String className, String propertyKey) {
    if (schema == null || className == null || propertyKey == null) {
      return false;
    }
    var clazz = schema.getClass(className);
    if (clazz == null) {
      return false;
    }
    var property = clazz.getProperty(propertyKey);
    return property != null && property.getType() == PropertyType.STRING;
  }
}
