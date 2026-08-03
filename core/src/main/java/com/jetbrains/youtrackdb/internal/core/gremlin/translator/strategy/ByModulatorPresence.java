package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;

/**
 * Adds the {@code IS DEFINED} conjunct a key-side {@code by(key)} modulator implies, on the alias
 * the modulator reads.
 *
 * <p>A Gremlin {@code by(...)} modulator is a traversal, not a field reference: {@code
 * order().by("age")} maps each element through {@code values("age")}, and an element with no
 * {@code age} produces nothing, so its traverser is dropped. {@code g.V().order().by("age")} on the
 * modern graph therefore emits four vertices, not six. SQL has no such rule — {@code ORDER BY
 * v.age} keeps every row and sorts the missing ones as {@code null}, {@code GROUP BY v.age}
 * collects them into a {@code null} bucket, and a following {@code count()} counts them. Every one
 * of those is a silently larger multiset than Gremlin's, so each recogniser that consumes a
 * key-side modulator calls {@link #requireModulatedProperty} on the alias it modulates.
 *
 * <p>{@code IS DEFINED} rather than {@code IS NOT NULL}: TinkerPop's rule is
 * {@code Property.isPresent()}, so a property stored with a literal {@code null} value is present
 * and its traverser survives. That is the same entity-layer view {@code has(key)} maps to.
 */
final class ByModulatorPresence {

  /** Shared builder — construction is trivial and the builder is stateless. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private ByModulatorPresence() {
    // Static helper — no instances.
  }

  /**
   * Contributes {@code key IS DEFINED} on {@code alias} when {@code modulator} reads a property.
   * A no-op for record-attribute modulators ({@code by(T.id)} / {@code by(T.label)}), for a null
   * or identity modulator, and for any shape {@link ByModulatorTranslator} does not classify — the
   * caller declines those independently, so this method never has to gate on recognisability.
   */
  static void requireModulatedProperty(
      RecognitionContext ctx, String alias, Traversal.Admin<?, ?> modulator) {
    ByModulatorTranslator.keyModulatorPropertyKey(modulator)
        .ifPresent(key -> requireProperty(ctx, alias, key));
  }

  /**
   * Contributes {@code key IS DEFINED} on {@code alias} for an already-resolved property key, or
   * nothing when {@code ProductiveByStrategy} makes that key productive — see {@link
   * RecognitionContext#byModulatorIsProductive}, where the whole inversion is explained.
   */
  static void requireProperty(RecognitionContext ctx, String alias, String key) {
    if (ctx.byModulatorIsProductive(key)) {
      return;
    }
    ctx.putAliasFilter(alias, WHERE.wrap(WHERE.isDefined(key)));
  }
}
