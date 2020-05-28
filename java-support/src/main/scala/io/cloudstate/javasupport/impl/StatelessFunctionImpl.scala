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

import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, StatelessFactory, StatelessHandler}
import io.cloudstate.protocol.entity.{Failure, Reply}
import io.cloudstate.protocol.function.FunctionReply.Response
import io.cloudstate.protocol.function._

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

class StatelessFunctionImpl(_system: ActorSystem,
                            _services: Map[String, StatelessFunctionService],
                            rootContext: Context)(implicit mat: Materializer)
    extends StatelessFunction {

  private[this] final val log = Logging.getLogger(_system, this.getClass)

  private final val system = _system
  private implicit val ec = _system.dispatcher
  private final val services = _services.iterator.toMap

  private final val handlerInit = new AtomicBoolean(false)
  private final var handler: StatelessHandler = _

  // FIXME FunctionCommand should have id?
  override def handleUnary(command: FunctionCommand): Future[FunctionReply] = {
    log.info(s"handleUnary called command - $command")
    mayBeInit(command.serviceName)

    Future.unit
      .map { _ =>
        val context = new CommandContextImpl(command.name)
        val reply = try {
          handler.handleCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
        } catch {
          // context.fail(...) was called
          case FailInvoked => Optional.empty[JavaPbAny]()
        } finally {
          context.deactivate() // Very important to deactivate the context!
        }
        val ufReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
        FunctionReply(Response.Reply(Reply(ufReply)), context.sideEffects)
      }
      .recover {
        case err =>
          system.log.error(
            err,
            s"Unexpected error during the invocation of the unary call ${command.name} for the service ${command.serviceName}."
          )
          FunctionReply(Response.Failure(Failure(description = err.getMessage)))
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
        import scala.collection.JavaConverters._
        val javaAnyCommands = commands.map(command => ScalaPbAny.toJavaProto(command.payload.get))
        val context = new CommandContextImpl(commands.head.name)
        val reply = try {
          handler.handleStreamInCommand(javaAnyCommands.asJava, context)
        } catch {
          // context.fail(...) was called
          case FailInvoked => Optional.empty[JavaPbAny]()
        } finally {
          context.deactivate() // Very important to deactivate the context!
        }
        val ufReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
        FunctionReply(Response.Reply(Reply(ufReply)), context.sideEffects)
      }
    // deal with exception
  }

  override def handleStreamedOut(command: FunctionCommand): Source[FunctionReply, NotUsed] = {
    log.info(s"handleStreamedOut called")
    mayBeInit(command.serviceName)
    val context = new CommandContextImpl(command.name)
    val replies = try {
      handler.handleStreamOutCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
    } catch {
      // context.fail(...) was called
      case FailInvoked => java.util.List.of[JavaPbAny]()
    } finally {
      context.deactivate() // Very important to deactivate the context!
    }
    Source.fromIterator { () =>
      import scala.collection.JavaConverters._
      replies.asScala
        .map(r => FunctionReply(Response.Reply(Reply(Some(ScalaPbAny.fromJavaProto(r)))), context.sideEffects))
        .iterator
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
        val context = new CommandContextImpl(command.name)
        val reply = try {
          handler.handleCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
        } catch {
          // context.fail(...) was called
          case FailInvoked => Optional.empty[JavaPbAny]()
        } finally {
          context.deactivate() // Very important to deactivate the context!
        }
        val ufReply = if (reply.isEmpty) None else Some(ScalaPbAny.fromJavaProto(reply.get()))
        FunctionReply(Response.Reply(Reply(ufReply)), context.sideEffects)
      }
  }

  private def mayBeInit(serviceName: String): Unit =
    if (handlerInit.compareAndSet(false, true)) {
      val service = services.getOrElse(serviceName, throw new RuntimeException(s"Service not found: $serviceName"))
      handler = service.factory.create(StatelessContextImpl)
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
