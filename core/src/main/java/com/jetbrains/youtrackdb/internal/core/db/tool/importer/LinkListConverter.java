package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.List;

public class LinkListConverter extends AbstractCollectionConverter<List<Identifiable>> {

  public LinkListConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public List<Identifiable> convert(DatabaseSessionInternal session, List<Identifiable> value) {
    final var result = session.newLinkList();

    final var callback =
        new ResultCallback() {
          @Override
          public void add(Object item) {
            result.add((Identifiable) item);
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
