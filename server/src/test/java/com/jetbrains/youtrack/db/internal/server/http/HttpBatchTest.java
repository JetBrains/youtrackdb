package com.jetbrains.youtrack.db.internal.server.http;

import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.Assert;
import org.junit.Before;

/**
 * Test HTTP "batch" command.
 */
public class HttpBatchTest extends BaseHttpDatabaseTest {

  @Before
  public void beforeMethod() throws Exception {
    getServer().getPlugin("script-interpreter").startup();
  }

  public void batchUpdate() throws Exception {
    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("create class User", CONTENT.TEXT)
            .getResponse()
            .getCode());

    Assert.assertEquals(
        200,
        post("command/" + getDatabaseName() + "/sql/")
            .payload("insert into User content {\"userID\": \"35862601\"}", CONTENT.TEXT)
            .setUserName("admin")
            .setUserPassword("admin")
            .getResponse()
            .getCode());

    var response = EntityUtils.toString(getResponse().getEntity());

    Assert.assertNotNull(response);

    var responseDoc = new EntityImpl(null);
    responseDoc.updateFromJSON(response);
    var insertedDocument =
        ((List<EntityImpl>) responseDoc.getProperty("result")).get(0);

    // TEST UPDATE
    Assert.assertEquals(
        200,
        post("batch/" + getDatabaseName() + "/sql/")
            .payload(
                "{\n"
                    + "    \"transaction\": true,\n"
                    + "    \"operations\": [{\n"
                    + "        \"record\": {\n"
                    + "            \"userID\": \"35862601\",\n"
                    + "            \"externalID\": \"35862601\",\n"
                    + "            \"@rid\": \""
                    + insertedDocument.getIdentity()
                    + "\", \"@class\": \"User\", \"@version\": "
                    + insertedDocument.getVersion()
                    + "\n"
                    + "        },\n"
                    + "        \"type\": \"u\"\n"
                    + "    }]\n"
                    + "}",
                CONTENT.JSON)
            .getResponse()
            .getCode());

    // TEST DOUBLE UPDATE
    Assert.assertEquals(
        200,
        post("batch/" + getDatabaseName() + "/sql/")
            .payload(
                "{\n"
                    + "    \"transaction\": true,\n"
                    + "    \"operations\": [{\n"
                    + "        \"record\": {\n"
                    + "            \"userID\": \"35862601\",\n"
                    + "            \"externalID\": \"35862601\",\n"
                    + "            \"@rid\": \""
                    + insertedDocument.getIdentity()
                    + "\", \"@class\": \"User\", \"@version\": "
                    + (insertedDocument.getVersion() + 1)
                    + "\n"
                    + "        },\n"
                    + "        \"type\": \"u\"\n"
                    + "    }]\n"
                    + "}",
                CONTENT.JSON)
            .getResponse()
            .getCode());

    // TEST WRONG VERSION ON UPDATE
    Assert.assertEquals(
        409,
        post("batch/" + getDatabaseName() + "/sql/")
            .payload(
                "{\n"
                    + "    \"transaction\": true,\n"
                    + "    \"operations\": [{\n"
                    + "        \"record\": {\n"
                    + "            \"userID\": \"35862601\",\n"
                    + "            \"externalID\": \"35862601\",\n"
                    + "            \"@rid\": \""
                    + insertedDocument.getIdentity()
                    + "\", \"@class\": \"User\", \"@version\": "
                    + (insertedDocument.getVersion() + 1)
                    + "\n"
                    + "        },\n"
                    + "        \"type\": \"u\"\n"
                    + "    }]\n"
                    + "}",
                CONTENT.JSON)
            .getResponse()
            .getCode());

    batchWithEmpty();
  }

  private void batchWithEmpty() throws IOException {
    var json =
        "{\n"
            + "\"operations\": [{\n"
            + "\"type\": \"script\",\n"
            + "\"language\": \"SQL\","
            + "\"script\": \"let $a = select from User limit 2 \\n"
            + "let $b = select sum(foo) from (select from User where name = 'adsfafoo') \\n"
            + "return [$a, $b]\""
            + "}]\n"
            + "}";
    var response = post("batch/" + getDatabaseName()).payload(json, CONTENT.TEXT).getResponse();

    Assert.assertEquals(200, response.getCode());
    var stream = response.getEntity().getContent();
    var string = "";
    var reader = new BufferedReader(new InputStreamReader(stream));
    var line = reader.readLine();
    while (line != null) {
      string += line;
      line = reader.readLine();
    }
    System.out.println(string);
    var doc = new EntityImpl(null);
    doc.updateFromJSON(string);

    stream.close();
    var iterable = (Iterable) doc.getProperty("result.value");

    System.out.println(iterable);
    var iterator = iterable.iterator();
    Assert.assertTrue(iterator.hasNext());
    iterator.next();
    Assert.assertTrue(iterator.hasNext());
    var emptyList = iterator.next();
    Assert.assertNotNull(emptyList);
    Assert.assertTrue(emptyList instanceof Iterable);
    var emptyListIterator = ((Iterable) emptyList).iterator();
    Assert.assertFalse(emptyListIterator.hasNext());
  }

  @Override
  public String getDatabaseName() {
    return "httpscript";
  }
}
