package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Collection;
import java.util.Map;

public interface EmbeddedTrackedMultiValue<K, V> extends TrackedMultiValue<K, V> {

  default void checkValue(V value) {
    if (value instanceof RID) {
      throw new SchemaException(
          "Cannot add a RID or a non-embedded entity to a embedded data container");
    }

    if (value instanceof Entity entity) {
      if (!entity.isEmbedded()) {
        throw new SchemaException(
            "Cannot add a non-embedded entity to a embedded data container");
      }

      if (isOneOfOwners((RecordElement) entity)) {
        throw new IllegalStateException(
            "Cannot add an entity to a embedded data container as this entity is "
                + "owner of this container");
      }
    }

    if (PropertyTypeInternal.isSingleValueType(value) || ((value instanceof Entity entity)
        && entity.isEmbedded())) {
      return;
    }

    if (value instanceof EntityEmbeddedListImpl<?> ||
        value instanceof EntityEmbeddedSetImpl<?> || value instanceof EntityEmbeddedMapImpl<?>) {
      return;
    }

    if ((value instanceof Collection<?>) || (value instanceof Map<?, ?>)) {
      throw new SchemaException(
          "Cannot add a non embedded collection to a embedded data container. Please use "
              + DatabaseSession.class.getName() +
              " factory methods instead : "
              + "newEmbeddedList(), newEmbeddedSet(), newEmbeddedMap().");
    }

    throw new SchemaException("Value " + value + " is not supported by data container.");
  }
}
