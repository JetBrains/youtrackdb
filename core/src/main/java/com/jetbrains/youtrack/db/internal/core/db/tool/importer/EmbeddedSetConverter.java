package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Set;

/**
 *
 */
public final class EmbeddedSetConverter extends AbstractCollectionConverter<Set<Object>> {

  public EmbeddedSetConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Set<Object> convert(DatabaseSessionInternal session, Set<Object> value) {
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
