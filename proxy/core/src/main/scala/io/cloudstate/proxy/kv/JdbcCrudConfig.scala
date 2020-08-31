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

import com.typesafe.config.Config
import slick.basic.DatabaseConfig
import slick.jdbc.{JdbcBackend, JdbcProfile}
import slick.jdbc.JdbcBackend.Database

class CrudStateTableColumnNames(config: Config) {
  private val cfg = config.getConfig("tables.state-store.columnNames")

  val persistentEntityId: String = cfg.getString("persistentEntityId")
  val entityId: String = cfg.getString("entityId")
  val state: String = cfg.getString("state")

  override def toString: String = s"CrudStateTableColumnNames($persistentEntityId,$entityId,$state)"
}

class CrudStateTableConfiguration(config: Config) {
  private val cfg = config.getConfig("tables.state-store")

  val tableName: String = cfg.getString("tableName")
  val schemaName: Option[String] = Option(cfg.getString("schemaName")).map(_.trim)
  val columnNames: CrudStateTableColumnNames = new CrudStateTableColumnNames(config)

  override def toString: String = s"CrudStateTableConfiguration($tableName,$schemaName,$columnNames)"
}

object JDBCSlickDatabase {

  def apply(config: Config): JDBCSlickDatabase = {
    val database: JdbcBackend.Database = Database.forConfig(
      "crud-database",
      config
    )
    val profile: JdbcProfile = DatabaseConfig
      .forConfig[JdbcProfile](
        "crud-database",
        config
      )
      .profile

    JDBCSlickDatabase(database, profile)
  }

}

case class JDBCSlickDatabase(database: JdbcBackend.Database, profile: JdbcProfile)
