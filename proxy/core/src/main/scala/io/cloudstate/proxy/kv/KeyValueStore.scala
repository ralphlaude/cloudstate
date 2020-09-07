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

import scala.concurrent.Future

/*
 *
 * TODOs
 * 1- define an interface for a Key-Value store
 * 2- implement the Key-Value store interface for In-Memory
 * 3- implement the Key-Value store interface for Postgres
 * 4- implement the Key-Value store interface for MySQL
 * 5- implement the Key-Value store interface for a native Key-Value database like Cassandra
 *
 * TODOs implementation
 * 1- load the right Key-Value store implementation depending on the configuration
 * 2- implement a repository which wrap the right Key-Value store implementation
 * 3- define the schema for application.conf for the JDBC Key-Value store
 * 4- define the schema for application.conf for the Cassandra Key-Value store
 *
 * The Key-Value store implementation for In-Memory is here InMemoryKeyValueStore.
 * The class JdbcKeyValueStoreUseCase shows how to use the JdcRepository and the JdbcKeyValueStore.
 *
 * The configuration of a specific kv-store will look like this in the application.conf:
 *
 */

object KeyValueStore {

  case class Key(persistentEntityId: String, entityId: String)

}

trait KeyValueStore[K, V] {

  def get(key: K): Future[Option[V]]

  def set(key: K, value: V): Future[Unit]

  def delete(key: K): Future[Unit]

}
