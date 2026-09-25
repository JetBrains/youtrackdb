package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import javax.annotation.Nonnull;

/**
 * Maps a {@code select(…).by(key)} / {@code project(…).by(key)} emit key onto an entity RETURN
 * column and property. The plan step loads the entity from {@link #entityColumnAlias()} (stripped
 * from the emitted map), reads {@link #propertyKey()} via {@code hasProperty} / {@code getProperty},
 * and puts the value under {@link #mapKey()} (the user select / project label).
 *
 * <p>Absence policy:
 * <ul>
 *   <li>{@link #dropOnAbsent()} — absent nonproductive {@code select} key drops the whole row
 *       (native {@code by(key)} drops the traverser).
 *   <li>{@link #omitOnAbsent()} — absent nonproductive {@code project} key omits that map entry
 *       and keeps the row (default {@code ProductiveByStrategy} contract for {@code project}).
 *   <li>Neither — productive key emits {@code null} when absent.
 * </ul>
 *
 * <p>Pattern {@code IS DEFINED} must not run before {@code LIMIT}, {@code SKIP}, or {@code
 * DISTINCT}.
 *
 * @param entityColumnAlias RETURN column holding the entity (or its RID); stripped from the emitted
 *     map
 * @param propertyKey property read on that entity
 * @param mapKey key in the emitted map (the {@code select} / {@code project} label)
 * @param dropOnAbsent whether absence drops the complete row
 * @param omitOnAbsent whether absence omits this map entry without dropping the row
 */
public record AliasPropertyPresence(
    @Nonnull String entityColumnAlias,
    @Nonnull String propertyKey,
    @Nonnull String mapKey,
    boolean dropOnAbsent,
    boolean omitOnAbsent) {

  /**
   * Creates a presence check that drops rows when the property is absent ({@code select} default).
   */
  public AliasPropertyPresence(String entityColumnAlias, String propertyKey, String mapKey) {
    this(entityColumnAlias, propertyKey, mapKey, true, false);
  }

  /**
   * Creates a presence check with an explicit row-drop policy and no omit-on-absent (select /
   * productive emit-null path).
   */
  public AliasPropertyPresence(
      String entityColumnAlias, String propertyKey, String mapKey, boolean dropOnAbsent) {
    this(entityColumnAlias, propertyKey, mapKey, dropOnAbsent, false);
  }

  /**
   * Compact canonical constructor — drop and omit are mutually exclusive.
   */
  public AliasPropertyPresence {
    assert !(dropOnAbsent && omitOnAbsent)
        : "AliasPropertyPresence cannot both drop the row and omit the key";
  }

  /** Presence that omits the map entry when the property is absent ({@code project} default). */
  public static AliasPropertyPresence omitWhenAbsent(
      String entityColumnAlias, String propertyKey, String mapKey) {
    return new AliasPropertyPresence(entityColumnAlias, propertyKey, mapKey, false, true);
  }
}
