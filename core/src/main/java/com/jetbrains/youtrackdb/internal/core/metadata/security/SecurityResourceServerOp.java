package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class SecurityResourceServerOp extends SecurityResource {

  public static SecurityResourceServerOp SERVER = new SecurityResourceServerOp("server");
  public static SecurityResourceServerOp STATUS = new SecurityResourceServerOp("server.status");
  public static SecurityResourceServerOp REMOVE = new SecurityResourceServerOp("server.remove");
  public static SecurityResourceServerOp ADMIN = new SecurityResourceServerOp("server.admin");

  private SecurityResourceServerOp(String resourceString) {
    super(resourceString);
  }
}
