package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.NotP;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/**
 * Length-prefixed token writer and positional-parameter harvest for the pre-walk translation-cache
 * key. Recognisers append structural step tokens while extraction supplies the boundary class and
 * fold position for each has contribution. Comparison values stay out of the key (only their runtime
 * class and prefix layout enter) so they rebound as {@code ?}.
 *
 * <p>Every variable-length token is length-prefixed, matching {@link GremlinPlanFingerprint}, so a
 * user identifier cannot forge another walk's key by embedding delimiters.
 */
final class FrozenTextEncoder {

  private final StringBuilder sb = new StringBuilder(256);

  private final LinkedHashMap<Object, Object> bindings = new LinkedHashMap<>();

  @Nullable private final Schema schema;

  private final ParamSink sink = this::bindParam;

  private boolean complete = true;

  private HasBindingContext hasBindingContext;

  private final List<HasBindingContext.Slot> hasSlots = new ArrayList<>();

  private final List<HasBindingContext.Contribution> hasContributions = new ArrayList<>();

  void setHasBindingContext(HasBindingContext context) {
    hasBindingContext = context;
  }

  HasBindingContext hasBindingContext() {
    return hasBindingContext;
  }

  int hasSlotCount() {
    return hasSlots.size();
  }

  void recordHasSlot(HasBindingContext.Slot slot) {
    hasSlots.add(slot);
  }

  void recordHasContribution(HasBindingContext context, int fromSlot) {
    hasContributions.add(new HasBindingContext.Contribution(context,
        hasSlots.subList(fromSlot, hasSlots.size())));
  }

  List<HasBindingContext.Contribution> hasContributions() {
    return List.copyOf(hasContributions);
  }

  FrozenTextEncoder(@Nullable Schema schema) {
    this.schema = schema;
  }

  @Nullable Schema schema() {
    return schema;
  }

  ParamSink paramSink() {
    return sink;
  }

  boolean complete() {
    return complete;
  }

  /**
   * Marks this extraction unsafe to cache as {@code Translate}. An unknown lambda modulator or a
   * recogniser that cannot prove its encoding is complete takes this path; {@code apply} then walks
   * and does not store a template.
   */
  void markIncomplete() {
    complete = false;
  }

  @Nonnull
  String key() {
    return sb.toString();
  }

  @Nonnull
  Map<Object, Object> bindings() {
    return Map.copyOf(bindings);
  }

  void appendToken(String token) {
    sb.append(token.length()).append(':').append(token);
  }

  void appendToken(String label, String token) {
    sb.append(label).append(':');
    appendToken(token);
  }

  void appendStringSeq(String label, String[] keys) {
    sb.append(label).append(':').append(keys.length).append(':');
    for (String key : keys) {
      appendToken(key == null ? "" : key);
    }
  }

  void appendStringSeq(String label, Collection<String> keys) {
    sb.append(label).append(':').append(keys.size()).append(':');
    for (String key : keys) {
      appendToken(key == null ? "" : key);
    }
  }

  /**
   * Encodes a predicate's operator tree. Comparison values contribute only their runtime class
   * unless {@code valuesAreStructural} is set. Label names, ids and {@code where(P)} labels are
   * typed pattern structure, not rebound slots.
   */
  void appendPredicate(@Nullable P<?> predicate, boolean valuesAreStructural) {
    if (predicate == null) {
      appendToken("P", "-");
      return;
    }
    if (predicate instanceof NotP<?> notP) {
      // NotP has no public getter for the wrapped predicate; negate() returns it (see
      // GremlinPredicateAdapter.translate).
      sb.append("NOT:");
      appendPredicate(notP.negate(), valuesAreStructural);
      return;
    }
    if (predicate instanceof AndP<?> andP) {
      sb.append("AND:");
      appendPredicateList(andP.getPredicates(), valuesAreStructural);
      return;
    }
    if (predicate instanceof OrP<?> orP) {
      sb.append("OR:");
      appendPredicateList(orP.getPredicates(), valuesAreStructural);
      return;
    }
    var bi = predicate.getBiPredicate();
    appendToken("bi", bi == null ? "-" : bi.getClass().getName());
    if (bi != null) {
      // RegexPredicate.toString embeds the pattern. Its compiled matcher is reconstructed at
      // splice, so only the operator and negation belong in the structural key.
      appendToken(bi instanceof Text.RegexPredicate ? "regex" : String.valueOf(bi));
    }
    // Text.regex and Text.notRegex share RegexPredicate; toString does not include isNegate(),
    // so a cached regex plan would be spliced onto notRegex (TinkerPop Has.feature: marko only
    // instead of everyone else). The walker reads the flag in translateRegex.
    if (bi instanceof Text.RegexPredicate regex) {
      appendToken("neg", regex.isNegate() ? "1" : "0");
    }
    if (!valuesAreStructural && (bi == Text.startingWith || bi == Text.notStartingWith)
        && predicate.getValue() instanceof String prefix) {
      // Two range slots cannot share a cached plan with the strict, one-slot form. A prefix
      // consisting only of maximum code points has no finite bound, just like an empty prefix.
      appendToken("prefixRange",
          GremlinPredicateAdapter.finitePrefixUpperBound(prefix) == null ? "0" : "1");
    }
    if (valuesAreStructural) {
      appendStructuralValue(predicate.getValue());
    } else {
      appendValueClass(predicate.getValue());
    }
  }

  /** The built-plan key uses the same value-independent predicate encoding as extraction. */
  static String predicateStructure(P<?> predicate, boolean valuesAreStructural) {
    var encoder = new FrozenTextEncoder(null);
    encoder.appendPredicate(predicate, valuesAreStructural);
    return encoder.key();
  }

  private void appendPredicateList(List<? extends P<?>> children, boolean valuesAreStructural) {
    sb.append(children.size()).append(':');
    for (var child : children) {
      appendPredicate(child, valuesAreStructural);
    }
  }

  void appendStructuralValue(@Nullable Object value) {
    appendStructuralValue(value, new IdentityHashMap<>());
  }

  private void appendStructuralValue(@Nullable Object value,
      IdentityHashMap<Collection<?>, Boolean> activeCollections) {
    // A value that prints like a String must not reuse an accepted String-label plan. Include the
    // type at every level so null, scalar values and nested collections also stay distinct.
    appendToken("sc", value == null ? "N" : value.getClass().getName());
    if (value instanceof Collection<?> collection) {
      if (activeCollections.put(collection, Boolean.TRUE) != null) {
        // Cyclic values have no finite predicate encoding. Bypass the cache and let native handle
        // the value rather than overflowing during the pre-walk extraction.
        markIncomplete();
        appendToken("cycle");
        return;
      }
      sb.append("C:").append(collection.size()).append(':');
      for (var element : collection) {
        appendStructuralValue(element, activeCollections);
      }
      activeCollections.remove(collection);
      return;
    }
    appendToken(String.valueOf(value));
  }

  void appendValueClass(@Nullable Object value) {
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
    // Harvest consumes only values and slot order, never the parameter AST.
    return null;
  }

  // The adapter routes recogniser calls to the frozen builder. No encoder behaviour is inherited.
  private final GremlinShapeEncoder bridge = new GremlinShapeEncoder(null) {
    @Override
    void appendToken(String token) {
      FrozenTextEncoder.this.appendToken(token);
    }

    @Override
    void appendToken(String label, String token) {
      FrozenTextEncoder.this.appendToken(label, token);
    }

    @Override
    void appendStringSeq(String label, String[] keys) {
      FrozenTextEncoder.this.appendStringSeq(label, keys);
    }

    @Override
    void appendStringSeq(String label, Collection<String> keys) {
      FrozenTextEncoder.this.appendStringSeq(label, keys);
    }

    @Override
    void appendPredicate(P<?> predicate, boolean structural) {
      FrozenTextEncoder.this.appendPredicate(predicate, structural);
    }

    @Override
    void appendStructuralValue(Object value) {
      FrozenTextEncoder.this.appendStructuralValue(value);
    }

    @Override
    void appendValueClass(Object value) {
      FrozenTextEncoder.this.appendValueClass(value);
    }

    @Override
    void markIncomplete() {
      FrozenTextEncoder.this.markIncomplete();
    }

    @Override
    Schema schema() {
      return FrozenTextEncoder.this.schema();
    }

    @Override
    ParamSink paramSink() {
      return FrozenTextEncoder.this.paramSink();
    }

    @Override
    boolean complete() {
      return FrozenTextEncoder.this.complete();
    }

    @Override
    void setHasBindingContext(HasBindingContext context) {
      FrozenTextEncoder.this.setHasBindingContext(context);
    }

    @Override
    HasBindingContext hasBindingContext() {
      return FrozenTextEncoder.this.hasBindingContext();
    }

    @Override
    int hasSlotCount() {
      return FrozenTextEncoder.this.hasSlotCount();
    }

    @Override
    void recordHasSlot(HasBindingContext.Slot slot) {
      FrozenTextEncoder.this.recordHasSlot(slot);
    }

    @Override
    void recordHasContribution(HasBindingContext context, int fromSlot) {
      FrozenTextEncoder.this.recordHasContribution(context, fromSlot);
    }
  };

  boolean contribute(StepRecogniser recogniser, Step<?, ?> step) {
    return step instanceof HasStep<?> ? contributeHas(step, this)
        : recogniser.contributeShape(step, bridge);
  }

  private boolean contributeHas(Step<?, ?> step, FrozenTextEncoder encoder) {
    if (!(step instanceof HasStep<?> hasStep)) {
      return false;
    }
    var containers = hasStep.getHasContainers();
    encoder.appendToken("H", Integer.toString(containers.size()));
    var context = encoder.hasBindingContext();
    if (context == null) {
      context = HasBindingContext.forVertex(HasStepRecogniser.labelNames(containers), null, false,
          HasBindingContext.Destination.MATCH_VERTEX);
    }
    GremlinPredicateAdapter.PropertyTypeGate typeGate = null;
    for (int index = 0; index < containers.size(); index++) {
      HasContainer container = containers.get(index);
      var key = container.getKey();
      if (org.apache.tinkerpop.gremlin.structure.T.label.getAccessor().equals(key)) {
        encoder.appendToken("lab");
        encoder.appendPredicate(container.getPredicate(), true);
        continue;
      }
      if (org.apache.tinkerpop.gremlin.structure.T.id.getAccessor().equals(key)) {
        encoder.appendToken("id");
        encoder.appendPredicate(container.getPredicate(), true);
        continue;
      }
      encoder.appendToken(key == null ? "" : key);
      encoder.appendPredicate(container.getPredicate(), false);
      int containerIndex = index;
      // Ordered-filter slots describe the native predicate's layout, but never enter MATCH's
      // positional map. Otherwise a later MATCH HasStep would be bound at the wrong SQL slot.
      if (typeGate == null) {
        typeGate = context.gate(encoder.schema());
      }
      // Harvest ignores the returned parameter. The native layout uses roles, not SQL indexes.
      ParamSink sink = context.destination() == HasBindingContext.Destination.ORDERED_FILTER
          ? value -> null : encoder.paramSink();
      GremlinPredicateAdapter.INSTANCE.bindParams(container, typeGate,
          GremlinPredicateAdapter.withRoles(sink,
              role -> encoder.recordHasSlot(new HasBindingContext.Slot(containerIndex, role))),
          !context.folded());
    }
    return true;
  }

}
