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
package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

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
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLFunctionsTest extends BaseDBTest {
  @BeforeClass
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    generateCompanyData();
    generateProfiles();
    generateGraphData();
  }

  @Test
  public void queryMax() {
    session.begin();
    var result = session.execute("select max(id) as max from Account");

    assertNotNull(result.next().getProperty("max"));
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void queryMaxInline() {
    var result =
        session.query("select max(1,2,7,0,-2,3) as max").toList();

    assertEquals(result.size(), 1);
    for (var r : result) {
      assertNotNull(r.getProperty("max"));

      assertEquals(((Number) r.getProperty("max")).intValue(), 7);
    }
  }

  @Test
  public void queryMin() {
    session.begin();
    var result = session.execute("select min(id) as min from Account");

    var d = result.next();
    assertNotNull(d.getProperty("min"));

    assertEquals(((Number) d.getProperty("min")).longValue(), 0L);
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void queryMinInline() {
    var resultSet =
        session.query("select min(1,2,7,0,-2,3) as min").toList();

    assertEquals(resultSet.size(), 1);
    for (var r : resultSet) {
      assertNotNull(r.getProperty("min"));

      assertEquals(((Number) r.getProperty("min")).intValue(), -2);
    }
  }

  @Test
  public void querySum() {
    session.begin();
    var result = session.execute("select sum(id) as sum from Account");
    var d = result.next();
    assertNotNull(d.getProperty("sum"));
    assertFalse(result.hasNext());
    result.close();
    session.commit();
  }

  @Test
  public void queryCount() {
    var result = session.execute("select count(*) as total from Account");
    var d = result.next();
    assertNotNull(d.getProperty("total"));
    assertTrue(((Number) d.getProperty("total")).longValue() > 0);
    assertFalse(result.hasNext());
    result.close();
  }

  @Test
  public void queryCountWithConditions() {
    var indexed = session.getMetadata().getSchema().getOrCreateClass("Indexed");
    indexed.createProperty("key", PropertyType.STRING);
    indexed.createIndex("keyed", SchemaClass.INDEX_TYPE.NOTUNIQUE, "key");

    session.begin();
    session.newInstance("Indexed").setProperty("key", "one");
    session.newInstance("Indexed").setProperty("key", "two");
    session.commit();

    var resultSet =
        session.query("select count(*) as total from Indexed where key > 'one'").toList();

    assertEquals(resultSet.size(), 1);
    for (var result : resultSet) {
      assertNotNull(result.getProperty("total"));
      assertTrue(((Number) result.getProperty("total")).longValue() > 0);
    }
  }

  @Test
  public void queryDistinct() {
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

  @Test
  public void queryFunctionRenamed() {
    var result =
        session.query("select distinct(name) as dist from City").toList();

    assertTrue(result.size() > 1);
    for (var city : result) {
      assertTrue(city.hasProperty("dist"));
    }
  }

  @Test
  public void queryUnionAllAsAggregationNotRemoveDuplicates() {
    var result = session.query("select from City").toList();
    var count = result.size();

    result =
        session.query("select unionAll(name) as name from City").toList();
    Collection<Object> citiesFound = result.getFirst().getProperty("name");
    assertEquals(citiesFound.size(), count);
  }

  @Test
  public void querySetNotDuplicates() {
    var result =
        session.query("select set(name) as name from City").toList();

    assertEquals(result.size(), 1);

    Collection<Object> citiesFound = result.getFirst().getProperty("name");
    assertTrue(citiesFound.size() > 1);

    Set<String> cities = new HashSet<>();
    for (var city : citiesFound) {
      assertFalse(cities.contains(city.toString()));
      cities.add(city.toString());
    }
  }

  @Test
  public void queryList() {
    var result =
        session.query("select list(name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      List<Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  public void testSelectMap() {
    var result =
        session
            .query("select list( 1, 4, 5.00, 'john', map( 'kAA', 'vAA' ) ) as myresult")
            .toList();

    assertEquals(result.size(), 1);

    var document = result.getFirst();
    @SuppressWarnings("rawtypes")
    List myresult = document.getProperty("myresult");
    assertNotNull(myresult);

    assertTrue(myresult.remove(Integer.valueOf(1)));
    assertTrue(myresult.remove(Integer.valueOf(4)));
    assertTrue(myresult.remove(Float.valueOf(5)));
    assertTrue(myresult.remove("john"));

    assertEquals(myresult.size(), 1);

    assertTrue(myresult.getFirst() instanceof Map, "The object is: " + myresult.getClass());
    @SuppressWarnings("rawtypes")
    var map = (Map) myresult.getFirst();

    var value = (String) map.get("kAA");
    assertEquals(value, "vAA");

    assertEquals(map.size(), 1);
  }

  @Test
  public void querySet() {
    var result =
        session.query("select set(name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      Set<Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  @Test
  public void queryMap() {
    var result =
        session.query("select map(name, country.name) as names from City").toList();

    assertFalse(result.isEmpty());

    for (var d : result) {
      Map<Object, Object> citiesFound = d.getProperty("names");
      assertTrue(citiesFound.size() > 1);
    }
  }

  @Test
  public void queryUnionAllAsInline() {
    var result =
        session.query("select unionAll(out, in) as edges from V").toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertEquals(d.getPropertyNames().size(), 1);
      assertTrue(d.hasProperty("edges"));
    }
  }

  @Test
  public void queryComposedAggregates() {
    var result =
        session
            .query(
                "select MIN(id) as min, max(id) as max, AVG(id) as average, sum(id) as total"
                    + " from Account").toList();

    assertEquals(result.size(), 1);
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

  @Test
  public void queryFormat() {
    var result =
        session
            .query(
                "select format('%d - %s (%s)', nr, street, type, dummy ) as output from"
                    + " Account").toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertNotNull(d.getProperty("output"));
    }
  }

  @Test
  public void querySysdateNoFormat() {
    session.begin();
    var result = session.execute("select sysdate() as date from Account");

    assertTrue(result.hasNext());
    while (result.hasNext()) {
      var d = result.next();
      assertNotNull(d.getProperty("date"));
    }
    session.commit();
  }

  @Test
  public void querySysdateWithFormat() {
    var result =
        session.query("select sysdate('dd-MM-yyyy') as date from Account")
            .toList();

    assertTrue(result.size() > 1);
    for (var d : result) {
      assertNotNull(d.getProperty("date"));
    }
  }

  @Test
  public void queryDate() {
    var result = session.execute("select count(*) as tot from Account");

    var tot = ((Number) result.next().getProperty("tot")).intValue();
    assertFalse(result.hasNext());

    session.begin();
    long updated =
        session.execute("update Account set created = date()").next().getProperty("count");
    session.commit();

    assertEquals(updated, tot);

    var pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    var dateFormat = new SimpleDateFormat(pattern);

    result =
        session.query(
            "select from Account where created <= date('"
                + dateFormat.format(new Date())
                + "', \""
                + pattern
                + "\")");

    assertEquals(result.stream().count(), tot);
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

  @Test(expectedExceptions = CommandSQLParsingException.class)
  public void queryUndefinedFunction() {
    session.query("select blaaaa(salary) as max from Account")
        .toList();
  }

  @Test
  public void queryCustomFunction() {
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

  @Test
  public void queryAsLong() {
    var moreThanInteger = 1 + (long) Integer.MAX_VALUE;
    var sql =
        "select numberString.asLong() as value from ( select '"
            + moreThanInteger
            + "' as numberString from Account ) limit 1";
    var result = session.query(sql).toList();

    assertEquals(result.size(), 1);
    for (var d : result) {
      assertNotNull(d.getProperty("value"));
      assertTrue(d.getProperty("value") instanceof Long);
      assertEquals(d.<Object>getProperty("value"), moreThanInteger);
    }
  }

  @Test
  public void testHashMethod() throws UnsupportedEncodingException, NoSuchAlgorithmException {
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

  @Test
  public void testFirstFunction() {
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

    assertEquals(result.size(), 2);
    assertEquals(result.get(0).<Object>getProperty("first"), 0L);
    assertEquals(result.get(1).<Object>getProperty("first"), 1L);
  }

  @Test
  public void testLastFunction() {
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

    assertEquals(result.size(), 2);

    assertEquals(result.get(0).<Object>getProperty("last"), 99L);
    assertEquals(result.get(1).<Object>getProperty("last"), 98L);
  }

  @Test
  public void querySplit() {
    var sql = "select v.split('-') as value from ( select '1-2-3' as v ) limit 1";

    var result = session.query(sql).toList();

    assertEquals(result.size(), 1);
    for (var d : result) {
      assertEquals(d.getEmbeddedList("value"), List.of("1", "2", "3"));
    }
  }
}
