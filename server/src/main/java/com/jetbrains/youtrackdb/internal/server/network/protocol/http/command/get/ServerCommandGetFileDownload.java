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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Blob;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.common.util.PatternConst;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;
import java.util.Date;

/**
 *
 */
public class ServerCommandGetFileDownload extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|fileDownload/*"};

  @Override
  public boolean execute(HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            3,
            "Syntax error: fileDownload/<database>/rid/[/<fileName>][/<fileType>].");

    final var fileName = urlParts.length > 3 ? encodeResponseText(urlParts[3]) : "unknown";

    final String fileType;
    if (urlParts.length > 5) {
      fileType = encodeResponseText(urlParts[4]) + '/' + encodeResponseText(urlParts[5]);
    } else {
      fileType = (urlParts.length > 4 ? encodeResponseText(urlParts[4]) : "");
    }

    final var rid = urlParts[2];

    iRequest.getData().commandInfo = "Download";
    iRequest.getData().commandDetail = rid;

    final RecordAbstract response;
    var session = getProfiledDatabaseSessionInstance(iRequest);
    try {
      try {
        response = session.load(new RecordId(rid));
        if (response instanceof Blob) {
          sendORecordBinaryFileContent(
              iResponse,
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              fileType,
              (Blob) response,
              fileName);
        } else if (response instanceof EntityImpl) {
          SchemaImmutableClass result = null;
          if (response != null) {
            result = ((EntityImpl) response).getImmutableSchemaClass(session);
          }
          for (var prop :
              result
                  .getProperties()) {
            if (prop.getType().equals(PropertyType.BINARY)) {
              final String iFieldName = prop.getName();
              sendBinaryFieldFileContent(
                  iRequest,
                  iResponse,
                  HttpUtils.STATUS_OK_CODE,
                  HttpUtils.STATUS_OK_DESCRIPTION,
                  fileType,
                  ((EntityImpl) response).getProperty(iFieldName),
                  fileName);
            }
          }
        }
      } catch (RecordNotFoundException rnf) {
        iResponse.send(
            HttpUtils.STATUS_INVALIDMETHOD_CODE,
            "Record requested is not a file nor has a readable schema",
            HttpUtils.CONTENT_TEXT_PLAIN,
            "Record requested is not a file nor has a readable schema",
            null);
      }
    } catch (Exception e) {
      iResponse.send(
          HttpUtils.STATUS_INTERNALERROR_CODE,
          HttpUtils.STATUS_INTERNALERROR_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          e.getMessage(),
          null);
    } finally {
      if (session != null) {
        session.close();
      }
    }

    return false;
  }

  protected static void sendORecordBinaryFileContent(
      final HttpResponse iResponse,
      final int iCode,
      final String iReason,
      final String iContentType,
      final Blob record,
      final String iFileName)
      throws IOException {
    iResponse.writeStatus(iCode, iReason);
    iResponse.writeHeaders(iContentType);
    iResponse.writeLine("Content-Disposition: attachment; filename=" + iFileName);
    iResponse.writeLine("Date: " + new Date());
    iResponse.writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (((RecordAbstract) record).getSize()));
    iResponse.writeLine(null);

    record.toOutputStream(iResponse.getOutputStream());

    iResponse.flush();
  }

  protected void sendBinaryFieldFileContent(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final int iCode,
      final String iReason,
      final String iContentType,
      final byte[] record,
      final String iFileName)
      throws IOException {
    iResponse.writeStatus(iCode, iReason);
    iResponse.writeHeaders(iContentType);
    iResponse.writeLine("Content-Disposition: attachment; filename=" + iFileName);
    iResponse.writeLine("Date: " + new Date());
    iResponse.writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (record.length));
    iResponse.writeLine(null);

    iResponse.getOutputStream().write(record);

    iResponse.flush();
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  private static String encodeResponseText(String iText) {
    iText = PatternConst.PATTERN_SINGLE_SPACE.matcher(iText).replaceAll("%20");
    iText = PatternConst.PATTERN_AMP.matcher(iText).replaceAll("%26");
    return iText;
  }
}
