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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

public class YouTrackDbCreationHelper {

  public static void loadDB(DatabaseSession db, int documents) throws IOException {
    var tx = db.begin();
    for (var i = 1; i <= documents; i++) {
      var doc = ((EntityImpl) tx.newEntity("Item"));
      doc = createItem(i, doc);
    }

    createAuthorAndArticles(db, 50, 50);
    createArticleWithAttachmentSplitted(db);

    createWriterAndPosts(db, 10, 10);
    tx.commit();
  }

  public static EntityImpl createItem(int id, EntityImpl doc) {
    var itemKey = Integer.valueOf(id).toString();

    doc.setProperty("stringKey", itemKey);
    doc.setProperty("intKey", id);
    var contents =
        "YouTrackDB is a deeply scalable Document-Graph DBMS with the flexibility of the Document"
            + " databases and the power to manage links of the Graph databases. It can work in"
            + " schema-less mode, schema-full or a mix of both. Supports advanced features such as"
            + " ACID Transactions, Fast Indexes, Native and SQL queries. It imports and exports"
            + " documents in JSON. Graphs of hundreads of linked documents can be retrieved all in"
            + " memory in few milliseconds without executing costly JOIN such as the Relational"
            + " DBMSs do. YouTrackDB uses a new indexing algorithm called MVRB-Tree, derived from the"
            + " Red-Black Tree and from the B+Tree with benefits of both: fast insertion and ultra"
            + " fast lookup. The transactional engine can run in distributed systems supporting up"
            + " to 9.223.372.036 Billions of records for the maximum capacity of"
            + " 19.807.040.628.566.084 Terabytes of data distributed on multiple disks in multiple"
            + " nodes. YouTrackDB is FREE for any use. Open Source License Apache 2.0. ";
    doc.setProperty("text", contents);
    doc.setProperty("title", "youTrackDB");
    doc.setProperty("score", BigDecimal.valueOf(contents.length() / id));
    doc.setProperty("length", contents.length(), PropertyType.LONG);
    doc.setProperty("published", id % 2 > 0);
    doc.setProperty("author", "anAuthor" + id);
    // PropertyType.EMBEDDEDLIST);
    var instance = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    instance.add(Calendar.HOUR_OF_DAY, -id);
    var time = instance.getTime();
    doc.setProperty("date", time, PropertyType.DATE);
    doc.setProperty("time", time, PropertyType.DATETIME);

    return doc;
  }

  public static void createAuthorAndArticles(DatabaseSession db, int totAuthors, int totArticles)
      throws IOException {
    db.executeInTx(transaction -> {
      var articleSerial = 0;
      for (var a = 1; a <= totAuthors; ++a) {
        var author = ((EntityImpl) transaction.newEntity("Author"));
        var articles = db.newLinkList(totArticles);
        author.setProperty("articles", articles);

        author.setProperty("uuid", a, PropertyType.DOUBLE);
        author.setProperty("name", "Jay");
        author.setProperty("rating", new Random().nextDouble());

        for (var i = 1; i <= totArticles; ++i) {
          var article = ((EntityImpl) transaction.newEntity("Article"));

          var instance = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
          var time = instance.getTime();
          article.setProperty("date", time, PropertyType.DATE);

          article.setProperty("uuid", articleSerial++);
          article.setProperty("title", "the title for article " + articleSerial);
          article.setProperty("content", "the content for article " + articleSerial);
          try {
            article.setProperty("attachment", loadFile(db, "./src/test/resources/file.pdf"));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
          articles.add(article);
        }
      }
    });
  }

  public static EntityImpl createArticleWithAttachmentSplitted(DatabaseSession db)
      throws IOException {

    return db.computeInTx(transaction -> {
      var article = ((EntityImpl) transaction.newEntity("Article"));
      var instance = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

      var time = instance.getTime();
      article.setProperty("date", time, PropertyType.DATE);

      article.setProperty("uuid", 1000000);
      article.setProperty("title", "the title 2");
      article.setProperty("content", "the content 2");
      if (new File("./src/test/resources/file.pdf").exists()) {
        try {
          article.setProperty("attachment", loadFile(db, "./src/test/resources/file.pdf", 256));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      return article;
    });
  }

  public static void createWriterAndPosts(DatabaseSession db, int totAuthors, int totArticles)
      throws IOException {
    db.executeInTx(transaction -> {
      var articleSerial = 0;
      for (var a = 1; a <= totAuthors; ++a) {
        var writer = transaction.newVertex("Writer");
        writer.setProperty("uuid", a);
        writer.setProperty("name", "happy writer");
        writer.setProperty("is_active", Boolean.TRUE);
        writer.setProperty("isActive", Boolean.TRUE);

        for (var i = 1; i <= totArticles; ++i) {

          var post = transaction.newVertex("Post");

          var instance = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
          var time = instance.getTime();
          post.setProperty("date", time, PropertyType.DATE);
          post.setProperty("uuid", articleSerial++);
          post.setProperty("title", "the title");
          post.setProperty("content", "the content");

          transaction.newStatefulEdge(writer, post, "Writes");
        }
      }

      // additional wrong data
      var writer = transaction.newVertex("Writer");
      writer.setProperty("uuid", totAuthors << 1);
      writer.setProperty("name", "happy writer");
      writer.setProperty("is_active", Boolean.TRUE);
      writer.setProperty("isActive", Boolean.TRUE);

      var post = transaction.newVertex("Post");

      // no date!!

      post.setProperty("uuid", articleSerial << 1);
      post.setProperty("title", "the title");
      post.setProperty("content", "the content");

      transaction.newStatefulEdge(writer, post, "Writes");
    });
  }

  private static Blob loadFile(DatabaseSession database, String filePath) throws IOException {
    return database.computeInTx(transaction -> {
      final var f = new File(filePath);
      if (f.exists()) {
        BufferedInputStream inputStream = null;
        try {
          inputStream = new BufferedInputStream(new FileInputStream(f));

          var record = transaction.newBlob();
          record.fromInputStream(inputStream);
          return record;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      return null;
    });
  }

  private static List<Identifiable> loadFile(DatabaseSession database, String filePath,
      int bufferSize)
      throws IOException {
    var binaryFile = new File(filePath);
    var binaryFileLength = binaryFile.length();
    var numberOfRecords = (int) (binaryFileLength / bufferSize);
    var remainder = (int) (binaryFileLength % bufferSize);
    if (remainder > 0) {
      numberOfRecords++;
    }
    var binaryChuncks = database.newLinkList(numberOfRecords);
    var binaryStream = new BufferedInputStream(new FileInputStream(binaryFile));

    for (var i = 0; i < numberOfRecords; i++) {
      var index = i;
      var recnum = numberOfRecords;

      database.executeInTx(
          transaction -> {
            byte[] chunk;
            if (index == recnum - 1) {
              chunk = new byte[remainder];
            } else {
              chunk = new byte[bufferSize];
            }
            try {
              binaryStream.read(chunk);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }

            var recordChunk = transaction.newBlob(chunk);
            binaryChuncks.add(recordChunk.getIdentity());
          });
    }

    return binaryChuncks;
  }

  public static void createSchemaDB(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSchema();

    // item
    var item = schema.createClass("Item");

    item.createProperty("stringKey", PropertyType.STRING).createIndex(INDEX_TYPE.UNIQUE);
    item.createProperty("intKey", PropertyType.INTEGER).createIndex(INDEX_TYPE.UNIQUE);
    item.createProperty("date", PropertyType.DATE).createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("time", PropertyType.DATETIME).createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("text", PropertyType.STRING);
    item.createProperty("score", PropertyType.DECIMAL);
    item.createProperty("length", PropertyType.INTEGER).createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("published", PropertyType.BOOLEAN)
        .createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("title", PropertyType.STRING).createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("author", PropertyType.STRING).createIndex(INDEX_TYPE.NOTUNIQUE);
    item.createProperty("tags", PropertyType.EMBEDDEDLIST);

    // class Article
    var article = schema.createClass("Article");

    article.createProperty("uuid", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    article.createProperty("date", PropertyType.DATE).createIndex(INDEX_TYPE.NOTUNIQUE);
    article.createProperty("title", PropertyType.STRING);
    article.createProperty("content", PropertyType.STRING);
    // article.createProperty("attachment", PropertyType.LINK);

    // author
    var author = schema.createClass("Author");

    author.createProperty("uuid", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    author.createProperty("name", PropertyType.STRING).setMin("3");
    author.createProperty("rating", PropertyType.DOUBLE);
    author.createProperty("articles", PropertyType.LINKLIST, article);

    // link article-->author
    article.createProperty("author", PropertyType.LINK, author);

    // Graph

    var v = schema.getClass("V");
    if (v == null) {
      schema.createClass("V");
    }

    var post = schema.createClass("Post", v);
    post.createProperty("uuid", PropertyType.LONG);
    post.createProperty("title", PropertyType.STRING);
    post.createProperty("date", PropertyType.DATE).createIndex(INDEX_TYPE.NOTUNIQUE);
    post.createProperty("content", PropertyType.STRING);

    var writer = schema.createClass("Writer", v);
    writer.createProperty("uuid", PropertyType.LONG).createIndex(INDEX_TYPE.UNIQUE);
    writer.createProperty("name", PropertyType.STRING);
    writer.createProperty("is_active", PropertyType.BOOLEAN);
    writer.createProperty("isActive", PropertyType.BOOLEAN);

    var e = schema.getClass("E");
    if (e == null) {
      schema.createClass("E");
    }

    schema.createClass("Writes", e);
  }
}
