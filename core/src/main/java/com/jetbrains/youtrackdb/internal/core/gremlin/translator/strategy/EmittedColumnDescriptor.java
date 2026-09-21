package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

/**
 * What a Gremlin user label currently emits after a translated modulated {@code select}. A trailing
 * overlapping {@code select(label)} projects from this descriptor instead of rebinding the label
 * through the path/{@code as} map ({@link RecognitionContext#resolveUserLabel}), which would
 * silently emit the pattern Vertex while native Gremlin reads the map cell.
 */
sealed interface EmittedColumnDescriptor {

  /** Map cell is {@code internalAlias.propertyKey} (key-side {@code by(property)}). */
  record AliasProperty(String internalAlias, String propertyKey, boolean productive)
      implements EmittedColumnDescriptor {
  }

  /**
   * Map cell is a record attribute ({@code @rid} for {@code by(T.id)}, {@code @class} for {@code
   * by(T.label)}).
   */
  record RecordAttribute(String internalAlias, String attribute)
      implements EmittedColumnDescriptor {
  }
}
