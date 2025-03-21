package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

/**
 * Drops a use.
 *
 * @since 4/22/2015
 */
public class CommandExecutorSQLDropUser extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_DROP = "DROP";
  public static final String KEYWORD_USER = "USER";

  private static final String SYNTAX = "DROP USER <user-name>";
  private static final String USER_CLASS = "OUser";
  private static final String USER_FIELD_NAME = "name";

  private String userName;

  @Override
  public CommandExecutorSQLDropUser parse(DatabaseSessionInternal session,
      CommandRequest iRequest) {
    init(session, (CommandRequestText) iRequest);

    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_DROP);
    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_USER);
    this.userName = parserRequiredWord(false, "Expected <user name>", session.getDatabaseName());

    return this;
  }

  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    if (this.userName == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    // Build following command:
    // DELETE FROM OUser WHERE name='<name>'

    //
    var sb =
        "DELETE FROM " + USER_CLASS + " WHERE " + USER_FIELD_NAME + "='" + this.userName + "'";

    //
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSyntax() {
    return SYNTAX;
  }
}
