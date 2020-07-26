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

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.entity.{ClientAction, Command, Failure}
import io.cloudstate.proxy.ConcurrencyEnforcer
import io.cloudstate.proxy.ConcurrencyEnforcer.ConcurrencyEnforcerSettings
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.duration._

object AbstractCrudEntitySpec {

  def config: Config =
    ConfigFactory
      .parseString("""
      | # use in-memory journal for testing
      | cloudstate.proxy.journal-enabled = true
      | akka.persistence {
      |   journal.plugin = "akka.persistence.journal.inmem"
      |   snapshot-store.plugin = inmem-snapshot-store
      | }
      | inmem-snapshot-store.class = "io.cloudstate.proxy.crud.InMemSnapshotStore"
      """.stripMargin)

  final val ServiceName = "some.ServiceName"
  final val UserFunctionName = "user-function-name"

  // Some useful anys
  final val command = ProtoAny("command", ByteString.copyFromUtf8("foo"))
  final val updatePayload = ProtoAny("payload", ByteString.copyFromUtf8("update-payload"))
  final val updatePayload1 = ProtoAny("payload1", ByteString.copyFromUtf8("update1-payload"))
  final val updatePayload2 = ProtoAny("payload2", ByteString.copyFromUtf8("update2-payload"))
  final val element1 = ProtoAny("element1", ByteString.copyFromUtf8("1"))
  final val element2 = ProtoAny("element2", ByteString.copyFromUtf8("2"))
  final val element3 = ProtoAny("element3", ByteString.copyFromUtf8("3"))

}

abstract class AbstractCrudEntitySpec
    extends TestKit(ActorSystem("CrudTest", AbstractCrudEntitySpec.config))
    with WordSpecLike
    with Matchers
    with Inside
    with Eventually
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ImplicitSender
    with OptionValues {

  import AbstractCrudEntitySpec._

  // These are set and read in the entities
  @volatile protected var userFunction: TestProbe = _
  @volatile protected var concurrencyEnforcer: ActorRef = _
  protected val statsCollector: TestProbe = TestProbe()

  protected var entity: ActorRef = _

  // Incremented for each test by before() callback
  private var idSeq = 0

  protected type T <: CrudInitState
  protected type S

  protected def initial: T
  protected def extractState(state: CrudInitState): S

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(200, Millis))

  protected def entityId: String = "entity" + idSeq.toString

  protected def sendAndExpectCommand(name: String, payload: ProtoAny): Long = {
    entity ! EntityCommand(entityId, name, Some(payload))
    expectCommand(name, payload)
  }

  protected def expectCommand(name: String, payload: ProtoAny): Long =
    inside(userFunction.expectMsgType[CrudStreamIn].message) {
      case CrudStreamIn.Message.Command(Command(eid, cid, n, p, s, _, _)) =>
        eid should ===(entityId)
        n should ===(name)
        p shouldBe Some(payload)
        s shouldBe false
        cid
    }

  protected def sendAndExpectReply(commandId: Long, action: Option[CrudAction.Action] = None): UserFunctionReply = {
    val reply = doSendAndExpectReply(commandId, action)
    reply.clientAction shouldBe None
    reply
  }

  protected def doSendAndExpectReply(commandId: Long, action: Option[CrudAction.Action] = None): UserFunctionReply = {
    sendReply(commandId, action)
    expectMsgType[UserFunctionReply]
  }

  protected def sendReply(commandId: Long, action: Option[CrudAction.Action] = None) =
    entity ! CrudStreamOut(
      CrudStreamOut.Message.Reply(
        CrudReply(
          commandId = commandId,
          sideEffects = Nil,
          clientAction = None,
          crudAction = action.map(a => CrudAction(a))
        )
      )
    )

  protected def sendAndExpectFailure(commandId: Long, description: String): UserFunctionReply = {
    sendFailure(commandId, description)
    val reply = expectMsgType[UserFunctionReply]
    inside(reply.clientAction) {
      case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
        failure should ===(Failure(0, description))
    }
    reply
  }

  protected def sendAndExpectFailure(commandId: Long): UserFunctionReply = {
    sendReply(commandId)
    val reply = expectMsgType[UserFunctionReply]
    inside(reply.clientAction) {
      case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
        failure should ===(Failure(0, "Incorrect command id in reply, expecting 1 but got -1"))
    }
    reply
  }

  protected def sendFailure(commandId: Long, description: String) =
    entity ! CrudStreamOut(
      CrudStreamOut.Message.Failure(
        Failure(
          commandId = commandId,
          description = description
        )
      )
    )

  protected def createAndExpectInit(): Option[S] = {
    userFunction = TestProbe()
    concurrencyEnforcer =
      system.actorOf(ConcurrencyEnforcer.props(ConcurrencyEnforcerSettings(1, 10.second, 5.second), statsCollector.ref))
    entity = system.actorOf(
      CrudEntity.props(
        CrudEntity.Configuration(ServiceName, UserFunctionName, Timeout(10.minutes), 100),
        entityId,
        userFunction.ref,
        concurrencyEnforcer,
        statsCollector.ref
      )
    )

    val init = userFunction.expectMsgType[CrudStreamIn]
    inside(init.message) {
      case CrudStreamIn.Message.Init(CrudInit(serviceName, eid, state, _)) =>
        serviceName should ===(ServiceName)
        eid should ===(entityId)
        state should ===(Some(CrudInitState()))
        state.map(s => extractState(s))
    }
  }

  final protected def createEntity(): Unit = {
    userFunction = TestProbe()
    concurrencyEnforcer =
      system.actorOf(ConcurrencyEnforcer.props(ConcurrencyEnforcerSettings(1, 10.second, 5.second), statsCollector.ref))
    entity = system.actorOf(
      CrudEntity.props(
        CrudEntity.Configuration(ServiceName, UserFunctionName, 30.seconds, 100),
        entityId,
        userFunction.ref,
        concurrencyEnforcer,
        statsCollector.ref
      )
    )
  }

  final protected def expectInitState(initState: Option[CrudInitState]): Unit =
    inside(userFunction.expectMsgType[CrudStreamIn].message) {
      case CrudStreamIn.Message.Init(CrudInit(serviceName, eid, state, _)) =>
        serviceName should ===(ServiceName)
        eid should ===(entityId)
        state should ===(initState)
    }

  protected def sendCommand(dest: ActorRef, name: String, payload: ProtoAny): Unit =
    dest ! EntityCommand(entityId, name, Some(payload))

  protected def expectUserFunctionReceivedCommand(name: String, payload: ProtoAny): Long =
    inside(userFunction.expectMsgType[CrudStreamIn].message) {
      case CrudStreamIn.Message.Command(Command(eid, cid, n, p, s, _, _)) =>
        eid should ===(entityId)
        n should ===(name)
        p shouldBe Some(payload)
        s shouldBe false
        cid
    }

  final protected def sendReplyy(dest: ActorRef, commandId: Long, action: Option[CrudAction.Action] = None) =
    dest ! CrudStreamOut(
      CrudStreamOut.Message.Reply(
        CrudReply(
          commandId = commandId,
          sideEffects = Nil,
          clientAction = None,
          crudAction = action.map(a => CrudAction(a))
        )
      )
    )

  final protected def expectReply(commandId: Long, action: Option[CrudAction.Action] = None): Unit = {
    val reply = expectMsgType[UserFunctionReply]
    reply.clientAction shouldBe None
  }

  before {
    idSeq += 1
  }

  after {
    if (entity != null) {
      concurrencyEnforcer ! PoisonPill
      concurrencyEnforcer = null
      userFunction.testActor ! PoisonPill
      userFunction = null
      entity ! PoisonPill
      entity = null
    }
  }

  override protected def beforeAll(): Unit = Unit
  //cluster.join(cluster.selfAddress)

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), 10.seconds)
    shutdown()
  }

}
