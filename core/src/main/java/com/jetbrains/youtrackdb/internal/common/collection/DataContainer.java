/*
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
 */
package com.jetbrains.youtrackdb.internal.common.collection;

import com.jetbrains.youtrackdb.internal.common.util.Sizeable;

/**
 * If class implements given interface it means that this class represents collection which is not
 * part of Java Collections Framework.
 *
 * @param <T> Collection item type.
 */
public interface DataContainer<T> extends Iterable<T>, Sizeable {

  void add(T value);

  boolean remove(T value);
}
