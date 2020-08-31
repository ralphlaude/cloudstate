/*
 * Copyright 2019 Lightbend Inc.
 *
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

package io.cloudstate.proxy.kv

import akka.util.ByteString
import io.cloudstate.proxy.kv.KeyValueStore.Key

import scala.concurrent.Future

final class InMemoryKeyValueStore extends KeyValueStore[Key, ByteString] {

  private final var store = Map.empty[Key, ByteString]

  override def get(key: Key): Future[Option[ByteString]] =
    Future.successful(store.get(key))

  override def set(key: Key, value: ByteString): Future[Unit] = {
    store += key -> value
    Future.unit
  }

  override def delete(key: Key): Future[Unit] = {
    store -= key
    Future.unit
  }
}
