package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

/**
 *
 */
public final class EmbeddedMapConverter extends AbstractCollectionConverter<Map<String, Object>> {

  public EmbeddedMapConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Map<String, Object> convert(DatabaseSessionInternal session, Map<String, Object> value) {
    var result = session.newEmbeddedMap();
    var updated = false;
    final class MapResultCallback implements ResultCallback {

      private String key;

      @Override
      public void add(Object item) {
        result.put(key, item);
      }

      public void setKey(Object key) {
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
