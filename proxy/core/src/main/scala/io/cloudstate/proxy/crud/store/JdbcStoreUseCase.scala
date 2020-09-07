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

package io.cloudstate.proxy.crud.store

import akka.actor.ActorSystem
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import com.google.protobuf.{ByteString => PbByteString}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object JdbcStoreUseCase {

  private implicit val system = ActorSystem("JdbcKeyValueStoreUseCase")
  private implicit val ec = system.dispatcher

  private val postgresConfig: Config =
    ConfigFactory
      .parseString(
        """
          | # use POSTGRES for testing
          |
          | # TODOs: a dedicated table for each persistent entity!!!
          |
          | # Enable the CRUD the functionality by setting it to true
          | crud-enabled = true
          |
          | # Configures the CRUD functionality
          | crud {
          |
          |  # This property indicate the type of CRUD store to be used.
          |  # Valid options are: "jdbc", "in-memory"
          |  # "in-memory" means the data are persisted in memory.
          |  # "jdbc" means the data are persisted in the native JDBC database. This is the recommended option for production setup
          |  store-type = "jdbc"
          |
          |  # This property indicates which profile must be used by Slick.
          |  jdbc.database.slick {
          |    profile = "slick.jdbc.PostgresProfile$"
          |    connectionPool = disabled
          |    #connectionPool = "HikariCP" # should be used for prod!!!
          |    driver = "org.postgresql.Driver"
          |    url = "jdbc:postgresql://localhost:5432/cloudstate"
          |    user = "cloudstate"
          |    password = "cloudstate"
          |
          |    # may be more properties here!!! help needed!!!
          |  }
          |
          |  # This property indicates the CRUD table in use.
          |  jdbc-state-store {
          |   tables {
          |     state {
          |      tableName = "crud_state_entity"
          |      schemaName = "public"
          |      columnNames {
          |        persistentId = "persistent_id"
          |        entityId = "entity_id"
          |        state = "state"
          |      }
          |     }
          |   }
          |  }
          | }
          |
      """.stripMargin
      )

  def main(args: Array[String]): Unit =
    //useJdbcKeyValueStore()
    useJdbcRepositoryWithKeyValueStoreFactory()

  //useJdbcRepositoryWithJdbcKeyValueStore()
  //useJdbcRepositoryWithInMemoryValueStore()

  private def useJdbcRepositoryWithInMemoryValueStore(): Unit = {
    println(s"starting JdbcRepository with InMemoryKeyValueStore")

    val store = new JdbcInMemoryStore
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

  private def useJdbcRepositoryWithKeyValueStoreFactory(): Unit = {
    println(s"starting JdbcRepository with KeyValueStoreFactory and JdbcKeyValueStore")

    val config = JdbcStoreUseCase.postgresConfig
    val store = new JdbcStoreFactory(config).buildCrudStore()
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
            println(s"after set store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"after set store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
    Await.result(store.delete(key), 1.seconds)
    Await.result(
      repository
        .get(key)
        .map {
          case Some(value) =>
            println(s"after delete store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"after delete store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
  }

  private def useJdbcRepositoryWithJdbcKeyValueStore(): Unit = {
    println(s"starting JdbcRepository with JdbcKeyValueStore")

    val config = JdbcStoreUseCase.postgresConfig
    val slickDatabase = JdbcSlickDatabase(config)
    val crudStateTableConfiguration = new JdbcCrudStateTableConfiguration(
      config.getConfig("crud.jdbc-state-store")
    )
    val crudStateQueries = new JdbcCrudStateQueries(slickDatabase.profile, crudStateTableConfiguration)
    val store = new JdbcStoreImpl(slickDatabase, crudStateQueries)
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
            println(s"after set store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"after set store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
    Await.result(store.delete(key), 1.seconds)
    Await.result(
      repository
        .get(key)
        .map {
          case Some(value) =>
            println(s"after delete store.get key - $key returns value - $value")
            Some(value)

          case None =>
            println(s"after delete store.get key - $key returns value - None")
            None
        },
      1.seconds
    )
  }

  private def useJdbcKeyValueStore(): Unit = {
    println(s"starting JDBCKeyValueStore")

    val config = JdbcStoreUseCase.postgresConfig
    val slickDatabase = JdbcSlickDatabase(config)
    val crudStateTableConfiguration = new JdbcCrudStateTableConfiguration(
      config.getConfig("crud.jdbc-state-store")
    )
    val crudStateQueries = new JdbcCrudStateQueries(slickDatabase.profile, crudStateTableConfiguration)
    val store = new JdbcStoreImpl(slickDatabase, crudStateQueries)
    val state = ScalaPbAny("crud-state-type-url", PbByteString.copyFromUtf8("state"))
    val key = Key("persistentEntityId", "entityId")

    val stateAsString = ByteString(state.toByteArray)
    println(s"stateAsString - $stateAsString")
    Await.result(store.update(key, stateAsString), 1.seconds)
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
