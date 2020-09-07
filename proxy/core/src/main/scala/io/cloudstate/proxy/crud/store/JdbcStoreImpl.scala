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

import akka.util.ByteString
import io.cloudstate.proxy.crud.store.JdbcCrudStateTable.CrudStateRow
import io.cloudstate.proxy.crud.store.JdbcStore.Key

import scala.concurrent.{ExecutionContext, Future}

final class JdbcStoreImpl(slickDatabase: JdbcSlickDatabase, queries: JdbcCrudStateQueries)(
    implicit ec: ExecutionContext
) extends JdbcStore[Key, ByteString] {

  import slickDatabase.profile.api._

  private val db = slickDatabase.database

  override def get(key: Key): Future[Option[ByteString]] =
    db.run(queries.selectByKey(key).result.headOption.map(mayBeState => mayBeState.map(s => ByteString(s.state))))

  override def update(key: Key, value: ByteString): Future[Unit] =
    db.run(queries.insertOrUpdate(CrudStateRow(key, value.utf8String))).map(_ => ())

  override def delete(key: Key): Future[Unit] =
    db.run(queries.deleteByKey(key)).map(_ => ())

}
