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
package com.jetbrains.youtrackdb.internal.core.serialization;

import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import java.io.Serializable;

/**
 * Base interface of serialization.
 */
public interface SerializableStream extends Serializable {

  /**
   * Marshalls the object. Transforms the current object in byte[] form to being stored or
   * transferred over the network.
   *
   * @return The byte array representation of the object
   * @throws SerializationException if the marshalling does not succeed
   * @see #fromStream(byte[])
   */
  byte[] toStream() throws SerializationException;

  /**
   * Unmarshalls the object. Fills the current object with the values contained in the byte array
   * representation restoring a previous state. Usually byte[] comes from the storage or network.
   *
   * @param iStream byte array representation of the object
   * @return The Object instance itself giving a "fluent interface". Useful to call multiple methods
   * in chain.
   * @throws SerializationException if the unmarshalling does not succeed
   */
  SerializableStream fromStream(byte[] iStream) throws SerializationException;
}
