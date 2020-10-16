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

package io.cloudstate.proxy.crud

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.grpc.GrpcClientSettings
import akka.testkit.TestEvent.Mute
import akka.testkit.{EventFilter, TestActorRef}
import akka.util.ByteString
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.protocol.crud.CrudClient
import com.google.protobuf.{ByteString => PbByteString}
import io.cloudstate.proxy.crud.store.{JdbcRepositoryImpl, JdbcStore}
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.AbstractTelemetrySpec
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.crud.CrudMessages

import scala.concurrent.Future
import scala.concurrent.duration._

class DatabaseExceptionHandlingSpec extends AbstractTelemetrySpec {

  private val testkitConfig = """
    | include "test-in-memory"
    | akka {
    |   loglevel = ERROR
    |   loggers = ["akka.testkit.TestEventListener"]
    |   remote.artery.canonical.port = 0
    |   remote.artery.bind.port = ""
    | }
    """
  private val service = TestService()
  private val entityConfiguration = CrudEntity.Configuration(
    serviceName = "service",
    userFunctionName = "test",
    passivationTimeout = 30.seconds,
    sendQueueSize = 100
  )

  "The CrudEntity" should {

    "crash entity on init when loading state failures" in withTestKit(testkitConfig) { testKit =>
      import testKit._
      import system.dispatcher

      silentDeadLettersAndUnhandledMessages

      val client = CrudClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))
      val repository = new JdbcRepositoryImpl(TestJdbcStore.storeWithGetFailure())
      val entity = watch(system.actorOf(CrudEntitySupervisor.props(client, entityConfiguration, repository), "entity"))

      val connection = service.crud.expectConnection()
      connection.expectClosed()
    }

    "crash entity on update state failures" in withTestKit(testkitConfig) { testKit =>
      import testKit._
      import CrudMessages._
      import system.dispatcher

      silentDeadLettersAndUnhandledMessages

      val forwardReply = forwardReplyActor(testActor)

      val client = CrudClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))
      val repository = new JdbcRepositoryImpl(TestJdbcStore.storeWithUpdateFailure())
      val entity = watch(system.actorOf(CrudEntitySupervisor.props(client, entityConfiguration, repository), "entity"))
      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      val connection = service.crud.expectConnection()
      connection.expect(init("service", "entity"))
      entity.tell(EntityCommand(entityId = "test", name = "command1", emptyCommand), forwardReply)
      connection.expect(command(1, "entity", "command1"))

      val state = ScalaPbAny("state", PbByteString.copyFromUtf8("state"))
      connection.send(reply(1, EmptyJavaMessage, update(state)))
      expectMsg(UserFunctionReply(clientActionFailure("Unexpected CRUD entity failure")))
      connection.expectClosed()
    }

    "crash entity on delete state failures" in withTestKit(testkitConfig) { testKit =>
      import testKit._
      import CrudMessages._
      import system.dispatcher

      silentDeadLettersAndUnhandledMessages

      val forwardReply = forwardReplyActor(testActor)

      val client = CrudClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))
      val repository = new JdbcRepositoryImpl(TestJdbcStore.storeWithDeleteFailure())
      val entity = watch(system.actorOf(CrudEntitySupervisor.props(client, entityConfiguration, repository), "entity"))
      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      val connection = service.crud.expectConnection()
      connection.expect(init("service", "entity"))

      entity.tell(EntityCommand(entityId = "test", name = "command1", emptyCommand), forwardReply)
      connection.expect(command(1, "entity", "command1"))
      connection.send(reply(1, EmptyJavaMessage, update(ScalaPbAny("state", PbByteString.copyFromUtf8("state")))))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(EmptyJavaMessage))))

      entity.tell(EntityCommand(entityId = "test", name = "command2", emptyCommand), forwardReply)
      connection.expect(command(2, "entity", "command2"))
      connection.send(reply(2, EmptyJavaMessage, delete()))
      expectMsg(UserFunctionReply(clientActionFailure("Unexpected CRUD entity failure")))

      connection.expectClosed()
    }
  }

  private final class TestJdbcStore(status: String) extends JdbcStore[Key, ByteString] {
    import TestJdbcStore.JdbcStoreStatus._

    private var store = Map.empty[Key, ByteString]

    override def get(key: Key): Future[Option[ByteString]] =
      status match {
        case `getFailure` => Future.failed(new RuntimeException("Database GET access failed because of boom!"))
        case _ => Future.successful(store.get(key))
      }
    override def update(key: Key, value: ByteString): Future[Unit] =
      status match {
        case `updateFailure` => Future.failed(new RuntimeException("Database Update access failed because of boom!"))
        case _ =>
          store += key -> value
          Future.unit
      }

    override def delete(key: Key): Future[Unit] =
      status match {
        case `deleteFailure` => Future.failed(new RuntimeException("Database Delete access failed because of boom!"))
        case _ =>
          store -= key
          Future.unit
      }
  }

  private object TestJdbcStore {

    private object JdbcStoreStatus {
      val normal = "normal"
      val getFailure = "GetFailure"
      val updateFailure = "UpdateFailure"
      val deleteFailure = "DeleteFailure"
    }

    def storeWithGetFailure(): JdbcStore[Key, ByteString] = new TestJdbcStore(JdbcStoreStatus.getFailure)

    def storeWithUpdateFailure(): JdbcStore[Key, ByteString] = new TestJdbcStore(JdbcStoreStatus.updateFailure)

    def storeWithDeleteFailure(): JdbcStore[Key, ByteString] = new TestJdbcStore(JdbcStoreStatus.deleteFailure)
  }

  private def silentDeadLettersAndUnhandledMessages(implicit system: ActorSystem): Unit = {
    // silence any dead letters or unhandled messages during shutdown (when using test event listener)
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))
  }

  private def forwardReplyActor(actor: ActorRef)(implicit system: ActorSystem) =
    TestActorRef(new Actor {
      def receive: Receive = {
        case message =>
          actor forward message
      }
    })

}