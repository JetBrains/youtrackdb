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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.api.config.ContextConfiguration;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.SecurityAccessException;
import com.jetbrains.youtrack.db.internal.common.concur.lock.LockException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.QueryDatabaseState;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityUserIml;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrack.db.internal.enterprise.channel.SocketChannel;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.NetworkProtocolException;
import com.jetbrains.youtrack.db.internal.enterprise.channel.text.SocketChannelTextServer;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.config.ServerCommandConfiguration;
import com.jetbrains.youtrack.db.internal.server.monitoring.HttpRequestEvent;
import com.jetbrains.youtrack.db.internal.server.monitoring.HttpRequestEvent.Error;
import com.jetbrains.youtrack.db.internal.server.network.ServerNetworkListener;
import com.jetbrains.youtrack.db.internal.server.network.protocol.NetworkProtocol;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommand;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.all.ServerCommandFunction;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete.ServerCommandDeleteClass;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete.ServerCommandDeleteDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete.ServerCommandDeleteDocument;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete.ServerCommandDeleteIndex;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete.ServerCommandDeleteProperty;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetClass;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetCluster;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetConnect;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetConnections;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetDictionary;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetDisconnect;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetDocument;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetDocumentByClass;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetExportDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetFileDownload;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetIndex;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetListDatabases;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetPing;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetQuery;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetSSO;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetServerVersion;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetStorageAllocation;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetSupportedLanguages;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandIsEnterprise;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.options.ServerCommandOptions;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.patch.ServerCommandPatchDocument;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostAuthToken;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostBatch;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostClass;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostCommandGraph;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostDocument;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostImportRecords;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostInstallDatabase;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostKillDbConnection;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostProperty;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostServer;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostServerCommand;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post.ServerCommandPostStudio;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.put.ServerCommandPostConnection;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.put.ServerCommandPutDocument;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.put.ServerCommandPutIndex;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.multipart.HttpMultipartBaseInputStream;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginHelper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public abstract class NetworkProtocolHttpAbstract extends NetworkProtocol
    implements ONetworkHttpExecutor {

  private static final String COMMAND_SEPARATOR = "|";
  private static final Charset utf8 = StandardCharsets.UTF_8;
  private static int requestMaxContentLength; // MAX = 10Kb
  private static int socketTimeout;
  private final StringBuilder requestContent = new StringBuilder(512);
  protected ClientConnection connection;
  protected SocketChannelTextServer channel;
  protected SecurityUserIml account;
  protected OHttpRequest request;
  protected HttpResponse response;
  protected HttpNetworkCommandManager cmdManager;
  private String responseCharSet;
  private boolean jsonResponseError;
  private boolean sameSiteCookie;
  private String[] additionalResponseHeaders;
  private String listeningAddress = "?";
  private ContextConfiguration configuration;

  public NetworkProtocolHttpAbstract(YouTrackDBServer server) {
    super(server.getThreadGroup(), "IO-HTTP");
  }

  @Override
  public void config(
      final ServerNetworkListener iListener,
      final YouTrackDBServer iServer,
      final Socket iSocket,
      final ContextConfiguration iConfiguration)
      throws IOException {
    configuration = iConfiguration;

    final boolean installDefaultCommands =
        iConfiguration.getValueAsBoolean(
            GlobalConfiguration.NETWORK_HTTP_INSTALL_DEFAULT_COMMANDS);
    if (installDefaultCommands) {
      registerStatelessCommands(iListener);
    }

    final String addHeaders =
        iConfiguration.getValueAsString("network.http.additionalResponseHeaders", null);
    if (addHeaders != null) {
      additionalResponseHeaders = addHeaders.split(";");
    }

    // CREATE THE CLIENT CONNECTION
    connection = iServer.getClientConnectionManager().connect(this);

    server = iServer;
    requestMaxContentLength =
        iConfiguration.getValueAsInteger(GlobalConfiguration.NETWORK_HTTP_MAX_CONTENT_LENGTH);
    socketTimeout = iConfiguration.getValueAsInteger(GlobalConfiguration.NETWORK_SOCKET_TIMEOUT);
    responseCharSet =
        iConfiguration.getValueAsString(GlobalConfiguration.NETWORK_HTTP_CONTENT_CHARSET);

    jsonResponseError =
        iConfiguration.getValueAsBoolean(GlobalConfiguration.NETWORK_HTTP_JSON_RESPONSE_ERROR);
    sameSiteCookie =
        iConfiguration.getValueAsBoolean(
            GlobalConfiguration.NETWORK_HTTP_SESSION_COOKIE_SAME_SITE);

    channel = new SocketChannelTextServer(iSocket, iConfiguration);
    channel.connected();

    connection.getData().caller = channel.toString();

    listeningAddress = getListeningAddress();

    ServerPluginHelper.invokeHandlerCallbackOnSocketAccepted(server, this);

    start();
  }

  public void service() throws NetworkProtocolException, IOException {
    ++connection.getStats().totalRequests;
    connection.getData().commandInfo = null;
    connection.getData().commandDetail = null;

    final String callbackF;
    if (server
        .getContextConfiguration()
        .getValueAsBoolean(GlobalConfiguration.NETWORK_HTTP_JSONP_ENABLED)
        && request.getParameters() != null
        && request.getParameters().containsKey(HttpUtils.CALLBACK_PARAMETER_NAME)) {
      callbackF = request.getParameters().get(HttpUtils.CALLBACK_PARAMETER_NAME);
    } else {
      callbackF = null;
    }

    response =
        new HttpResponseImpl(
            channel.outStream,
            request.getHttpVersion(),
            additionalResponseHeaders,
            responseCharSet,
            "YouTrackDB",
            request.getSessionId(),
            callbackF,
            request.isKeepAlive(),
            connection,
            server.getContextConfiguration());
    response.setJsonErrorResponse(jsonResponseError);
    response.setSameSiteCookie(sameSiteCookie);
    if (request.getAcceptEncoding() != null
        && request.getAcceptEncoding().equals(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED)) {
      response.setContentEncoding(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED);
    }
    // only for static resources
    if (request.getAcceptEncoding() != null
        && request.getAcceptEncoding().contains(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED)) {
      response.setStaticEncoding(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED);
    }

    final long begin = System.currentTimeMillis();

    boolean isChain;
    do {
      isChain = false;
      final String command;
      if (request.getUrl().length() < 2) {
        command = "";
      } else {
        command = request.getUrl().substring(1);
      }

      final String commandString = getCommandString(command, request.getHttpMethod());

      final ServerCommand cmd = (ServerCommand) cmdManager.getCommand(commandString);
      Map<String, String> requestParams = cmdManager.extractUrlTokens(commandString);
      if (requestParams != null) {
        if (request.getParameters() == null) {
          request.setParameters(new HashMap<String, String>());
        }
        for (Map.Entry<String, String> entry : requestParams.entrySet()) {
          request
              .getParameters()
              .put(entry.getKey(), URLDecoder.decode(entry.getValue(), StandardCharsets.UTF_8));
        }
      }

      if (cmd != null) {
        try {
          if (cmd.beforeExecute(request, response)) {
            try {
              // EXECUTE THE COMMAND
              isChain = cmd.execute(request, response);
            } finally {
              cmd.afterExecute(request, response);
            }
          }

        } catch (Exception e) {
          handleError(e, request);
        }
      } else {
        try {
          LogManager.instance()
              .warn(
                  this,
                  "->"
                      + channel.socket.getInetAddress().getHostAddress()
                      + ": Command not found: "
                      + request.getHttpMethod()
                      + "."
                      + URLDecoder.decode(command, StandardCharsets.UTF_8));

          sendError(
              HttpUtils.STATUS_INVALIDMETHOD_CODE,
              HttpUtils.STATUS_INVALIDMETHOD_DESCRIPTION,
              null,
              HttpUtils.CONTENT_TEXT_PLAIN,
              "Command not found: " + command,
              request.isKeepAlive());
        } catch (IOException e1) {
          sendShutdown();
        }
      }
    } while (isChain);

    connection.getStats().lastCommandInfo = connection.getData().commandInfo;
    connection.getStats().lastCommandDetail = connection.getData().commandDetail;

    connection.getStats().activeQueries = getActiveQueries(connection.getDatabase());

    connection.getStats().lastCommandExecutionTime = System.currentTimeMillis() - begin;
    connection.getStats().totalCommandExecutionTime +=
        connection.getStats().lastCommandExecutionTime;

    // request type does not have
    ServerPluginHelper.invokeHandlerCallbackOnAfterClientRequest(server, connection, (byte) -1);
  }

  private List<String> getActiveQueries(DatabaseSessionInternal database) {
    if (database == null) {
      return null;
    }
    try {

      Map<String, QueryDatabaseState> queries = database.getActiveQueries();
      return queries.values().stream()
          .map(x -> x.getResultSet().getExecutionPlan())
          .filter(x -> (x.isPresent() && x.get() instanceof InternalExecutionPlan))
          .map(InternalExecutionPlan.class::cast)
          .map(x -> x.getStatement())
          .collect(Collectors.toList());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public void sendShutdown() {
    super.sendShutdown();

    try {
      // FORCE SOCKET CLOSING
      if (channel.socket != null) {
        channel.socket.close();
      }
    } catch (final Exception e) {
    }
  }

  @Override
  public void shutdown() {
    try {
      sendShutdown();
      channel.close();

    } finally {
      server.getClientConnectionManager().disconnect(connection.getId());
      ServerPluginHelper.invokeHandlerCallbackOnSocketDestroyed(server, this);
      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance().debug(this, "Connection closed");
      }
    }
  }

  public OHttpRequest getRequest() {
    return request;
  }

  public HttpResponse getResponse() {
    return response;
  }

  @Override
  public SocketChannel getChannel() {
    return channel;
  }

  public SecurityUserIml getAccount() {
    return account;
  }

  public String getSessionID() {
    return request.getSessionId();
  }

  public String getResponseCharSet() {
    return responseCharSet;
  }

  public void setResponseCharSet(String responseCharSet) {
    this.responseCharSet = responseCharSet;
  }

  public String[] getAdditionalResponseHeaders() {
    return additionalResponseHeaders;
  }

  public HttpNetworkCommandManager getCommandManager() {
    return cmdManager;
  }

  protected void handleError(Throwable e, OHttpRequest iRequest) {
    if (LogManager.instance().isDebugEnabled()) {
      LogManager.instance().debug(this, "Caught exception", e);
    }

    int errorCode = 500;
    String errorReason = null;
    String errorMessage = null;
    String responseHeaders = null;

    if (e instanceof IllegalFormatException || e instanceof InputMismatchException) {
      errorCode = HttpUtils.STATUS_BADREQ_CODE;
      errorReason = HttpUtils.STATUS_BADREQ_DESCRIPTION;
    } else if (e instanceof RecordNotFoundException) {
      errorCode = HttpUtils.STATUS_NOTFOUND_CODE;
      errorReason = HttpUtils.STATUS_NOTFOUND_DESCRIPTION;
    } else if (e instanceof ConcurrentModificationException) {
      errorCode = HttpUtils.STATUS_CONFLICT_CODE;
      errorReason = HttpUtils.STATUS_CONFLICT_DESCRIPTION;
    } else if (e instanceof LockException) {
      errorCode = 423;
    } else if (e instanceof UnsupportedOperationException) {
      errorCode = HttpUtils.STATUS_NOTIMPL_CODE;
      errorReason = HttpUtils.STATUS_NOTIMPL_DESCRIPTION;
    } else if (e instanceof IllegalArgumentException) {
      errorCode = HttpUtils.STATUS_INTERNALERROR_CODE;
    }

    if (e instanceof DatabaseException
        || e instanceof SecurityAccessException
        || e instanceof CommandExecutionException
        || e instanceof LockException) {
      // GENERIC DATABASE EXCEPTION
      Throwable cause;
      do {
        cause = e instanceof SecurityAccessException ? e : e.getCause();
        if (cause instanceof SecurityAccessException) {
          // SECURITY EXCEPTION
          if (account == null) {
            // UNAUTHORIZED
            errorCode = HttpUtils.STATUS_AUTH_CODE;
            errorReason = HttpUtils.STATUS_AUTH_DESCRIPTION;

            String xRequestedWithHeader = iRequest.getHeader("X-Requested-With");
            if (xRequestedWithHeader == null || !xRequestedWithHeader.equals("XMLHttpRequest")) {
              // Defaults to "WWW-Authenticate: Basic" if not an AJAX Request.
              responseHeaders =
                  server
                      .getSecurity()
                      .getAuthenticationHeader(
                          ((SecurityAccessException) cause).getDatabaseName());
            }
            errorMessage = null;
          } else {
            // USER ACCESS DENIED
            errorCode = 530;
            errorReason = "The current user does not have the privileges to execute the request.";
            errorMessage = "530 User access denied";
          }
          break;
        }

        if (cause != null) {
          e = cause;
        }
      } while (cause != null);
    } else if (e instanceof CommandSQLParsingException) {
      errorMessage = e.getMessage();
      errorCode = HttpUtils.STATUS_BADREQ_CODE;
    }

    if (errorMessage == null) {
      // FORMAT GENERIC MESSAGE BY READING THE EXCEPTION STACK
      final StringBuilder buffer = new StringBuilder(256);
      buffer.append(e);
      Throwable cause = e.getCause();
      while (cause != null && cause != cause.getCause()) {
        buffer.append("\r\n--> ");
        buffer.append(cause);
        cause = cause.getCause();
      }
      errorMessage = buffer.toString();
    }

    if (errorReason == null) {
      errorReason = HttpUtils.STATUS_INTERNALERROR_DESCRIPTION;
      if (e instanceof NullPointerException) {
        LogManager.instance().error(this, "Internal server error:\n", e);
      } else {
        LogManager.instance().debug(this, "Internal server error:\n", e);
      }
    }

    try {
      sendError(
          errorCode,
          errorReason,
          responseHeaders,
          HttpUtils.CONTENT_TEXT_PLAIN,
          errorMessage,
          this.request.isKeepAlive());
    } catch (IOException e1) {
      sendShutdown();
    }
  }

  protected void sendTextContent(
      final int iCode,
      final String iReason,
      String iHeaders,
      final String iContentType,
      final String iContent,
      final boolean iKeepAlive)
      throws IOException {
    final boolean empty = iContent == null || iContent.length() == 0;

    sendStatus(empty && iCode == 200 ? 204 : iCode, iReason);
    sendResponseHeaders(iContentType, iKeepAlive);

    if (iHeaders != null) {
      writeLine(iHeaders);
    }

    final byte[] binaryContent = empty ? null : iContent.getBytes(utf8);

    writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (empty ? 0 : binaryContent.length));

    writeLine(null);

    if (binaryContent != null) {
      channel.writeBytes(binaryContent);
    }
    channel.flush();
  }

  protected void sendError(
      final int iCode,
      final String iReason,
      String iHeaders,
      final String iContentType,
      final String iContent,
      final boolean iKeepAlive)
      throws IOException {
    final byte[] binaryContent;

    if (!jsonResponseError) {
      sendTextContent(iCode, iReason, iHeaders, iContentType, iContent, iKeepAlive);
      return;
    }

    sendStatus(iCode, iReason);
    sendResponseHeaders(HttpUtils.CONTENT_JSON, iKeepAlive);

    if (iHeaders != null) {
      writeLine(iHeaders);
    }

    EntityImpl response = new EntityImpl();
    EntityImpl error = new EntityImpl();

    error.field("code", iCode);
    error.field("reason", iCode);
    error.field("content", iContent);

    List<EntityImpl> errors = new ArrayList<EntityImpl>();
    errors.add(error);

    response.field("errors", errors);

    binaryContent = response.toJSON("prettyPrint").getBytes(utf8);

    writeLine(
        HttpUtils.HEADER_CONTENT_LENGTH + (binaryContent != null ? binaryContent.length : 0));
    writeLine(null);

    if (binaryContent != null) {
      channel.writeBytes(binaryContent);
    }
    channel.flush();
  }

  protected void writeLine(final String iContent) throws IOException {
    if (iContent != null) {
      channel.outStream.write(iContent.getBytes());
    }
    channel.outStream.write(HttpUtils.EOL);
  }

  protected void sendStatus(final int iStatus, final String iReason) throws IOException {
    writeLine(request.getHttpVersion() + " " + iStatus + " " + iReason);
  }

  protected void sendResponseHeaders(final String iContentType, final boolean iKeepAlive)
      throws IOException {
    writeLine("Cache-Control: no-cache, no-store, max-age=0, must-revalidate");
    writeLine("Pragma: no-cache");
    writeLine("Date: " + new Date());
    writeLine("Content-Type: " + iContentType + "; charset=" + responseCharSet);
    writeLine("Server: YouTrackDB");
    writeLine("Connection: " + (iKeepAlive ? "Keep-Alive" : "close"));
    if (getAdditionalResponseHeaders() != null) {
      for (String h : getAdditionalResponseHeaders()) {
        writeLine(h);
      }
    }
  }

  protected void readAllContent(final OHttpRequest iRequest) throws IOException {
    iRequest.setContent(null);

    int in;
    char currChar;
    int contentLength = -1;
    boolean endOfHeaders = false;

    final StringBuilder request = new StringBuilder(512);

    while (!channel.socket.isInputShutdown()) {
      in = channel.read();
      if (in == -1) {
        break;
      }

      currChar = (char) in;

      if (currChar == '\r') {
        if (request.length() > 0 && !endOfHeaders) {
          final String line = request.toString();
          if (StringSerializerHelper.startsWithIgnoreCase(line, HttpUtils.HEADER_AUTHORIZATION)) {
            // STORE AUTHORIZATION INFORMATION INTO THE REQUEST
            final String auth = line.substring(HttpUtils.HEADER_AUTHORIZATION.length());
            if (StringSerializerHelper.startsWithIgnoreCase(
                auth, HttpUtils.AUTHORIZATION_BASIC)) {
              iRequest.setAuthorization(
                  auth.substring(HttpUtils.AUTHORIZATION_BASIC.length() + 1));
              iRequest.setAuthorization(
                  new String(Base64.getDecoder().decode(iRequest.getAuthorization())));
            } else if (StringSerializerHelper.startsWithIgnoreCase(
                auth, HttpUtils.AUTHORIZATION_BEARER)) {
              iRequest.setBearerTokenRaw(
                  auth.substring(HttpUtils.AUTHORIZATION_BEARER.length() + 1));
            } else if (StringSerializerHelper.startsWithIgnoreCase(
                auth, HttpUtils.AUTHORIZATION_NEGOTIATE)) {
              // Retrieves the SPNEGO authorization token.
              iRequest.setAuthorization(
                  "Negotiate:" + auth.substring(HttpUtils.AUTHORIZATION_NEGOTIATE.length() + 1));
            } else {
              throw new IllegalArgumentException(
                  "Only HTTP Basic and Bearer authorization are supported");
            }
          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_CONNECTION)) {
            iRequest.setKeepAlive(
                line.substring(HttpUtils.HEADER_CONNECTION.length())
                    .equalsIgnoreCase("Keep-Alive"));
          } else if (StringSerializerHelper.startsWithIgnoreCase(line, HttpUtils.HEADER_COOKIE)) {
            final String sessionPair = line.substring(HttpUtils.HEADER_COOKIE.length());

            final String[] sessionItems = sessionPair.split(";");
            for (String sessionItem : sessionItems) {
              final String[] sessionPairItems = sessionItem.trim().split("=");
              if (sessionPairItems.length == 2
                  && HttpUtils.OSESSIONID.equals(sessionPairItems[0])) {
                iRequest.setSessionId(sessionPairItems[1]);
                break;
              }
            }

          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_CONTENT_LENGTH)) {
            contentLength =
                Integer.parseInt(line.substring(HttpUtils.HEADER_CONTENT_LENGTH.length()));
            if (contentLength > requestMaxContentLength) {
              LogManager.instance()
                  .warn(
                      this,
                      "->"
                          + channel.socket.getInetAddress().getHostAddress()
                          + ": Error on content size "
                          + contentLength
                          + ": the maximum allowed is "
                          + requestMaxContentLength);
            }

          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_CONTENT_TYPE)) {
            iRequest.setContentType(line.substring(HttpUtils.HEADER_CONTENT_TYPE.length()));
            if (StringSerializerHelper.startsWithIgnoreCase(
                iRequest.getContentType(), HttpUtils.CONTENT_TYPE_MULTIPART)) {
              iRequest.setMultipart(true);
              iRequest.setBoundary(
                  line.substring(
                      HttpUtils.HEADER_CONTENT_TYPE.length()
                          + HttpUtils.CONTENT_TYPE_MULTIPART.length()
                          + 2
                          + HttpUtils.BOUNDARY.length()
                          + 1));
            }
          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_IF_MATCH)) {
            iRequest.setIfMatch(line.substring(HttpUtils.HEADER_IF_MATCH.length()));
          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_X_FORWARDED_FOR)) {
            connection.getData().caller =
                line.substring(HttpUtils.HEADER_X_FORWARDED_FOR.length());
          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_AUTHENTICATION)) {
            iRequest.setAuthentication(line.substring(HttpUtils.HEADER_AUTHENTICATION.length()));
          } else if (StringSerializerHelper.startsWithIgnoreCase(line, "Expect: 100-continue"))
          // SUPPORT THE CONTINUE TO AUTHORIZE THE CLIENT TO SEND THE CONTENT WITHOUT WAITING THE
          // DELAY
          {
            sendTextContent(100, null, null, null, null, iRequest.isKeepAlive());
          } else if (StringSerializerHelper.startsWithIgnoreCase(
              line, HttpUtils.HEADER_CONTENT_ENCODING)) {
            iRequest.setContentEncoding(
                line.substring(HttpUtils.HEADER_CONTENT_ENCODING.length()));
          }

          // SAVE THE HEADER
          iRequest.addHeader(line);
        }

        // CONSUME /r or /n
        in = channel.read();
        if (in == -1) {
          break;
        }

        currChar = (char) in;

        if (!endOfHeaders && request.length() == 0) {
          if (contentLength <= 0) {
            return;
          }

          // FIRST BLANK LINE: END OF HEADERS
          endOfHeaders = true;
        }

        request.setLength(0);
      } else if (endOfHeaders && request.length() == 0 && currChar != '\r' && currChar != '\n') {
        // END OF HEADERS
        if (iRequest.isMultipart()) {
          iRequest.setContent("");
          iRequest.setMultipartStream(
              new HttpMultipartBaseInputStream(channel.inStream, currChar, contentLength));
          return;
        } else {
          byte[] buffer = new byte[contentLength];
          buffer[0] = (byte) currChar;

          channel.read(buffer, 1, contentLength - 1);

          if (iRequest.getContentEncoding() != null
              && iRequest.getContentEncoding().equals(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED)) {
            iRequest.setContent(this.deCompress(buffer));
          } else {
            iRequest.setContent(new String(buffer));
          }
          return;
        }
      } else {
        request.append(currChar);
      }
    }

    if (LogManager.instance().isDebugEnabled()) {
      LogManager.instance()
          .debug(
              this,
              "Error on parsing HTTP content from client %s:\n%s",
              channel.socket.getInetAddress().getHostAddress(),
              request);
    }
  }

  @Override
  protected void execute() throws Exception {

    final var event = new HttpRequestEvent();

    try {
      if (channel.socket.getInetAddress() != null) {
        event.setClientAddress(channel.socket.getInetAddress().getHostAddress());
      }
      if (channel.socket.isInputShutdown() || channel.socket.isClosed()) {
        event.setError(Error.CONNECTION_CLOSED);
        sendShutdown();
        return;
      }

      connection.getData().commandInfo = "Listening";
      connection.getData().commandDetail = null;

      channel.socket.setSoTimeout(socketTimeout);
      connection.getStats().lastCommandReceived = -1;

      char c = (char) channel.read();

      if (channel.inStream.available() == 0) {
        event.setError(Error.CONNECTION_CLOSED);
        sendShutdown();
        return;
      }

      channel.socket.setSoTimeout(socketTimeout);
      event.begin();
      connection.getStats().lastCommandReceived = System.currentTimeMillis();

      request = new HttpRequestImpl(this, channel.inStream, connection.getData(), configuration);

      requestContent.setLength(0);
      request.setMultipart(false);

      if (c != '\n')
      // AVOID INITIAL /N
      {
        requestContent.append(c);
      }

      while (!channel.socket.isInputShutdown()) {
        c = (char) channel.read();

        if (c == '\r') {
          final String[] words = requestContent.toString().split(" ");
          if (words.length < 3) {
            LogManager.instance()
                .warn(
                    this,
                    "->"
                        + channel.socket.getInetAddress().getHostAddress()
                        + ": Error on invalid content:\n"
                        + requestContent);
            while (channel.inStream.available() > 0) {
              channel.read();
            }
            break;
          }

          // CONSUME THE NEXT \n
          channel.read();

          request.setHttpMethod(words[0].toUpperCase(Locale.ENGLISH));
          request.setUrl(words[1].trim());

          final int parametersPos = request.getUrl().indexOf('?');
          if (parametersPos > -1) {
            request.setParameters(
                HttpUtils.getParameters(request.getUrl().substring(parametersPos)));
            request.setUrl(request.getUrl().substring(0, parametersPos));
          }

          request.setHttpVersion(words[2]);
          readAllContent(request);

          if (request.getContent() != null
              && request.getContentType() != null
              && request.getContentType().equals(HttpUtils.CONTENT_TYPE_URLENCODED)) {
            request.setContent(
                URLDecoder.decode(request.getContent(), StandardCharsets.UTF_8).trim());
          }

          if (LogManager.instance().isDebugEnabled()) {
            LogManager.instance()
                .debug(
                    this,
                    "[NetworkProtocolHttpAbstract.execute] Requested: %s %s",
                    request.getHttpMethod(),
                    request.getUrl());
          }

          service();
          return;
        }
        requestContent.append(c);
        // review this number: NETWORK_HTTP_MAX_CONTENT_LENGTH should refer to the body only...
        if (GlobalConfiguration.NETWORK_HTTP_MAX_CONTENT_LENGTH.getValueAsInteger() > -1
            && requestContent.length()
            >= 10000
            + GlobalConfiguration.NETWORK_HTTP_MAX_CONTENT_LENGTH.getValueAsInteger()
            * 2) {
          while (channel.inStream.available() > 0) {
            channel.read();
          }
          throw new NetworkProtocolException("Invalid http request, max content length exceeded");
        }
      }

      if (LogManager.instance().isDebugEnabled()) {
        LogManager.instance()
            .debug(
                this,
                "Parsing request from client "
                    + channel.socket.getInetAddress().getHostAddress()
                    + ":\n"
                    + requestContent);
      }

    } catch (SocketException e) {
      event.setError(Error.SOCKET_ERROR);
      sendShutdown();

    } catch (SocketTimeoutException e) {
      event.setError(Error.TIMEOUT);
      sendShutdown();

    } catch (Exception t) {
      if (request.getHttpMethod() != null && request.getUrl() != null) {
        try {
          sendError(
              505,
              "Error on executing of "
                  + request.getHttpMethod()
                  + " for the resource: "
                  + request.getUrl(),
              null,
              "text/plain",
              t.toString(),
              request.isKeepAlive());
        } catch (IOException e) {
        }
      } else {
        sendError(
            505,
            "Error on executing request",
            null,
            "text/plain",
            t.toString(),
            request.isKeepAlive());
      }

      readAllContent(request);
    } finally {
      if (request != null) {
        event.setMethod(request.getHttpMethod());
        event.setUrl(request.getUrl());
      }
      event.setListeningAddress(listeningAddress);
      event.commit();
      request = null;
      response = null;
    }
  }

  protected String deCompress(byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) {
      return null;
    }
    GZIPInputStream gzip = null;
    ByteArrayInputStream in = null;
    ByteArrayOutputStream baos = null;
    try {
      in = new ByteArrayInputStream(zipBytes);
      gzip = new GZIPInputStream(in, 16384); // 16KB
      byte[] buffer = new byte[1024];
      baos = new ByteArrayOutputStream();
      int len = -1;
      while ((len = gzip.read(buffer, 0, buffer.length)) != -1) {
        baos.write(buffer, 0, len);
      }
      String newstr = baos.toString(StandardCharsets.UTF_8);
      return newstr;
    } catch (Exception ex) {
      LogManager.instance().error(this, "Error on decompressing HTTP response", ex);
    } finally {
      try {
        if (gzip != null) {
          gzip.close();
        }
        if (in != null) {
          in.close();
        }
        if (baos != null) {
          baos.close();
        }
      } catch (Exception ex) {
      }
    }
    return null;
  }

  public static void registerHandlers(
      Object caller,
      YouTrackDBServer server,
      ServerNetworkListener iListener,
      HttpNetworkCommandManager cmdManager) {

    cmdManager.registerCommand(new ServerCommandGetConnect());
    cmdManager.registerCommand(new ServerCommandGetDisconnect());
    cmdManager.registerCommand(new ServerCommandGetClass());
    cmdManager.registerCommand(new ServerCommandGetCluster());
    cmdManager.registerCommand(new ServerCommandGetDatabase());
    cmdManager.registerCommand(new ServerCommandGetDictionary());
    cmdManager.registerCommand(new ServerCommandGetDocument());
    cmdManager.registerCommand(new ServerCommandGetDocumentByClass());
    cmdManager.registerCommand(new ServerCommandGetQuery());
    cmdManager.registerCommand(new ServerCommandGetServer());
    cmdManager.registerCommand(new ServerCommandGetServerVersion());
    cmdManager.registerCommand(new ServerCommandGetConnections());
    cmdManager.registerCommand(new ServerCommandGetStorageAllocation());
    cmdManager.registerCommand(new ServerCommandGetFileDownload());
    cmdManager.registerCommand(new ServerCommandGetIndex());
    cmdManager.registerCommand(new ServerCommandGetListDatabases());
    cmdManager.registerCommand(new ServerCommandIsEnterprise());
    cmdManager.registerCommand(new ServerCommandGetExportDatabase());
    cmdManager.registerCommand(new ServerCommandPatchDocument());
    cmdManager.registerCommand(new ServerCommandPostBatch());
    cmdManager.registerCommand(new ServerCommandPostClass());
    cmdManager.registerCommand(new ServerCommandPostCommandGraph());
    cmdManager.registerCommand(new ServerCommandPostDatabase());
    cmdManager.registerCommand(new ServerCommandPostInstallDatabase());
    cmdManager.registerCommand(new ServerCommandPostDocument());
    cmdManager.registerCommand(new ServerCommandPostImportRecords());
    cmdManager.registerCommand(new ServerCommandPostProperty());
    cmdManager.registerCommand(new ServerCommandPostConnection());
    cmdManager.registerCommand(new ServerCommandPostServer());
    cmdManager.registerCommand(new ServerCommandPostServerCommand());
    cmdManager.registerCommand(new ServerCommandPostStudio());
    cmdManager.registerCommand(new ServerCommandPutDocument());
    cmdManager.registerCommand(new ServerCommandPutIndex());
    cmdManager.registerCommand(new ServerCommandDeleteClass());
    cmdManager.registerCommand(new ServerCommandDeleteDatabase());
    cmdManager.registerCommand(new ServerCommandDeleteDocument());
    cmdManager.registerCommand(new ServerCommandDeleteProperty());
    cmdManager.registerCommand(new ServerCommandDeleteIndex());
    cmdManager.registerCommand(new ServerCommandOptions());
    cmdManager.registerCommand(new ServerCommandFunction());
    cmdManager.registerCommand(new ServerCommandPostKillDbConnection());
    cmdManager.registerCommand(new ServerCommandGetSupportedLanguages());
    cmdManager.registerCommand(new ServerCommandPostAuthToken());
    cmdManager.registerCommand(new ServerCommandGetSSO());
    cmdManager.registerCommand(new ServerCommandGetPing());

    for (ServerCommandConfiguration c : iListener.getStatefulCommands()) {
      try {
        cmdManager.registerCommand(ServerNetworkListener.createCommand(server, c));
      } catch (Exception e) {
        LogManager.instance()
            .error(caller, "Error on creating stateful command '%s'", e, c.implementation);
      }
    }

    for (ServerCommand c : iListener.getStatelessCommands()) {
      cmdManager.registerCommand(c);
    }
  }

  protected void registerStatelessCommands(final ServerNetworkListener iListener) {

    cmdManager = new HttpNetworkCommandManager(server, null);

    registerHandlers(this, server, iListener, cmdManager);
  }

  public ClientConnection getConnection() {
    return connection;
  }

  public static String getCommandString(final String command, String method) {
    final int getQueryPosition = command.indexOf('?');

    final StringBuilder commandString = new StringBuilder(256);
    commandString.append(method);
    commandString.append(COMMAND_SEPARATOR);

    if (getQueryPosition > -1) {
      commandString.append(command, 0, getQueryPosition);
    } else {
      commandString.append(command);
    }
    return commandString.toString();
  }

  @Override
  public String getRemoteAddress() {
    return ((InetSocketAddress) channel.socket.getRemoteSocketAddress())
        .getAddress()
        .getHostAddress();
  }

  @Override
  public void setDatabase(DatabaseSessionInternal db) {
    connection.setDatabase(db);
  }
}
