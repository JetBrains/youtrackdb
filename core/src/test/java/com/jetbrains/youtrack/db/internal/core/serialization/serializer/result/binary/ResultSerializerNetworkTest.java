package com.jetbrains.youtrack.db.internal.core.serialization.serializer.result.binary;

import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ResultSerializerNetworkTest {

  @Test
  public void test() {
    try (var youTrackDB = new YouTrackDBImpl("memory", YouTrackDBConfig.defaultConfig())) {
      youTrackDB.createIfNotExists("test", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var db = (DatabaseSessionInternal) youTrackDB.open("test", "admin", "admin")) {
        var serializer = new ResultSerializerNetwork();

        var original = new ResultInternal(db);
        original.setProperty("string", "foo");
        original.setProperty("integer", 12);
        original.setProperty("float", 12.4f);
        original.setProperty("double", 12.4d);
        original.setProperty("boolean", true);
        original.setProperty("rid", new RecordId("#12:0"));

        var embeddedProj = new ResultInternal(db);
        embeddedProj.setProperty("name", "bar");
        original.setProperty("embeddedProj", embeddedProj);

        List list = new ArrayList();
        list.add("foo");
        list.add("bar");
        original.setProperty("list", list);

        Set set = new HashSet<>();
        set.add("foox");
        set.add("barx");
        original.setProperty("set", "set");

        var bytes = new BytesContainer();
        serializer.serialize(db, original, bytes);

        bytes.offset = 0;
        var deserialized = serializer.deserialize(db, bytes);
        Assert.assertEquals(original, deserialized);
      }
    }
  }
}
