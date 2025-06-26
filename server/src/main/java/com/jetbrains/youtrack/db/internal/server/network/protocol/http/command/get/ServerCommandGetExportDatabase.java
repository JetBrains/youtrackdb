/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExport;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;
import java.net.SocketException;
import java.util.Date;
import java.util.zip.GZIPOutputStream;

public class ServerCommandGetExportDatabase extends ServerCommandAuthenticatedDbAbstract
    implements CommandOutputListener {

  private static final String[] NAMES = {"GET|export/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    var urlParts =
        checkSyntax(iRequest.getUrl(), 2, "Syntax error: export/<database>/[<name>][?params*]");

    if (urlParts.length <= 2) {
      exportStandard(iRequest, iResponse);
    }
    return false;
  }

  protected void exportStandard(final HttpRequest iRequest, final HttpResponse iResponse)
      throws InterruptedException, IOException {
    iRequest.getData().commandInfo = "Database export";
    final var database = getProfiledDatabaseSessionInstance(iRequest);
    try {
      iResponse.writeStatus(HttpUtils.STATUS_OK_CODE, HttpUtils.STATUS_OK_DESCRIPTION);
      iResponse.writeHeaders(HttpUtils.CONTENT_GZIP);
      iResponse.writeLine(
          "Content-Disposition: attachment; filename=" + database.getDatabaseName() + ".gz");
      iResponse.writeLine("Date: " + new Date());
      iResponse.writeLine(null);
      final var export =
          new DatabaseExport(
              database, new GZIPOutputStream(iResponse.getOutputStream(), 16384), this);
      export.exportDatabase();

      try {
        iResponse.flush();
      } catch (SocketException e) {
      }
    } finally {
      if (database != null) {
        database.close();
      }
    }
  }

  @Override
  public void onMessage(String iText) {
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
