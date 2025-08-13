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
package com.jetbrains.youtrackdb.internal.common.console;

import com.jetbrains.youtrackdb.internal.common.console.annotation.ConsoleCommand;
import com.jetbrains.youtrackdb.internal.common.console.annotation.ConsoleParameter;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.parser.StringParser;
import com.jetbrains.youtrackdb.internal.common.util.ArrayUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class ConsoleApplication {

  public static final String PARAM_DISABLE_HISTORY = "--disable-history";

  public static final String ONLINE_HELP_URL = "";
  public static final String ONLINE_HELP_EXT = ".md";
  protected static final String[] COMMENT_PREFIXS = new String[]{"#", "--", "//"};
  protected final StringBuilder commandBuffer = new StringBuilder(2048);
  protected InputStream in = System.in; // System.in;
  protected PrintStream out = System.out;
  protected PrintStream err = System.err;
  protected String wordSeparator = " ";
  protected String[] helpCommands = {"help", "?"};
  protected String[] exitCommands = {"exit", "bye", "quit"};
  protected Map<String, String> properties = new HashMap<String, String>();
  protected ConsoleReader reader = new DefaultConsoleReader();
  protected boolean interactiveMode;
  protected String[] args;
  protected TreeMap<Method, Object> methods;
  private boolean isInCollectingMode = false;

  public ConsoleApplication(String[] iArgs) {
    this.args = iArgs;
  }

  public static String getCorrectMethodName(Method m) {
    var buffer = new StringBuilder(128);
    buffer.append(getClearName(m.getName()));
    for (var i = 0; i < m.getParameterAnnotations().length; i++) {
      for (var j = 0; j < m.getParameterAnnotations()[i].length; j++) {
        if (m.getParameterAnnotations()[i][j]
            instanceof ConsoleParameter) {
          buffer.append(
              " <"
                  + ((ConsoleParameter)
                  m.getParameterAnnotations()[i][j])
                  .name()
                  + ">");
        }
      }
    }
    return buffer.toString();
  }

  public static String getClearName(String iJavaName) {
    var buffer = new StringBuilder();

    char c;
    if (iJavaName != null) {
      buffer.append(iJavaName.charAt(0));
      for (var i = 1; i < iJavaName.length(); ++i) {
        c = iJavaName.charAt(i);

        if (Character.isUpperCase(c)) {
          buffer.append(' ');
        }

        buffer.append(Character.toLowerCase(c));
      }
    }
    return buffer.toString();
  }

  public void setReader(ConsoleReader iReader) {
    this.reader = iReader;
    reader.setConsole(this);
  }

  public int run() {
    interactiveMode = isInteractiveMode(args);
    onBefore();

    var result = 0;

    if (interactiveMode) {
      // EXECUTE IN INTERACTIVE MODE
      // final BufferedReader reader = new BufferedReader(new InputStreamReader(in));

      String consoleInput = null;

      while (true) {
        try {
          if (commandBuffer.length() == 0) {
            out.println();
            out.print(getPrompt());
          }

          consoleInput = reader.readLine();

          if (consoleInput == null || consoleInput.length() == 0) {
            continue;
          }

          if (!executeCommands(new ConsoleCommandStream(consoleInput), false)) {
            break;
          }
        } catch (Exception e) {
          result = 1;
          out.print("Error on reading console input: " + e.getMessage());
          LogManager.instance().error(this, "Error on reading console input: %s", e, consoleInput);
        }
      }
    } else {
      // EXECUTE IN BATCH MODE
      result = executeBatch(getCommandLine(args)) ? 0 : 1;
    }

    onAfter();

    return result;
  }

  protected void message(final String iMessage) {
    final var verboseLevel = getVerboseLevel();
    if (verboseLevel > 1) {
      out.print(iMessage);
    }
  }

  public void error(final String iMessage) {
    final var verboseLevel = getVerboseLevel();
    if (verboseLevel > 0) {
      out.print(iMessage);
    }
  }

  public int getVerboseLevel() {
    final var v = properties.get(ConsoleProperties.VERBOSE);
    final var verboseLevel = v != null ? Integer.parseInt(v) : 2;
    return verboseLevel;
  }

  protected int getConsoleWidth() {
    final var width = properties.get(ConsoleProperties.WIDTH);
    return width == null ? reader.getConsoleWidth() : Integer.parseInt(width);
  }

  public boolean isEchoEnabled() {
    return isPropertyEnabled(ConsoleProperties.ECHO);
  }

  protected boolean isPropertyEnabled(final String iPropertyName) {
    var v = properties.get(iPropertyName);
    if (v != null) {
      v = v.toLowerCase(Locale.ENGLISH);
      return v.equals("true") || v.equals("on");
    }
    return false;
  }

  protected String getPrompt() {
    return String.format("%s> ", getContext());
  }

  protected String getContext() {
    return "";
  }

  protected static boolean isInteractiveMode(String[] args) {
    for (var arg : args) {
      if (!isInteractiveConfigParam(arg)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isInteractiveConfigParam(String arg) {
    return arg.equalsIgnoreCase(PARAM_DISABLE_HISTORY);
  }

  protected boolean executeBatch(final String commandLine) {
    var commandFile = new File(commandLine);
    if (!commandFile.isAbsolute()) {
      commandFile = new File(new File("."), commandLine);
    }

    CommandStream scanner;
    try {
      scanner = new ConsoleCommandStream(commandFile);
    } catch (FileNotFoundException ignore) {
      scanner = new ConsoleCommandStream(commandLine);
    }

    return executeCommands(scanner, true);
  }

  protected boolean executeCommands(final CommandStream commandStream, final boolean iBatchMode) {
    try {
      while (commandStream.hasNext()) {
        var commandLine = commandStream.nextCommand();

        if (commandLine.isEmpty())
        // EMPTY LINE
        {
          continue;
        }

        if (isComment(commandLine)) {
          continue;
        }

        // SCRIPT CASE: MANAGE ENSEMBLING ALL TOGETHER
        if (isCollectingCommands(commandLine)) {
          // BEGIN: START TO COLLECT
          out.println("[Started multi-line command. Type just 'end' to finish and execute]");
          commandBuffer.append(commandLine);
          commandLine = null;
          isInCollectingMode = true;
        } else if (commandLine.startsWith("end") && commandBuffer.length() > 0) {
          // END: FLUSH IT
          commandLine = commandBuffer.toString();
          commandBuffer.setLength(0);
          isInCollectingMode = false;
        } else if (commandBuffer.length() > 0) {
          // BUFFER IT
          commandBuffer.append(' ');
          commandBuffer.append(commandLine);
          commandBuffer.append('\n');
          commandLine = null;
        }

        if (commandLine != null) {
          if (isEchoEnabled()) {
            out.println();
            out.print(getPrompt());
            out.print(commandLine);
            out.println();
          }

          if (commandLine.endsWith(";")) {
            commandLine = commandLine.substring(0, commandLine.length() - 1);
          }
          final var status = execute(commandLine);
          commandLine = null;

          if (status == RESULT.EXIT
              || (status == RESULT.ERROR
              && !Boolean.parseBoolean(properties.get(ConsoleProperties.IGNORE_ERRORS)))
              && iBatchMode) {
            return false;
          }
        }
      }

      if (!isInCollectingMode && commandBuffer.length() > 0) {
        if (iBatchMode && isEchoEnabled()) {
          out.println();
          out.print(getPrompt());
          out.print(commandBuffer);
          out.println();
        }

        final var status = execute(commandBuffer.toString());
        if (status == RESULT.EXIT
            || (status == RESULT.ERROR
            && !Boolean.parseBoolean(properties.get(ConsoleProperties.IGNORE_ERRORS)))
            && iBatchMode) {
          return false;
        }
      }
    } finally {
      commandStream.close();
    }
    return true;
  }

  protected boolean isComment(final String commandLine) {
    for (var comment : COMMENT_PREFIXS) {
      if (commandLine.startsWith(comment)) {
        return true;
      }
    }
    return false;
  }

  protected boolean isCollectingCommands(final String iLine) {
    return false;
  }

  protected RESULT execute(String iCommand) {
    var result = executeServerCommand(iCommand);
    if (result != RESULT.NOT_EXECUTED) {
      return result;
    }

    iCommand = iCommand.replaceAll("\n", ";\n");
    iCommand = iCommand.trim();

    if (iCommand.length() == 0)
    // NULL LINE: JUMP IT
    {
      return RESULT.OK;
    }

    if (isComment(iCommand))
    // COMMENT: JUMP IT
    {
      return RESULT.OK;
    }

    String[] commandWords;
    if (iCommand.toLowerCase().startsWith("load script")
        || iCommand.toLowerCase().startsWith("create database")
        || iCommand.toLowerCase().startsWith("drop database")
        || iCommand.toLowerCase().startsWith("connect")) {
      commandWords = iCommand.split(" ");
      commandWords = Arrays.stream(commandWords).filter(s -> s.length() > 0).toArray(String[]::new);
      for (var i = 2; i < commandWords.length; i++) {
        var wrappedInQuotes = false;
        if (commandWords[i].startsWith("'") && commandWords[i].endsWith("'")) {
          wrappedInQuotes = true;
        } else if (commandWords[i].startsWith("\"") && commandWords[i].endsWith("\"")) {
          wrappedInQuotes = true;
        }

        if (wrappedInQuotes) {
          commandWords[i] = commandWords[i].substring(1, commandWords[i].length() - 1);
        }
      }
    } else {
      commandWords = StringParser.getWords(iCommand, wordSeparator);
    }

    for (var cmd : helpCommands) {
      if (cmd.equals(commandWords[0])) {
        if (iCommand.length() > cmd.length()) {
          help(iCommand.substring(cmd.length() + 1));
        } else {
          help(null);
        }

        return RESULT.OK;
      }
    }

    for (var cmd : exitCommands) {
      if (cmd.equalsIgnoreCase(commandWords[0])) {
        return RESULT.EXIT;
      }
    }

    Method lastMethodInvoked = null;
    final var lastCommandInvoked = new StringBuilder(1024);

    var commandLowerCaseBuilder = new StringBuilder();
    for (var i = 0; i < commandWords.length; i++) {
      if (i > 0) {
        commandLowerCaseBuilder.append(" ");
      }
      commandLowerCaseBuilder.append(commandWords[i].toLowerCase(Locale.ENGLISH));
    }
    var commandLowerCase = commandLowerCaseBuilder.toString();

    for (var entry : getConsoleMethods().entrySet()) {
      final var m = entry.getKey();
      final var methodName = m.getName();
      final var ann = m.getAnnotation(ConsoleCommand.class);

      final var commandName = new StringBuilder();
      char ch;
      var commandWordCount = 1;
      for (var i = 0; i < methodName.length(); ++i) {
        ch = methodName.charAt(i);
        if (Character.isUpperCase(ch)) {
          commandName.append(" ");
          ch = Character.toLowerCase(ch);
          commandWordCount++;
        }
        commandName.append(ch);
      }

      if (!commandLowerCase.contentEquals(commandName)
          && !commandLowerCase.startsWith(commandName + " ")) {
        if (ann == null) {
          continue;
        }

        var aliases = ann.aliases();
        if (aliases == null || aliases.length == 0) {
          continue;
        }

        var aliasMatch = false;
        for (var alias : aliases) {
          if (iCommand.startsWith(alias.split(" ")[0])) {
            aliasMatch = true;
            commandWordCount = 1;
            break;
          }
        }

        if (!aliasMatch) {
          continue;
        }
      }

      Object[] methodArgs;

      // BUILD PARAMETERS
      if (ann != null && !ann.splitInWords()) {
        methodArgs = new String[]{iCommand.substring(iCommand.indexOf(' ') + 1)};
      } else {
        final var actualParamCount = commandWords.length - commandWordCount;
        if (m.getParameterTypes().length > actualParamCount) {
          // METHOD PARAMS AND USED PARAMS MISMATCH: CHECK FOR OPTIONALS
          for (var paramNum = m.getParameterAnnotations().length - 1;
              paramNum > actualParamCount - 1;
              paramNum--) {
            final var paramAnn = m.getParameterAnnotations()[paramNum];
            if (paramAnn != null) {
              for (var annNum = paramAnn.length - 1; annNum > -1; annNum--) {
                if (paramAnn[annNum] instanceof ConsoleParameter annotation) {
                  if (annotation.optional()) {
                    commandWords = ArrayUtils.copyOf(commandWords, commandWords.length + 1);
                  }
                  break;
                }
              }
            }
          }
        }
        methodArgs = ArrayUtils.copyOfRange(commandWords, commandWordCount, commandWords.length);
      }

      try {
        m.invoke(entry.getValue(), methodArgs);

      } catch (IllegalArgumentException ignore) {
        lastMethodInvoked = m;
        // GET THE COMMAND NAME
        lastCommandInvoked.setLength(0);
        for (var i = 0; i < commandWordCount; ++i) {
          if (lastCommandInvoked.length() > 0) {
            lastCommandInvoked.append(" ");
          }
          lastCommandInvoked.append(commandWords[i]);
        }
        continue;
      } catch (Exception e) {
        if (e.getCause() != null) {
          onException(e.getCause());
        } else {
          e.printStackTrace(err);
        }
        return RESULT.ERROR;
      }
      return RESULT.OK;
    }

    if (lastMethodInvoked != null) {
      syntaxError(lastCommandInvoked.toString(), lastMethodInvoked);
    }

    error(String.format("\n!Unrecognized command: '%s'", iCommand));
    return RESULT.ERROR;
  }

  protected RESULT executeServerCommand(String iCommand) {
    return RESULT.NOT_EXECUTED;
  }

  private int getCompatibilityLevel() {
    try {
      var compLevelString = properties.get(ConsoleProperties.COMPATIBILITY_LEVEL);
      return Integer.parseInt(compLevelString);
    } catch (Exception e) {
      return ConsoleProperties.COMPATIBILITY_LEVEL_LATEST;
    }
  }

  @Nullable
  protected Method getMethod(String iCommand) {
    iCommand = iCommand.trim();

    if (iCommand.length() == 0)
    // NULL LINE: JUMP IT
    {
      return null;
    }

    if (isComment(iCommand))
    // COMMENT: JUMP IT
    {
      return null;
    }

    final var commandLowerCase = iCommand.toLowerCase(Locale.ENGLISH);

    final var methodMap = getConsoleMethods();

    final var commandSignature = new StringBuilder();
    var separator = false;
    for (var i = 0; i < iCommand.length(); ++i) {
      final var ch = iCommand.charAt(i);
      if (ch == ' ') {
        separator = true;
      } else {
        if (separator) {
          separator = false;
          commandSignature.append(Character.toUpperCase(ch));
        } else {
          commandSignature.append(ch);
        }
      }
    }

    final var commandSignatureToCheck = commandSignature.toString();

    for (var entry : methodMap.entrySet()) {
      final var m = entry.getKey();
      if (m.getName().equals(commandSignatureToCheck))
      // FOUND EXACT MATCH
      {
        return m;
      }
    }

    for (var entry : methodMap.entrySet()) {
      final var m = entry.getKey();
      final var methodName = m.getName();
      final var ann = m.getAnnotation(ConsoleCommand.class);

      final var commandName = new StringBuilder();
      char ch;
      for (var i = 0; i < methodName.length(); ++i) {
        ch = methodName.charAt(i);
        if (Character.isUpperCase(ch)) {
          commandName.append(" ");
          ch = Character.toLowerCase(ch);
        }
        commandName.append(ch);
      }

      if (!commandLowerCase.contentEquals(commandName)
          && !commandLowerCase.startsWith(commandName + " ")) {
        if (ann == null) {
          continue;
        }

        var aliases = ann.aliases();
        if (aliases == null || aliases.length == 0) {
          continue;
        }

        for (var alias : aliases) {
          if (iCommand.startsWith(alias.split(" ")[0])) {
            return m;
          }
        }
      } else {
        return m;
      }
    }

    error(String.format("\n!Unrecognized command: '%s'", iCommand));
    return null;
  }

  protected void syntaxError(String iCommand, Method m) {
    error(
        String.format(
            "\n"
                + "!Wrong syntax. If you're running in batch mode make sure all commands are"
                + " delimited by semicolon (;) or a linefeed (\\n"
                + "). Expected: \n\r\n\r"
                + "%s",
            formatCommandSpecs(iCommand, m)));
  }

  protected String formatCommandSpecs(final String iCommand, final Method m) {
    final var buffer = new StringBuilder();
    final var signature = new StringBuilder();

    signature.append(iCommand);

    String paramName = null;
    String paramDescription = null;
    var paramOptional = false;

    buffer.append("\n\nWHERE:\n\n");

    for (var annotations : m.getParameterAnnotations()) {
      for (var ann : annotations) {
        if (ann instanceof ConsoleParameter) {
          paramName =
              ((ConsoleParameter) ann).name();
          paramDescription =
              ((ConsoleParameter) ann)
                  .description();
          paramOptional =
              ((ConsoleParameter) ann).optional();
          break;
        }
      }

      if (paramName == null) {
        paramName = "?";
      }

      if (paramOptional) {
        signature.append(" [<" + paramName + ">]");
      } else {
        signature.append(" <" + paramName + ">");
      }

      buffer.append("* ");
      buffer.append(String.format("%-18s", paramName));

      if (paramDescription != null) {
        buffer.append(paramDescription);
      }

      if (paramOptional) {
        buffer.append(" (optional)");
      }

      buffer.append("\n");
    }

    signature.append(buffer);

    return signature.toString();
  }

  /**
   * Returns a map of all console method and the object they can be called on.
   *
   * @return Map&lt;Method,Object&gt;
   */
  protected Map<Method, Object> getConsoleMethods() {
    if (methods != null) {
      return methods;
    }

    // search for declared command collections
    final var ite =
        ServiceLoader.load(ConsoleCommandCollection.class).iterator();
    final Collection<Object> candidates = new ArrayList<Object>();
    candidates.add(this);
    while (ite.hasNext()) {
      try {
        // make a copy and set it's context
        final var cc = ite.next().getClass().newInstance();
        cc.setContext(this);
        candidates.add(cc);
      } catch (InstantiationException ex) {
        Logger.getLogger(ConsoleApplication.class.getName()).log(Level.WARNING, ex.getMessage());
      } catch (IllegalAccessException ex) {
        Logger.getLogger(ConsoleApplication.class.getName()).log(Level.WARNING, ex.getMessage());
      }
    }

    methods =
        new TreeMap<Method, Object>(
            new Comparator<Method>() {
              @Override
              public int compare(Method o1, Method o2) {
                final var ann1 = o1.getAnnotation(ConsoleCommand.class);
                final var ann2 = o2.getAnnotation(ConsoleCommand.class);

                if (ann1 != null && ann2 != null) {
                  if (ann1.priority() != ann2.priority())
                  // PRIORITY WINS
                  {
                    return ann1.priority() - ann2.priority();
                  }
                }

                var res = o1.getName().compareTo(o2.getName());
                if (res == 0) {
                  res = o1.toString().compareTo(o2.toString());
                }
                return res;
              }
            });

    for (final var candidate : candidates) {
      final var classMethods = candidate.getClass().getMethods();

      for (var m : classMethods) {
        if (Modifier.isAbstract(m.getModifiers())
            || Modifier.isStatic(m.getModifiers())
            || !Modifier.isPublic(m.getModifiers())) {
          continue;
        }
        if (m.getReturnType() != Void.TYPE) {
          continue;
        }
        methods.put(m, candidate);
      }
    }
    return methods;
  }

  protected Map<String, Object> addCommand(Map<String, Object> commandsTree, String commandLine) {
    return commandsTree;
  }

  @ConsoleCommand(
      splitInWords = false,
      description =
          "Receives help on available commands or a specific one. Use 'help -online <cmd>' to fetch"
              + " online documentation")
  public void help(
      @ConsoleParameter(name = "command", description = "Command to receive help")
      String iCommand) {
    if (iCommand == null || iCommand.trim().isEmpty()) {
      // GENERIC HELP
      message("\nAVAILABLE COMMANDS:\n");

      for (var m : getConsoleMethods().keySet()) {
        var annotation = m.getAnnotation(ConsoleCommand.class);

        if (annotation == null) {
          continue;
        }

        message(String.format("* %-85s%s\n", getCorrectMethodName(m), annotation.description()));
      }
      message(String.format("* %-85s%s\n", getClearName("exit"), "Close the console"));
      return;
    }

    final var commandWords = StringParser.getWords(iCommand, wordSeparator);

    var onlineMode = commandWords.length > 1 && commandWords[0].equalsIgnoreCase("-online");
    if (onlineMode) {
      iCommand = iCommand.substring("-online".length() + 1);
    }

    final var m = getMethod(iCommand);
    if (m != null) {
      final var ann = m.getAnnotation(ConsoleCommand.class);

      message("\nCOMMAND: " + iCommand + "\n\n");
      if (ann != null) {
        // FETCH ONLINE CONTENT
        if (onlineMode && !ann.onlineHelp().isEmpty()) {
          // try {
          final var text = getOnlineHelp(ONLINE_HELP_URL + ann.onlineHelp() + ONLINE_HELP_EXT);
          if (text != null && !text.isEmpty()) {
            message(text);
            // ONLINE FETCHING SUCCEED: RETURN
            return;
          }
          // } catch (Exception e) {
          // }
          error(
              "!CANNOT FETCH ONLINE DOCUMENTATION, CHECK IF COMPUTER IS CONNECTED TO THE"
                  + " INTERNET.");
          return;
        }

        message(ann.description() + "." + "\r\n\r\nSYNTAX: ");

        // IN ANY CASE DISPLAY INFORMATION BY READING ANNOTATIONS
        message(formatCommandSpecs(iCommand, m));

      } else {
        message("No description available");
      }
    }
  }

  protected static String getCommandLine(String[] arguments) {
    var command = new StringBuilder(512);
    var first = true;
    for (var argument : arguments) {
      if (isInteractiveConfigParam(argument)) {
        continue;
      }
      if (!first) {
        command.append(" ");
      }

      command.append(argument);
      first = false;
    }
    return command.toString();
  }

  protected void onBefore() {
  }

  protected void onAfter() {
  }

  protected void onException(final Throwable throwable) {
    throwable.printStackTrace(err);
  }

  public void setOutput(PrintStream iOut) {
    this.out = iOut;
  }

  protected String getOnlineHelp(final String urlToRead) {
    URL url;
    HttpURLConnection conn;
    BufferedReader rd;
    String line;
    var result = new StringBuilder();
    try {
      url = new URL(urlToRead);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
      while ((line = rd.readLine()) != null) {
        if (line.startsWith("```")) {
          continue;
        } else if (line.startsWith("# ")) {
          continue;
        }

        if (result.length() > 0) {
          result.append("\n");
        }

        result.append(line);
      }
      rd.close();
    } catch (Exception ignore) {
    }
    return result.toString();
  }

  protected enum RESULT {
    OK,
    ERROR,
    EXIT,
    NOT_EXECUTED
  }
}
