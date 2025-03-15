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

import com.jetbrains.youtrack.db.internal.core.db.SystemDatabase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ServerCommandGetListDatabases extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"GET|listDatabases"};

  public ServerCommandGetListDatabases() {
    super("server.listDatabases");
  }

  @Override
  public boolean beforeExecute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws IOException {
    return authenticate(iRequest, iResponse, false);
  }

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    checkSyntax(iRequest.getUrl(), 1, "Syntax error: server");

    iRequest.getData().commandInfo = "Server status";

    final var result = new EntityImpl(null);

    // We copy the returned set so that we can modify it, and we use a LinkedHashSet to preserve the
    // ordering.
    java.util.Set<String> storageNames =
        new java.util.LinkedHashSet(server.getAvailableStorageNames().keySet());

    // This just adds the system database if the guest user has the specified permission
    // (server.listDatabases.system).
    if (server.getSecurity() != null
        && server
        .getSecurity()
        .isAuthorized(null, ServerConfiguration.GUEST_USER, "server.listDatabases.system")) {
      storageNames.add(SystemDatabase.SYSTEM_DB_NAME);
    }

    // ORDER DATABASE NAMES (CASE INSENSITIVE)
    final List<String> orderedStorages = new ArrayList<String>(storageNames);
    Collections.sort(
        orderedStorages,
        new Comparator<String>() {
          @Override
          public int compare(final String o1, final String o2) {
            return o1.toLowerCase().compareTo(o2.toLowerCase());
          }
        });

    result.setProperty("databases", orderedStorages);
    iResponse.writeRecord(result);

    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
