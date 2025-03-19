package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.security.SecurityUser;
import com.jetbrains.youtrack.db.internal.server.TokenHandler;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAbstract;
import java.io.IOException;
import java.util.Locale;

/**
 *
 */
public class ServerCommandPostAuthToken extends ServerCommandAbstract {

  private static final String[] NAMES = {"POST|token/*"};
  private static final String RESPONSE_FORMAT = "indent:-1,attribSameRow";
  private volatile TokenHandler tokenHandler;

  @Override
  public String[] getNames() {
    return NAMES;
  }

  private void init() {

    if (tokenHandler == null
        && server
        .getContextConfiguration()
        .getValueAsBoolean(GlobalConfiguration.NETWORK_HTTP_USE_TOKEN)) {
      tokenHandler = server.getTokenHandler();
    }
  }

  @Override
  public boolean execute(HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    init();
    var urlParts = checkSyntax(iRequest.getUrl(), 2, "Syntax error: token/<database>");
    iRequest.setDatabaseName(urlParts[1]);

    iRequest.getData().commandInfo = "Generate authentication token";

    // Parameter names consistent with 4.3.2 (Access Token Request) of RFC 6749
    var content = iRequest.getUrlEncodedContent();
    if (content == null) {
      var result = new EntityImpl(null);
      result.setProperty("error", "missing_auth_data");
      sendError(iRequest, iResponse, result);
      return false;
    }
    var signedToken = ""; // signedJWT.serialize();

    var grantType = content.get("grant_type").toLowerCase(Locale.ENGLISH);
    var username = content.get("username");
    var password = content.get("password");
    String authenticatedRid;
    EntityImpl result;

    if (grantType.equals("password")) {
      authenticatedRid = authenticate(username, password, iRequest.getDatabaseName());
      if (authenticatedRid == null) {
        sendAuthorizationRequest(iRequest, iResponse, iRequest.getDatabaseName());
      } else if (tokenHandler != null) {
        // Generate and return a JWT access token

        SecurityUser user = null;
        try (var db = server.openSession(iRequest.getDatabaseName(),
            username,
            password)) {
          user = db.getCurrentUser();

          if (user != null) {
            var tokenBytes = tokenHandler.getSignedWebToken(db, user);
            signedToken = new String(tokenBytes);
          }

        } catch (SecurityAccessException e) {
          // WRONG USER/PASSWD
        } catch (LockException e) {
          LogManager.instance()
              .error(this, "Cannot access to the database '" + iRequest.getDatabaseName() + "'", e);
        }

        // 4.1.4 (Access Token Response) of RFC 6749
        result = new EntityImpl(null);
        result.setProperty("access_token", signedToken);
        result.setProperty("expires_in", 3600);

        iResponse.writeRecord(result, RESPONSE_FORMAT, null);
      } else {
        result = new EntityImpl(null);
        result.setProperty("error", "unsupported_grant_type");
        sendError(iRequest, iResponse, result);
      }
    } else {
      result = new EntityImpl(null);
      result.setProperty("error", "unsupported_grant_type");
      sendError(iRequest, iResponse, result);
    }

    return false;
  }

  // Return user rid if authentication successful.
  // If user is server user (doesn't have a rid) then '<server user>' is returned.
  // null is returned in all other cases and means authentication was unsuccessful.
  protected String authenticate(
      final String username, final String password, final String iDatabaseName) throws IOException {
    DatabaseSessionInternal db = null;
    String userRid = null;
    try {
      db = server.openSession(iDatabaseName, username, password);

      userRid = (db.getCurrentUser() == null ? "<server user>"
          : db.getCurrentUser().getIdentity().toString());
    } catch (SecurityAccessException e) {
      // WRONG USER/PASSWD
    } catch (LockException e) {
      LogManager.instance()
          .error(this, "Cannot access to the database '" + iDatabaseName + "'", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
    return userRid;
  }

  protected void sendError(
      final HttpRequest iRequest, final HttpResponse iResponse, final EntityImpl error)
      throws IOException {
    iResponse.send(
        HttpUtils.STATUS_BADREQ_CODE,
        HttpUtils.STATUS_BADREQ_DESCRIPTION,
        HttpUtils.CONTENT_JSON,
        error.toJSON(),
        null);
  }

  protected void sendAuthorizationRequest(
      final HttpRequest iRequest, final HttpResponse iResponse, final String iDatabaseName)
      throws IOException {

    String header = null;
    var xRequestedWithHeader = iRequest.getHeader("X-Requested-With");
    if (xRequestedWithHeader == null || !xRequestedWithHeader.equals("XMLHttpRequest")) {
      // Defaults to "WWW-Authenticate: Basic" if not an AJAX Request.
      header = server.getSecurity().getAuthenticationHeader(iDatabaseName);

      var headers = server.getSecurity().getAuthenticationHeaders(iDatabaseName);
      headers.entrySet().forEach(s -> iResponse.addHeader(s.getKey(), s.getValue()));
    }

    if (isJsonResponse(iResponse)) {
      sendJsonError(
          iResponse,
          HttpUtils.STATUS_BADREQ_CODE,
          HttpUtils.STATUS_BADREQ_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
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
}
