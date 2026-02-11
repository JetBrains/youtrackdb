package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;

/**
 *
 */
public final class LinkConverter implements ValuesConverter<Identifiable> {

  private final ConverterData converterData;

  public LinkConverter(ConverterData importer) {
    this.converterData = importer;
  }

  @Override
  public Identifiable convert(DatabaseSessionEmbedded session, Identifiable value) {
    final var rid = value.getIdentity();
    if (!rid.isPersistent()) {
      return value;
    }

    if (converterData.brokenRids.contains(rid)) {
      return ImportConvertersFactory.BROKEN_LINK;
    }

    return converterData.session.computeInTx(transaction -> {
      try (final var resultSet =
          transaction.query(
              "select value from " + DatabaseImport.EXPORT_IMPORT_CLASS_NAME + " where key = ?",
              rid.toString())) {
        if (resultSet.hasNext()) {
          return RecordIdInternal.fromString(resultSet.next().getProperty("value"), false);
        }
        return value;
      }
    });
  }
}
