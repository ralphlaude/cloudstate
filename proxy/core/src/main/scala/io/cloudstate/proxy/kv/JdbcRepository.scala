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
import io.cloudstate.proxy.kv.KeyValueStore.Key

import scala.concurrent.{ExecutionContext, Future}

class JdbcRepository(val kvStore: KeyValueStore[Key, ByteString])(implicit ec: ExecutionContext) extends Repository {

  def get(key: Key): Future[Option[ScalaPbAny]] =
    kvStore
      .get(key)
      .map {
        case Some(value) => Some(ScalaPbAny.parseFrom(value.toByteBuffer.array()))
        case None => None
      }

  def update(key: Key, entity: ScalaPbAny): Future[Unit] =
    kvStore.set(key, ByteString(entity.toByteArray))

  def delete(key: Key): Future[Unit] = kvStore.delete(key)
}
