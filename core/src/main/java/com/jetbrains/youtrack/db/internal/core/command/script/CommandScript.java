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
package com.jetbrains.youtrack.db.internal.core.command.script;

import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestTextAbstract;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.MemoryStream;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetwork;
import java.util.List;
import javax.annotation.Nonnull;
import javax.script.CompiledScript;

/**
 * Script command request implementation. It just stores the request and delegated the execution to
 * the configured CommandExecutor.
 *
 * @see CommandExecutorScript
 */
@SuppressWarnings("serial")
public class CommandScript extends CommandRequestTextAbstract {

  private String language;
  private CompiledScript compiledScript;

  public CommandScript() {
    useCache = true;
  }

  public CommandScript(final String iLanguage, final String iText) {
    super(iText);
    setLanguage(iLanguage);
    useCache = true;
  }

  public CommandScript(final String iText) {
    this("sql", iText);
  }

  @Override
  public List<EntityImpl> execute(@Nonnull DatabaseSessionInternal querySession, Object... iArgs) {
    return List.of();
  }

  @Override
  public boolean isIdempotent() {
    return false;
  }

  public String getLanguage() {
    return language;
  }

  public CommandScript setLanguage(String language) {
    if (language == null || language.isEmpty()) {
      throw new IllegalArgumentException("Not a valid script language specified: " + language);
    }
    this.language = language;
    return this;
  }

  @Override
  public CommandRequestText fromStream(DatabaseSessionEmbedded session, byte[] iStream,
      RecordSerializerNetwork serializer)
      throws SerializationException {
    final var buffer = new MemoryStream(iStream);
    language = buffer.getAsString();
    fromStream(session, buffer, serializer);
    return this;
  }

  @Override
  public byte[] toStream(DatabaseSessionInternal session, RecordSerializerNetwork serializer)
      throws SerializationException {
    final var buffer = new MemoryStream();
    buffer.setUtf8(language);
    return toStream(buffer, session);
  }

  public CompiledScript getCompiledScript() {
    return compiledScript;
  }

  public void setCompiledScript(CompiledScript script) {
    compiledScript = script;
  }

  @Override
  public String toString() {
    if (language != null) {
      return language + "." + text;
    }
    return "script." + text;
  }
}
