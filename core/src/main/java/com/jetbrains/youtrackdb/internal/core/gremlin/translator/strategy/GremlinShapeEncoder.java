package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLPositionalParameter;
import java.util.Collection;
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
 * Length-prefixed token writer and positional-parameter harvest for the pre-walk translation-cache
 * key. Recognisers append the same step-local tokens they read in {@link StepRecogniser#recognize};
 * comparison values stay out of the key (only their runtime class) so they rebound as {@code ?}.
 *
 * <p>Every variable-length token is length-prefixed, matching {@link GremlinPlanFingerprint}, so a
 * user identifier cannot forge another walk's key by embedding delimiters.
 */
final class GremlinShapeEncoder {

  private final StringBuilder sb = new StringBuilder(256);

  private final LinkedHashMap<Object, Object> bindings = new LinkedHashMap<>();

  @Nullable private final Schema schema;

  private final ParamSink sink = this::bindParam;

  private boolean complete = true;

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
   * unless {@code valuesAreStructural} is set ({@code where(P)} label names are pattern structure,
   * not rebound slots).
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
      appendToken(String.valueOf(bi));
    }
    // Text.regex and Text.notRegex share RegexPredicate; toString does not include isNegate(),
    // so a cached regex plan would be spliced onto notRegex (TinkerPop Has.feature: marko only
    // instead of everyone else). The walker reads the flag in translateRegex.
    if (bi instanceof Text.RegexPredicate regex) {
      appendToken("neg", regex.isNegate() ? "1" : "0");
    }
    if (valuesAreStructural) {
      appendStructuralValue(predicate.getValue());
    } else {
      appendValueClass(predicate.getValue());
    }
  }

  private void appendPredicateList(List<? extends P<?>> children, boolean valuesAreStructural) {
    sb.append(children.size()).append(':');
    for (var child : children) {
      appendPredicate(child, valuesAreStructural);
    }
  }

  void appendStructuralValue(@Nullable Object value) {
    if (value instanceof Collection<?> collection) {
      sb.append("C:").append(collection.size()).append(':');
      for (var element : collection) {
        appendToken(String.valueOf(element));
      }
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
    return SQLPositionalParameter.forSlot(slot);
  }
}
