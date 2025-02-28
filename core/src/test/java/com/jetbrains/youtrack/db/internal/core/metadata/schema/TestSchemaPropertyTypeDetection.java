package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.SerializableStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestSchemaPropertyTypeDetection extends DbTestBase {

  @Test
  public void testOTypeFromClass() {

    assertEquals(PropertyType.BOOLEAN, PropertyType.getTypeByClass(Boolean.class));

    assertEquals(PropertyType.BOOLEAN, PropertyType.getTypeByClass(Boolean.TYPE));

    assertEquals(PropertyType.LONG, PropertyType.getTypeByClass(Long.class));

    assertEquals(PropertyType.LONG, PropertyType.getTypeByClass(Long.TYPE));

    assertEquals(PropertyType.INTEGER, PropertyType.getTypeByClass(Integer.class));

    assertEquals(PropertyType.INTEGER, PropertyType.getTypeByClass(Integer.TYPE));

    assertEquals(PropertyType.SHORT, PropertyType.getTypeByClass(Short.class));

    assertEquals(PropertyType.SHORT, PropertyType.getTypeByClass(Short.TYPE));

    assertEquals(PropertyType.FLOAT, PropertyType.getTypeByClass(Float.class));

    assertEquals(PropertyType.FLOAT, PropertyType.getTypeByClass(Float.TYPE));

    assertEquals(PropertyType.DOUBLE, PropertyType.getTypeByClass(Double.class));

    assertEquals(PropertyType.DOUBLE, PropertyType.getTypeByClass(Double.TYPE));

    assertEquals(PropertyType.BYTE, PropertyType.getTypeByClass(Byte.class));

    assertEquals(PropertyType.BYTE, PropertyType.getTypeByClass(Byte.TYPE));

    assertEquals(PropertyType.STRING, PropertyType.getTypeByClass(Character.class));

    assertEquals(PropertyType.STRING, PropertyType.getTypeByClass(Character.TYPE));

    assertEquals(PropertyType.STRING, PropertyType.getTypeByClass(String.class));

    // assertEquals(PropertyType.BINARY, PropertyType.getTypeByClass(Byte[].class));

    assertEquals(PropertyType.BINARY, PropertyType.getTypeByClass(byte[].class));

    assertEquals(PropertyType.DATETIME, PropertyType.getTypeByClass(Date.class));

    assertEquals(PropertyType.DECIMAL, PropertyType.getTypeByClass(BigDecimal.class));

    assertEquals(PropertyType.INTEGER, PropertyType.getTypeByClass(BigInteger.class));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByClass(Identifiable.class));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByClass(RecordId.class));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByClass(DBRecord.class));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByClass(EntityImpl.class));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByClass(ArrayList.class));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByClass(List.class));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByClass(TrackedList.class));

    assertEquals(PropertyType.EMBEDDEDSET, PropertyType.getTypeByClass(Set.class));

    assertEquals(PropertyType.EMBEDDEDSET, PropertyType.getTypeByClass(HashSet.class));

    assertEquals(PropertyType.EMBEDDEDSET, PropertyType.getTypeByClass(TrackedSet.class));

    assertEquals(PropertyType.EMBEDDEDMAP, PropertyType.getTypeByClass(Map.class));

    assertEquals(PropertyType.EMBEDDEDMAP, PropertyType.getTypeByClass(HashMap.class));

    assertEquals(PropertyType.EMBEDDEDMAP, PropertyType.getTypeByClass(TrackedMap.class));

    assertEquals(PropertyType.LINKSET, PropertyType.getTypeByClass(LinkSet.class));

    assertEquals(PropertyType.LINKLIST, PropertyType.getTypeByClass(LinkList.class));

    assertEquals(PropertyType.LINKMAP, PropertyType.getTypeByClass(LinkMap.class));

    assertEquals(PropertyType.LINKBAG, PropertyType.getTypeByClass(RidBag.class));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByClass(Object[].class));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByClass(String[].class));

    assertEquals(PropertyType.EMBEDDED, PropertyType.getTypeByClass(EntitySerializable.class));

    assertEquals(PropertyType.EMBEDDED, PropertyType.getTypeByClass(DocumentSer.class));
  }

  @Test
  public void testOTypeFromValue() {
    session.begin();
    assertEquals(PropertyType.BOOLEAN, PropertyType.getTypeByValue(true));

    assertEquals(PropertyType.LONG, PropertyType.getTypeByValue(2L));

    assertEquals(PropertyType.INTEGER, PropertyType.getTypeByValue(2));

    assertEquals(PropertyType.SHORT, PropertyType.getTypeByValue((short) 4));

    assertEquals(PropertyType.FLOAT, PropertyType.getTypeByValue(0.5f));

    assertEquals(PropertyType.DOUBLE, PropertyType.getTypeByValue(0.7d));

    assertEquals(PropertyType.BYTE, PropertyType.getTypeByValue((byte) 10));

    assertEquals(PropertyType.STRING, PropertyType.getTypeByValue('a'));

    assertEquals(PropertyType.STRING, PropertyType.getTypeByValue("yaaahooooo"));

    assertEquals(PropertyType.BINARY, PropertyType.getTypeByValue(new byte[]{0, 1, 2}));

    assertEquals(PropertyType.DATETIME, PropertyType.getTypeByValue(new Date()));

    assertEquals(PropertyType.DECIMAL, PropertyType.getTypeByValue(new BigDecimal(10)));

    assertEquals(PropertyType.INTEGER, PropertyType.getTypeByValue(new BigInteger("20")));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByValue(session.newEntity()));

    assertEquals(PropertyType.LINK, PropertyType.getTypeByValue(new ChangeableRecordId()));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByValue(new ArrayList<Object>()));

    assertEquals(
        PropertyType.EMBEDDEDLIST,
        PropertyType.getTypeByValue(new TrackedList<Object>((EntityImpl) session.newEntity()
        )));

    assertEquals(PropertyType.EMBEDDEDSET, PropertyType.getTypeByValue(new HashSet<Object>()));

    assertEquals(PropertyType.EMBEDDEDMAP,
        PropertyType.getTypeByValue(new HashMap<Object, Object>()));

    assertEquals(PropertyType.LINKSET,
        PropertyType.getTypeByValue(new LinkSet((EntityImpl) session.newEntity())));

    assertEquals(PropertyType.LINKLIST,
        PropertyType.getTypeByValue(new LinkList((EntityImpl) session.newEntity())));

    assertEquals(PropertyType.LINKMAP,
        PropertyType.getTypeByValue(new LinkMap((EntityImpl) session.newEntity())));

    assertEquals(PropertyType.LINKBAG, PropertyType.getTypeByValue(new RidBag(session)));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByValue(new Object[]{}));

    assertEquals(PropertyType.EMBEDDEDLIST, PropertyType.getTypeByValue(new String[]{}));

    assertEquals(PropertyType.EMBEDDED, PropertyType.getTypeByValue(new DocumentSer()));

    session.rollback();
  }

  @Test
  public void testOTypeFromValueInternal() {
    session.begin();
    Map<String, RecordId> linkmap = new HashMap<String, RecordId>();
    linkmap.put("some", new ChangeableRecordId());
    assertEquals(PropertyType.LINKMAP, PropertyType.getTypeByValue(linkmap));

    Map<String, DBRecord> linkmap2 = new HashMap<String, DBRecord>();
    linkmap2.put("some", session.newEntity());
    assertEquals(PropertyType.LINKMAP, PropertyType.getTypeByValue(linkmap2));

    List<RecordId> linkList = new ArrayList<RecordId>();
    linkList.add(new ChangeableRecordId());
    assertEquals(PropertyType.LINKLIST, PropertyType.getTypeByValue(linkList));

    List<DBRecord> linkList2 = new ArrayList<DBRecord>();
    linkList2.add(session.newEntity());
    assertEquals(PropertyType.LINKLIST, PropertyType.getTypeByValue(linkList2));

    Set<RecordId> linkSet = new HashSet<RecordId>();
    linkSet.add(new ChangeableRecordId());
    assertEquals(PropertyType.LINKSET, PropertyType.getTypeByValue(linkSet));

    Set<DBRecord> linkSet2 = new HashSet<DBRecord>();
    linkSet2.add(session.newEntity());
    assertEquals(PropertyType.LINKSET, PropertyType.getTypeByValue(linkSet2));

    var document = (EntityImpl) session.newEmbeddedEntity();
    document.setOwner((EntityImpl) session.newEntity());
    assertEquals(PropertyType.EMBEDDED, PropertyType.getTypeByValue(document));
    session.rollback();
  }

  public class CustomClass implements SerializableStream {

    @Override
    public byte[] toStream() throws SerializationException {
      return null;
    }

    @Override
    public SerializableStream fromStream(byte[] iStream) throws SerializationException {
      return null;
    }
  }

  public class DocumentSer implements EntitySerializable {

    @Override
    public EntityImpl toEntity(DatabaseSessionInternal db) {
      // TODO Auto-generated method stub
      return null;
    }

    @Override
    public void fromDocument(EntityImpl document) {
      // TODO Auto-generated method stub

    }
  }

  public class ClassSerializable implements Serializable {

    private String aaa;
  }
}
