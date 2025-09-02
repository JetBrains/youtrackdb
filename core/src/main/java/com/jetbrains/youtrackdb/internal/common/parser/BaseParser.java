/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.common.parser;

import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Abstract generic command to parse.
 */
public abstract class BaseParser {

  public String parserText;
  public String parserTextUpperCase;

  private final transient StringBuilder parserLastWord = new StringBuilder(256);
  private transient int parserEscapeSequenceCount = 0;
  private transient int parserCurrentPos = 0;
  private transient int parserPreviousPos = 0;
  private transient char parserLastSeparator = ' ';

  public static int nextWord(
      final String iText,
      final String iTextUpperCase,
      int ioCurrentPosition,
      final StringBuilder ioWord,
      final boolean iForceUpperCase) {
    return nextWord(iText, iTextUpperCase, ioCurrentPosition, ioWord, iForceUpperCase, " =><(),");
  }

  public static int nextWord(
      final String iText,
      final String iTextUpperCase,
      int ioCurrentPosition,
      final StringBuilder ioWord,
      final boolean iForceUpperCase,
      final String iSeparatorChars) {
    ioWord.setLength(0);

    ioCurrentPosition = StringParser.jumpWhiteSpaces(iText, ioCurrentPosition, -1);
    if (ioCurrentPosition < 0) {
      return -1;
    }

    getWordStatic(
        iForceUpperCase ? iTextUpperCase : iText, ioCurrentPosition, iSeparatorChars, ioWord);

    if (ioWord.length() > 0) {
      ioCurrentPosition += ioWord.length();
    }

    return ioCurrentPosition;
  }

  /**
   * @param iText           Text where to search
   * @param iBeginIndex     Begin index
   * @param iSeparatorChars Separators as a String of multiple characters
   * @param ioBuffer        StringBuilder object with the word found
   */
  public static void getWordStatic(
      final CharSequence iText,
      int iBeginIndex,
      final String iSeparatorChars,
      final StringBuilder ioBuffer) {
    ioBuffer.setLength(0);

    var stringBeginChar = ' ';
    char c;

    for (var i = iBeginIndex; i < iText.length(); ++i) {
      c = iText.charAt(i);
      var found = false;
      for (var sepIndex = 0; sepIndex < iSeparatorChars.length(); ++sepIndex) {
        if (iSeparatorChars.charAt(sepIndex) == c) {
          // SEPARATOR AT THE BEGINNING: JUMP IT
          found = true;
          break;
        }
      }
      if (!found) {
        break;
      }

      iBeginIndex++;
    }

    for (var i = iBeginIndex; i < iText.length(); ++i) {
      c = iText.charAt(i);

      if (c == '\'' || c == '"' || c == '`') {
        if (stringBeginChar != ' ') {
          // CLOSE THE STRING?
          if (stringBeginChar == c) {
            // SAME CHAR AS THE BEGIN OF THE STRING: CLOSE IT AND PUSH
            stringBeginChar = ' ';
          }
        } else {
          // START STRING
          stringBeginChar = c;
        }
      } else if (stringBeginChar == ' ') {
        for (var sepIndex = 0; sepIndex < iSeparatorChars.length(); ++sepIndex) {
          if (iSeparatorChars.charAt(sepIndex) == c && ioBuffer.length() > 0) {
            // SEPARATOR (OUTSIDE A STRING): PUSH
            return;
          }
        }
      }

      ioBuffer.append(c);
    }
  }

  public String getSyntax() {
    return "?";
  }

  /**
   * Returns the last separator encountered, otherwise returns a blank (' ').
   */
  public char parserGetLastSeparator() {
    return parserLastSeparator;
  }

  /**
   * Overwrites the last separator. To ignore it set it to blank (' ').
   */
  public void parserSetLastSeparator(final char iSeparator) {
    parserLastSeparator = iSeparator;
  }

  /**
   * Returns the stream position before last parsing.
   *
   * @return Offset from the beginning
   */
  public int parserGetPreviousPosition() {
    return parserPreviousPos;
  }

  /**
   * Tells if the parsing has reached the end of the content.
   *
   * @return True if is ended, otherwise false
   */
  public boolean parserIsEnded() {
    return parserCurrentPos == -1;
  }

  /**
   * Returns the current stream position.
   *
   * @return Offset from the beginning
   */
  public int parserGetCurrentPosition() {
    return parserCurrentPos;
  }

  /**
   * Returns the current character in the current stream position
   *
   * @return The current character in the current stream position. If the end is reached, then a
   * blank (' ') is returned
   */
  public char parserGetCurrentChar() {
    if (parserCurrentPos < 0) {
      return ' ';
    }
    return parserText.charAt(parserCurrentPos);
  }

  /**
   * Returns the last parsed word.
   *
   * @return Last parsed word as String
   */
  public String parserGetLastWord() {
    return parserLastWord.toString();
  }

  public int getLastWordLength() {
    return parserLastWord.length() + parserEscapeSequenceCount;
  }

  /**
   * Throws a syntax error exception.
   *
   * @param dbName
   * @param iText  Text about the problem.
   */
  protected abstract void throwSyntaxErrorException(String dbName, final String iText);

  /**
   * Parses the next word. It returns the word parsed if any.
   *
   * @param iUpperCase True if must return UPPERCASE, otherwise false
   * @return The word parsed if any, otherwise null
   */
  @Nullable
  protected String parserOptionalWord(final boolean iUpperCase) {
    parserPreviousPos = parserCurrentPos;

    parserNextWord(iUpperCase);
    if (parserLastWord.length() == 0) {
      return null;
    }
    return parserLastWord.toString();
  }

  /**
   * Parses the next word. If any word is parsed it's checked against the word array received as
   * parameter. If the parsed word is not enlisted in it a SyntaxError exception is thrown. It
   * returns the word parsed if any.
   *
   * @param dbName
   * @param iUpperCase True if must return UPPERCASE, otherwise false
   * @return The word parsed if any, otherwise null
   */
  @Nullable
  protected String parseOptionalWord(String dbName, final boolean iUpperCase,
      final String... iWords) {
    parserNextWord(iUpperCase);

    if (iWords.length > 0) {
      if (parserLastWord.length() == 0) {
        return null;
      }

      var found = false;
      for (var w : iWords) {
        if (parserLastWord.toString().equals(w)) {
          found = true;
          break;
        }
      }

      if (!found) {
        throwSyntaxErrorException(dbName,
            "Found unexpected keyword '"
                + parserLastWord
                + "' while it was expected '"
                + Arrays.toString(iWords)
                + "'");
      }
    }

    if (parserLastWord.length() > 1
        && parserLastWord.charAt(0) == '`'
        && parserLastWord.charAt(parserLastWord.length() - 1) == '`') {
      return parserLastWord.substring(1, parserLastWord.length() - 1);
    }

    return parserLastWord.toString();
  }

  /**
   * Goes back to the previous position.
   *
   * @return The previous position
   */
  protected int parserGoBack() {
    parserCurrentPos = parserPreviousPos;
    return parserCurrentPos;
  }

  /**
   * Parses the next word. If no word is found an SyntaxError exception is thrown It returns the
   * word parsed if any.
   *
   * @param dbName
   * @param iUpperCase True if must return UPPERCASE, otherwise false
   * @return The word parsed
   */
  protected String parserRequiredWord(String dbName, final boolean iUpperCase) {
    return parserRequiredWord(iUpperCase, "Syntax error", null, dbName);
  }

  /**
   * Parses the next word. If no word is found an SyntaxError exception with the custom message
   * received as parameter is thrown It returns the word parsed if any.
   *
   * @param iUpperCase     True if must return UPPERCASE, otherwise false
   * @param iCustomMessage Custom message to include in case of SyntaxError exception
   * @param dbName
   * @return The word parsed
   */
  protected String parserRequiredWord(final boolean iUpperCase, final String iCustomMessage,
      String dbName) {
    return parserRequiredWord(iUpperCase, iCustomMessage, null, dbName);
  }

  /**
   * Parses the next word. If no word is found or the parsed word is not present in the word array
   * received as parameter then a SyntaxError exception with the custom message received as
   * parameter is thrown. It returns the word parsed if any.
   *
   * @param iUpperCase     True if must return UPPERCASE, otherwise false
   * @param iCustomMessage Custom message to include in case of SyntaxError exception
   * @param iSeparators    Separator characters
   * @param dbName
   * @return The word parsed
   */
  protected String parserRequiredWord(
      final boolean iUpperCase, final String iCustomMessage, String iSeparators, String dbName) {
    if (iSeparators == null) {
      iSeparators = " ()=><,\r\n";
    }

    parserNextWord(iUpperCase, iSeparators);
    if (parserLastWord.length() == 0) {
      throwSyntaxErrorException(dbName, iCustomMessage);
    }
    if (parserLastWord.charAt(0) == '`'
        && parserLastWord.charAt(parserLastWord.length() - 1) == '`') {
      return parserLastWord.substring(1, parserLastWord.length() - 1);
    }
    return parserLastWord.toString();
  }

  /**
   * Parses the next word. If no word is found or the parsed word is not present in the word array
   * received as parameter then a SyntaxError exception is thrown.
   *
   * @param dbName
   * @param iWords Array of expected keywords
   */
  protected void parserRequiredKeyword(String dbName, final String... iWords) {
    parserNextWord(true, " \r\n,()");
    if (parserLastWord.length() == 0) {
      throwSyntaxErrorException(dbName,
          "Cannot find expected keyword '" + Arrays.toString(iWords) + "'");
    }

    var found = false;
    for (var w : iWords) {
      if (parserLastWord.toString().equals(w)) {
        found = true;
        break;
      }
    }

    if (!found) {
      throwSyntaxErrorException(dbName,
          "Found unexpected keyword '"
              + parserLastWord
              + "' while it was expected '"
              + Arrays.toString(iWords)
              + "'");
    }
  }

  /**
   * Parses the next sequence of chars.
   *
   * @return The position of the word matched if any, otherwise -1 or an exception if iMandatory is
   * true
   */
  protected int parserNextChars(
      String dbName, final boolean iUpperCase, final boolean iMandatory,
      final String... iCandidateWords) {
    parserPreviousPos = parserCurrentPos;
    parserSkipWhiteSpaces();

    parserEscapeSequenceCount = 0;
    parserLastWord.setLength(0);

    final var processedWords = Arrays.copyOf(iCandidateWords, iCandidateWords.length);

    // PARSE THE CHARS
    final var text2Use = iUpperCase ? parserTextUpperCase : parserText;
    final var max = text2Use.length();

    parserCurrentPos = parserCurrentPos + parserTextUpperCase.length() - parserText.length();
    // PARSE TILL 1 CHAR AFTER THE END TO SIMULATE A SEPARATOR AS EOF
    for (var i = 0; parserCurrentPos <= max; ++i) {
      final var ch = parserCurrentPos < max ? text2Use.charAt(parserCurrentPos) : '\n';
      final var separator = ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t' || ch == '(';
      if (!separator) {
        parserLastWord.append(ch);
      }

      // CLEAR CANDIDATES
      var candidatesWordsCount = 0;
      var candidatesWordsPos = -1;
      for (var c = 0; c < processedWords.length; ++c) {
        final var w = processedWords[c];
        if (w != null) {
          final var wordSize = w.length();
          if ((separator && wordSize > i)
              || (!separator && (i > wordSize - 1 || w.charAt(i) != ch)))
          // DISCARD IT
          {
            processedWords[c] = null;
          } else {
            candidatesWordsCount++;
            if (candidatesWordsCount == 1)
            // REMEMBER THE POSITION
            {
              candidatesWordsPos = c;
            }
          }
        }
      }

      if (candidatesWordsCount == 1) {
        // ONE RESULT, CHECKING IF FOUND
        final var w = processedWords[candidatesWordsPos];
        if (w.length() == i + (separator ? 0 : 1) && !Character.isLetter(ch))
        // FOUND!
        {
          return candidatesWordsPos;
        }
      }

      if (candidatesWordsCount == 0 || separator) {
        break;
      }

      parserCurrentPos++;
    }

    if (iMandatory) {
      throwSyntaxErrorException(dbName,
          "Found unexpected keyword '"
              + parserLastWord
              + "' while it was expected '"
              + Arrays.toString(iCandidateWords)
              + "'");
    }

    return -1;
  }

  /**
   * Parses optional keywords between the iWords. If a keyword is found but doesn't match with
   * iWords then a SyntaxError is raised.
   *
   * @param dbName
   * @param iWords Optional words to match as keyword. If at least one is passed, then the check is
   *               made
   * @return true if a keyword was found, otherwise false
   */
  protected boolean parserOptionalKeyword(String dbName, final String... iWords) {
    parserNextWord(true, " \r\n,");
    if (parserLastWord.length() == 0) {
      return false;
    }

    // FOUND: CHECK IF IT'S IN RANGE
    var found = iWords.length == 0;
    for (var w : iWords) {
      if (parserLastWord.toString().equals(w)) {
        found = true;
        break;
      }
    }

    if (!found) {
      throwSyntaxErrorException(dbName,
          "Found unexpected keyword '"
              + parserLastWord
              + "' while it was expected '"
              + Arrays.toString(iWords)
              + "'");
    }

    return true;
  }

  /**
   * Skips not valid characters like spaces and line feeds.
   *
   * @return True if the string is not ended, otherwise false
   */
  protected boolean parserSkipWhiteSpaces() {
    if (parserCurrentPos == -1) {
      return false;
    }

    parserCurrentPos = StringParser.jumpWhiteSpaces(parserText, parserCurrentPos, -1);
    return parserCurrentPos > -1;
  }

  /**
   * Overwrites the current stream position.
   *
   * @param iPosition New position
   * @return True if the string is not ended, otherwise false
   */
  protected boolean parserSetCurrentPosition(final int iPosition) {
    parserCurrentPos = iPosition;
    if (parserCurrentPos >= parserText.length())
    // END OF TEXT
    {
      parserCurrentPos = -1;
    }
    return parserCurrentPos > -1;
  }

  /**
   * Sets the end of text as position
   */
  protected void parserSetEndOfText() {
    parserCurrentPos = -1;
  }

  /**
   * Moves the current stream position forward or backward of iOffset characters
   *
   * @param iOffset Number of characters to move. Negative numbers means backwards
   * @return True if the string is not ended, otherwise false
   */
  protected boolean parserMoveCurrentPosition(final int iOffset) {
    if (parserCurrentPos < 0) {
      return false;
    }
    return parserSetCurrentPosition(parserCurrentPos + iOffset);
  }

  /**
   * Parses the next word.
   *
   * @param iForceUpperCase True if must return UPPERCASE, otherwise false
   */
  protected String parserNextWord(final boolean iForceUpperCase) {
    return parserNextWord(iForceUpperCase, " =><(),\r\n");
  }

  /**
   * Parses the next word.
   *
   * @param iForceUpperCase True if must return UPPERCASE, otherwise false
   * @param iSeparatorChars
   */
  protected String parserNextWord(final boolean iForceUpperCase, final String iSeparatorChars) {
    return parserNextWord(iForceUpperCase, iSeparatorChars, false);
  }

  @Nullable
  protected String parserNextWord(
      final boolean iForceUpperCase, final String iSeparatorChars, boolean preserveEscapes) {
    parserPreviousPos = parserCurrentPos;
    parserLastWord.setLength(0);
    parserEscapeSequenceCount = 0;

    parserSkipWhiteSpaces();
    if (parserCurrentPos == -1) {
      return null;
    }

    var stringBeginChar = ' ';

    final var text2Use = iForceUpperCase ? parserTextUpperCase : parserText;

    while (parserCurrentPos < text2Use.length()) {
      final var c = text2Use.charAt(parserCurrentPos);
      var found = false;
      for (var sepIndex = 0; sepIndex < iSeparatorChars.length(); ++sepIndex) {
        if (iSeparatorChars.charAt(sepIndex) == c) {
          // SEPARATOR AT THE BEGINNING: JUMP IT
          found = true;
          break;
        }
      }
      if (!found) {
        break;
      }

      parserCurrentPos++;
    }

    try {
      var openParenthesis = 0;
      var openBracket = 0;
      var openGraph = 0;

      var escapePos = -1;

      for (; parserCurrentPos < text2Use.length(); parserCurrentPos++) {
        final var c = text2Use.charAt(parserCurrentPos);

        if (escapePos == -1 && c == '\\' && ((parserCurrentPos + 1) < text2Use.length())) {
          // ESCAPE CHARS

          if (openGraph == 0) {
            final var nextChar = text2Use.charAt(parserCurrentPos + 1);
            if (preserveEscapes) {
              parserLastWord.append(c);
              parserLastWord.append(nextChar);
              parserCurrentPos++;
            } else {

              if (nextChar == 'u') {
                parserCurrentPos =
                    StringParser.readUnicode(text2Use, parserCurrentPos + 2, parserLastWord);
                parserEscapeSequenceCount += 5;
              } else {
                if (nextChar == 'n') {
                  parserLastWord.append('\n');
                } else if (nextChar == 'r') {
                  parserLastWord.append('\r');
                } else if (nextChar == 't') {
                  parserLastWord.append('\t');
                } else if (nextChar == 'b') {
                  parserLastWord.append('\b');
                } else if (nextChar == 'f') {
                  parserLastWord.append('\f');
                } else {
                  parserLastWord.append(nextChar);
                  parserEscapeSequenceCount++;
                }

                parserCurrentPos++;
              }
            }
            continue;
          } else {
            escapePos = parserCurrentPos;
          }
        }

        if (escapePos == -1 && (c == '\'' || c == '"')) {
          if (stringBeginChar != ' ') {
            // CLOSE THE STRING?
            if (stringBeginChar == c) {
              // SAME CHAR AS THE BEGIN OF THE STRING: CLOSE IT AND PUSH
              stringBeginChar = ' ';

              if (openBracket == 0 && openGraph == 0 && openParenthesis == 0) {
                parserCurrentPos++;
                parserLastWord.append(c);
                break;
              }
            }
          } else
          // START STRING
          {
            stringBeginChar = c;
          }
        }

        if (stringBeginChar == ' ') {
          if (openBracket == 0
              && openGraph == 0
              && openParenthesis == 0
              && parserCheckSeparator(c, iSeparatorChars)) {
            // SEPARATOR FOUND!
            break;
          } else if (c == '(') {
            openParenthesis++;
          } else if (c == ')' && openParenthesis > 0) {
            openParenthesis--;
          } else if (c == '[') {
            openBracket++;
          } else if (c == ']' && openBracket > 0) {
            openBracket--;
          } else if (c == '{') {
            openGraph++;
          } else if (c == '}' && openGraph > 0) {
            openGraph--;
          }
        }

        if (escapePos != -1) {
          parserEscapeSequenceCount++;
        }

        if (escapePos != parserCurrentPos) {
          escapePos = -1;
        }

        parserLastWord.append(c);
      }

      // CHECK MISSING CHARACTER
      if (stringBeginChar != ' ') {
        throw new IllegalStateException(
            "Missing closed string character: '"
                + stringBeginChar
                + "', position: "
                + parserCurrentPos);
      }
      if (openBracket > 0) {
        throw new IllegalStateException(
            "Missing closed braket character: ']', position: " + parserCurrentPos);
      }
      if (openGraph > 0) {
        throw new IllegalStateException(
            "Missing closed graph character: '}', position: " + parserCurrentPos);
      }
      if (openParenthesis > 0) {
        throw new IllegalStateException(
            "Missing closed parenthesis character: ')', position: " + parserCurrentPos);
      }

    } finally {
      if (parserCurrentPos >= text2Use.length()) {
        // END OF TEXT
        parserCurrentPos = -1;
        parserLastSeparator = ' ';
      }
    }

    return parserLastWord.toString();
  }

  /**
   * Check for a separator
   *
   * @param c
   * @param iSeparatorChars
   * @return
   */
  private boolean parserCheckSeparator(final char c, final String iSeparatorChars) {
    for (var sepIndex = 0; sepIndex < iSeparatorChars.length(); ++sepIndex) {
      if (iSeparatorChars.charAt(sepIndex) == c) {
        parserLastSeparator = c;
        return true;
      }
    }
    return false;
  }
}
