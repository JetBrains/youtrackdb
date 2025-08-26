package com.jetbrains.youtrackdb.internal.core.serialization.serializer.result.binary;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.BytesContainer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
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
    try (var youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(ResultSerializerNetworkTest.class))) {
      youTrackDB.createIfNotExists("test", DatabaseType.MEMORY, "admin", "admin", "admin");
      try (var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "admin", "admin")) {

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
        ResultSerializerNetwork.serialize(original, bytes, db.getDatabaseTimeZone());

        bytes.offset = 0;
        var deserialized = ResultSerializerNetwork.deserialize(bytes, () -> new ResultInternal(db),
            db.getDatabaseTimeZone());
        Assert.assertEquals(original, deserialized);
      }
    }
  }
}
