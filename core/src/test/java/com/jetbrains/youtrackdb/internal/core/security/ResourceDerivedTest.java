/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 */

package com.jetbrains.youtrackdb.internal.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class ResourceDerivedTest {

  private YouTrackDBImpl youTrackDB;

  @Before
  public void before() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
    var db = youTrackDB.open("test", "admin", "admin");

    var tx = db.begin();
    tx.command(
        "CREATE SECURITY POLICY r SET create = (false), read = (true), before update = (false),"
            + " after update = (false), delete = (false), execute = (true)");
    tx.command(
        "CREATE SECURITY POLICY rw SET create = (true), read = (true), before update = (true),"
            + " after update = (true), delete = (true), execute = (true)");
    tx.commit();

    db.computeScript("sql", "CREATE CLASS Customer extends V ABSTRACT");
    db.computeScript("sql", "CREATE PROPERTY Customer.name String");

    db.computeScript("sql", "CREATE CLASS Customer_t1 extends Customer");
    db.computeScript("sql", "CREATE CLASS Customer_t2 extends Customer");

    db.computeScript("sql", "CREATE CLASS Customer_u1 extends Customer_t1");
    db.computeScript("sql", "CREATE CLASS Customer_u2 extends Customer_t2");

    tx = db.begin();
    tx.command("INSERT INTO ORole SET name = 'tenant1', mode = 0");
    tx.commit();
    tx = db.begin();
    tx.command("ALTER ROLE tenant1 set policy rw ON database.class.*.*");
    tx.commit();
    tx = db.begin();
    tx.command("UPDATE ORole SET rules = {'database.class.customer': 2} WHERE name = ?", "tenant1");
    tx.commit();
    tx = db.begin();
    tx.command("ALTER ROLE tenant1 set policy r ON database.class.Customer");
    tx.command(
        "UPDATE ORole SET rules = {'database.class.customer_t1': 31} WHERE name = ?", "tenant1");
    tx.commit();
    tx = db.begin();
    tx.command("ALTER ROLE tenant1 set policy rw ON database.class.Customer_t1");
    tx.command(
        "UPDATE ORole SET rules = {'database.class.customer_t2': 2} WHERE name = ?", "tenant1");
    tx.commit();

    tx = db.begin();
    tx.command("ALTER ROLE tenant1 set policy r ON database.class.Custome_t2r");
    tx.command(
        "UPDATE ORole SET rules = {'database.class.customer_u2': 0} WHERE name = ?", "tenant1");
    tx.command(
        "UPDATE ORole SET inheritedRole = (SELECT FROM ORole WHERE name = 'reader') WHERE name = ?",
        "tenant1");

    tx.command(
        "INSERT INTO OUser set name = 'tenant1', password = 'password', status = 'ACTIVE', roles ="
            + " (SELECT FROM ORole WHERE name = 'tenant1')");

    tx.command("INSERT INTO ORole SET name = 'tenant2', mode = 0");
    tx.commit();

    tx = db.begin();
    tx.command("ALTER ROLE tenant2 set policy rw ON database.class.*.*");
    tx.commit();

    tx = db.begin();
    tx.command(
        "UPDATE ORole SET rules = {'database.class.customer_t1': 0} WHERE name = ?", "tenant2");
    tx.command(
        "UPDATE ORole SET rules = {'database.class.customer_t2': 31} WHERE name = ?", "tenant2");
    tx.command("ALTER ROLE tenant2 set policy rw ON database.class.Customer_t2");
    tx.command("UPDATE ORole SET rules = {'database.class.customer': 0} WHERE name = ?", "tenant2");
    tx.command(
        "UPDATE ORole SET inheritedRole = (SELECT FROM ORole WHERE name = 'reader') WHERE name ="
            + " 'tenant2'");

    tx.command(
        "INSERT INTO OUser set name = 'tenant2', password = 'password', status = 'ACTIVE', roles ="
            + " (SELECT FROM ORole WHERE name = 'tenant2')");

    tx.command("create vertex Customer_t1 set name='Amy'");
    tx.command("create vertex Customer_t2 set name='Bob'");

    tx.command("create vertex Customer_u1 set name='Fred'");
    tx.command("create vertex Customer_u2 set name='George'");
    tx.commit();

    db.close();
  }

  private ResultSet query(DatabaseSessionEmbedded db, String sql, Object... params) {
    return db.query(sql, params);
  }

  @After
  public void after() {
    youTrackDB.close();
  }

  @Test
  // This tests for a result size of three.  The "Customer_u2" record should not be included.
  public void shouldTestFiltering() {

    var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "tenant1", "password");

    var tx = db.begin();
    try {
      var result = query(db, "SELECT FROM Customer");

      assertThat(result.stream().toList()).hasSize(3);
      tx.commit();
    } finally {
      db.close();
    }
  }

  @Test
  // This should return the record in "Customer_t2" but filter out the "Customer_u2" record.
  public void shouldTestCustomer_t2() {

    var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "tenant1", "password");

    var tx = db.begin();
    try {
      var result = query(db, "SELECT FROM Customer_t2");

      assertThat(result.toList()).hasSize(1);
      tx.commit();
    } finally {
      db.close();
    }
  }

  @SuppressWarnings("JUnit4TestNotRun")
  public void shouldTestAccess2() {

    var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "tenant1", "password");

    var tx = db.begin();
    try {
      var result = query(db, "SELECT FROM Customer_u2");
      assertThat(result.toList()).hasSize(0);
    } finally {
      tx.rollback();
      db.close();
    }
  }

  @SuppressWarnings("JUnit4TestNotRun")
  public void shouldTestCustomer() {
    var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "tenant2", "password");
    var tx = db.begin();
    try {
      var result = query(db, "SELECT FROM Customer");
      assertThat(result.toList()).hasSize(0);
    } finally {
      tx.rollback();
      db.close();
    }
  }

  @Test
  // This tests for a result size of two.  The "Customer_t1" and "Customer_u1" records should not be
  // included.
  public void shouldTestCustomer_t2Tenant2() {

    var db = (DatabaseSessionEmbedded) youTrackDB.open("test", "tenant2", "password");
    var tx = db.begin();
    try {
      var result = query(db, "SELECT FROM Customer_t2");

      assertThat(result.toList()).hasSize(2);
      tx.commit();
    } finally {
      db.close();
    }
  }
}
