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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequestException;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponseAbstract;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class ServerCommandAbstract implements ServerCommand {

  protected YouTrackDBServer server;

  /**
   * Default constructor. Disable cache of content at HTTP level
   */
  public ServerCommandAbstract() {
  }

  @Override
  public boolean beforeExecute(final HttpRequest iRequest, HttpResponse iResponse)
      throws IOException {
    setNoCache(iResponse);
    return true;
  }

  @Override
  public boolean afterExecute(final HttpRequest iRequest, HttpResponse iResponse)
      throws IOException {
    return true;
  }

  protected static String[] checkSyntax(
      final String iURL, final int iArgumentCount, final String iSyntax) {
    final var parts =
        StringSerializerHelper.smartSplit(
            iURL, HttpResponseAbstract.URL_SEPARATOR, 1, -1, true, true, false, false);
    parts.replaceAll(s -> URLDecoder.decode(s, StandardCharsets.UTF_8));

    if (parts.size() < iArgumentCount) {
      throw new HttpRequestException(iSyntax);
    }

    return parts.toArray(new String[0]);
  }

  public YouTrackDBServer getServer() {
    return server;
  }

  public void configure(final YouTrackDBServer server) {
    this.server = server;
  }

  protected static void setNoCache(final HttpResponse iResponse) {
    // DEFAULT = DON'T CACHE
    iResponse.setHeader(
        "Cache-Control: no-cache, no-store, max-age=0, must-revalidate\r\nPragma: no-cache");
    iResponse.addHeader("Cache-Control", "no-cache, no-store, max-age=0");
    iResponse.addHeader("Pragma", "no-cache");
  }

  protected static boolean isJsonResponse(HttpResponse response) {
    return response.isJsonErrorResponse();
  }

  protected static void sendJsonError(
      HttpResponse iResponse,
      final int iCode,
      final String iReason,
      final Object iContent,
      final String iHeaders)
      throws IOException {
    var response = new HashMap<String, Object>();
    var error = new HashMap<String, Object>();
    error.put("code", iCode);
    error.put("reason", iReason);
    error.put("content", iContent);
    List<Map<String, Object>> errors = new ArrayList<>();
    errors.add(error);
    response.put("errors", errors);
    iResponse.send(
        iCode, iReason, HttpUtils.CONTENT_JSON, JSONSerializerJackson.INSTANCE.mapToJson(response),
        iHeaders);
  }
}
