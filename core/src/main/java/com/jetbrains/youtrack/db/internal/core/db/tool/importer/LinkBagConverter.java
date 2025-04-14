package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;

public final class LinkBagConverter extends AbstractCollectionConverter<LinkBag> {

  public LinkBagConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public LinkBag convert(DatabaseSessionInternal session, LinkBag value) {
    final var result = new LinkBag(session);
    var updated = false;
    final ResultCallback callback =
        item -> result.add(((Identifiable) item).getIdentity());

    for (Identifiable identifiable : value) {
      updated = convertSingleValue(session, identifiable, callback, updated);
    }

    if (updated) {
      return result;
    }

    return value;
  }
}
