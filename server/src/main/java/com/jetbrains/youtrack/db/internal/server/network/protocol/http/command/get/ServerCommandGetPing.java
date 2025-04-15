package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAbstract;

public class ServerCommandGetPing extends ServerCommandAbstract {

  private static final String[] NAMES = {"GET|ping"};

  @Override
  public String[] getNames() {
    return NAMES;
  }

  public ServerCommandGetPing() {
  }

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        "pong",
        null);

    return false; // Is not a chained command.
  }
}
