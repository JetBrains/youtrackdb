package com.jetbrains.youtrackdb.internal.core.db.tool;

import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_CLASS_NAME;
import static com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_INDEX_NAME;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class TestImportRewriteLinks {

  @Test
  @Ignore
  public void testLinkRewrite() {
    try (final var youTrackDb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPath(getClass()))) {
      youTrackDb.create("testDB", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
      try (var session =
          (DatabaseSessionEmbedded) youTrackDb.open("testDB", "admin",
              DbTestBase.ADMIN_PASSWORD)) {
        final Schema schema = session.getMetadata().getSchema();

        final var cls = schema.createClass(EXPORT_IMPORT_CLASS_NAME);
        cls.createProperty("key", PropertyType.STRING);
        cls.createProperty("value", PropertyType.STRING);
        cls.createIndex(EXPORT_IMPORT_INDEX_NAME, INDEX_TYPE.UNIQUE, "key");

        session.begin();
        var e1 = ((EntityImpl) session.newEntity(EXPORT_IMPORT_CLASS_NAME));
        e1.setProperty("key", new RecordId(10, 4).toString());
        e1.setProperty("value", new RecordId(10, 3).toString());

        var e2 = ((EntityImpl) session.newEntity(EXPORT_IMPORT_CLASS_NAME));
        e2.setProperty("key", new RecordId(11, 1).toString());
        e2.setProperty("value", new RecordId(21, 1).toString());

        var e3 = ((EntityImpl) session.newEntity(EXPORT_IMPORT_CLASS_NAME));
        e3.setProperty("key", new RecordId(31, 1).toString());
        e3.setProperty("value", new RecordId(41, 1).toString());

        var e4 = ((EntityImpl) session.newEntity(EXPORT_IMPORT_CLASS_NAME));
        e4.setProperty("key", new RecordId(51, 1).toString());
        e4.setProperty("value", new RecordId(61, 1).toString());

        session.commit();

        session.begin();
        final Set<RID> brokenRids = new HashSet<>();

        var entity = (EntityImpl) session.newEntity();

        entity.setProperty("link", new RecordId(10, 4));
        entity.setProperty("brokenLink", new RecordId(10, 5));

        var linkList = session.newLinkList();

        linkList.add(new RecordId(11, 2));
        linkList.add(new RecordId(11, 1));

        brokenRids.add(new RecordId(10, 5));
        brokenRids.add(new RecordId(11, 2));
        brokenRids.add(new RecordId(31, 2));
        brokenRids.add(new RecordId(51, 2));

        var linkSet = session.newLinkSet();

        linkSet.add(new RecordId(31, 2));
        linkSet.add(new RecordId(31, 1));

        var linkMap = session.newLinkMap();

        linkMap.put("key1", new RecordId(51, 1));
        linkMap.put("key2", new RecordId(51, 2));

        entity.setProperty("linkList", linkList);
        entity.setProperty("linkSet", linkSet);
        entity.setProperty("linkMap", linkMap);

        DatabaseImport.doRewriteLinksInDocument(session, entity,
            brokenRids);

        Assert.assertEquals(new RecordId(10, 3), entity.getLink("link"));
        Assert.assertNull(entity.getProperty("brokenLink"));

        List<Identifiable> resLinkList = new ArrayList<>();
        resLinkList.add(new RecordId(21, 1));

        Assert.assertEquals(entity.getProperty("linkList"), resLinkList);

        Set<Identifiable> resLinkSet = new HashSet<>();
        resLinkSet.add(new RecordId(41, 1));

        Assert.assertEquals(entity.getProperty("linkSet"), resLinkSet);

        Map<String, Identifiable> resLinkMap = new HashMap<>();
        resLinkMap.put("key1", new RecordId(61, 1));

        Assert.assertEquals(entity.getProperty("linkMap"), resLinkMap);
        session.rollback();
      }
    }
  }
}
