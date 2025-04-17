package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrack.db.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import javax.annotation.Nullable;

/**
 *
 */
public final class ImportConvertersFactory {

  public static final RID BROKEN_LINK = new RecordId(-1, -42);
  public static final ImportConvertersFactory INSTANCE = new ImportConvertersFactory();

  @Nullable
  public ValuesConverter getConverter(Object value, ConverterData converterData) {
    if (value instanceof EntityLinkMapIml) {
      return new LinkMapConverter(converterData);
    }
    if (value instanceof EntityEmbeddedMapImpl<?>) {
      return new EmbeddedMapConverter(converterData);
    }

    if (value instanceof EntityLinkListImpl) {
      return new LinkListConverter(converterData);
    }
    if (value instanceof EntityEmbeddedListImpl<?>) {
      return new EmbeddedListConverter(converterData);
    }

    if (value instanceof EntityLinkSetImpl) {
      return new LinkSetConverter(converterData);
    }
    if (value instanceof EntityEmbeddedSetImpl<?>) {
      return new EmbeddedSetConverter(converterData);
    }

    if (value instanceof LinkBag) {
      return new LinkBagConverter(converterData);
    }

    if (value instanceof Identifiable) {
      return new LinkConverter(converterData);
    }

    return null;
  }
}
