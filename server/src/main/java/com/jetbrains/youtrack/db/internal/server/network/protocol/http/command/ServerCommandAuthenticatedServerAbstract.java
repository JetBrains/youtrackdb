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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfiguration;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Server based authenticated commands. Authenticates against the YouTrackDB server users found in
 * configuration.
 */
public abstract class ServerCommandAuthenticatedServerAbstract extends ServerCommandAbstract {

  private static final String SESSIONID_UNAUTHORIZED = "-";
  private static final String SESSIONID_LOGOUT = "!";

  private final String resource;
  protected String serverUser;
  protected String serverPassword;

  protected ServerCommandAuthenticatedServerAbstract(final String iRequiredResource) {
    resource = iRequiredResource;
  }

  @Override
  public boolean beforeExecute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws IOException {
    super.beforeExecute(iRequest, iResponse);
    return authenticate(iRequest, iResponse, true);
  }

  protected boolean authenticate(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final boolean iAskForAuthentication,
      String resource)
      throws IOException {
    if (checkGuestAccess()) {
      // GUEST ACCESSES TO THE RESOURCE: OK ALSO WITHOUT AN AUTHENTICATION.
      iResponse.setSessionId(null);
      return true;
    }

    if (iAskForAuthentication) {
      if (iRequest.getAuthorization() == null || SESSIONID_LOGOUT.equals(iRequest.getSessionId())) {
        // NO AUTHENTICATION AT ALL
        sendAuthorizationRequest(iRequest, iResponse);
        return false;
      }
    }

    if (iRequest.getAuthorization() != null) {
      // GET CREDENTIALS
      final var authParts = iRequest.getAuthorization().split(":");
      if (authParts.length != 2) {
        // NO USER : PASSWD
        sendAuthorizationRequest(iRequest, iResponse);
        return false;
      }

      serverUser = authParts[0];
      serverPassword = authParts[1];
      if (authParts.length == 2 && server.authenticate(serverUser, serverPassword, resource))
      // AUTHORIZED
      {
        return true;
      }
    }

    // NON AUTHORIZED FOR RESOURCE
    sendNotAuthorizedResponse(iRequest, iResponse);
    return false;
  }

  protected boolean authenticate(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final boolean iAskForAuthentication)
      throws IOException {
    return authenticate(iRequest, iResponse, iAskForAuthentication, resource);
  }

  protected boolean checkGuestAccess() {
    return server.getSecurity().isAuthorized(null, ServerConfiguration.GUEST_USER, resource);
  }

  protected void sendNotAuthorizedResponse(
      final HttpRequest iRequest, final HttpResponse iResponse) throws IOException {
    sendAuthorizationRequest(iRequest, iResponse);
  }

  protected void sendAuthorizationRequest(
      final HttpRequest iRequest, final HttpResponse iResponse) throws IOException {
    // UNAUTHORIZED
    iRequest.setSessionId(SESSIONID_UNAUTHORIZED);

    String header = null;
    var xRequestedWithHeader = iRequest.getHeader("X-Requested-With");
    if (xRequestedWithHeader == null || !xRequestedWithHeader.equals("XMLHttpRequest")) {
      // Defaults to "WWW-Authenticate: Basic" if not an AJAX Request.
      header = server.getSecurity().getAuthenticationHeader(null);

      var headers = server.getSecurity().getAuthenticationHeaders(null);
      headers.entrySet().forEach(s -> iResponse.addHeader(s.getKey(), s.getValue()));
    }

    if (isJsonResponse(iResponse)) {
      sendJsonError(
          iResponse,
          HttpUtils.STATUS_AUTH_CODE,
          HttpUtils.STATUS_AUTH_DESCRIPTION,
          "401 Unauthorized.",
          header);
    } else {
      iResponse.send(
          HttpUtils.STATUS_AUTH_CODE,
          HttpUtils.STATUS_AUTH_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "401 Unauthorized.",
          header);
    }
  }

  @Nullable
  public String getUser(final HttpRequest iRequest) {
    var session = server.getHttpSessionManager().getSession(iRequest.getSessionId());
    if (session != null) {
      return session.getUserName();
    }
    if (iRequest.getAuthorization() != null) {
      // GET CREDENTIALS
      final var authParts = iRequest.getAuthorization().split(":");
      if (authParts.length == 2) {
        return authParts[0];
      }
    }
    return null;
  }

  public String getResource() {
    return resource;
  }
}
