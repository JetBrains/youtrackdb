/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.command.script.CommandScript;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.ClusterPositionMap;
import com.jetbrains.youtrack.db.internal.core.storage.cluster.PaginatedCluster;
import com.jetbrains.youtrack.db.internal.core.storage.disk.LocalPaginatedStorage;
import java.io.File;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class SQLCommandsTest extends BaseDBTest {

  @Parameters(value = "remote")
  public SQLCommandsTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  public void createProperty() {
    Schema schema = session.getMetadata().getSchema();
    if (!schema.existsClass("account")) {
      schema.createClass("account");
    }

    session.command("create property account.timesheet string").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("timesheet")
            .getType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createProperty")
  public void createLinkedClassProperty() {
    session.command("create property account.knows embeddedmap account").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("knows")
            .getType(),
        PropertyType.EMBEDDEDMAP);
    Assert.assertEquals(
        session
            .getMetadata()
            .getSchema()
            .getClass("account")
            .getProperty("knows")
            .getLinkedClass(),
        session.getMetadata().getSchema().getClass("account"));
  }

  @Test(dependsOnMethods = "createLinkedClassProperty")
  public void createLinkedTypeProperty() {
    session.command("create property account.tags embeddedlist string").close();

    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("tags")
            .getType(),
        PropertyType.EMBEDDEDLIST);
    Assert.assertEquals(
        session.getMetadata().getSchema().getClass("account").getProperty("tags")
            .getLinkedType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createLinkedTypeProperty")
  public void removeProperty() {
    session.command("drop property account.timesheet").close();
    session.command("drop property account.tags").close();

    Assert.assertFalse(
        session.getMetadata().getSchema().getClass("account").existsProperty("timesheet"));
    Assert.assertFalse(
        session.getMetadata().getSchema().getClass("account").existsProperty("tags"));
  }

  @Test(dependsOnMethods = "removeProperty")
  public void testSQLScript() {
    var cmd = "";
    cmd += "select from ouser limit 1;begin;";
    cmd += "let a = create vertex set script = true\n";
    cmd += "let b = select from v limit 1;";
    cmd += "create edge from $a to $b;";
    cmd += "commit;";
    cmd += "return $a;";

    var result = session.command(new CommandScript("sql", cmd)).execute(session);

    Assert.assertTrue(result instanceof Identifiable);
    Assert.assertTrue(((Identifiable) result).getRecord(session) instanceof EntityImpl);
    EntityImpl entity = session.bindToSession(
        ((Identifiable) result).getRecord(session));
    Assert.assertTrue(
        entity.getProperty("script"));
  }

  public void testClusterRename() {
    if (session.getURL().startsWith("memory:")) {
      return;
    }

    var names = session.getClusterNames();
    Assert.assertFalse(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    session.command("create cluster testClusterRename").close();

    names = session.getClusterNames();
    Assert.assertTrue(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    session.command("alter cluster testClusterRename name testClusterRename42").close();
    names = session.getClusterNames();

    Assert.assertTrue(names.contains("testClusterRename42".toLowerCase(Locale.ENGLISH)));
    Assert.assertFalse(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    if (!remoteDB && databaseType.equals(DatabaseType.PLOCAL)) {
      var storagePath = session.getStorage().getConfiguration().getDirectory();

      final var wowCache =
          (WOWCache) ((LocalPaginatedStorage) session.getStorage()).getWriteCache();

      var dataFile =
          new File(
              storagePath,
              wowCache.nativeFileNameById(
                  wowCache.fileIdByName("testClusterRename42" + PaginatedCluster.DEF_EXTENSION)));
      var mapFile =
          new File(
              storagePath,
              wowCache.nativeFileNameById(
                  wowCache.fileIdByName(
                      "testClusterRename42" + ClusterPositionMap.DEF_EXTENSION)));

      Assert.assertTrue(dataFile.exists());
      Assert.assertTrue(mapFile.exists());
    }
  }
}
