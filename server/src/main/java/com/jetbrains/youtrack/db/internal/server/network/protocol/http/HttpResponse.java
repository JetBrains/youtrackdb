package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;

public interface HttpResponse {

  String JSON_FORMAT =
      "type,indent:-1,rid,version,attribSameRow,class,keepTypes,alwaysFetchEmbeddedDocuments";

  void send(int iCode, String iReason, String iContentType, Object iContent, String iHeaders);

  void writeStatus(int iStatus, String iReason) throws IOException;

  void writeHeaders(String iContentType) throws IOException;

  void writeHeaders(String iContentType, boolean iKeepAlive) throws IOException;

  void writeLine(String iContent) throws IOException;

  void writeContent(String iContent) throws IOException;

  void writeResult(Object result, DatabaseSessionInternal session)
      throws InterruptedException, IOException;

  void writeResult(
      Object iResult,
      String iFormat,
      String iAccept,
      DatabaseSessionInternal session)
      throws InterruptedException, IOException;

  void writeResult(
      Object iResult,
      String iFormat,
      String iAccept,
      Map<String, Object> iAdditionalProperties,
      DatabaseSessionInternal session)
      throws InterruptedException, IOException;

  void writeResult(
      Object iResult,
      String iFormat,
      String iAccept,
      Map<String, Object> iAdditionalProperties,
      String mode,
      DatabaseSessionInternal session);

  void writeRecords(Object iRecords, DatabaseSessionInternal session)
      throws IOException;

  void writeRecords(
      Object iRecords,
      String iFetchPlan,
      String iFormat,
      String accept,
      Map<String, Object> iAdditionalProperties,
      DatabaseSessionInternal session);

  void writeRecords(
      Object iRecords,
      String iFetchPlan,
      String iFormat,
      String accept,
      Map<String, Object> iAdditionalProperties,
      String mode,
      DatabaseSessionInternal session)
      throws IOException;

  void formatMultiValue(
      Iterator<?> iIterator,
      Writer buffer,
      String format,
      DatabaseSessionInternal session)
      throws IOException;

  void writeRecord(DBRecord iRecord);

  void writeRecord(DBRecord iRecord, String iFetchPlan, String iFormat);

  void sendStream(int iCode, String iReason, String iContentType, InputStream iContent, long iSize)
      throws IOException;

  void sendStream(
      int iCode,
      String iReason,
      String iContentType,
      InputStream iContent,
      long iSize,
      String iFileName)
      throws IOException;

  void sendStream(
      int iCode,
      String iReason,
      String iContentType,
      InputStream iContent,
      long iSize,
      String iFileName,
      Map<String, String> additionalHeaders)
      throws IOException;

  void sendStream(
      int iCode,
      String iReason,
      String iContentType,
      String iFileName,
      CallableFunction<Void, ChunkedResponse> iWriter)
      throws IOException;

  // Compress content string
  byte[] compress(String jsonStr);

  /**
   * Stores additional headers to send
   */
  void setHeader(String iHeader);

  OutputStream getOutputStream();

  void flush() throws IOException;

  String getContentType();

  void setContentType(String contentType);

  String getContentEncoding();

  void setContentEncoding(String contentEncoding);

  void setStaticEncoding(String contentEncoding);

  void setSessionId(String sessionId);

  String getContent();

  void setContent(String content);

  int getCode();

  void setCode(int code);

  void setJsonErrorResponse(boolean jsonErrorResponse);

  void setStreaming(boolean streaming);

  String getHttpVersion();

  OutputStream getOut();

  String getHeaders();

  void setHeaders(String headers);

  String[] getAdditionalHeaders();

  void setAdditionalHeaders(String[] additionalHeaders);

  String getCharacterSet();

  void setCharacterSet(String characterSet);

  String getServerInfo();

  void setServerInfo(String serverInfo);

  String getSessionId();

  String getCallbackFunction();

  void setCallbackFunction(String callbackFunction);

  String getStaticEncoding();

  boolean isSendStarted();

  void setSendStarted(boolean sendStarted);

  boolean isKeepAlive();

  void setKeepAlive(boolean keepAlive);

  boolean isJsonErrorResponse();

  ClientConnection getConnection();

  void setConnection(ClientConnection connection);

  boolean isStreaming();

  void setSameSiteCookie(boolean sameSiteCookie);

  boolean isSameSiteCookie();

  ContextConfiguration getContextConfiguration();

  void addHeader(String name, String value);

  Map<String, String> getHeadersMap();

  void checkConnection() throws IOException;
}
