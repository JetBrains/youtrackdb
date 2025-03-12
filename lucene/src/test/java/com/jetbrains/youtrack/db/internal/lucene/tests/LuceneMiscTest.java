/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrack.db.internal.lucene.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import java.util.HashMap;
import java.util.Map;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneMiscTest extends LuceneBaseTest {

  @Test
  public void testDoubleLucene() {

    session.command("create class Test extends V");
    session.command("create property Test.attr1 string");
    session.command("create index Test.attr1 on Test(attr1) FULLTEXT ENGINE LUCENE");
    session.command("create property Test.attr2 string");
    session.command("create index Test.attr2 on Test(attr2) FULLTEXT ENGINE LUCENE");

    session.begin();
    session.command("insert into Test set attr1='foo', attr2='bar'");
    session.command("insert into Test set attr1='bar', attr2='foo'");
    session.commit();

    var results =
        session.query(
            "select from Test where search_index('Test.attr1',\"foo*\") =true OR"
                + " search_index('Test.attr2', \"foo*\")=true  ");
    assertThat(results).hasSize(2);
    results.close();

    results =
        session.query(
            "select from Test where SEARCH_FIELDS( ['attr1'], 'bar') = true OR"
                + " SEARCH_FIELDS(['attr2'], 'bar*' )= true ");
    assertThat(results).hasSize(2);
    results.close();

    results =
        session.query(
            "select from Test where SEARCH_FIELDS( ['attr1'], 'foo*') = true AND"
                + " SEARCH_FIELDS(['attr2'], 'bar*') = true");
    assertThat(results).hasSize(1);
    results.close();

    results =
        session.query(
            "select from Test where SEARCH_FIELDS( ['attr1'], 'bar*') = true AND"
                + " SEARCH_FIELDS(['attr2'], 'foo*')= true");
    assertThat(results).hasSize(1);
    results.close();
  }

  @Test
  public void testSubLucene() {

    session.command("create class Person extends V");

    session.command("create property Person.name string");

    session.command("create index Person.name on Person(name) FULLTEXT ENGINE LUCENE");

    session.begin();
    session.command("insert into Person set name='Enrico', age=18");
    session.commit();

    var query =
        "select  from (select from Person where age = 18) where search_fields(['name'],'Enrico') ="
            + " true";
    var results = session.query(query);

    assertThat(results).hasSize(1);
    results.close();

    // WITH PROJECTION it works using index directly

    query =
        "select  from (select name from Person where age = 18) where"
            + " search_index('Person.name','Enrico') = true";
    results = session.query(query);
    assertThat(results).hasSize(1);
    results.close();
  }

  @Test
  public void testNamedParams() {

    session.command("create class Test extends V");

    session.command("create property Test.attr1 string");

    session.command("create index Test.attr1 on Test(attr1) FULLTEXT ENGINE LUCENE");

    session.begin();
    session.command("insert into Test set attr1='foo', attr2='bar'");
    session.commit();

    var query = "select from Test where  search_class( :name) =true";
    Map params = new HashMap();
    params.put("name", "FOO or");
    var results = session.command(query, params);

    assertThat(results).hasSize(1);
  }

  @Test
  @Ignore
  public void dottedNotationTest() {

    Schema schema = session.getMetadata().getSchema();
    var v = schema.getClass("V");
    var e = schema.getClass("E");
    var author = schema.createClass("Author", v);
    author.createProperty("name", PropertyType.STRING);

    var song = schema.createClass("Song", v);
    song.createProperty("title", PropertyType.STRING);

    var authorOf = schema.createClass("AuthorOf", e);
    authorOf.createProperty("in", PropertyType.LINK, song);
    session.commit();

    session.command("create index AuthorOf.in on AuthorOf (in) NOTUNIQUE");
    session.command("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE");

    var authorVertex = session.newVertex("Author");
    authorVertex.setProperty("name", "Bob Dylan");

    session.begin();
    session.commit();

    var songVertex = session.newVertex("Song");
    songVertex.setProperty("title", "hurricane");

    session.begin();
    session.commit();

    session.begin();
    authorVertex.addEdge(songVertex, "AuthorOf");
    session.commit();

    var results = session.query("select from AuthorOf");

    assertThat(results).hasSize(1);

    results.close();
    results = session.query("select from AuthorOf where in.title lucene 'hurricane'");

    assertThat(results).hasSize(1);
    results.close();
  }
}
