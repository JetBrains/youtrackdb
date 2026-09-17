package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import javax.annotation.Nonnull;

/**
 * Maps a {@code select(…).by(key)} emit key onto an entity RETURN column and property. The plan
 * step loads the entity from {@link #entityColumnAlias()} (stripped from the emitted map), reads
 * {@link #propertyKey()} via {@code hasProperty} / {@code getProperty}, and puts the value under
 * {@link #mapKey()} (the user select label).
 *
 * <p>After a cardinality clause, {@link AbstractMatchPlanStep} also uses the list as a whole-row
 * drop when {@code dropOnAbsent} is set — Gremlin {@code by} drops the traverser if any modulated
 * key is absent, and pattern {@code IS DEFINED} must not run before {@code LIMIT}/{@code SKIP}/
 * {@code DISTINCT}.
 *
 * @param entityColumnAlias RETURN column holding the entity (or its RID); stripped from the emitted
 *     map
 * @param propertyKey property read on that entity
 * @param mapKey key in the emitted map (the {@code select} label)
 * @param dropOnAbsent whether absence drops the complete row
 */
public record AliasPropertyPresence(
    @Nonnull String entityColumnAlias,
    @Nonnull String propertyKey,
    @Nonnull String mapKey,
    boolean dropOnAbsent) {

  /** Creates a presence check that drops rows when the property is absent. */
  public AliasPropertyPresence(String entityColumnAlias, String propertyKey, String mapKey) {
    this(entityColumnAlias, propertyKey, mapKey, true);
  }
}
