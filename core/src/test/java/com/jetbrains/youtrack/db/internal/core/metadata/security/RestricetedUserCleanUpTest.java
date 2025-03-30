package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class RestricetedUserCleanUpTest extends DbTestBase {

  @Test
  public void testAutoCleanUserAfterDelete() {
    Schema schema = session.getMetadata().getSchema();
    schema.createClass("TestRecord", schema.getClass(SecurityShared.RESTRICTED_CLASSNAME));

    System.gc();
    DirectMemoryAllocator.instance().checkTrackedPointerLeaks();

    var security = session.getMetadata().getSecurity();

    session.begin();
    var auser = security.createUser("auser", "wherever", new String[]{});
    var reader = security.getUser("admin");
    var doc = (EntityImpl) session.newEntity("TestRecord");

    var users = new HashSet<Identifiable>();
    users.add(auser.getIdentity());
    users.add(reader.getIdentity());

    doc.newLinkSet(SecurityShared.ALLOW_READ_FIELD).addAll(users);
    doc.newLinkSet(SecurityShared.ALLOW_UPDATE_FIELD).addAll(users);
    doc.newLinkSet(SecurityShared.ALLOW_DELETE_FIELD).addAll(users);
    doc.newLinkSet(SecurityShared.ALLOW_ALL_FIELD).addAll(users);

    var rid = doc;
    session.commit();

    System.gc();
    DirectMemoryAllocator.instance().checkTrackedPointerLeaks();
    session.begin();
    security.dropUser("auser");
    session.commit();

    session.begin();
    doc = session.load(rid.getIdentity());
    Assert.assertEquals(1, ((Set<?>) doc.getProperty(SecurityShared.ALLOW_ALL_FIELD)).size());
    Assert.assertEquals(1, ((Set<?>) doc.getProperty(SecurityShared.ALLOW_UPDATE_FIELD)).size());
    Assert.assertEquals(1, ((Set<?>) doc.getProperty(SecurityShared.ALLOW_DELETE_FIELD)).size());
    Assert.assertEquals(1, ((Set<?>) doc.getProperty(SecurityShared.ALLOW_ALL_FIELD)).size());

    doc.setProperty("abc", "abc");

    session.commit();

    System.gc();
    DirectMemoryAllocator.instance().checkTrackedPointerLeaks();
//
//    db.begin();
//    doc = db.load(rid.getIdentity());
//
//    ((Set<?>) doc.field(SecurityShared.ALLOW_ALL_FIELD)).remove(null);
//    ((Set<?>) doc.field(SecurityShared.ALLOW_UPDATE_FIELD)).remove(null);
//    ((Set<?>) doc.field(SecurityShared.ALLOW_DELETE_FIELD)).remove(null);
//    ((Set<?>) doc.field(SecurityShared.ALLOW_ALL_FIELD)).remove(null);
//
//    Assert.assertEquals(1, ((Set<?>) doc.field(SecurityShared.ALLOW_ALL_FIELD)).size());
//    Assert.assertEquals(1, ((Set<?>) doc.field(SecurityShared.ALLOW_UPDATE_FIELD)).size());
//    Assert.assertEquals(1, ((Set<?>) doc.field(SecurityShared.ALLOW_DELETE_FIELD)).size());
//    Assert.assertEquals(1, ((Set<?>) doc.field(SecurityShared.ALLOW_ALL_FIELD)).size());
//    doc.field("abc", "abc");
//    doc.save();
//    db.commit();
//
//    System.gc();
//    DirectMemoryAllocator.instance().checkTrackedPointerLeaks();
//
//    System.gc();
//    DirectMemoryAllocator.instance().checkTrackedPointerLeaks();
  }
}
