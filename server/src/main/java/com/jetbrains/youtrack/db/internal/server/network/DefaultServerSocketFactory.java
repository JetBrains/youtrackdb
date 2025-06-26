package com.jetbrains.youtrack.db.internal.server.network;

import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

class DefaultServerSocketFactory extends ServerSocketFactory {

  DefaultServerSocketFactory() {
  }

  public ServerSocket createServerSocket() throws IOException {
    return new ServerSocket();
  }

  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    return new ServerSocket(port);
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    return new ServerSocket(port, backlog);
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress)
      throws IOException {
    return new ServerSocket(port, backlog, ifAddress);
  }

  @Override
  public void config(String name, ServerParameterConfiguration[] iParameters) {
    super.config(name, iParameters);
  }

  @Override
  public String getName() {
    return "default";
  }
}
