package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;

/**
 * Thrown by {@link GremlinStepWalker}'s reserved-prefix pre-flight scan when a Gremlin traversal
 * carries a user {@code as(...)} label in the reserved {@code $} namespace ({@link
 * WalkerContext#RESERVED_ALIAS_PREFIX}). That namespace holds the translator's minted {@code $g2m_}
 * aliases and YouTrackDB's query-context variables, so a user label there is prohibited rather than
 * translated.
 *
 * <p>Unlike every other failure inside {@link GremlinToMatchStrategy#apply}, this one rejects the
 * input; it is not a best-effort-translation failure. The strategy's throw-safety net re-throws this
 * exact type so the query fails with a clear error, while it degrades every other {@link
 * RuntimeException} to a silent native decline. Extending {@link CommandExecutionException} places it
 * in YouTrackDB's query-error hierarchy and makes it a {@code RuntimeException}, so it propagates
 * through TinkerPop's {@code applyStrategies()} to the caller.
 */
final class ReservedAliasException extends CommandExecutionException {

  ReservedAliasException(String message) {
    super(message);
  }
}
