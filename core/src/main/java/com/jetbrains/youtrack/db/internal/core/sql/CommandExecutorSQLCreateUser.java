package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates a new user.
 *
 * @since 4/22/2015
 */
public class CommandExecutorSQLCreateUser extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_CREATE = "CREATE";
  public static final String KEYWORD_USER = "USER";
  public static final String KEYWORD_IDENTIFIED = "IDENTIFIED";
  public static final String KEYWORD_BY = "BY";
  public static final String KEYWORD_ROLE = "ROLE";
  public static final String SYNTAX =
      "CREATE USER <user-name> IDENTIFIED BY <user-password> [ ROLE <role-name> ]";

  private static final String USER_CLASS = "OUser";
  private static final String USER_FIELD_NAME = "name";
  private static final String USER_FIELD_PASSWORD = "password";
  private static final String USER_FIELD_STATUS = "status";
  private static final String USER_FIELD_ROLES = "roles";

  private static final String DEFAULT_STATUS = "ACTIVE";
  private static final String DEFAULT_ROLE = "writer";
  private static final String ROLE_CLASS = "ORole";
  private static final String ROLE_FIELD_NAME = "name";

  private String userName;
  private String pass;
  private List<String> roles;

  @Override
  public CommandExecutorSQLCreateUser parse(DatabaseSessionInternal session,
      CommandRequest iRequest) {
    init(session, (CommandRequestText) iRequest);

    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_CREATE);
    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_USER);
    this.userName = parserRequiredWord(false, "Expected <user-name>", session.getDatabaseName());

    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_IDENTIFIED);
    parserRequiredKeyword(session.getDatabaseName(), KEYWORD_BY);
    this.pass = parserRequiredWord(false, "Expected <user-password>", session.getDatabaseName());

    this.roles = new ArrayList<String>();

    String temp;
    while ((temp = parseOptionalWord(session.getDatabaseName(), true)) != null) {
      if (parserIsEnded()) {
        break;
      }

      if (temp.equals(KEYWORD_ROLE)) {
        var role = parserRequiredWord(false, "Expected <role-name>", session.getDatabaseName());
        var roleLen = (role != null) ? role.length() : 0;
        if (roleLen > 0) {
          if (role.charAt(0) == '[' && role.charAt(roleLen - 1) == ']') {
            role = role.substring(1, role.length() - 1);
            var splits = role.split("[, ]");
            for (var spl : splits) {
              if (spl.length() > 0) {
                this.roles.add(spl);
              }
            }
          } else {
            this.roles.add(role);
          }
        }
      }
    }

    return this;
  }

  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    if (this.userName == null) {
      throw new CommandExecutionException(session.getDatabaseName(),
          "Cannot execute the command because it has not been parsed yet");
    }

    // Build following command:
    // INSERT INTO OUser SET name='<name>', password='<pass>', status='ACTIVE',
    // role=(SELECT FROM Role WHERE name in ['<role1>', '<role2>', ...])

    // INSERT INTO OUser SET
    var sb = new StringBuilder();
    sb.append("INSERT INTO ");
    sb.append(USER_CLASS);
    sb.append(" SET ");

    // name=<name>
    sb.append(USER_FIELD_NAME);
    sb.append("='");
    sb.append(this.userName);
    sb.append("'");

    // pass=<pass>
    sb.append(',');
    sb.append(USER_FIELD_PASSWORD);
    sb.append("='");
    sb.append(this.pass);
    sb.append("'");

    // status=ACTIVE
    sb.append(',');
    sb.append(USER_FIELD_STATUS);
    sb.append("='");
    sb.append(DEFAULT_STATUS);
    sb.append("'");

    // role=(select from Role where name in [<input_role || 'writer'>)]
    if (this.roles.size() == 0) {
      this.roles.add(DEFAULT_ROLE);
    }

    sb.append(',');
    sb.append(USER_FIELD_ROLES);
    sb.append("=(SELECT FROM ");
    sb.append(ROLE_CLASS);
    sb.append(" WHERE ");
    sb.append(ROLE_FIELD_NAME);
    sb.append(" IN [");
    for (var i = 0; i < this.roles.size(); ++i) {
      if (i > 0) {
        sb.append(", ");
      }
      var role = roles.get(i);
      if (role.startsWith("'") || role.startsWith("\"")) {
        sb.append(this.roles.get(i));
      } else {
        sb.append("'");
        sb.append(this.roles.get(i));
        sb.append("'");
      }
    }
    sb.append("])");
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSyntax() {
    return SYNTAX;
  }
}
