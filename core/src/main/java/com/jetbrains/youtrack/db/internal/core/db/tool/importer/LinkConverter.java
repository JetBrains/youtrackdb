package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;

/**
 *
 */
public final class LinkConverter implements ValuesConverter<Identifiable> {

  private final ConverterData converterData;

  public LinkConverter(ConverterData importer) {
    this.converterData = importer;
  }

  @Override
  public Identifiable convert(DatabaseSessionInternal session, Identifiable value) {
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
          return new RecordId(resultSet.next().<String>getProperty("value"));
        }
        return value;
      }
    });
  }
}
