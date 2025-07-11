package com.jetbrains.youtrack.db.internal.core.metadata.security;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SecurityPolicyTest extends DbTestBase {

  @Test
  public void testSecurityPolicyCreate() {
    session.begin();
    var rs =
        session.query(
            "select from " + SecurityPolicy.CLASS_NAME + " WHERE name = ?", "test");
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.rollback();
    var security = session.getSharedContext().getSecurity();

    session.begin();
    security.createSecurityPolicy(session, "test");
    session.commit();

    session.begin();
    rs =
        session.query(
            "select from " + SecurityPolicy.CLASS_NAME + " WHERE name = ?", "test");
    Assert.assertTrue(rs.hasNext());
    var item = rs.next();
    Assert.assertEquals("test", item.getProperty("name"));
    Assert.assertFalse(rs.hasNext());
    rs.close();
    session.commit();
  }

  @Test
  public void testSecurityPolicyGet() {
    var security = session.getSharedContext().getSecurity();
    Assert.assertNull(security.getSecurityPolicy(session, "test"));

    session.begin();
    security.createSecurityPolicy(session, "test");
    session.commit();

    session.begin();
    Assert.assertNotNull(security.getSecurityPolicy(session, "test"));
    session.commit();
  }

  @Test
  public void testValidPredicates() {
    var security = session.getSharedContext().getSecurity();
    Assert.assertNull(security.getSecurityPolicy(session, "test"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "test");
    policy.setCreateRule("name = 'create'");
    policy.setReadRule("name = 'read'");
    policy.setBeforeUpdateRule("name = 'beforeUpdate'");
    policy.setAfterUpdateRule("name = 'afterUpdate'");
    policy.setDeleteRule("name = 'delete'");
    policy.setExecuteRule("name = 'execute'");

    security.saveSecurityPolicy(session, policy);
    session.commit();

    session.begin();
    SecurityPolicy readPolicy = security.getSecurityPolicy(session, "test");
    Assert.assertNotNull(policy);
    Assert.assertEquals("name = 'create'", readPolicy.getCreateRule());
    Assert.assertEquals("name = 'read'", readPolicy.getReadRule());
    Assert.assertEquals("name = 'beforeUpdate'", readPolicy.getBeforeUpdateRule());
    Assert.assertEquals("name = 'afterUpdate'", readPolicy.getAfterUpdateRule());
    Assert.assertEquals("name = 'delete'", readPolicy.getDeleteRule());
    Assert.assertEquals("name = 'execute'", readPolicy.getExecuteRule());
    session.commit();
  }

  @Test
  public void testInvalidPredicates() {
    var security = session.getSharedContext().getSecurity();
    Assert.assertNull(security.getSecurityPolicy(session, "test"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "test");
    session.commit();
    try {
      session.begin();
      policy.setCreateRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
    try {
      session.begin();
      policy.setReadRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
    try {
      session.begin();
      policy.setBeforeUpdateRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
    try {
      session.begin();
      policy.setAfterUpdateRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
    try {
      session.begin();
      policy.setDeleteRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
    try {
      session.begin();
      policy.setExecuteRule("foo bar");
      session.commit();
      Assert.fail();
    } catch (IllegalArgumentException ex) {
    }
  }

  @Test
  public void testAddPolicyToRole() {
    var security = session.getSharedContext().getSecurity();
    Assert.assertNull(security.getSecurityPolicy(session, "test"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "test");
    policy.setCreateRule("1 = 1");
    policy.setBeforeUpdateRule("1 = 2");
    policy.setActive(true);
    security.saveSecurityPolicy(session, policy);
    session.commit();

    session.begin();
    var reader = security.getRole(session, "reader");
    var resource = "database.class.Person";
    security.setSecurityPolicy(session, reader, resource, policy);
    session.commit();

    session.begin();
    var policyRid = policy.getIdentity();
    try (var rs = session.query("select from " + Role.CLASS_NAME + " where name = 'reader'")) {
      Map<String, Identifiable> rolePolicies = rs.next().getProperty("policies");
      var id = rolePolicies.get(resource);
      Assert.assertEquals(id.getIdentity(), policyRid);
    }

    var policy2 = security.getSecurityPolicy(session, reader, resource);
    Assert.assertNotNull(policy2);
    Assert.assertEquals(policy2.getIdentity(), policyRid);
    session.commit();
  }

  @Test
  public void testRemovePolicyToRole() {
    var security = session.getSharedContext().getSecurity();
    Assert.assertNull(security.getSecurityPolicy(session, "test"));

    session.begin();
    var policy = security.createSecurityPolicy(session, "test");
    policy.setCreateRule("1 = 1");
    policy.setBeforeUpdateRule("1 = 2");
    policy.setActive(true);
    security.saveSecurityPolicy(session, policy);
    session.commit();

    session.begin();
    var reader = security.getRole(session, "reader");
    var resource = "database.class.Person";
    security.setSecurityPolicy(session, reader, resource, policy);
    security.removeSecurityPolicy(session, reader, resource);
    session.commit();

    Assert.assertNull(security.getSecurityPolicy(session, reader, resource));
  }
}
