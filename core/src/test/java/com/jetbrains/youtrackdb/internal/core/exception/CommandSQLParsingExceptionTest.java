/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.ParseException;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Token;
import com.jetbrains.youtrackdb.internal.core.sql.parser.TokenMgrError;
import org.junit.Test;

/**
 * Bespoke tests for {@link CommandSQLParsingException} — outside the parameterized fan because the
 * class has six distinct constructors driven by parser inputs ({@link ParseException}, {@link
 * TokenMgrError}, raw text + position) plus the canonical message-only and dbName-message paths.
 * Pins the {@code line} / {@code column} / {@code statement} accessors and the visual-pointer
 * generation in {@code generateMessage}, which is the user-visible parse-error format.
 */
public class CommandSQLParsingExceptionTest {

  /**
   * The simple {@code (String iMessage)} ctor is one of the canonical paths. Pin the message
   * round-trip via {@link Throwable#getMessage()}.
   */
  @Test
  public void messageOnlyConstructorRoundTripsMessage() {
    var ex = new CommandSQLParsingException("syntax error");
    assertThat(ex.getMessage()).contains("syntax error");
  }

  /**
   * The {@code (String dbName, String iMessage)} ctor must round-trip both fields. Pin the
   * dbName via {@link BaseException#getDbName()}.
   */
  @Test
  public void dbNameAndMessageConstructorRoundTripsBothFields() {
    var ex = new CommandSQLParsingException("dbA", "missing FROM");
    assertThat(ex.getMessage()).contains("missing FROM");
    assertThat(ex.getDbName()).isEqualTo("dbA");
  }

  /**
   * The {@code (String iMessage, String iText, int iPosition)} ctor builds a multi-line message
   * with a visual {@code ^} pointer indicating the parse position in the offending text. Pin the
   * pointer-generation behaviour so a future refactor that drops the visual hint fails loudly.
   */
  @Test
  public void textPositionConstructorBuildsVisualPointer() {
    var ex = new CommandSQLParsingException("unexpected token", "SELECT * FROM bar", 8);
    assertThat(ex.getMessage())
        .contains("unexpected token")
        .contains("SELECT * FROM bar")
        .contains("^");
  }

  /**
   * The {@code (String dbName, String iMessage, String iText, int iPosition)} ctor combines the
   * visual pointer with a database name. Pin both observables.
   */
  @Test
  public void dbNameTextPositionConstructorRoundTripsAllFields() {
    var ex =
        new CommandSQLParsingException("dbA", "unexpected token", "SELECT * FROM bar", 8);
    assertThat(ex.getMessage())
        .contains("unexpected token")
        .contains("SELECT * FROM bar")
        .contains("^");
    assertThat(ex.getDbName()).isEqualTo("dbA");
  }

  /**
   * The {@code (DatabaseSessionEmbedded session, String iMessage, String iText, int iPosition)}
   * ctor delegates to the dbName-text-position ctor with a null-safe extraction. Pin the null
   * session arm — passing null must not NPE.
   */
  @Test
  public void sessionTextPositionConstructorAcceptsNullSession() {
    var ex =
        new CommandSQLParsingException(
            (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) null,
            "missing column",
            "INSERT INTO foo",
            4);
    assertThat(ex.getMessage())
        .contains("missing column")
        .contains("INSERT INTO foo")
        .contains("^");
    assertThat(ex.getDbName()).isNull();
  }

  /**
   * The {@code (String dbName, ParseException, String statement)} ctor pulls the line/column from
   * {@link ParseException#currentToken}'s next token, formats a multi-line error message, and
   * stores the diagnostic accessors. Pin {@code getLine()} / {@code getColumn()} /
   * {@code getStatement()} round-trip.
   */
  @Test
  public void parseExceptionConstructorRecordsPositionAndStatement() {
    // Build a synthetic ParseException with a token chain. ParseException's currentToken is
    // public and holds the token we'd point at; its .next token carries the parse position.
    var current = new Token();
    current.next = new Token();
    current.next.beginLine = 5;
    current.next.endColumn = 12;
    current.next.image = "BADTOKEN";

    var pe = new ParseException("unexpected token");
    pe.currentToken = current;

    var ex = new CommandSQLParsingException("dbA", pe, "SELECT * FROM foo\nWHERE x = 1\nBADTOKEN");
    assertThat(ex.getLine()).isEqualTo(5);
    assertThat(ex.getColumn()).isEqualTo(12);
    assertThat(ex.getStatement()).isEqualTo("SELECT * FROM foo\nWHERE x = 1\nBADTOKEN");
    assertThat(ex.getMessage()).contains("Error parsing query");
  }

  /**
   * The {@code (TokenMgrError, String statement)} ctor uses zero line/column placeholders and
   * pulls the raw error message from the {@link TokenMgrError}. Pin both observables.
   */
  @Test
  public void tokenMgrErrorConstructorUsesZeroLineAndColumn() {
    var err = new TokenMgrError("lexer error: bad char $", 0);
    var ex = new CommandSQLParsingException(err, "SELECT $ FROM foo");

    assertThat(ex.getLine()).isEqualTo(0);
    assertThat(ex.getColumn()).isEqualTo(0);
    assertThat(ex.getStatement()).isEqualTo("SELECT $ FROM foo");
    assertThat(ex.getMessage()).contains("lexer error: bad char $");
  }

  /**
   * The {@code (String dbName, TokenMgrError, String statement)} ctor combines the dbName with
   * the lexer-error path. Pin all three accessors plus the dbName.
   */
  @Test
  public void dbNameTokenMgrErrorConstructorPopulatesDbName() {
    var err = new TokenMgrError("lexer error: unterminated", 0);
    var ex = new CommandSQLParsingException("dbA", err, "SELECT");

    assertThat(ex.getLine()).isEqualTo(0);
    assertThat(ex.getColumn()).isEqualTo(0);
    assertThat(ex.getStatement()).isEqualTo("SELECT");
    assertThat(ex.getDbName()).isEqualTo("dbA");
    assertThat(ex.getMessage()).contains("lexer error: unterminated");
  }

  /**
   * The copy ctor must propagate the message and dbName. Pin via a round-trip from a built
   * ParseException-driven instance.
   */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var current = new Token();
    current.next = new Token();
    current.next.beginLine = 1;
    current.next.endColumn = 1;
    current.next.image = "X";
    var pe = new ParseException("bad");
    pe.currentToken = current;

    var original = new CommandSQLParsingException("dbA", pe, "X");
    var copy = new CommandSQLParsingException(original);

    assertThat(copy.getMessage()).contains("Error parsing query");
    assertThat(copy.getDbName()).isEqualTo("dbA");
  }
}
