package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.record.EmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;

/**
 *
 */
public final class ImportConvertersFactory {

  public static final RID BROKEN_LINK = new RecordId(-1, -42);
  public static final ImportConvertersFactory INSTANCE = new ImportConvertersFactory();

  public ValuesConverter getConverter(Object value, ConverterData converterData) {
    if (value instanceof LinkMap) {
      return new LinkMapConverter(converterData);
    }
    if (value instanceof TrackedMap<?>) {
      return new EmbeddedMapConverter(converterData);
    }

    if (value instanceof LinkList) {
      return new LinkListConverter(converterData);
    }
    if (value instanceof TrackedList<?>) {
      return new EmbeddedListConverter(converterData);
    }

    if (value instanceof LinkSetImpl) {
      return new LinkSetConverter(converterData);
    }
    if (value instanceof EmbeddedSetImpl<?>) {
      return new EmbeddedSetConverter(converterData);
    }

    if (value instanceof RidBag) {
      return new RidBagConverter(converterData);
    }

    if (value instanceof Identifiable) {
      return new LinkConverter(converterData);
    }

    return null;
  }
}
