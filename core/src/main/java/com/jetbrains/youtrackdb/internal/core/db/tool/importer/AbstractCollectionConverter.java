package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;

/**
 * Base converter for collection-typed values that handles individual element conversion.
 */
public abstract class AbstractCollectionConverter<T> implements ValuesConverter<T> {

  private final ConverterData converterData;

  protected AbstractCollectionConverter(ConverterData converterData) {
    this.converterData = converterData;
  }

  public interface ResultCallback {

    void add(Object item);
  }

  protected boolean convertSingleValue(DatabaseSessionEmbedded db, final Object item,
      ResultCallback result, boolean updated) {
    if (item == null) {
      result.add(null);
      return false;
    }

    if (item instanceof Identifiable) {
      final var converter =
          (ValuesConverter<Identifiable>)
              ImportConvertersFactory.INSTANCE.getConverter(item, converterData);

      final var newValue = converter.convert(db, (Identifiable) item);

      // this code intentionally uses == instead of equals, in such case we may distinguish rids
      // which already contained in
      // document and RID which is used to indicate broken record
      if (newValue != ImportConvertersFactory.BROKEN_LINK) {
        result.add(newValue);
      }

      if (!newValue.equals(item)) {
        updated = true;
      }
    } else {
      final var valuesConverter =
          ImportConvertersFactory.INSTANCE.getConverter(item, converterData);
      if (valuesConverter == null) {
        result.add(item);
      } else {
        final var newValue = valuesConverter.convert(db, item);
        if (newValue != item) {
          updated = true;
        }

        result.add(newValue);
      }
    }

    return updated;
  }
}
