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
import java.util.Collection;
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
    Schema schema = database.getMetadata().getSchema();
    if (!schema.existsClass("account")) {
      schema.createClass("account");
    }

    database.command("create property account.timesheet string").close();

    Assert.assertEquals(
        database.getMetadata().getSchema().getClass("account").getProperty(database, "timesheet")
            .getType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createProperty")
  public void createLinkedClassProperty() {
    database.command("create property account.knows embeddedmap account").close();

    Assert.assertEquals(
        database.getMetadata().getSchema().getClass("account").getProperty(database, "knows")
            .getType(),
        PropertyType.EMBEDDEDMAP);
    Assert.assertEquals(
        database
            .getMetadata()
            .getSchema()
            .getClass("account")
            .getProperty(database, "knows")
            .getLinkedClass(database),
        database.getMetadata().getSchema().getClass("account"));
  }

  @Test(dependsOnMethods = "createLinkedClassProperty")
  public void createLinkedTypeProperty() {
    database.command("create property account.tags embeddedlist string").close();

    Assert.assertEquals(
        database.getMetadata().getSchema().getClass("account").getProperty(database, "tags")
            .getType(),
        PropertyType.EMBEDDEDLIST);
    Assert.assertEquals(
        database.getMetadata().getSchema().getClass("account").getProperty(database, "tags")
            .getLinkedType(),
        PropertyType.STRING);
  }

  @Test(dependsOnMethods = "createLinkedTypeProperty")
  public void removeProperty() {
    database.command("drop property account.timesheet").close();
    database.command("drop property account.tags").close();

    Assert.assertFalse(
        database.getMetadata().getSchema().getClass("account")
            .existsProperty(database, "timesheet"));
    Assert.assertFalse(
        database.getMetadata().getSchema().getClass("account").existsProperty(database, "tags"));
  }

  @Test(dependsOnMethods = "removeProperty")
  public void testSQLScript() {
    String cmd = "";
    cmd += "select from ouser limit 1;begin;";
    cmd += "let a = create vertex set script = true\n";
    cmd += "let b = select from v limit 1;";
    cmd += "create edge from $a to $b;";
    cmd += "commit;";
    cmd += "return $a;";

    Object result = database.command(new CommandScript("sql", cmd)).execute(database);

    Assert.assertTrue(result instanceof Identifiable);
    Assert.assertTrue(((Identifiable) result).getRecord() instanceof EntityImpl);
    Assert.assertTrue(
        database.bindToSession((EntityImpl) ((Identifiable) result).getRecord())
            .field("script"));
  }

  public void testClusterRename() {
    if (database.getURL().startsWith("memory:")) {
      return;
    }

    Collection<String> names = database.getClusterNames();
    Assert.assertFalse(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    database.command("create cluster testClusterRename").close();

    names = database.getClusterNames();
    Assert.assertTrue(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    database.command("alter cluster testClusterRename name testClusterRename42").close();
    names = database.getClusterNames();

    Assert.assertTrue(names.contains("testClusterRename42".toLowerCase(Locale.ENGLISH)));
    Assert.assertFalse(names.contains("testClusterRename".toLowerCase(Locale.ENGLISH)));

    if (!remoteDB && databaseType.equals(DatabaseType.PLOCAL)) {
      String storagePath = database.getStorage().getConfiguration().getDirectory();

      final WOWCache wowCache =
          (WOWCache) ((LocalPaginatedStorage) database.getStorage()).getWriteCache();

      File dataFile =
          new File(
              storagePath,
              wowCache.nativeFileNameById(
                  wowCache.fileIdByName("testClusterRename42" + PaginatedCluster.DEF_EXTENSION)));
      File mapFile =
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
