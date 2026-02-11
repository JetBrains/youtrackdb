package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.List;

/**
 *
 */
public final class EmbeddedListConverter extends AbstractCollectionConverter<List<Object>> {

  public EmbeddedListConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public List<Object> convert(DatabaseSessionEmbedded session, List<Object> value) {
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
