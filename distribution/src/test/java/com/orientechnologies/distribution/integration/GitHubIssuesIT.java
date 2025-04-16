package com.orientechnologies.distribution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrack.db.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * This integration test keeps track of issues to avoid regressions. It creates a database called as
 * the class name, which is dropped at the end of the work.
 */
public class GitHubIssuesIT extends SingleOrientDBServerWithDatabasePerTestMethodBaseIT {

  @Test
  public void Issue7264() throws Exception {

    final var session = pool.acquire();
    final var oOtherClass = session.getSchema().createVertexClass("OtherClass");

    final var oPropertyOtherCI = oOtherClass.createProperty("OtherCI", PropertyType.STRING);
    oPropertyOtherCI.setCollate(CaseInsensitiveCollate.NAME);

    final var oPropertyOtherCS = oOtherClass.createProperty("OtherCS", PropertyType.STRING);
    oPropertyOtherCS.setCollate(DefaultCollate.NAME);

    oOtherClass.createIndex("other_ci_idx", INDEX_TYPE.NOTUNIQUE, "OtherCI");
    oOtherClass.createIndex("other_cs_idx", INDEX_TYPE.NOTUNIQUE, "OtherCS");

    session.executeInTx(tx -> {
      tx.command("INSERT INTO OtherClass SET OtherCS='abc', OtherCI='abc';");
      tx.command("INSERT INTO OtherClass SET OtherCS='ABC', OtherCI='ABC';");
      tx.command("INSERT INTO OtherClass SET OtherCS='Abc', OtherCI='Abc';");
      tx.command("INSERT INTO OtherClass SET OtherCS='aBc', OtherCI='aBc';");
      tx.command("INSERT INTO OtherClass SET OtherCS='abC', OtherCI='abC';");
      tx.command("INSERT INTO OtherClass SET OtherCS='ABc', OtherCI='ABc';");
      tx.command("INSERT INTO OtherClass SET OtherCS='aBC', OtherCI='aBC';");
      tx.command("INSERT INTO OtherClass SET OtherCS='AbC', OtherCI='AbC';");

      var results = tx.query("SELECT FROM OtherClass WHERE OtherCS='abc'");
      assertThat(results).hasSize(1);
      results = tx.query("SELECT FROM OtherClass WHERE OtherCI='abc'");
      assertThat(results).hasSize(8);
    });

    final var oClassCS = session.getSchema().createVertexClass("CaseSensitiveCollationIndex");

    final var oPropertyGroupCS = oClassCS.createProperty("Group", PropertyType.STRING);
    oPropertyGroupCS.setCollate(DefaultCollate.NAME);
    final var oPropertyNameCS = oClassCS.createProperty("Name", PropertyType.STRING);
    oPropertyNameCS.setCollate(DefaultCollate.NAME);
    final var oPropertyVersionCS = oClassCS.createProperty("Version", PropertyType.STRING);
    oPropertyVersionCS.setCollate(DefaultCollate.NAME);

    oClassCS.createIndex(
        "group_name_version_cs_idx", INDEX_TYPE.NOTUNIQUE, "Group", "Name", "Version");

    session.executeInTx(tx -> {
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='abc', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='ABC', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='Abc', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='aBc', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='abC', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='ABc', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='aBC', Version='1';");
      tx.command(
          "INSERT INTO CaseSensitiveCollationIndex SET `Group`='1', Name='AbC', Version='1';");

      var results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE Version='1' AND `Group` = '1' AND"
                  + " Name='abc'");
      assertThat(results).hasSize(1);

      results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE Version='1' AND Name='abc' AND `Group` ="
                  + " '1'");
      assertThat(results).hasSize(1);

      results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE `Group` = '1' AND Name='abc' AND"
                  + " Version='1'");
      assertThat(results).hasSize(1);

      results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE `Group` = '1' AND Version='1' AND"
                  + " Name='abc'");
      assertThat(results).hasSize(1);

      results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE Name='abc' AND Version='1' AND `Group` ="
                  + " '1'");
      assertThat(results).hasSize(1);

      results =
          tx.query(
              "SELECT FROM CaseSensitiveCollationIndex WHERE Name='abc' AND `Group` = '1' AND"
                  + " Version='1'");
      assertThat(results).hasSize(1);
    });

    final var oClassCI = session.getSchema().createVertexClass("CaseInsensitiveCollationIndex");

    final var oPropertyGroupCI = oClassCI.createProperty("Group", PropertyType.STRING);
    oPropertyGroupCI.setCollate(CaseInsensitiveCollate.NAME);
    final var oPropertyNameCI = oClassCI.createProperty("Name", PropertyType.STRING);
    oPropertyNameCI.setCollate(CaseInsensitiveCollate.NAME);
    final var oPropertyVersionCI = oClassCI.createProperty("Version", PropertyType.STRING);
    oPropertyVersionCI.setCollate(CaseInsensitiveCollate.NAME);

    oClassCI.createIndex(
        "group_name_version_ci_idx", INDEX_TYPE.NOTUNIQUE, "Group", "Name", "Version");

    session.executeInTx(tx -> {
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='abc', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='ABC', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='Abc', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='aBc', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='abC', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='ABc', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='aBC', Version='1';");
      tx.command(
          "INSERT INTO CaseInsensitiveCollationIndex SET `Group`='1', Name='AbC', Version='1';");

      var results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE Version='1' AND `Group` = '1' AND"
                  + " Name='abc'");
      assertThat(results).hasSize(8);

      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE Version='1' AND Name='abc' AND `Group`"
                  + " = '1'");
      assertThat(results).hasSize(8);

      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE `Group` = '1' AND Name='abc' AND"
                  + " Version='1'");

      assertThat(results).hasSize(8);

      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE `Group` = '1' AND Version='1' AND"
                  + " Name='abc'");
      assertThat(results).hasSize(8);

      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE Name='abc' AND Version='1' AND `Group`"
                  + " = '1'");
      assertThat(results).hasSize(8);

      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE Name='abc' AND `Group` = '1' AND"
                  + " Version='1'");
      assertThat(results).hasSize(8);

      // test that Group = 1 (integer) is correctly converted to String
      results =
          tx.query(
              "SELECT FROM CaseInsensitiveCollationIndex WHERE Name='abc' AND `Group` = 1 AND"
                  + " Version='1'");
      assertThat(results).hasSize(8);
    });
  }

  @Test
  public void Issue7249() throws Exception {
    final var session = ((DatabaseSessionInternal) pool.acquire());

    session.command("CREATE CLASS t7249Profiles EXTENDS V;");
    session.command("CREATE CLASS t7249HasFriend EXTENDS E;");

    session.executeInTx(tx -> {
      session.command("INSERT INTO t7249Profiles SET Name = 'Santo';");
      session.command("INSERT INTO t7249Profiles SET Name = 'Luca';");
      session.command("INSERT INTO t7249Profiles SET Name = 'Luigi';");
      session.command("INSERT INTO t7249Profiles SET Name = 'Colin';");
      session.command("INSERT INTO t7249Profiles SET Name = 'Enrico';");

      session.command(
          "CREATE EDGE t7249HasFriend FROM (SELECT FROM t7249Profiles WHERE Name='Santo') TO (SELECT"
              + " FROM t7249Profiles WHERE Name='Luca');");
      session.command(
          "CREATE EDGE t7249HasFriend FROM (SELECT FROM t7249Profiles WHERE Name='Santo') TO (SELECT"
              + " FROM t7249Profiles WHERE Name='Luigi');");
      session.command(
          "CREATE EDGE t7249HasFriend FROM (SELECT FROM t7249Profiles WHERE Name='Santo') TO (SELECT"
              + " FROM t7249Profiles WHERE Name='Colin');");
      session.command(
          "CREATE EDGE t7249HasFriend FROM (SELECT FROM t7249Profiles WHERE Name='Enrico') TO (SELECT"
              + " FROM t7249Profiles WHERE Name='Santo');");

      var rs =
          session.query(
              "SELECT in('t7249HasFriend').size() as InFriendsNumber FROM t7249Profiles WHERE"
                  + " Name='Santo'");
      var results = rs.stream().collect(Collectors.toList());

      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("InFriendsNumber")).isEqualTo(1);

      rs =
          session.query(
              "SELECT out('t7249HasFriend').size() as OutFriendsNumber FROM t7249Profiles WHERE"
                  + " Name='Santo'");
      results = rs.stream().collect(Collectors.toList());

      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("OutFriendsNumber")).isEqualTo(3);

      rs =
          session.query(
              "SELECT both('t7249HasFriend').size() as TotalFriendsNumber FROM t7249Profiles WHERE"
                  + " Name='Santo'");
      results = rs.stream().collect(Collectors.toList());

      assertThat(results).hasSize(1);
      assertThat(results.get(0).<Integer>getProperty("TotalFriendsNumber")).isEqualTo(4);
    });
  }

  @Test
  public void Issue7256() throws Exception {

    final var session = ((DatabaseSessionInternal) pool.acquire());

    session.command("CREATE CLASS t7265Customers EXTENDS V;");
    session.command("CREATE CLASS t7265Services EXTENDS V;");
    session.command("CREATE CLASS t7265Hotels EXTENDS V, t7265Services;");
    session.command("CREATE CLASS t7265Restaurants EXTENDS V, t7265Services;");
    session.command("CREATE CLASS t7265Countries EXTENDS V;");

    session.command("CREATE CLASS t7265IsFromCountry EXTENDS E;");
    session.command("CREATE CLASS t7265HasUsedService EXTENDS E;");
    session.command("CREATE CLASS t7265HasStayed EXTENDS E, t7265HasUsedService;");
    session.command("CREATE CLASS t7265HasEaten EXTENDS E, t7265HasUsedService;");

    session.executeInTx(tx -> {
      session.command("INSERT INTO t7265Customers SET OrderedId = 1, Phone = '+1400844724';");
      session.command(
          "INSERT INTO t7265Hotels SET Id = 1, Name = 'Best Western Ascott', Type = 'hotel';");
      session.command(
          "INSERT INTO t7265Restaurants SET Id = 1, Name = 'La Brasserie de Milan', Type ="
              + " 'restaurant';");
      session.command("INSERT INTO t7265Countries SET Id = 1, Code = 'AD', Name = 'Andorra';");

      session.command(
          "CREATE EDGE t7265HasEaten FROM (SELECT FROM t7265Customers WHERE OrderedId=1) TO (SELECT"
              + " FROM t7265Restaurants WHERE Id=1);");
      session.command(
          "CREATE EDGE t7265HasStayed FROM (SELECT FROM t7265Customers WHERE OrderedId=1) TO (SELECT"
              + " FROM t7265Hotels WHERE Id=1);");
      session.command(
          "CREATE EDGE t7265IsFromCountry FROM (SELECT FROM t7265Customers WHERE OrderedId=1) TO"
              + " (SELECT FROM t7265Countries WHERE Id=1);");

      final var results =
          session.query(
              "MATCH {class: t7265Customers, as: customer, where: (OrderedId=1)}--{Class:"
                  + " t7265Services, as: service} RETURN service.Name");

      assertThat(results).hasSize(2);
    });
  }
}
