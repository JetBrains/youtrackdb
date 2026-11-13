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

  private final List<Object> tokens = new ArrayList<>(96);

  private final List<Runnable> harvest = new ArrayList<>();

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
    harvest.add(action);
  }

  void harvest() {
    if (!harvested) {
      harvested = true;
      harvest.forEach(Runnable::run);
      harvest.clear();
      frozenBindings = bindings == null ? Map.of() : Map.copyOf(bindings);
      frozenContributions = List.copyOf(hasContributions);
    }
  }

  boolean harvested() {
    return harvested;
  }

  GremlinShapeKey compactKey() {
    return new GremlinShapeKey(tokens);
  }

  private void appendRaw(String token) {
    tokens.add(token);
  }

  private void appendCount(int count) {
    tokens.add(count);
  }

  @Nullable private LinkedHashMap<Object, Object> bindings;

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
    return frozenContributions == null ? List.copyOf(hasContributions) : frozenContributions;
  }

  GremlinShapeEncoder(@Nullable Schema schema) {
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
      bindings = new LinkedHashMap<>();
    }
    var slot = bindings.size();
    bindings.put(slot, value);
    // Harvest consumes only values and slot order, never the parameter AST.
    return null;
  }
}
