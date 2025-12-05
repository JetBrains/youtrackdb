package com.jetbrains.youtrackdb.internal.server;


import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.server.plugin.ServerPluginAbstract;


public class ServerFailingOnStarupPluginStub extends ServerPluginAbstract {

  @Override
  public void startup() {
    throw new DatabaseException("this plugin is not starting correctly");
  }

  @Override
  public String getName() {
    return "failing on startup plugin";
  }
}
