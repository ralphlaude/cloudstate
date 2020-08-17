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
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString => PbByteString}
import io.cloudstate.proxy.kv.KeyValueStore.Key

import scala.concurrent.Future

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

  def get(key: K): Future[Option[V]]

  def set(key: K, value: V): Future[Unit]

  def delete(key: K): Future[Unit]

}

class InMemoryKeyValueStore extends KeyValueStore[Key, ByteString] {

  private[this] final var store = Map.empty[Key, ByteString]

  override def get(key: Key): Future[Option[ByteString]] = {
    Future.successful(store.get(key))
  }

  override def set(key: Key, value: ByteString): Future[Unit] = {
    store += key -> value
    Future.unit
  }

  override def delete(key: Key): Future[Unit] = {
    store -= key
    Future.unit
  }
}

object InMemoryKeyValueStore {

  // This is just a use case sample
  def main(args: Array[String]): Unit = {
    val inMemKV = new InMemoryKeyValueStore
    val state = ScalaPbAny("crud-state", PbByteString.copyFromUtf8("state"))
    val key = Key("persistentEntityId", "entityId")

    val setKey: Future[Unit] = inMemKV.set(key, ByteString(state.toByteArray))
    val maybeState: Future[Option[ByteString]] = inMemKV.get(key)
    val deleteKey: Future[Unit] = inMemKV.delete(key)
  }

}
