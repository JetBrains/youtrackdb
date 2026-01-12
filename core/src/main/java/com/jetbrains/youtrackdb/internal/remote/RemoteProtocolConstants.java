package com.jetbrains.youtrackdb.internal.remote;

public interface RemoteProtocolConstants {

  String PROCESSOR_NAME = "ytdbServerCommands";

  String DATABASE_NAME_PARAMETER = "ytdb-database-name-parameter";
  String USER_CREDENTIALS_PARAMETER = "ytdb-user-credentials-parameter";
  String BACKUP_PATH_PARAMETER = "ytdb-backup-path-parameter";
  String DATABASE_TYPE_PARAMETER = "ytdb-database-type-parameter";
  String CONFIGURATION_PARAMETER = "ytdb-configuration-parameter";
  String USER_NAME_PARAMETER = "ytdb-user-name-parameter";
  String USER_ROLES_PARAMETER = "ytdb-user-roles-parameter";
  String USER_PASSWORD_PARAMETER = "ytdb-user-password-parameter";

  String SERVER_COMMAND_CREATE_DATABASE = "ytdb-server-command-create-database";
  String SERVER_COMMAND_CREATE_DATABASE_IF_NOT_EXIST = "ytdb-server-command-create-database-if-not-exist";
  String SERVER_COMMAND_DROP_DATABASE = "ytdb-server-command-drop-database";
  String SERVER_COMMAND_LIST_DATABASES = "ytdb-server-command-list-databases";
  String SERVER_COMMAND_EXISTS = "ytdb-server-command-exists";
  String SERVER_COMMAND_RESTORE = "ytdb-server-command-restore";
  String SERVER_COMMAND_CREATE_SYSTEM_USER = "ytdb-server-command-create-system-user";
  String SERVER_COMMAND_LIST_SYSTEM_USERS = "ytdb-server-command-list-system-users";
  String SERVER_COMMAND_DROP_SYSTEM_USER = "ytdb-server-command-drop-system-user";

  String RESULT_METADATA_COMMITTED_RIDS_KEY = "committedRIDs";
}
