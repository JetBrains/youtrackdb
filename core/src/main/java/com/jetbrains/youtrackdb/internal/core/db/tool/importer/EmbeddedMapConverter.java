package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Map;

/**
 * Converts embedded map values by rewriting broken links during database import.
 */
public final class EmbeddedMapConverter extends AbstractCollectionConverter<Map<String, Object>> {

  public EmbeddedMapConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Map<String, Object> convert(DatabaseSessionEmbedded session, Map<String, Object> value) {
    var result = session.newEmbeddedMap();
    var updated = false;
    final class MapResultCallback implements ResultCallback {

      private String key;

      @Override
      public void add(Object item) {
        result.put(key, item);
      }

      void setKey(Object key) {
        this.key = key.toString();
      }
    }

    final var callback = new MapResultCallback();
    for (var entry : value.entrySet()) {
      callback.setKey(entry.getKey());
      updated = convertSingleValue(session, entry.getValue(), callback, updated) || updated;
    }
    if (updated) {
      return result;
    }

    return value;
  }
}
