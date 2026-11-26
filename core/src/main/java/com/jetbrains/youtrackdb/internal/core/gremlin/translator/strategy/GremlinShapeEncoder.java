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
import org.apache.tinkerpop.gremlin.process.traversal.Text;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;

/**
 * Structural token writer with deferred positional-parameter harvest for the translation-cache
 * key. Recognisers append structural step tokens while extraction supplies the boundary class and
 * fold position for each has contribution. Comparison values stay out of the key (only their runtime
 * class and prefix layout enter) so they rebound as {@code ?}.
 *
 * <p>Tokens keep explicit boundaries. Text rendering uses length prefixes for predicate
 * fingerprints. Deferred harvest actions belong to this invocation and never enter a shared key.
 */
class GremlinShapeEncoder {

  private final List<Object> tokens;
  final GremlinShapeBuffer buffer;
  private final long leaseEpoch;

  private void requireLease() {
    if (buffer != null) {
      buffer.requireLease(leaseEpoch);
    }
  }

  private GremlinShapeKey frozenKey;
  java.util.function.BiConsumer<
      org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer,
      Integer> containerObserver;

  void recordContainer(
      org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer container, int index) {
    if (buffer != null
        && container.getClass()
            != org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer.class) {
      throw GremlinShapeBuffer.UNSUPPORTED;
    }
    if (containerObserver != null) {
      containerObserver.accept(container, index);
    }
  }

  Object lookupKey() {
    requireLease();
    return buffer == null ? compactKey() : buffer;
  }

  private final List<Runnable> harvest;

  List<NativeHasOperands> nativeOperands;
  boolean orderedExpandAbsent;
  int propertyMapBulkingCapacity;
  // Only private extraction supplies deferred checkpoints. Production keeps the shared empty list.
  List<GremlinShapeExtractor.BindingEnd> rootEnds = List.of();

  GremlinShapeExtractor.Extraction extraction() {
    GremlinShapeBuffer.count(counters -> counters.extractions++);
    return new GremlinShapeExtractor.Extraction(buffer == null ? compactKey() : null, Map.of(),
        complete, List.of(), nativeOperands, orderedExpandAbsent, propertyMapBulkingCapacity, this,
        rootEnds);
  }

  private List<NativeHasOperands> frozenNativeOperands;

  List<NativeHasOperands> nativeOperands(List<NativeHasOperands> operands) {
    harvest();
    if (frozenNativeOperands == null) {
      frozenNativeOperands = List.copyOf(operands);
    }
    return frozenNativeOperands;
  }

  private boolean harvested;
  private Map<Object, Object> frozenBindings;
  private List<HasBindingContext.Contribution> frozenContributions;

  void deferHarvest(Runnable action) {
    if (buffer == null) {
      harvest.add(action);
    } else {
      buffer.defer(action);
    }
  }

  void harvest() {
    requireLease();
    if (!harvested) {
      harvested = true;
      if (buffer == null) {
        harvest.forEach(Runnable::run);
        harvest.clear();
      } else {
        buffer.replay();
      }
      frozenBindings = bindings == null ? Map.of() : Map.copyOf(bindings);
      frozenContributions = hasContributions == null ? List.of() : List.copyOf(hasContributions);
    }
  }

  boolean harvested() {
    return harvested;
  }

  GremlinShapeKey compactKey() {
    requireLease();
    if (frozenKey == null) {
      frozenKey = buffer == null ? new GremlinShapeKey(tokens) : buffer.freeze();
    }
    return frozenKey;
  }

  private void appendRaw(String token) {
    frozenKey = null;
    if (buffer == null) {
      tokens.add(token);
    } else {
      buffer.append(token);
    }
  }

  private void appendCount(int count) {
    frozenKey = null;
    if (buffer == null) {
      tokens.add(count);
    } else {
      buffer.append(count);
    }
  }

  @Nullable private LinkedHashMap<Object, Object> bindings;

  @Nullable private final Schema schema;

  private final ParamSink sink = this::bindParam;

  private boolean complete = true;

  @Nullable private final RuntimeRidStartEligibility.EligibleStart runtimeRidStart;

  private HasBindingContext hasBindingContext;

  private List<HasBindingContext.Slot> hasSlots;

  private List<HasBindingContext.Contribution> hasContributions;

  void setHasBindingContext(HasBindingContext context) {
    hasBindingContext = context;
  }

  HasBindingContext hasBindingContext() {
    return hasBindingContext;
  }

  int bindingCount() {
    return bindings == null ? 0 : bindings.size();
  }

  int contributionCount() {
    return hasContributions == null ? 0 : hasContributions.size();
  }

  int hasSlotCount() {
    return hasSlots == null ? 0 : hasSlots.size();
  }

  void recordHasSlot(HasBindingContext.Slot slot) {
    if (hasSlots == null) {
      hasSlots = new ArrayList<>();
    }
    hasSlots.add(slot);
  }

  void recordHasContribution(HasBindingContext context, int fromSlot) {
    if (hasContributions == null) {
      hasContributions = new ArrayList<>();
    }
    GremlinShapeBuffer.count(counters -> counters.contributions++);
    hasContributions.add(new HasBindingContext.Contribution(context,
        hasSlots == null ? List.of() : hasSlots.subList(fromSlot, hasSlots.size())));
  }

  List<HasBindingContext.Contribution> hasContributions() {
    return frozenContributions == null
        ? (hasContributions == null ? List.of() : List.copyOf(hasContributions))
        : frozenContributions;
  }

  GremlinShapeEncoder(@Nullable Schema schema) {
    this(schema, null, null);
  }

  GremlinShapeEncoder(@Nullable Schema schema, GremlinShapeBuffer buffer) {
    this(schema, buffer, null);
  }

  GremlinShapeEncoder(@Nullable Schema schema, GremlinShapeBuffer buffer,
      @Nullable RuntimeRidStartEligibility.EligibleStart runtimeRidStart) {
    this.schema = schema;
    this.runtimeRidStart = runtimeRidStart;
    this.buffer = buffer;
    leaseEpoch = buffer == null ? 0 : buffer.epoch();
    tokens = buffer == null ? new ArrayList<>(96) : null;
    harvest = buffer == null ? new ArrayList<>() : null;
  }

  private int runtimeRidSlot = -1;

  @Nullable RuntimeRidStartEligibility.EligibleStart runtimeRidStart() {
    return runtimeRidStart;
  }

  void appendRuntimeRidStart() {
    appendToken("rrs");
    if (runtimeRidStart.idContainer() == null) {
      deferHarvest(this::bindRuntimeRidStart);
    }
  }

  void bindRuntimeRidStart() {
    runtimeRidSlot = bindings == null ? 0 : bindings.size();
    bindParam(runtimeRidStart.rid());
  }

  int runtimeRidSlot() {
    harvest();
    return runtimeRidSlot;
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
    if (buffer != null) {
      throw GremlinShapeBuffer.UNSUPPORTED;
    }
    complete = false;
  }

  @Nonnull
  String key() {
    return compactKey().toString();
  }

  @Nonnull
  Map<Object, Object> bindings() {
    return frozenBindings == null
        ? (bindings == null ? Map.of() : Map.copyOf(bindings)) : frozenBindings;
  }

  void appendToken(String token) {
    appendCount(token.length());
    appendRaw(token);
  }

  void appendToken(String label, String token) {
    appendRaw(label);
    appendRaw(":");
    appendToken(token);
  }

  void appendStringSeq(String label, String[] keys) {
    appendRaw(label);
    appendRaw(":");
    appendCount(keys.length);
    for (String key : keys) {
      appendToken(key == null ? "" : key);
    }
  }

  void appendStringSeq(String label, Collection<String> keys) {
    appendRaw(label);
    appendRaw(":");
    appendCount(keys.size());
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
    if (buffer != null && predicate != null) {
      // The audited fast subset uses scalar leaves. Connectives and custom predicates restart
      // on the complete encoder before invoking conversions or walking their containers.
      var type = predicate.getClass();
      if (type != P.class && type != org.apache.tinkerpop.gremlin.process.traversal.TextP.class) {
        throw GremlinShapeBuffer.UNSUPPORTED;
      }
      var operator = predicate.getBiPredicate();
      if (!(operator instanceof org.apache.tinkerpop.gremlin.process.traversal.Compare
          || operator instanceof org.apache.tinkerpop.gremlin.process.traversal.Contains
          || operator instanceof Text
          || (operator != null && operator.getClass() == Text.RegexPredicate.class))
          || predicate.getValue() instanceof Collection<?>) {
        throw GremlinShapeBuffer.UNSUPPORTED;
      }
    }
    if (predicate == null) {
      appendToken("P", "-");
      return;
    }
    if (predicate instanceof NotP<?> notP) {
      // NotP has no public getter for the wrapped predicate; negate() returns it (see
      // GremlinPredicateAdapter.translate).
      appendRaw("NOT:");
      appendPredicate(notP.negate(), valuesAreStructural);
      return;
    }
    if (predicate instanceof AndP<?> andP) {
      appendRaw("AND:");
      appendPredicateList(andP.getPredicates(), valuesAreStructural);
      return;
    }
    if (predicate instanceof OrP<?> orP) {
      appendRaw("OR:");
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
    var encoder = new GremlinShapeEncoder(null);
    encoder.appendPredicate(predicate, valuesAreStructural);
    return encoder.key();
  }

  private void appendPredicateList(List<? extends P<?>> children, boolean valuesAreStructural) {
    appendCount(children.size());
    for (var child : children) {
      appendPredicate(child, valuesAreStructural);
    }
  }

  void appendStructuralValue(@Nullable Object value) {
    if (buffer != null && value != null && !(value instanceof String)
        && !(value instanceof Integer) && !(value instanceof Long)
        && !(value instanceof Boolean) && !(value instanceof Double)
        && !(value instanceof Float) && !(value instanceof Short)
        && !(value instanceof Byte) && !(value instanceof Character)) {
      throw GremlinShapeBuffer.UNSUPPORTED;
    }
    // Only collections need cycle tracking. Fast mode admits scalars only.
    appendStructuralValue(value, value instanceof Collection<?> ? new IdentityHashMap<>() : null);
  }

  private void appendStructuralValue(@Nullable Object value,
      @Nullable IdentityHashMap<Collection<?>, Boolean> activeCollections) {
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
      appendRaw("C:");
      appendCount(collection.size());
      for (var element : collection) {
        appendStructuralValue(element, activeCollections);
      }
      activeCollections.remove(collection);
      return;
    }
    appendToken(String.valueOf(value));
  }

  void appendValueClass(@Nullable Object value) {
    if (buffer != null && value instanceof Collection<?>) {
      throw GremlinShapeBuffer.UNSUPPORTED;
    }
    if (value == null) {
      appendToken("vc", "N");
      return;
    }
    if (value instanceof Collection<?> collection) {
      appendRaw("VC:");
      appendCount(collection.size());
      for (var element : collection) {
        appendToken(element == null ? "N" : element.getClass().getName());
      }
      return;
    }
    appendToken("vc", value.getClass().getName());
  }

  private SQLPositionalParameter bindParam(Object value) {
    if (bindings == null) {
      GremlinShapeBuffer.count(counters -> counters.bindingMaps++);
      bindings = new LinkedHashMap<>();
    }
    var slot = bindings.size();
    bindings.put(slot, value);
    // Harvest consumes only values and slot order, never the parameter AST.
    return null;
  }
}
