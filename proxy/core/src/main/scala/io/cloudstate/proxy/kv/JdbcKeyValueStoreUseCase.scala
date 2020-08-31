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

import akka.actor.{ActorRef, ActorSystem}
import akka.util.ByteString
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString => PbByteString}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.proxy.crud.CrudEntity
import io.cloudstate.proxy.kv.KeyValueStore.Key

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object JdbcKeyValueStoreUseCase {

  private implicit val system = ActorSystem("JdbcKeyValueStoreUseCase")
  private implicit val ec = system.dispatcher

  private val postgresConfig: Config =
    ConfigFactory
      .parseString("""
      | # represents the configuration for the JDBC configuration using Slick in the application.conf file.
      | # use POSTGRES for testing
      |
      | crud-database {
      |   profile = "slick.jdbc.PostgresProfile$"
      |   connectionPool = disabled
      |   driver = "org.postgresql.Driver"
      |   url = "jdbc:postgresql://localhost:5432/cloudstate"
      |   user = "cloudstate"
      |   password = "cloudstate"
      | }
      |
      | # represents the configuration for the CRUD database schema using JDBC.
      |
      | jdbc-crud-state-store {
      |   tables {
      |     state-store {
      |      tableName = "crud_state_entity"
      |      schemaName = "public"
      |      columnNames {
      |        persistentEntityId = "persistent_entity_id"
      |        entityId = "entity_id"
      |        state = "state"
      |      }
      |     }
      |   }
      | }
      |
      """.stripMargin)

  def main(args: Array[String]): Unit =
    //useJdbcKeyValueStore()
    //useJdbcRepositoryWithJdbcKeyValueStore()
    useJdbcRepositoryWithInMemoryValueStore()

  private def useJdbcRepositoryWithInMemoryValueStore(): Unit = {
    println(s"starting JdbcRepository with InMemoryKeyValueStore")

    val store = new InMemoryKeyValueStore
    val repository = new JdbcRepository(store)
    val state = ScalaPbAny("crud-state-type-url", PbByteString.copyFromUtf8("state"))
    val key = Key("persistentEntityId", "entityId")

    Await.result(repository.update(key, state), 1.seconds)
    Await.result(
      repository
        .get(key)
        .map {
          case Some(value) =>
            println(s"store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
    Await.result(repository.delete(key), 1.seconds)
  }

  private def useJdbcRepositoryWithJdbcKeyValueStore(): Unit = {
    println(s"starting JdbcRepository with JdbcKeyValueStore")

    val config = JdbcKeyValueStoreUseCase.postgresConfig
    val slickDatabase = JDBCSlickDatabase(config)
    val crudStateTableConfiguration = new CrudStateTableConfiguration(
      config.getConfig("jdbc-crud-state-store")
    )
    val crudStateQueries = new JdbcCrudStateQueries(slickDatabase.profile, crudStateTableConfiguration)
    val store = new JdbcKeyValueStore(slickDatabase, crudStateQueries)
    val repository = new JdbcRepository(store)
    val state = ScalaPbAny("crud-state-type-url", PbByteString.copyFromUtf8("state"))
    val key = Key("persistentEntityId", "entityId")

    println(s"stateAsString - ${ByteString(state.toByteArray).utf8String}")
    Await.result(repository.update(key, state), 1.seconds)
    Await.result(
      repository
        .get(key)
        .map {
          case Some(value) =>
            println(s"store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
    Await.result(store.delete(key), 1.seconds)
  }

  private def useJdbcKeyValueStore(): Unit = {
    println(s"starting JDBCKeyValueStore")

    val config = JdbcKeyValueStoreUseCase.postgresConfig
    val slickDatabase = JDBCSlickDatabase(config)
    val crudStateTableConfiguration = new CrudStateTableConfiguration(
      config.getConfig("jdbc-crud-state-store")
    )
    val crudStateQueries = new JdbcCrudStateQueries(slickDatabase.profile, crudStateTableConfiguration)
    val store = new JdbcKeyValueStore(slickDatabase, crudStateQueries)
    val state = ScalaPbAny("crud-state-type-url", PbByteString.copyFromUtf8("state"))
    val key = Key("persistentEntityId", "entityId")

    val stateAsString = ByteString(state.toByteArray)
    println(s"stateAsString - $stateAsString")
    Await.result(store.set(key, stateAsString), 1.seconds)
    Await.result(
      store
        .get(key)
        .map {
          case Some(value) =>
            println(s"store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
    //Await.result(store.delete(key), 1.seconds)
  }
}

// The CrudEntity could be passed the Repository like this.
// Of course the protocol of the CrudEntity should be adapted.
final class JdbcCrudEntity(configuration: CrudEntity.Configuration,
                           entityId: String,
                           relay: ActorRef,
                           concurrencyEnforcer: ActorRef,
                           statsCollector: ActorRef,
                           repository: Repository)
