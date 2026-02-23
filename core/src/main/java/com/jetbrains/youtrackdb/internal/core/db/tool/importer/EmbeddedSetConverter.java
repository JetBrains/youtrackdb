package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Set;

/**
 * Converts embedded set values by rewriting broken links during database import.
 */
public final class EmbeddedSetConverter extends AbstractCollectionConverter<Set<Object>> {

  public EmbeddedSetConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Set<Object> convert(DatabaseSessionEmbedded session, Set<Object> value) {
    var updated = false;
    final var result = session.newEmbeddedSet();

    final var callback =
        new ResultCallback() {
          @Override
          public void add(Object item) {
            result.add(item);
          }
        };

    for (var item : value) {
      updated = convertSingleValue(session, item, callback, updated);
    }

    if (updated) {
      return result;
    }

    return value;
  }
}
