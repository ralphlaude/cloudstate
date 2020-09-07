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

import akka.actor.Status.Success
import akka.util.Timeout
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud.{
  CrudAction,
  CrudDelete,
  CrudInit,
  CrudInitState,
  CrudReply,
  CrudStreamIn,
  CrudStreamOut,
  CrudUpdate
}
import io.cloudstate.protocol.entity.{ClientAction, Command, Failure}
import io.cloudstate.proxy.crud.CrudEntity.{Stop, StreamClosed, StreamFailed}
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.duration._

class CrudEntitySpec extends AbstractCrudEntitySpec {

  import AbstractCrudEntitySpec._

  override protected type T = CrudInitState
  override protected type S = CrudInitState

  override protected def initial: CrudInitState = CrudInitState()

  override protected def extractState(state: CrudInitState): CrudInitState = state

  "The CrudEntity" should {

    //TODO sendAndExpectCommand("cmd", command) multiples times does not work????

    "handle StreamFailed message new" in {
      createAndExpectInit()

      sendAndExpectCommand("cmd", command)
      //sendAndExpectCommand("cmd", command)
      entity ! StreamFailed(new RuntimeException("test stream failed"))

      userFunction.expectMsgType[Success]
      userFunction.expectMsgType[CrudStreamIn]
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Unexpected CRUD entity termination"))
      }
      expectNoMessage(200.millis)
    }

    "sendAndExpectCommand multiple times" in {
      createAndExpectInit()

      /*
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid)
      val cidd = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cidd)
       */

      entity ! EntityCommand(entityId, "cmd", Some(command))
      entity ! EntityCommand(entityId, "cmd", Some(command))
      entity ! CrudStreamOut(CrudStreamOut.Message.Empty)

      userFunction.expectMsgType[CrudStreamIn]
      userFunction.expectMsgType[Success]
      expectMsgType[UserFunctionReply]
      expectMsgType[UserFunctionReply]
      /*
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Empty or unknown message from entity output stream"))
      }
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Entity terminated"))
      }
       */

      expectNoMessage(200.millis)
    }

    "be initialised successfully" in {
      createAndExpectInit() shouldBe Some(CrudInitState())
    }

    "be initialised successfully new" in {
      createEntity()
      expectInitState(Some(CrudInitState()))
    }

    "handle read command and reply new" in {
      createEntity()
      expectInitState(Some(CrudInitState()))

      sendCommand(entity, "cmd", command)
      val commandId = expectUserFunctionReceivedCommand("cmd", command)
      println(s"commandId - $commandId")

      sendReplyy(entity, commandId)
      expectReply(commandId)
      userFunction.expectNoMessage(200.millis)
    }

    "handle read command and reply" in {
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid)
      userFunction.expectNoMessage(200.millis)
    }

    "handle update command and reply" in {
      createEntity()
      expectInitState(Some(CrudInitState()))

      sendCommand(entity, "cmd", command)
      val commandId = expectUserFunctionReceivedCommand("cmd", command)

      sendReplyy(entity, commandId, Some(Update(CrudUpdate(Some(updatePayload)))))
      expectReply(commandId, Some(Update(CrudUpdate(Some(updatePayload)))))
      userFunction.expectNoMessage(200.millis)

      /*
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, Some(Update(CrudUpdate(Some(updatePayload)))))
      userFunction.expectNoMessage(200.millis)
     */
    }

    "handle multiple update commands and reply how?" in {
      createAndExpectInit()

      watch(entity)

      val cid1 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, Some(Update(CrudUpdate(Some(updatePayload)))))

      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid2, Some(Update(CrudUpdate(Some(updatePayload1)))))

      entity ! Stop

      userFunction.expectMsgType[Success]
      expectTerminated(entity)

      val reactivatedEntity = system.actorOf(
        CrudEntity.props(
          CrudEntity.Configuration(ServiceName, UserFunctionName, Timeout(10.minutes), 100),
          entityId,
          userFunction.ref,
          concurrencyEnforcer,
          statsCollector.ref,
          null
        )
      )
      watch(reactivatedEntity)

      inside(userFunction.expectMsgType[CrudStreamIn].message) {
        case CrudStreamIn.Message.Init(CrudInit(serviceName, eid, state, _)) =>
          serviceName should ===(ServiceName)
          eid should ===(entityId)
          state should ===(Some(CrudInitState(Some(updatePayload1), 2)))
          state.map(s => extractState(s))
      }

      reactivatedEntity ! EntityCommand(entityId, "cmd", Some(command))
      val cid3 = inside(userFunction.expectMsgType[CrudStreamIn].message) {
        case CrudStreamIn.Message.Command(Command(eid, cid, n, p, s, _, _)) =>
          eid should ===(entityId)
          n should ===("cmd")
          p shouldBe Some(command)
          s shouldBe false
          cid
      }

      reactivatedEntity ! CrudStreamOut(
        CrudStreamOut.Message.Reply(
          CrudReply(
            commandId = cid3,
            sideEffects = Nil,
            clientAction = None,
            crudAction = Some(CrudAction(Update(CrudUpdate(Some(updatePayload2)))))
          )
        )
      )

      val reply = expectMsgType[UserFunctionReply]
      reply.clientAction should ===(None)
      reply.sideEffects should ===(Nil)

      expectNoMessage(200.millis)
    }

    "handle delete command and reply" in {
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, Some(Delete(CrudDelete())))
      userFunction.expectNoMessage(200.millis)
    }

    "handle failure reply" in {
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectFailure(cid, "description")
      userFunction.expectNoMessage(200.millis)
    }

    "stash command when another command is being executed how?" in {}

    "crash when there is no command being executed for the reply" in {
      createAndExpectInit()

      entity ! CrudStreamOut(
        CrudStreamOut.Message.Reply(
          CrudReply(
            commandId = -1,
            sideEffects = Nil,
            clientAction = None,
            crudAction = None
          )
        )
      )

      userFunction.expectMsgType[Success]
    }

    "crash when the command being executed does not have the same command id as the one of the reply" in {
      createAndExpectInit()

      sendAndExpectCommand("cmd", command)
      entity ! CrudStreamOut(
        CrudStreamOut.Message.Reply(
          CrudReply(
            commandId = -1,
            sideEffects = Nil,
            clientAction = None,
            crudAction = None
          )
        )
      )

      userFunction.expectMsgType[Success]

      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Incorrect command id in reply, expecting 1 but got -1"))
      }
    }

    "crash for failure reply with command id 0" in {
      createAndExpectInit()
      entity ! CrudStreamOut(
        CrudStreamOut.Message.Failure(Failure(description = "description"))
      )
      userFunction.expectMsgType[Success]
    }

    "crash for failure reply when there is no command being executed" in {
      createAndExpectInit()
      entity ! CrudStreamOut(
        CrudStreamOut.Message.Failure(Failure(1, "description"))
      )
      userFunction.expectMsgType[Success]
    }

    "crash for failure reply when the command being executed does not have the same command id as the one of the reply" in {
      createAndExpectInit()
      sendAndExpectCommand("cmd", command)
      //entity ! EntityCommand(entityId, "cmd", Some(command))
      entity ! CrudStreamOut(
        CrudStreamOut.Message.Failure(Failure(-1, "description"))
      )

      userFunction.expectMsgType[Success]
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Incorrect command id in failure, expecting 1 but got -1"))
      }
    }

    "crash when output message is empty and a command is not being executed" in {
      createAndExpectInit()

      entity ! CrudStreamOut(CrudStreamOut.Message.Empty)

      userFunction.expectMsgType[Success]
    }

    "crash when output message is empty and a command is being executed" in {
      createAndExpectInit()

      sendAndExpectCommand("cmd", command)
      //sendAndExpectCommand("cmd", command)
      entity ! CrudStreamOut(CrudStreamOut.Message.Empty)

      userFunction.expectMsgType[Success]

      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Empty or unknown message from entity output stream"))
      }
    }

    "stop when received Stop" in {
      createAndExpectInit()
      watch(entity)

      entity ! Stop

      userFunction.expectMsgType[Success]
      expectTerminated(entity)
    }

    "stop when received StreamClosed" in {
      createAndExpectInit()
      watch(entity)

      sendAndExpectCommand("cmd", command)
      //sendAndExpectCommand("cmd", command)
      entity ! StreamClosed

      userFunction.expectMsgType[Success]

      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Unexpected CRUD entity termination"))
      }

      expectTerminated(entity)
    }

    "handle StreamFailed message" in {
      createAndExpectInit()

      sendAndExpectCommand("cmd", command)
      //sendAndExpectCommand("cmd", command)
      entity ! StreamFailed(new RuntimeException("test stream failed"))

      userFunction.expectMsgType[Success]
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Unexpected CRUD entity termination"))
      }
    }
  }
}
