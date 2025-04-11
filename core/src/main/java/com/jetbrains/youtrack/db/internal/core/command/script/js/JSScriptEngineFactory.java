package com.jetbrains.youtrack.db.internal.core.command.script.js;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.script.ScriptEngineFactory;

public class JSScriptEngineFactory {

  private static final List<String> WRAPPED_LANGUAGES = Arrays.asList("javascript", "ecmascript");

  public static ScriptEngineFactory maybeWrap(ScriptEngineFactory engineFactory) {
    final var factoryClassName = engineFactory.getClass().getName();

    if (WRAPPED_LANGUAGES.contains(engineFactory.getLanguageName().toLowerCase(Locale.ENGLISH))
        && (factoryClassName.equalsIgnoreCase(
        "jdk.nashorn.api.scripting.NashornScriptEngineFactory")
        || factoryClassName.equalsIgnoreCase(
        "com.oracle.truffle.js.scriptengine.GraalJSEngineFactory"))) {
      return new SecuredScriptEngineFactory(engineFactory);
    }
    return engineFactory;
  }
}
