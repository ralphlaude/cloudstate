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

package io.cloudstate.proxy.kvstore

import akka.util.ByteString
import io.cloudstate.proxy.kvstore.KeyValueStore.Key
import com.google.protobuf.any.{Any => ScalaPbAny}

/*
 * TODOs
 * This is the API interface i came up with.
 * Here the only concern is about the write side and the read side will come later.
 *
 * The configuration of a specific kv-store will look like this in the application.conf:
 *
 * crud {
 *  enabled = true
 *  store = "inmen" // could have jdbc, postgres or cassandra
 *  database = {
 *    database-name: "crud-persistentid"
 *    table {
 *      table-name = "crud-persistentid"
 *      columns-name: {
 *        persistentId = "persistentId"
 *        entityId = "entityId"
 *        state = "state"
 *      }
 *    }
 *  }
 * }
 *
 */

object KeyValueStore {

  case class Key(persistentId: String, entityId: String)

}

trait KeyValueStore[K, V] {

  def get(key: K): V

  def set(key: K, value: V): Unit

  def delete(key: K): Unit

}

class InMemoryKeyValueStore extends KeyValueStore[Key, ScalaPbAny] {

  private[this] final var store = Map.empty[Key, ByteString]

  override def get(key: Key): ScalaPbAny = {
    val v = store(key)
    ScalaPbAny.parseFrom(v.asByteBuffer.array())
  }

  override def set(key: Key, value: ScalaPbAny): Unit =
    store += key -> ByteString(value.toByteArray)

  override def delete(key: Key): Unit = store -= key
}

class JDBCKeyValueStore extends KeyValueStore[Key, ScalaPbAny] {

  override def get(key: Key): ScalaPbAny = ???

  override def set(key: Key, value: ScalaPbAny): Unit = ???

  override def delete(key: Key): Unit = ???
}

class CassandraKeyValueStore extends KeyValueStore[Key, ScalaPbAny] {

  override def get(key: Key): ScalaPbAny = ???

  override def set(key: Key, value: ScalaPbAny): Unit = ???

  override def delete(key: Key): Unit = ???
}
