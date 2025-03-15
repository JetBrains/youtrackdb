package com.jetbrains.youtrack.db.internal.core.db.tool;

import static com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_CLASS_NAME;
import static com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport.EXPORT_IMPORT_INDEX_NAME;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.CreateDatabaseUtil;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class TestImportRewriteLinks {

  @Test
  public void testNestedLinkRewrite() {
    try (final YouTrackDB youTrackDb =
        CreateDatabaseUtil.createDatabase(
            "testDB", DbTestBase.embeddedDBUrl(getClass()), CreateDatabaseUtil.TYPE_MEMORY)) {
      try (var session =
          (DatabaseSessionInternal) youTrackDb.open("testDB", "admin",
              CreateDatabaseUtil.NEW_ADMIN_PASSWORD)) {
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

        var doc = (EntityImpl) session.newEntity();

        var emb = (EntityImpl) session.newEmbeddedEntity();
        doc.setProperty("emb", emb, PropertyType.EMBEDDED);
        var emb1 = (EntityImpl) session.newEmbeddedEntity();
        emb.setProperty("emb1", emb1, PropertyType.EMBEDDED);
        emb1.setProperty("link", new RecordId(10, 4));
        emb1.setProperty("brokenLink", new RecordId(10, 5));

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

        emb1.setProperty("linkList", linkList);
        emb1.setProperty("linkSet", linkSet);
        emb1.setProperty("linkMap", linkMap);

        DatabaseImport.doRewriteLinksInDocument(session, doc,
            brokenRids);

        Assert.assertEquals(new RecordId(10, 3), emb1.getLink("link"));
        Assert.assertNull(emb1.getProperty("brokenLink"));

        List<Identifiable> resLinkList = new ArrayList<>();
        resLinkList.add(new RecordId(21, 1));

        Assert.assertEquals(emb1.getProperty("linkList"), resLinkList);

        Set<Identifiable> resLinkSet = new HashSet<>();
        resLinkSet.add(new RecordId(41, 1));

        Assert.assertEquals(emb1.getProperty("linkSet"), resLinkSet);

        Map<String, Identifiable> resLinkMap = new HashMap<>();
        resLinkMap.put("key1", new RecordId(61, 1));

        Assert.assertEquals(emb1.getProperty("linkMap"), resLinkMap);
        session.rollback();
      }
    }
  }
}
