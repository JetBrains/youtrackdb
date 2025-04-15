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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CommonConst;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseImport;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartContentBaseParser;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartDatabaseImportContentParser;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartRequestCommand;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 *
 */
public class ServerCommandPostImportDatabase
    extends HttpMultipartRequestCommand<String, InputStream> implements CommandOutputListener {

  protected static final String[] NAMES = {"POST|import/*"};
  protected StringWriter buffer;
  protected InputStream importData;
  protected DatabaseSessionInternal database;

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    if (!iRequest.isMultipart()) {
      database = getProfiledDatabaseSessionInstance(iRequest);
      try {
        var importer =
            new DatabaseImport(
                database,
                new ByteArrayInputStream(iRequest.getContent().getBytes(StandardCharsets.UTF_8)),
                this);
        for (var option : iRequest.getParameters().entrySet()) {
          importer.setOption(option.getKey(), option.getValue());
        }
        importer.importDatabase();

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_JSON,
            "{\"responseText\": \"Database imported Correctly, see server log for more"
                + " informations.\"}",
            null);
      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            e.getMessage() + ": " + e.getCause() != null ? e.getCause().getMessage() : "",
            HttpUtils.CONTENT_JSON,
            "{\"responseText\": \""
                + e.getMessage()
                + ": "
                + (e.getCause() != null ? e.getCause().getMessage() : "")
                + "\"}",
            null);
      } finally {
        if (database != null) {
          database.close();
        }
        database = null;
      }
    } else if (iRequest.getMultipartStream() == null
        || iRequest.getMultipartStream().available() <= 0) {
      iResponse.send(
          HttpUtils.STATUS_INVALIDMETHOD_CODE,
          "Content stream is null or empty",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Content stream is null or empty",
          null);
    } else {
      database = getProfiledDatabaseSessionInstance(iRequest);
      try {
        parse(
            iRequest,
            iResponse,
            new HttpMultipartContentBaseParser(),
            new HttpMultipartDatabaseImportContentParser(),
            database);

        var importer = new DatabaseImport(database, importData, this);
        for (var option : iRequest.getParameters().entrySet()) {
          importer.setOption(option.getKey(), option.getValue());
        }
        importer.importDatabase();

        iResponse.send(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            HttpUtils.CONTENT_JSON,
            "{\"responseText\": \"Database imported Correctly, see server log for more"
                + " informations.\"}",
            null);
      } catch (Exception e) {
        iResponse.send(
            HttpUtils.STATUS_INTERNALERROR_CODE,
            e.getMessage() + ": " + e.getCause() != null ? e.getCause().getMessage() : "",
            HttpUtils.CONTENT_JSON,
            "{\"responseText\": \""
                + e.getMessage()
                + ": "
                + (e.getCause() != null ? e.getCause().getMessage() : "")
                + "\"}",
            null);
      } finally {
        if (database != null) {
          database.close();
        }
        database = null;
        if (importData != null) {
          importData.close();
        }
        importData = null;
      }
    }
    return false;
  }

  @Override
  protected void processBaseContent(
      final HttpRequest iRequest,
      final String iContentResult,
      final HashMap<String, String> headers)
      throws Exception {
  }

  @Override
  protected void processFileContent(
      final HttpRequest iRequest,
      final InputStream iContentResult,
      final HashMap<String, String> headers)
      throws Exception {
    importData = iContentResult;
  }

  @Override
  protected String getDocumentParamenterName() {
    return "linkValue";
  }

  @Override
  protected String getFileParamenterName() {
    return "databaseFile";
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  @Override
  public void onMessage(String iText) {
    final var msg = iText.startsWith("\n") ? iText.substring(1) : iText;
    LogManager.instance().info(this, msg, CommonConst.EMPTY_OBJECT_ARRAY);
  }
}
