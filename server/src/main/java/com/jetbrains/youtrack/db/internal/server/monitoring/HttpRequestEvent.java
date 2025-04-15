package com.jetbrains.youtrack.db.internal.server.monitoring;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.jetbrains.youtrack.db.server.HttpRequest")
@Category({"YouTrackDB", "Server"})
@Label("HTTP request")
@Description("HTTP request")
@Enabled(false)
public class HttpRequestEvent extends jdk.jfr.Event {

  private String method;
  private String url;
  private String listeningAddress;
  private String clientAddress;
  private String error;

  public void setMethod(String method) {
    this.method = method;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public void setClientAddress(String clientAddress) {
    this.clientAddress = clientAddress;
  }

  public void setError(Error error) {
    this.error = error.name();
  }

  public void setListeningAddress(String listeningAddress) {
    this.listeningAddress = listeningAddress;
  }

  public enum Error {
    TIMEOUT,
    CONNECTION_CLOSED,
    SOCKET_ERROR
  }
}