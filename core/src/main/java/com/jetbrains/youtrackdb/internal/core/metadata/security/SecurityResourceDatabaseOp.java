package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class SecurityResourceDatabaseOp extends SecurityResource {

  public static SecurityResourceDatabaseOp DB = new SecurityResourceDatabaseOp("database");
  public static SecurityResourceDatabaseOp CREATE =
      new SecurityResourceDatabaseOp("database.create");
  public static SecurityResourceDatabaseOp COPY = new SecurityResourceDatabaseOp("database.copy");
  public static SecurityResourceDatabaseOp DROP = new SecurityResourceDatabaseOp("database.drop");
  public static SecurityResourceDatabaseOp EXISTS =
      new SecurityResourceDatabaseOp("database.exists");
  public static SecurityResourceDatabaseOp COMMAND =
      new SecurityResourceDatabaseOp("database.command");
  public static SecurityResourceDatabaseOp COMMAND_GREMLIN =
      new SecurityResourceDatabaseOp("database.command.gremlin");
  public static SecurityResourceDatabaseOp FREEZE =
      new SecurityResourceDatabaseOp("database.freeze");
  public static SecurityResourceDatabaseOp RELEASE =
      new SecurityResourceDatabaseOp("database.release");
  public static SecurityResourceDatabaseOp PASS_THROUGH =
      new SecurityResourceDatabaseOp("database.passthrough");
  public static SecurityResourceDatabaseOp BYPASS_RESTRICTED =
      new SecurityResourceDatabaseOp("database.bypassRestricted");
  public static SecurityResourceDatabaseOp HOOK_RECORD =
      new SecurityResourceDatabaseOp("database.hook.record");

  private SecurityResourceDatabaseOp(String resourceString) {
    super(resourceString);
  }
}
