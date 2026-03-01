package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;

public final class LinkBagConverter extends AbstractCollectionConverter<LinkBag> {

  public LinkBagConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public LinkBag convert(DatabaseSessionEmbedded session, LinkBag value) {
    final var result = new LinkBag(session);
    var updated = false;
    final ResultCallback callback =
        item -> result.add(((Identifiable) item).getIdentity());

    for (var ridPair : value) {
      updated = convertSingleValue(session, ridPair.primaryRid(), callback, updated);
    }

    if (updated) {
      return result;
    }

    return value;
  }
}
