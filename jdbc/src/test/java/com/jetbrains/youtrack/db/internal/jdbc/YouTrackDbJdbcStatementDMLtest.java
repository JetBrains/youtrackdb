/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrack.db.internal.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.IOException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Test;

public class YouTrackDbJdbcStatementDMLtest extends YouTrackDbJdbcDbPerMethodTemplateTest {

  @Test
  public void shouldInsertANewItem() throws Exception {

    Date date = new Date(System.currentTimeMillis());

    Statement stmt = conn.createStatement();
    stmt.execute("begin");
    int updated =
        stmt.executeUpdate(
            "INSERT into Item (stringKey, intKey, text, length, date) values ('100','100','dummy"
                + " text','10','"
                + date
                + "')");
    stmt.execute("commit");

    assertThat(updated).isEqualTo(1);

    stmt = conn.createStatement();
    ResultSet rs =
        stmt.executeQuery(
            "SELECT stringKey, intKey, text, length, date FROM Item where intKey = '100' ");
    rs.next();
    assertThat(rs.getInt("intKey")).isEqualTo(100);
    assertThat(rs.getString("stringKey")).isEqualTo("100");
    assertThat(rs.getDate("date").toString()).isEqualTo(date.toString());
  }

  @Test
  public void shouldUpdateAnItem() throws Exception {

    Statement stmt = conn.createStatement();
    stmt.execute("begin");
    int updated = stmt.executeUpdate("UPDATE Item set text = 'UPDATED'  WHERE intKey = '10'");
    stmt.execute("commit");

    assertThat(stmt.getMoreResults()).isFalse();
    assertThat(updated).isEqualTo(1);

    stmt = conn.createStatement();
    ResultSet rs =
        stmt.executeQuery(
            "SELECT stringKey, intKey, text, length, date FROM Item where intKey = '10' ");
    rs.next();
    assertThat(rs.getString("text")).isEqualTo("UPDATED");
  }

  @Test
  public void shouldDeleteAnItem() throws Exception {

    Statement stmt = conn.createStatement();
    int updated = stmt.executeUpdate("DELETE FROM Item WHERE intKey = '10'");

    assertThat(stmt.getMoreResults()).isFalse();
    assertThat(updated).isEqualTo(1);

    stmt = conn.createStatement();
    ResultSet rs =
        stmt.executeQuery(
            "SELECT stringKey, intKey, text, length, date FROM Item where intKey = '10' ");
    assertThat(rs.next()).isFalse();
  }

  @Test
  public void shoulCreateClassWithProperties() throws IOException, SQLException {

    Statement stmt = conn.createStatement();

    stmt.execute("CREATE CLASS Account ");
    stmt.execute("CREATE PROPERTY Account.id INTEGER ");
    stmt.execute("CREATE PROPERTY Account.birthDate DATE ");
    stmt.execute("CREATE PROPERTY Account.binary BINARY ");
    stmt.close();

    // double value test pattern?
    var database = (DatabaseSessionInternal) conn.getDatabase();
    assertThat(database.getClusterIdByName("account")).isNotNull();
    SchemaClass account = database.getMetadata().getSchema().getClass("Account");
    assertThat(account).isNotNull();
    assertThat(account.getProperty(database, "id").getType()).isEqualTo(PropertyType.INTEGER);
    assertThat(account.getProperty(database, "birthDate").getType()).isEqualTo(PropertyType.DATE);
    assertThat(account.getProperty(database, "binary").getType()).isEqualTo(PropertyType.BINARY);
  }

  @Test
  public void shouldCreateClassWithBatchCommand() throws IOException, SQLException {

    Statement stmt = conn.createStatement();

    stmt.addBatch("CREATE CLASS Account ");
    stmt.addBatch("CREATE PROPERTY Account.id INTEGER ");
    stmt.addBatch("CREATE PROPERTY Account.birthDate DATE ");
    stmt.addBatch("CREATE PROPERTY Account.binary BINARY ");
    assertThat(stmt.executeBatch()).hasSize(4);
    stmt.close();

    // double value test pattern?
    DatabaseSessionInternal database = (DatabaseSessionInternal) conn.getDatabase();
    assertThat(database.getClusterIdByName("account")).isNotNull();
    SchemaClass account = database.getMetadata().getSchema().getClass("Account");
    assertThat(account).isNotNull();
    assertThat(account.getProperty(database, "id").getType()).isEqualTo(PropertyType.INTEGER);
    assertThat(account.getProperty(database, "birthDate").getType()).isEqualTo(PropertyType.DATE);
    assertThat(account.getProperty(database, "binary").getType()).isEqualTo(PropertyType.BINARY);
  }
}
