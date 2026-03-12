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
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.security.SecurityManager;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class SQLFunctionsTest extends BaseDBJUnit5Test {

  @BeforeAll
  void setUpData() {
    generateCompanyData();
    generateProfiles();
    generateGraphData();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryMax
  @Test
  @Order(1)
  void queryMax() {
    session.begin();
    var result = session.execute("select max(id) as max from Account");

    assertNotNull(result.next().getProperty("max"));
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryMaxInline
  @Test
  @Order(2)
  void queryMaxInline() {
    var result =
        session.query("select max(1,2,7,0,-2,3) as max").toList();

    assertEquals(1, result.size());
    for (var r : result) {
      assertNotNull(r.getProperty("max"));

      assertEquals(7, ((Number) r.getProperty("max")).intValue());
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryMin
  @Test
  @Order(3)
  void queryMin() {
    session.begin();
    var result = session.execute("select min(id) as min from Account");

    var d = result.next();
    assertNotNull(d.getProperty("min"));

    assertEquals(0L, ((Number) d.getProperty("min")).longValue());
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryMinInline
  @Test
  @Order(4)
  void queryMinInline() {
    var resultSet =
        session.query("select min(1,2,7,0,-2,3) as min").toList();

    assertEquals(1, resultSet.size());
    for (var r : resultSet) {
      assertNotNull(r.getProperty("min"));

      assertEquals(-2, ((Number) r.getProperty("min")).intValue());
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySum
  @Test
  @Order(5)
  void querySum() {
    session.begin();
    var result = session.execute("select sum(id) as sum from Account");
    var d = result.next();
    assertNotNull(d.getProperty("sum"));
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryCount
  @Test
  @Order(6)
  void queryCount() {
    var result = session.execute("select count(*) as total from Account");
    var d = result.next();
    assertNotNull(d.getProperty("total"));
    assertTrue(((Number) d.getProperty("total")).longValue() > 0);
    assertFalse(result.hasNext());
    result.close();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryCountWithConditions
  @Test
  @Order(7)
  void queryCountWithConditions() {
    var indexed = session.getMetadata().getSchema().getOrCreateClass("Indexed");
    indexed.createProperty("key", PropertyType.STRING);
    indexed.createIndex("keyed", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    session.newInstance("Indexed").setProperty("key", "one");
    session.newInstance("Indexed").setProperty("key", "two");
    session.commit();

    var resultSet =
        session.query("select count(*) as total from Indexed where key > 'one'").toList();

    assertEquals(1, resultSet.size());
    for (var result : resultSet) {
      assertNotNull(result.getProperty("total"));
      assertTrue(((Number) result.getProperty("total")).longValue() > 0);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryDistinct
  @Test
  @Order(8)
  void queryDistinct() {
    var resultSet =
        session.query("select distinct(name) as name from City").toList();
    assertTrue(resultSet.size() > 1);

    Set<String> cities = new HashSet<>();
    for (var city : resultSet) {
      String cityName = city.getProperty("name");
      assertFalse(cities.contains(cityName));
      cities.add(cityName);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryFunctionRenamed
  @Test
  @Order(9)
  void queryFunctionRenamed() {
    var result =
        session.query("select distinct(name) as dist from City").toList();

    assertTrue(result.size() > 1);
    for (var city : result) {
      assertTrue(city.hasProperty("dist"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryUnionAllAsAggregationNotRemoveDuplicates
  @Test
  @Order(10)
  void queryUnionAllAsAggregationNotRemoveDuplicates() {
    var result = session.query("select from City").toList();
    var count = result.size();

    result =
        session.query("select unionAll(name) as name from City").toList();
    Collection<Object> citiesFound = result.getFirst().getProperty("name");
    assertEquals(count, citiesFound.size());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySetNotDuplicates
  @Test
  @Order(11)
  void querySetNotDuplicates() {
    var result =
        session.query("select set(name) as name from City").toList();

    assertEquals(1, result.size());

    Collection<Object> citiesFound = result.getFirst().getProperty("name");
    assertTrue(citiesFound.size() > 1);

    Set<String> cities = new HashSet<>();
    for (var city : citiesFound) {
      assertFalse(cities.contains(city.toString()));
      cities.add(city.toString());
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryList
  @Test
  @Order(12)
  void queryList() {
    var result =
        session.query("select list(name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      List<Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#testSelectMap
  @Test
  @Order(13)
  void testSelectMap() {
    var result =
        session
            .query("select list( 1, 4, 5.00, 'john', map( 'kAA', 'vAA' ) ) as myresult")
            .toList();

    assertEquals(1, result.size());

    var document = result.getFirst();
    @SuppressWarnings("rawtypes")
    List myresult = document.getProperty("myresult");
    assertNotNull(myresult);

    assertTrue(myresult.remove(Integer.valueOf(1)));
    assertTrue(myresult.remove(Integer.valueOf(4)));
    assertTrue(myresult.remove(Float.valueOf(5)));
    assertTrue(myresult.remove("john"));

    assertEquals(1, myresult.size());

    assertInstanceOf(Map.class, myresult.getFirst(), "The object is: " + myresult.getClass());
    @SuppressWarnings("rawtypes")
    var map = (Map) myresult.getFirst();

    var value = (String) map.get("kAA");
    assertEquals("vAA", value);

    assertEquals(1, map.size());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySet
  @Test
  @Order(14)
  void querySet() {
    var result =
        session.query("select set(name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      Set<Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryMap
  @Test
  @Order(15)
  void queryMap() {
    var result =
        session.query("select map(name, country.name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      Map<Object, Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryUnionAllAsInline
  @Test
  @Order(16)
  void queryUnionAllAsInline() {
    var result =
        session.query("select unionAll(out, in) as edges from V").toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertEquals(1, d.getPropertyNames().size());
      assertTrue(d.hasProperty("edges"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryComposedAggregates
  @Test
  @Order(17)
  void queryComposedAggregates() {
    var result =
        session
            .query(
                "select MIN(id) as min, max(id) as max, AVG(id) as average, sum(id) as total"
                    + " from Account")
            .toList();

    assertEquals(1, result.size());
    for (var d : result) {
      assertNotNull(d.getProperty("min"));
      assertNotNull(d.getProperty("max"));
      assertNotNull(d.getProperty("average"));
      assertNotNull(d.getProperty("total"));

      assertTrue(
          ((Number) d.getProperty("max")).longValue() > ((Number) d.getProperty(
              "average")).longValue());
      assertTrue(
          ((Number) d.getProperty("average")).longValue() >= ((Number) d.getProperty(
              "min")).longValue());
      assertTrue(
          ((Number) d.getProperty("total")).longValue() >= ((Number) d.getProperty(
              "max")).longValue(),
          "Total " + d.getProperty("total") + " max " + d.getProperty("max"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryFormat
  @Test
  @Order(18)
  void queryFormat() {
    var result =
        session
            .query(
                "select format('%d - %s (%s)', nr, street, type, dummy ) as output from"
                    + " Account")
            .toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertNotNull(d.getProperty("output"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySysdateNoFormat
  @Test
  @Order(19)
  void querySysdateNoFormat() {
    session.begin();
    var result = session.execute("select sysdate() as date from Account");

    assertTrue(result.hasNext());
    while (result.hasNext()) {
      var d = result.next();
      assertNotNull(d.getProperty("date"));
    }
    session.commit();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySysdateWithFormat
  @Test
  @Order(20)
  void querySysdateWithFormat() {
    var result =
        session.query("select sysdate('dd-MM-yyyy') as date from Account")
            .toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertNotNull(d.getProperty("date"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryDate
  @Test
  @Order(21)
  void queryDate() {
    var result = session.execute("select count(*) as tot from Account");

    var tot = ((Number) result.next().getProperty("tot")).intValue();
    assertFalse(result.hasNext());

    session.begin();
    long updated =
        session.execute("update Account set created = date()").next().getProperty("count");
    session.commit();

    assertEquals(tot, updated);

    var pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    var dateFormat = new SimpleDateFormat(pattern);

    result =
        session.query(
            "select from Account where created <= date('"
                + dateFormat.format(new Date())
                + "', \""
                + pattern
                + "\")");

    assertEquals(tot, result.stream().count());
    result =
        session.query(
            "select from Account where created <= date('"
                + dateFormat.format(new Date())
                + "', \""
                + pattern
                + "\")");
    while (result.hasNext()) {
      var d = result.next();
      assertNotNull(d.getProperty("created"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryUndefinedFunction
  // Original used: @Test(expectedExceptions = CommandSQLParsingException.class)
  @Test
  @Order(22)
  void queryUndefinedFunction() {
    assertThrows(CommandSQLParsingException.class,
        () -> session.query("select blaaaa(salary) as max from Account")
            .toList());
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryCustomFunction
  @Test
  @Order(23)
  void queryCustomFunction() {
    SQLEngine
        .registerFunction(
            "bigger",
            new SQLFunctionAbstract("bigger", 2, 2) {
              @Override
              public String getSyntax(DatabaseSessionEmbedded session) {
                return "bigger(<first>, <second>)";
              }

              @Override
              public Object execute(
                  Object iThis,
                  Result iCurrentRecord,
                  Object iCurrentResult,
                  final Object[] iParams,
                  CommandContext iContext) {
                if (iParams[0] == null || iParams[1] == null)
                // CHECK BOTH EXPECTED PARAMETERS
                {
                  return null;
                }

                if (!(iParams[0] instanceof Number) || !(iParams[1] instanceof Number))
                // EXCLUDE IT FROM THE RESULT SET
                {
                  return null;
                }

                // USE DOUBLE TO AVOID LOSS OF PRECISION
                final var v1 = ((Number) iParams[0]).doubleValue();
                final var v2 = ((Number) iParams[1]).doubleValue();

                return Math.max(v1, v2);
              }
            });

    session.begin();
    var result =
        session.query("select from Account where bigger(id,1000) = 1000").toList();

    assertFalse(result.isEmpty());
    for (var d : result) {
      assertTrue((Integer) d.getProperty("id") <= 1000);
    }
    session.commit();

    SQLEngine.unregisterFunction("bigger");
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#queryAsLong
  @Test
  @Order(24)
  void queryAsLong() {
    var moreThanInteger = 1 + (long) Integer.MAX_VALUE;
    var sql =
        "select numberString.asLong() as value from ( select '"
            + moreThanInteger
            + "' as numberString from Account ) limit 1";
    var result = session.query(sql).toList();

    assertEquals(1, result.size());
    for (var d : result) {
      assertNotNull(d.getProperty("value"));
      assertInstanceOf(Long.class, d.getProperty("value"));
      assertEquals(moreThanInteger, d.<Object>getProperty("value"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#testHashMethod
  @Test
  @Order(25)
  void testHashMethod() throws UnsupportedEncodingException, NoSuchAlgorithmException {
    var result =
        session
            .query("select name, name.hash() as n256, name.hash('sha-512') as n512 from OUser")
            .toList();

    assertFalse(result.isEmpty());
    for (var d : result) {
      final String name = d.getProperty("name");

      assertEquals(SecurityManager.createHash(name, "SHA-256"), d.getProperty("n256"));
      assertEquals(SecurityManager.createHash(name, "SHA-512"), d.getProperty("n512"));
    }
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#testFirstFunction
  @Test
  @Order(26)
  void testFirstFunction() {
    List<Long> sequence = new ArrayList<>(100);
    for (long i = 0; i < 100; ++i) {
      sequence.add(i);
    }

    session.begin();
    session.newVertex()
        .setProperty("sequence", session.newEmbeddedList(sequence), PropertyType.EMBEDDEDLIST);
    var newSequence = new ArrayList<>(sequence);
    newSequence.removeFirst();
    session.newVertex()
        .setProperty("sequence", session.newEmbeddedList(newSequence), PropertyType.EMBEDDEDLIST);
    session.commit();

    var result =
        session.query(
            "select first(sequence) as first from V where sequence is not null order by first")
            .toList();

    assertEquals(2, result.size());
    assertEquals(0L, result.get(0).<Object>getProperty("first"));
    assertEquals(1L, result.get(1).<Object>getProperty("first"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#testLastFunction
  @Test
  @Order(27)
  void testLastFunction() {
    List<Long> sequence = new ArrayList<>(100);
    for (long i = 0; i < 100; ++i) {
      sequence.add(i);
    }

    session.begin();
    session.newVertex().setProperty("sequence2", session.newEmbeddedList(sequence));

    var newSequence = new ArrayList<>(sequence);
    newSequence.remove(sequence.size() - 1);

    session.newVertex().setProperty("sequence2", session.newEmbeddedList(newSequence));
    session.commit();

    var result =
        session.query(
            "select last(sequence2) as last from V where sequence2 is not null order by last desc")
            .toList();

    assertEquals(2, result.size());

    assertEquals(99L, result.get(0).<Object>getProperty("last"));
    assertEquals(98L, result.get(1).<Object>getProperty("last"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLFunctionsTest#querySplit
  @Test
  @Order(28)
  void querySplit() {
    var sql = "select v.split('-') as value from ( select '1-2-3' as v ) limit 1";

    var result = session.query(sql).toList();

    assertEquals(1, result.size());
    for (var d : result) {
      assertEquals(List.of("1", "2", "3"), d.getEmbeddedList("value"));
    }
  }
}
