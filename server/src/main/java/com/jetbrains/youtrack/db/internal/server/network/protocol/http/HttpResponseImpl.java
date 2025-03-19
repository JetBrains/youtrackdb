package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.server.ClientConnection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;

public class HttpResponseImpl extends HttpResponseAbstract {

  public HttpResponseImpl(
      OutputStream iOutStream,
      String iHttpVersion,
      String[] iAdditionalHeaders,
      String iResponseCharSet,
      String iServerInfo,
      String iSessionId,
      String iCallbackFunction,
      boolean iKeepAlive,
      ClientConnection connection,
      ContextConfiguration contextConfiguration) {
    super(
        iOutStream,
        iHttpVersion,
        iAdditionalHeaders,
        iResponseCharSet,
        iServerInfo,
        iSessionId,
        iCallbackFunction,
        iKeepAlive,
        connection,
        contextConfiguration);
  }

  @Override
  public void send(
      final int iCode,
      final String iReason,
      final String iContentType,
      final Object iContent,
      final String iHeaders)
      throws IOException {
    if (isSendStarted()) {
      // AVOID TO SEND RESPONSE TWICE
      return;
    }
    setSendStarted(true);

    if (getCallbackFunction() != null) {
      setContent(getCallbackFunction() + "(" + iContent + ")");
      setContentType("text/javascript");
    } else {
      if (getContent() == null || getContent().length() == 0) {
        setContent(iContent != null ? iContent.toString() : null);
      }
      if (getContentType() == null || getContentType().length() == 0) {
        setContentType(iContentType);
      }
    }

    final var empty = getContent() == null || getContent().length() == 0;

    if (this.getCode() > 0) {
      writeStatus(this.getCode(), iReason);
    } else {
      writeStatus(empty && iCode == 200 ? 204 : iCode, iReason);
    }
    writeHeaders(getContentType(), isKeepAlive());

    if (iHeaders != null) {
      writeLine(iHeaders);
    }

    if (getSessionId() != null) {
      var sameSite = (isSameSiteCookie() ? "SameSite=Strict;" : "");
      writeLine(
          "Set-Cookie: "
              + HttpUtils.OSESSIONID
              + "="
              + getSessionId()
              + "; Path=/; HttpOnly;"
              + sameSite);
    }

    byte[] binaryContent = null;
    if (!empty) {
      if (getContentEncoding() != null
          && getContentEncoding().equals(HttpUtils.CONTENT_ACCEPT_GZIP_ENCODED)) {
        binaryContent = compress(getContent());
      } else {
        binaryContent = getContent().getBytes(utf8);
      }
    }

    writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (empty ? 0 : binaryContent.length));

    writeLine(null);

    if (binaryContent != null) {
      getOut().write(binaryContent);
    }

    flush();
  }

  @Override
  public void writeStatus(final int iStatus, final String iReason) throws IOException {
    writeLine(getHttpVersion() + " " + iStatus + " " + iReason);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize)
      throws IOException {
    sendStream(iCode, iReason, iContentType, iContent, iSize, null, null);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize,
      final String iFileName)
      throws IOException {
    sendStream(iCode, iReason, iContentType, iContent, iSize, iFileName, null);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize,
      final String iFileName,
      Map<String, String> additionalHeaders)
      throws IOException {
    writeStatus(iCode, iReason);
    writeHeaders(iContentType);
    writeLine("Content-Transfer-Encoding: binary");

    if (iFileName != null) {
      writeLine("Content-Disposition: attachment; filename=\"" + iFileName + "\"");
    }

    if (additionalHeaders != null) {
      for (var entry : additionalHeaders.entrySet()) {
        writeLine(String.format("%s: %s", entry.getKey(), entry.getValue()));
      }
    }
    if (iSize < 0) {
      // SIZE UNKNOWN: USE A MEMORY BUFFER
      final var o = new ByteArrayOutputStream();
      if (iContent != null) {
        int b;
        while ((b = iContent.read()) > -1) {
          o.write(b);
        }
      }

      var content = o.toByteArray();

      iContent = new ByteArrayInputStream(content);
      iSize = content.length;
    }

    writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (iSize));
    writeLine(null);

    if (iContent != null) {
      int b;
      while ((b = iContent.read()) > -1) {
        getOut().write(b);
      }
    }

    flush();
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      final String iFileName,
      final CallableFunction<Void, ChunkedResponse> iWriter)
      throws IOException {
    writeStatus(iCode, iReason);
    writeHeaders(iContentType);
    writeLine("Content-Transfer-Encoding: binary");
    writeLine("Transfer-Encoding: chunked");

    if (iFileName != null) {
      writeLine("Content-Disposition: attachment; filename=\"" + iFileName + "\"");
    }

    writeLine(null);

    final var chunkedOutput = new ChunkedResponse(this);
    iWriter.call(chunkedOutput);
    chunkedOutput.close();

    flush();
  }

  @Override
  public void checkConnection() throws IOException {
    final Socket socket;
    if (getConnection().getProtocol() == null
        || getConnection().getProtocol().getChannel() == null) {
      socket = null;
    } else {
      socket = getConnection().getProtocol().getChannel().socket;
    }
    if (socket == null || socket.isClosed() || socket.isInputShutdown()) {
      LogManager.instance()
          .debug(
              this,
              "[HttpResponse] found and removed pending closed channel %d (%s)",
              getConnection(),
              socket);
      throw new IOException("Connection is closed");
    }
  }
}
