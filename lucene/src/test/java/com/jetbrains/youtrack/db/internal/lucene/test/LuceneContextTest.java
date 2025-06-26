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

package com.jetbrains.youtrack.db.internal.lucene.test;

import static org.junit.Assert.assertFalse;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class LuceneContextTest extends BaseLuceneTest {

  @Before
  public void init() {
    var stream = ClassLoader.getSystemResourceAsStream("testLuceneIndex.sql");

    session.computeScript("sql", getScriptFromStream(stream)).close();

    session.execute("create index Song.title on Song (title) FULLTEXT ENGINE LUCENE").close();
    session.execute("create index Song.author on Song (author) FULLTEXT ENGINE LUCENE").close();
  }

  @Test
  public void testContext() {

    var docs =
        session.query(
            "select *,$score from Song where [title] LUCENE \"(title:man)\" order by $score desc");

    Float latestScore = 100f;
    var count = 0;
    while (docs.hasNext()) {
      count++;
      var doc = docs.next();
      Float score = doc.getProperty("$score");
      Assert.assertNotNull(score);
      Assert.assertTrue(score <= latestScore);
      latestScore = score;
    }
    Assert.assertEquals(count, 14);

    docs =
        session.query(
            "select *,$totalHits,$Song_title_totalHits from Song where [title] LUCENE"
                + " \"(title:man)\" limit 1");

    var doc = docs.next();
    Assert.assertEquals(Long.valueOf(14), doc.<Long>getProperty("$totalHits"));
    Assert.assertEquals(Long.valueOf(14), doc.<Long>getProperty("$Song_title_totalHits"));
    assertFalse(docs.hasNext());
  }
}
