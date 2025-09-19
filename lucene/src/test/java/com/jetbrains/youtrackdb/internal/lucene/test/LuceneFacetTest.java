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

package com.jetbrains.youtrackdb.internal.lucene.test;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 */
public class LuceneFacetTest extends BaseLuceneTest {

  @Before
  public void init() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("Item");

    oClass.createProperty("name", PropertyType.STRING);
    oClass.createProperty("category", PropertyType.STRING);

    session.execute(
            "create index Item.name_category on Item (name,category) FULLTEXT ENGINE LUCENE"
                + " METADATA { 'facetFields' : ['category']}")
        .close();

    var doc = ((EntityImpl) session.newEntity("Item"));
    doc.setProperty("name", "Pioneer");
    doc.setProperty("category", "Electronic/HiFi");

    session.begin();
    session.commit();

    doc = ((EntityImpl) session.newEntity("Item"));
    doc.setProperty("name", "Hitachi");
    doc.setProperty("category", "Electronic/HiFi");

    session.begin();
    session.commit();

    doc = ((EntityImpl) session.newEntity("Item"));
    doc.setProperty("name", "Philips");
    doc.setProperty("category", "Electronic/HiFi");

    session.begin();
    session.commit();

    doc = ((EntityImpl) session.newEntity("Item"));
    doc.setProperty("name", "HP");
    doc.setProperty("category", "Electronic/Computer");

    session.begin();
    session.commit();
  }

  @Test
  @Ignore
  public void baseFacetTest() {

    var resultSet =
        session.execute("select *,$facet from Item where name lucene '(name:P*)' limit 1 ")
            .toList();

    Assert.assertEquals(1, resultSet.size());

    List<EntityImpl> facets = resultSet.getFirst().getProperty("$facet");

    Assert.assertEquals(1, facets.size());

    var facet = facets.getFirst();
    Assert.assertEquals(1, facet.<Object>getProperty("childCount"));
    Assert.assertEquals(2, facet.<Object>getProperty("value"));
    Assert.assertEquals("category", facet.getProperty("dim"));

    List<EntityImpl> labelsValues = facet.getProperty("labelsValue");

    Assert.assertEquals(1, labelsValues.size());

    var labelValues = labelsValues.getFirst();

    Assert.assertEquals(2, labelValues.<Object>getProperty("value"));
    Assert.assertEquals("Electronic", labelValues.getProperty("label"));

    resultSet =
        session
            .execute(
                "select *,$facet from Item where name lucene { 'q' : 'H*', 'drillDown' :"
                    + " 'category:Electronic' }  limit 1 ").toList();

    Assert.assertEquals(1, resultSet.size());

    facets = resultSet.getFirst().getProperty("$facet");

    Assert.assertEquals(1, facets.size());

    facet = facets.getFirst();

    Assert.assertEquals(2, facet.<Object>getProperty("childCount"));
    Assert.assertEquals(2, facet.<Object>getProperty("value"));
    Assert.assertEquals("category", facet.getProperty("dim"));

    labelsValues = facet.getProperty("labelsValue");

    Assert.assertEquals(2, labelsValues.size());

    labelValues = labelsValues.getFirst();

    Assert.assertEquals(1, labelValues.<Object>getProperty("value"));
    Assert.assertEquals("HiFi", labelValues.getProperty("label"));

    labelValues = labelsValues.get(1);

    Assert.assertEquals(1, labelValues.<Object>getProperty("value"));
    Assert.assertEquals("Computer", labelValues.getProperty("label"));
  }
}
