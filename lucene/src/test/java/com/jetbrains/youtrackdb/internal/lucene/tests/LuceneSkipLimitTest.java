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

package com.jetbrains.youtrackdb.internal.lucene.tests;

import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneSkipLimitTest extends LuceneBaseTest {

  @Before
  public void init() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.computeScript("sql", getScriptFromStream(stream));

    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE");
    session.execute("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE");
  }

  @Test
  public void testContext() {
    session.begin();
    var docs =
        session.query("select * from Song where search_fields(['title'],\"(title:man)\")=true");

    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(14);
    docs.close();
    docs =
        session.query(
            "select * from Song where search_fields(['title'],\"(title:man)\")=true skip 10 limit"
                + " 10");

    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(4);

    //    Assert.assertEquals(docs.contains(doc), false);
    docs.close();
    docs =
        session.query(
            "select * from Song where search_fields(['title'],\"(title:man)\")=true skip 14 limit"
                + " 10");

    Assertions.assertThat(IteratorUtils.count(docs)).isEqualTo(0);
    docs.close();
    session.commit();
  }
}
