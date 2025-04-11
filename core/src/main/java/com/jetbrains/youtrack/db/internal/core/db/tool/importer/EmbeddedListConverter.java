package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.List;

/**
 *
 */
public final class EmbeddedListConverter extends AbstractCollectionConverter<List<Object>> {

  public EmbeddedListConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public List<Object> convert(DatabaseSessionInternal session, List<Object> value) {
    final var result = session.newEmbeddedList();

    final var callback =
        new ResultCallback() {
          @Override
          public void add(Object item) {
            result.add(item);
          }
        };
    var updated = false;

    for (var item : value) {
      updated = convertSingleValue(session, item, callback, updated);
    }

    if (updated) {
      return result;
    }

    return value;
  }
}
