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

package io.cloudstate.javasupport.impl

import java.util
import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.protobuf.Descriptors
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, StatelessFactory, StatelessHandler}
import io.cloudstate.protocol.entity.{Failure, Reply}
import io.cloudstate.protocol.function.FunctionReply.Response
import io.cloudstate.protocol.function._

import scala.collection.immutable
import scala.concurrent.Future

class StatelessFunctionService(val factory: StatelessFactory, override val descriptor: Descriptors.ServiceDescriptor)
    extends StatefulService {

  override def entityType: String = StatelessFunction.name

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }
}

// FIXME Implement support for this
class StatelessFunctionImpl(_system: ActorSystem,
                            _services: Map[String, StatelessFunctionService],
                            rootContext: Context)(implicit mat: Materializer)
    extends StatelessFunction {

  private[this] final val log = Logging.getLogger(_system, this.getClass)

  private final val system = _system
  private implicit val ec = _system.dispatcher
  private final val services = _services.iterator.toMap

  private final var handlerInit = false
  private final var handler: StatelessHandler = _

  // FIXME FunctionCommand should have id?
  // FIXME deal with forward?
  // FIXME how to deal with commandId? Do we need the commandId for the CommandContextImpl
  override def handleUnary(command: FunctionCommand): Future[FunctionReply] = {
    log.info(s"handleUnary called command - $command")
    mayBeInit(command.serviceName)
    command.payload match {
      case Some(p) =>
        Future.unit.map { _ =>
          val context = new CommandContextImpl(command.name)
          val reply = handler.handleCommand(ScalaPbAny.toJavaProto(p), context) // FIXME deal with exception?
          context.deactivate()
          val scalaReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
          FunctionReply(Response.Reply(Reply(scalaReply)), context.sideEffects)
        }

      case None =>
        Future.successful(
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        )
    }
  }

  override def handleStreamedIn(commandSource: Source[FunctionCommand, NotUsed]): Future[FunctionReply] = {
    log.info(s"handleStreamedIn called")
    commandSource
      .prefixAndTail(1)
      .flatMapConcat {
        case (firstCommands, source) =>
          mayBeInit(firstCommands.head.serviceName)
          Source(firstCommands).concat(source)
      }
      .runWith(Sink.seq)
      .map { commands =>
        //validation - check name and service name for all commands
        //validation - check payload for all commands
        if (commands.exists(_.payload.isEmpty)) {
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        } else {
          import scala.collection.JavaConverters._
          val javaAnyCommands = commands.map(command => ScalaPbAny.toJavaProto(command.payload.get))
          val context = new CommandContextImpl(commands.head.name)
          val reply = handler.handleStreamInCommand(javaAnyCommands.asJava, context)
          context.deactivate()
          val scalaReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
          FunctionReply(Response.Reply(Reply(scalaReply)), context.sideEffects)
        }
      }
  }

  override def handleStreamedOut(command: FunctionCommand): Source[FunctionReply, NotUsed] = {
    log.info(s"handleStreamedOut called")
    mayBeInit(command.serviceName)
    command.payload match {
      case Some(value) =>
        val context = new CommandContextImpl(command.name)
        val replies = handler.handleStreamOutCommand(ScalaPbAny.toJavaProto(value), context) // FIXME deal with exception?
        context.deactivate()
        Source.fromIterator { () =>
          import scala.collection.JavaConverters._
          replies.asScala
            .map(r => FunctionReply(Response.Reply(Reply(Some(ScalaPbAny.fromJavaProto(r)))), context.sideEffects))
            .iterator
        }

      case None =>
        Source.single(
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        )
    }
  }

  override def handleStreamed(commandSource: Source[FunctionCommand, NotUsed]): Source[FunctionReply, NotUsed] = {
    log.info(s"handleStreamed called")
    commandSource
      .prefixAndTail(1)
      .flatMapConcat {
        case (firstCommands, source) =>
          mayBeInit(firstCommands.head.serviceName)
          Source(firstCommands).concat(source)
      }
      .map { command =>
        //validation - check name and service name for all commands
        //validation - check payload for all commands
        command.payload match {
          case Some(p) =>
            val context = new CommandContextImpl(command.name)
            val reply = handler.handleCommand(ScalaPbAny.toJavaProto(p), context) // FIXME deal with exception?
            context.deactivate()
            val scalaReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
            FunctionReply(Response.Reply(Reply(scalaReply)), context.sideEffects)

          case None =>
            FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        }
      }
  }

  private def mayBeInit(serviceName: String): Unit =
    if (!handlerInit) {
      val service = services.getOrElse(serviceName, throw new RuntimeException(s"Service not found: $serviceName"))
      handler = service.factory.create(StatelessContextImpl)
      handlerInit = true
    }

  private class StatelessHandlerRunner() {
    import com.google.protobuf.{Any => JavaPbAny}

    private var handlerInit = false
    private var handler: StatelessHandler = _

    def handleCommand(command: FunctionCommand, context: CommandContext): Optional[JavaPbAny] = {
      mayBeInit(command.serviceName)
      command.payload match {
        case Some(p) => handler.handleCommand(ScalaPbAny.toJavaProto(p), context)
        case None => Optional.empty[JavaPbAny]()
      }

    }

    def handleStreamInCommand(commands: immutable.Seq[FunctionCommand],
                              context: CommandContext): Optional[JavaPbAny] = {
      import scala.collection.JavaConverters._

      mayBeInit(commands.head.serviceName)

      val javaAnyCommands = commands.map(command => ScalaPbAny.toJavaProto(command.payload.get))
      handler.handleStreamInCommand(javaAnyCommands.asJava, context)
    }

    def handleStreamOutCommand(command: FunctionCommand, context: CommandContext): util.List[JavaPbAny] = {
      mayBeInit(command.serviceName)

      handler.handleStreamOutCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
    }

    private def mayBeInit(serviceName: String): Unit =
      if (!handlerInit) {
        val service = services.getOrElse(serviceName, throw new RuntimeException(s"Service not found: $serviceName"))
        handler = service.factory.create(StatelessContextImpl)
        handlerInit = true
      }
  }

  trait AbstractContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  case object StatelessContextImpl extends Context with AbstractContext

  class CommandContextImpl(override val commandName: String)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {
    override def commandId: Long =
      Long.MinValue // FIXME how to deal with commandId? Do we need the commandId for the CommandContextImpl?
  }
}
