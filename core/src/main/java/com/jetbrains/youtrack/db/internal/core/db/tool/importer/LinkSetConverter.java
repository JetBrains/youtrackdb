package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Set;

public class LinkSetConverter extends AbstractCollectionConverter<Set<Identifiable>> {

  public LinkSetConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Set<Identifiable> convert(DatabaseSessionInternal session, Set<Identifiable> value) {
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
