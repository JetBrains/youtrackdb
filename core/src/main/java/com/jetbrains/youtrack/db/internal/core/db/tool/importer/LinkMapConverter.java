package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

public class LinkMapConverter extends AbstractCollectionConverter<Map<String, Identifiable>> {

  public LinkMapConverter(ConverterData converterData) {
    super(converterData);
  }

  @Override
  public Map<String, Identifiable> convert(DatabaseSessionInternal session,
      Map<String, Identifiable> value) {
    var result = session.newLinkMap();
    var updated = false;
    final class MapResultCallback implements ResultCallback {

      private String key;

      @Override
      public void add(Object item) {
        result.put(key, (Identifiable) item);
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
