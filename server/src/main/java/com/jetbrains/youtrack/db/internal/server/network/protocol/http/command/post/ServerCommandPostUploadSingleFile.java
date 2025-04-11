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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartContentBaseParser;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartFileToRecordContentParser;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartRequestCommand;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.HashMap;

/**
 *
 */
public class ServerCommandPostUploadSingleFile extends
    HttpMultipartRequestCommand<String, RecordId> {

  private static final String[] NAMES = {"POST|uploadSingleFile/*"};

  protected StringWriter buffer;
  protected JSONWriter writer;
  protected RecordId fileRID;
  protected String fileDocument;
  protected String fileName;
  protected String fileType;
  protected long now;
  protected DatabaseSessionInternal database;

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    if (!iRequest.isMultipart()) {
      iResponse.send(
          HttpUtils.STATUS_INVALIDMETHOD_CODE,
          "Request is not multipart/form-data",
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Request is not multipart/form-data",
          null);
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
        buffer = new StringWriter();
        writer = new JSONWriter(buffer);
        writer.beginObject();
        parse(
            iRequest,
            iResponse,
            new HttpMultipartContentBaseParser(),
            new HttpMultipartFileToRecordContentParser(),
            database);
        var ok = saveRecord(database, iRequest, iResponse);
        writer.endObject();
        writer.flush();
        if (ok) {
          iResponse.send(
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              HttpUtils.CONTENT_JSON,
              buffer.toString(),
              null);
        }
      } finally {
        if (database != null) {
          database.close();
        }
        database = null;
        if (buffer != null) {
          buffer.close();
        }
        buffer = null;
        if (writer != null) {
          writer.close();
        }
        writer = null;
        fileDocument = null;
        fileName = null;
        fileType = null;
        if (fileRID != null) {
          fileRID.reset();
        }
        fileRID = null;
      }
    }
    return false;
  }

  @Override
  protected void processBaseContent(
      HttpRequest iRequest, String iContentResult, HashMap<String, String> headers)
      throws Exception {
    if (headers.containsKey(HttpUtils.MULTIPART_CONTENT_NAME)
        && headers.get(HttpUtils.MULTIPART_CONTENT_NAME).equals(getDocumentParamenterName())) {
      fileDocument = iContentResult;
    }
  }

  @Override
  protected void processFileContent(
      HttpRequest iRequest, RecordId contentResult, HashMap<String, String> headers)
      throws Exception {
    if (headers.containsKey(HttpUtils.MULTIPART_CONTENT_NAME)
        && headers.get(HttpUtils.MULTIPART_CONTENT_NAME).equals(getFileParamenterName())) {
      fileRID = contentResult;
      if (headers.containsKey(HttpUtils.MULTIPART_CONTENT_FILENAME)) {
        fileName = headers.get(HttpUtils.MULTIPART_CONTENT_FILENAME);
        if (fileName.charAt(0) == '"') {
          fileName = fileName.substring(1);
        }
        if (fileName.charAt(fileName.length() - 1) == '"') {
          fileName = fileName.substring(0, fileName.length() - 1);
        }
        fileType = headers.get(HttpUtils.MULTIPART_CONTENT_TYPE);

        final var cal = Calendar.getInstance();
        final var formatter = DateHelper.getDateFormatInstance(database);
        now = cal.getTimeInMillis();

        writer.beginObject("uploadedFile");
        writer.writeAttribute(null, 1, true, "name", fileName);
        writer.writeAttribute(null, 1, true, "type", fileType);
        writer.writeAttribute(null, 1, true, "date", formatter.format(cal.getTime()));
        writer.writeAttribute(null, 1, true, "rid", fileRID);
        writer.endObject();
      }
    }
  }

  public boolean saveRecord(DatabaseSessionInternal db, HttpRequest iRequest,
      final HttpResponse iResponse)
      throws InterruptedException, IOException {
    if (fileDocument != null) {
      if (fileRID != null) {
        if (fileDocument.contains("$now")) {
          fileDocument = fileDocument.replace("$now", String.valueOf(now));
        }
        if (fileDocument.contains("$fileName")) {
          fileDocument = fileDocument.replace("$fileName", fileName);
        }
        if (fileDocument.contains("$fileType")) {
          fileDocument = fileDocument.replace("$fileType", fileType);
        }
        if (fileDocument.contains("$file")) {
          fileDocument = fileDocument.replace("$file", fileRID.toString());
        }
        var entity = new EntityImpl(db);
        entity.updateFromJSON(fileDocument);

        writer.beginObject("updatedDocument");
        writer.writeAttribute(db, 1, true, "rid", entity.getIdentity().toString());
        writer.endObject();
      } else {
        iResponse.send(
            HttpUtils.STATUS_INVALIDMETHOD_CODE,
            "File cannot be null",
            HttpUtils.CONTENT_TEXT_PLAIN,
            "File cannot be null",
            null);
        return false;
      }

      fileDocument = null;
    } else {
      if (fileRID == null) {
        iResponse.send(
            HttpUtils.STATUS_INVALIDMETHOD_CODE,
            "File cannot be null",
            HttpUtils.CONTENT_TEXT_PLAIN,
            "File cannot be null",
            null);
        return false;
      }
    }
    return true;
  }

  @Override
  protected String getDocumentParamenterName() {
    return "linkValue";
  }

  @Override
  protected String getFileParamenterName() {
    return "file";
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
