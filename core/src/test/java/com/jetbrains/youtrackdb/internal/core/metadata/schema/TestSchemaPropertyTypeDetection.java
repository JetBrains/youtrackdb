package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class TestSchemaPropertyTypeDetection extends DbTestBase {

  @Test
  public void testOTypeFromClass() {

    assertEquals(PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.getTypeByClass(Boolean.class));

    assertEquals(PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.getTypeByClass(Boolean.TYPE));

    assertEquals(PropertyTypeInternal.LONG, PropertyTypeInternal.getTypeByClass(Long.class));

    assertEquals(PropertyTypeInternal.LONG, PropertyTypeInternal.getTypeByClass(Long.TYPE));

    assertEquals(PropertyTypeInternal.INTEGER, PropertyTypeInternal.getTypeByClass(Integer.class));

    assertEquals(PropertyTypeInternal.INTEGER, PropertyTypeInternal.getTypeByClass(Integer.TYPE));

    assertEquals(PropertyTypeInternal.SHORT, PropertyTypeInternal.getTypeByClass(Short.class));

    assertEquals(PropertyTypeInternal.SHORT, PropertyTypeInternal.getTypeByClass(Short.TYPE));

    assertEquals(PropertyTypeInternal.FLOAT, PropertyTypeInternal.getTypeByClass(Float.class));

    assertEquals(PropertyTypeInternal.FLOAT, PropertyTypeInternal.getTypeByClass(Float.TYPE));

    assertEquals(PropertyTypeInternal.DOUBLE, PropertyTypeInternal.getTypeByClass(Double.class));

    assertEquals(PropertyTypeInternal.DOUBLE, PropertyTypeInternal.getTypeByClass(Double.TYPE));

    assertEquals(PropertyTypeInternal.BYTE, PropertyTypeInternal.getTypeByClass(Byte.class));

    assertEquals(PropertyTypeInternal.BYTE, PropertyTypeInternal.getTypeByClass(Byte.TYPE));

    assertEquals(PropertyTypeInternal.STRING, PropertyTypeInternal.getTypeByClass(Character.class));

    assertEquals(PropertyTypeInternal.STRING, PropertyTypeInternal.getTypeByClass(Character.TYPE));

    assertEquals(PropertyTypeInternal.STRING, PropertyTypeInternal.getTypeByClass(String.class));

    // assertEquals(PropertyTypeInternal.BINARY, PropertyTypeInternal.getTypeByClass(Byte[].class));

    assertEquals(PropertyTypeInternal.BINARY, PropertyTypeInternal.getTypeByClass(byte[].class));

    assertEquals(PropertyTypeInternal.DATETIME, PropertyTypeInternal.getTypeByClass(Date.class));

    assertEquals(PropertyTypeInternal.DECIMAL,
        PropertyTypeInternal.getTypeByClass(BigDecimal.class));

    assertEquals(PropertyTypeInternal.DECIMAL,
        PropertyTypeInternal.getTypeByClass(BigInteger.class));

    assertEquals(PropertyTypeInternal.LINK,
        PropertyTypeInternal.getTypeByClass(Identifiable.class));

    assertEquals(PropertyTypeInternal.LINK,
        PropertyTypeInternal.getTypeByClass(RecordIdInternal.class));

    assertEquals(PropertyTypeInternal.LINK, PropertyTypeInternal.getTypeByClass(DBRecord.class));

    assertEquals(PropertyTypeInternal.LINK, PropertyTypeInternal.getTypeByClass(EntityImpl.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByClass(ArrayList.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByClass(List.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByClass(EntityEmbeddedListImpl.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDSET, PropertyTypeInternal.getTypeByClass(Set.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.getTypeByClass(HashSet.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.getTypeByClass(EntityEmbeddedSetImpl.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.getTypeByClass(Map.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.getTypeByClass(HashMap.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.getTypeByClass(EntityEmbeddedMapImpl.class));

    assertEquals(PropertyTypeInternal.LINKSET,
        PropertyTypeInternal.getTypeByClass(EntityLinkSetImpl.class));

    assertEquals(PropertyTypeInternal.LINKLIST,
        PropertyTypeInternal.getTypeByClass(EntityLinkListImpl.class));

    assertEquals(PropertyTypeInternal.LINKMAP,
        PropertyTypeInternal.getTypeByClass(EntityLinkMapIml.class));

    assertEquals(PropertyTypeInternal.LINKBAG, PropertyTypeInternal.getTypeByClass(LinkBag.class));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByClass(Object[].class));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByClass(String[].class));

    assertEquals(PropertyTypeInternal.EMBEDDED,
        PropertyTypeInternal.getTypeByClass(EntitySerializable.class));

    assertEquals(PropertyTypeInternal.EMBEDDED,
        PropertyTypeInternal.getTypeByClass(DocumentSer.class));
  }

  @Test
  public void testOTypeFromValue() {
    session.begin();
    assertEquals(PropertyTypeInternal.BOOLEAN, PropertyTypeInternal.getTypeByValue(true));

    assertEquals(PropertyTypeInternal.LONG, PropertyTypeInternal.getTypeByValue(2L));

    assertEquals(PropertyTypeInternal.INTEGER, PropertyTypeInternal.getTypeByValue(2));

    assertEquals(PropertyTypeInternal.SHORT, PropertyTypeInternal.getTypeByValue((short) 4));

    assertEquals(PropertyTypeInternal.FLOAT, PropertyTypeInternal.getTypeByValue(0.5f));

    assertEquals(PropertyTypeInternal.DOUBLE, PropertyTypeInternal.getTypeByValue(0.7d));

    assertEquals(PropertyTypeInternal.BYTE, PropertyTypeInternal.getTypeByValue((byte) 10));

    assertEquals(PropertyTypeInternal.STRING, PropertyTypeInternal.getTypeByValue('a'));

    assertEquals(PropertyTypeInternal.STRING, PropertyTypeInternal.getTypeByValue("yaaahooooo"));

    assertEquals(PropertyTypeInternal.BINARY,
        PropertyTypeInternal.getTypeByValue(new byte[]{0, 1, 2}));

    assertEquals(PropertyTypeInternal.DATETIME, PropertyTypeInternal.getTypeByValue(new Date()));

    assertEquals(PropertyTypeInternal.DECIMAL,
        PropertyTypeInternal.getTypeByValue(new BigDecimal(10)));

    assertEquals(PropertyTypeInternal.DECIMAL,
        PropertyTypeInternal.getTypeByValue(new BigInteger("20")));

    assertEquals(PropertyTypeInternal.LINK,
        PropertyTypeInternal.getTypeByValue(session.newEntity()));

    assertEquals(PropertyTypeInternal.LINK,
        PropertyTypeInternal.getTypeByValue(
            new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID)));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByValue(new ArrayList<Object>()));

    assertEquals(
        PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByValue(
            new EntityEmbeddedListImpl<>((EntityImpl) session.newEntity()
            )));

    assertEquals(PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.getTypeByValue(new HashSet<Object>()));

    assertEquals(PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.getTypeByValue(new HashMap<Object, Object>()));

    assertEquals(PropertyTypeInternal.LINKSET,
        PropertyTypeInternal.getTypeByValue(
            new EntityLinkSetImpl((EntityImpl) session.newEntity())));

    assertEquals(PropertyTypeInternal.LINKLIST,
        PropertyTypeInternal.getTypeByValue(
            new EntityLinkListImpl((EntityImpl) session.newEntity())));

    assertEquals(PropertyTypeInternal.LINKMAP,
        PropertyTypeInternal.getTypeByValue(
            new EntityLinkMapIml((EntityImpl) session.newEntity())));

    assertEquals(PropertyTypeInternal.LINKBAG,
        PropertyTypeInternal.getTypeByValue(new LinkBag(session)));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByValue(new Object[]{}));

    assertEquals(PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.getTypeByValue(new String[]{}));

    assertEquals(PropertyTypeInternal.EMBEDDED,
        PropertyTypeInternal.getTypeByValue(new DocumentSer()));

    session.rollback();
  }

  @Test
  public void testOTypeFromValueInternal() {
    session.begin();
    Map<String, RecordIdInternal> linkmap = new HashMap<String, RecordIdInternal>();
    linkmap.put("some", new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));
    assertEquals(PropertyTypeInternal.LINKMAP, PropertyTypeInternal.getTypeByValue(linkmap));

    Map<String, DBRecord> linkmap2 = new HashMap<String, DBRecord>();
    linkmap2.put("some", session.newEntity());
    assertEquals(PropertyTypeInternal.LINKMAP, PropertyTypeInternal.getTypeByValue(linkmap2));

    List<RecordIdInternal> linkList = new ArrayList<RecordIdInternal>();
    linkList.add(new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));
    assertEquals(PropertyTypeInternal.LINKLIST, PropertyTypeInternal.getTypeByValue(linkList));

    List<DBRecord> linkList2 = new ArrayList<DBRecord>();
    linkList2.add(session.newEntity());
    assertEquals(PropertyTypeInternal.LINKLIST, PropertyTypeInternal.getTypeByValue(linkList2));

    Set<RecordIdInternal> linkSet = new HashSet<RecordIdInternal>();
    linkSet.add(new RecordId(RID.COLLECTION_ID_INVALID, RID.COLLECTION_POS_INVALID));
    assertEquals(PropertyTypeInternal.LINKSET, PropertyTypeInternal.getTypeByValue(linkSet));

    Set<DBRecord> linkSet2 = new HashSet<DBRecord>();
    linkSet2.add(session.newEntity());
    assertEquals(PropertyTypeInternal.LINKSET, PropertyTypeInternal.getTypeByValue(linkSet2));

    var document = (EntityImpl) session.newEmbeddedEntity();
    document.setOwner((EntityImpl) session.newEntity());
    assertEquals(PropertyTypeInternal.EMBEDDED, PropertyTypeInternal.getTypeByValue(document));
    session.rollback();
  }

  public class DocumentSer implements EntitySerializable {

    @Override
    public EntityImpl toEntity(DatabaseSessionEmbedded db) {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public void fromDocument(EntityImpl document) {
      // TODO Auto-generated method stub

    }
  }
}
