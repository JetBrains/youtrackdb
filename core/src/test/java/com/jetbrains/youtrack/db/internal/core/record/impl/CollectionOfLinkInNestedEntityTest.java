package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import java.util.Set;
import org.apache.commons.collections4.SetUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

public class CollectionOfLinkInNestedEntityTest extends DbTestBase {

  @Test
  public void nestedLinkSet() {
    session.begin();
    var doc1 = (EntityImpl) session.newEntity();
    doc1.field("value", "item 1");
    var doc2 = (EntityImpl) session.newEntity();
    doc2.field("value", "item 2");
    var nested = (EntityImpl) session.newEmbeddedEntity();
    var set = new LinkSet(nested);
    set.add(doc1);
    set.add(doc2);

    nested.field("set", set);

    var base = (EntityImpl) session.newEntity();
    base.field("nested", nested, PropertyType.EMBEDDED);
    Identifiable id = base;
    session.commit();

    session.begin();
    EntityImpl base1 = session.load(id.getIdentity());
    EntityImpl nest1 = base1.field("nested");
    assertNotNull(nest1);

    assertEquals(SetUtils.hashSet(doc1.getIdentity(), doc2.getIdentity()),
        nest1.<Set<Identifiable>>field("set"));
    session.commit();
  }

  @Test
  public void nestedLinkList() {
    session.begin();
    var doc1 = (EntityImpl) session.newEntity();
    doc1.field("value", "item 1");
    var doc2 = (EntityImpl) session.newEntity();
    doc2.field("value", "item 2");
    var nested = (EntityImpl) session.newEmbeddedEntity();
    var list = new LinkList(nested);
    list.add(doc1);
    list.add(doc2);

    nested.field("list", list);

    var base = (EntityImpl) session.newEntity();
    base.field("nested", nested, PropertyType.EMBEDDED);
    Identifiable id = base;
    session.commit();

    session.begin();
    EntityImpl base1 = session.load(id.getIdentity());
    EntityImpl nest1 = base1.field("nested");
    assertNotNull(nest1);
    assertEquals(list, nest1.field("list"));
    session.commit();
  }

  @Test
  public void nestedLinkMap() {
    session.begin();
    var doc1 = (EntityImpl) session.newEntity();
    doc1.field("value", "item 1");
    var doc2 = (EntityImpl) session.newEntity();
    doc2.field("value", "item 2");
    var nested = (EntityImpl) session.newEmbeddedEntity();
    var map = new LinkMap(nested);
    map.put("record1", doc1);
    map.put("record2", doc2);

    nested.field("map", map);

    var base = (EntityImpl) session.newEntity();
    base.field("nested", nested, PropertyType.EMBEDDED);
    Identifiable id = base;
    session.commit();

    session.begin();
    EntityImpl base1 = session.load(id.getIdentity());
    EntityImpl nest1 = base1.field("nested");
    assertNotNull(nest1);
    assertEquals(map, nest1.field("map"));
    session.commit();
  }
}
