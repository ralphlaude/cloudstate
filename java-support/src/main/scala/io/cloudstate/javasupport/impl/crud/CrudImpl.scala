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

package io.cloudstate.javasupport.impl.crud

import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.crud._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.crud.CrudStreamIn.Message.{Command => InCommand, Empty => InEmpty, Init => InInit}
import io.cloudstate.protocol.crud.CrudStreamOut.Message.Failure

import scala.compat.java8.OptionConverters._

final class CrudStatefulService(val factory: CrudEntityFactory,
                                override val descriptor: Descriptors.ServiceDescriptor,
                                val anySupport: AnySupport,
                                override val persistenceId: String,
                                val snapshotEvery: Int)
    extends StatefulService {

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  override final val entityType = io.cloudstate.protocol.crud.Crud.name

  final def withSnapshotEvery(snapshotEvery: Int): CrudStatefulService =
    if (snapshotEvery != this.snapshotEvery)
      new CrudStatefulService(this.factory, this.descriptor, this.anySupport, this.persistenceId, snapshotEvery)
    else
      this
}

final class CrudImpl(_system: ActorSystem,
                     _services: Map[String, CrudStatefulService],
                     rootContext: Context,
                     configuration: Configuration)
    extends io.cloudstate.protocol.crud.Crud {

  private final val system = _system
  private final implicit val ec = system.dispatcher
  private final val services = _services.iterator
    .map({
      case (name, crudss) =>
        // FIXME overlay configuration provided by _system
        (name, if (crudss.snapshotEvery == 0) crudss.withSnapshotEvery(configuration.snapshotEvery) else crudss)
    })
    .toMap

  override def handle(
      in: akka.stream.scaladsl.Source[CrudStreamIn, akka.NotUsed]
  ): akka.stream.scaladsl.Source[CrudStreamOut, akka.NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(CrudStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          Source.single(
            CrudStreamOut(
              Failure(
                io.cloudstate.protocol.entity.Failure(
                  0,
                  "Cloudstate protocol failure: expected init message"
                )
              )
            )
          )
      }
      .recover {
        case e =>
          CrudStreamOut(
            Failure(
              io.cloudstate.protocol.entity.Failure(
                0,
                s"Cloudstate protocol failure: ${e.getMessage}"
              )
            )
          )
      }

  // This follows the same pattern as for event sourced and CRDT entities. Each CRUD entity is a single value
  // that gets sharded across the Akka cluster, and when active, is stored in memory by the user function. When
  // a command is received for a particular entity, if there's no active gRPC handle stream for that entity, a new
  // stream is started, the value for the entity is looked up from the database, and then an init message is sent to
  // the user function containing the value (or no value if not present in the db). Then the command is sent, and
  // the user function can reply, optionally sending CRUD action, which can either update or delete the value
  // from the database. After a period of inactivity, the entity will be shut down, just like for event sourced entities.
  private def runEntity(init: CrudInit): Flow[CrudStreamIn, CrudStreamOut, NotUsed] = {

    // snapshotting is not needed for now
    // the init contains the payload from the entity --> the state of the UF can be initialized
    // handler.handleState(command.payload, new StateContext(...))
    // StateContext contains the entityId
    //
    // when command is received --> the command on the UF is called
    // handler.handleCommand(command.payload, new CommandContext(...))
    //
    // when crud action update is called by the UF
    // 1- create a new event and update the events list
    // 2- call handler.handleState(action.payload, new StateContext(...))
    // 3- send reply CrudStreamOut.Message with CrudAction(Update(action.payload)) to entity
    //
    // when crud action delete is called by the UF
    // 1- create a new event and update the events list
    // 2- call handler.handleState(action.payload empty, new StateContext(...))
    // 3- send reply CrudStreamOut.Message with CrudAction(Delete) to entity
    //
    // what to do after the entity is deleted?
    // what is the difference between new entity with empty payload and deleted entity with empty payload?
    // what to do when retrieving deleted entity?
    //

    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler = service.factory.create(new CrudContextImpl(init.entityId))
    val crudEntityId = init.entityId

    init.value.map { payload =>
      val encoded = service.anySupport.encodeScala(payload)
      handler.handleState(ScalaPbAny.toJavaProto(encoded), new StateContextImpl(crudEntityId))
    }

    Flow[CrudStreamIn]
      .map { in =>
        in.message match {
          case InCommand(command) =>
            if (crudEntityId != command.entityId) {
              CrudStreamOut(
                CrudStreamOut.Message.Failure(
                  io.cloudstate.protocol.entity.Failure(
                    command.id,
                    s"""Cloudstate protocol failure:
                       |Receiving entity - $crudEntityId is not the intended recipient
                       |of command with id - ${command.id} and name - ${command.name}""".stripMargin.replaceAll("\n",
                                                                                                                " ")
                  )
                )
              )
            } else {
              val cmd = ScalaPbAny.toJavaProto(command.payload.get)
              val context = new CommandContextImpl(crudEntityId,
                                                   command.name,
                                                   command.id,
                                                   service.anySupport,
                                                   handler,
                                                   service.snapshotEvery)
              val reply = try {
                handler.handleCommand(cmd, context)
              } catch {
                case FailInvoked => Optional.empty[JavaPbAny]
              } finally {
                context.deactivate() // Very important!
              }

              val clientAction = context.createClientAction(reply, allowNoReply = false)
              if (!context.hasError) {
                CrudStreamOut(
                  CrudStreamOut.Message.Reply(
                    CrudReply(
                      command.id,
                      clientAction,
                      context.sideEffects,
                      context.crudAction()
                    )
                  )
                )
              } else {
                CrudStreamOut(
                  CrudStreamOut.Message.Reply(
                    CrudReply(
                      commandId = command.id,
                      clientAction = clientAction,
                      crudAction = context.crudAction()
                    )
                  )
                )
              }
            }

          case InInit(_) =>
            throw new IllegalStateException("CRUD Entity already inited")

          case InEmpty =>
            throw new IllegalStateException("CRUD Entity received empty/unknown message")
        }
      }
  }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  private final class CommandContextImpl(override val entityId: String,
                                         override val commandName: String,
                                         override val commandId: Long,
                                         val anySupport: AnySupport,
                                         val handler: CrudEntityHandler,
                                         val snapshotEvery: Int)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    private var mayBeAction: Option[CrudAction] = None

    override def update(event: AnyRef): Unit = {
      checkActive()

      val encoded = anySupport.encodeScala(event)
      handler.handleState(ScalaPbAny.toJavaProto(encoded), new StateContextImpl(entityId))
      mayBeAction = Some(CrudAction(Update(CrudUpdate(Some(encoded)))))
    }

    override def delete(): Unit = {
      checkActive()

      //TODO it should be with optional
      //handler.handleState(Option.empty[JavaPbAny].asJava, new StateContextImpl(entityId))
      handler.handleState(ScalaPbAny.toJavaProto(ScalaPbAny.defaultInstance), new StateContextImpl(entityId))
      mayBeAction = Some(CrudAction(Delete(CrudDelete())))
    }

    def crudAction(): Option[CrudAction] = mayBeAction
  }

  private final class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext

  private final class StateContextImpl(override final val entityId: String) extends StateContext with AbstractContext
}
