package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import java.util.Set;

public class LinkSetConverter extends AbstractCollectionConverter<Set<Identifiable>> {

  public LinkSetConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Set<Identifiable> convert(DatabaseSessionEmbedded session, Set<Identifiable> value) {
    final var result = session.newLinkSet();

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
