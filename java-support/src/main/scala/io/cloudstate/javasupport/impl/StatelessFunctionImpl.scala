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

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.protobuf.Descriptors
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, StatelessFactory, StatelessHandler}
import io.cloudstate.protocol.entity.{Failure, Reply}
import io.cloudstate.protocol.function.FunctionReply.Response
import io.cloudstate.protocol.function._

import scala.concurrent.Future

class StatelessFunctionService(val factory: StatelessFactory,
                               override val descriptor: Descriptors.ServiceDescriptor,
                               val anySupport: AnySupport,
                               override val persistenceId: String)
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

  private final val system = _system
  private implicit val ec = _system.dispatcher
  private final val services = _services.iterator.toMap

  // FIXME how to access the serviceName for creating the service? The serviceName is in io.cloudstate.protocol.function.FunctionCommand
  private val service =
    services.getOrElse("init.serviceName", throw new RuntimeException(s"Service not found: init.serviceName"))
  private val handler: StatelessHandler = service.factory.create(StatelessContextImpl)

  override def handleUnary(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] =
    in.payload match {
      case Some(value) =>
        // FIXME deal with exception?
        // FIXME how to deal with commandId? Do we need the commandId for the CommandContextImpl
        Future {
          val context = new CommandContextImpl(in.name, 0)
          val reply = handler.handleCommand(ScalaPbAny.toJavaProto(value), context)
          val scalaReply = ScalaPbAny.fromJavaProto(reply.get()) // FIXME reply empty?
          FunctionReply(Response.Reply(Reply(Some(scalaReply))), context.sideEffects)
        }

      case None =>
        Future.successful(
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        )
    }

  override def handleStreamedIn(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] =
    in.runWith(Sink.seq)
      .map { commands =>
        val functionReplies = commands.map { command =>
          command.payload match {
            case Some(value) =>
              val context = new CommandContextImpl(command.name, 0)
              val reply = handler.handleCommand(ScalaPbAny.toJavaProto(value), context)
              val scalaReply = ScalaPbAny.fromJavaProto(reply.get()) // FIXME reply empty?
              FunctionReply(Response.Reply(Reply(Some(scalaReply))), context.sideEffects)

            case None =>
              FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
          }
        }
        // FIXME how to aggregate Seq[FunctionReply] to one FunctionReply
        functionReplies.head
      }

  override def handleStreamedOut(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = {
    val executed = in.payload match {
      case Some(value) =>
        // FIXME deal with exception?
        // FIXME how to deal with commandId? Do we need the commandId for the CommandContextImpl
        Future {
          val context = new CommandContextImpl(in.name, 0)
          val reply = handler.handleCommand(ScalaPbAny.toJavaProto(value), context)
          val scalaReply = ScalaPbAny.fromJavaProto(reply.get()) // FIXME reply empty?
          FunctionReply(Response.Reply(Reply(Some(scalaReply))), context.sideEffects)
        }

      case None =>
        Future.successful(
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        )
    }

    // FIXME how to create a Source[FunctionReply] from one FunctionReply
    Source.fromFuture(executed)
  }

  override def handleStreamed(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = ???

  trait AbstractContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  case object StatelessContextImpl extends Context with AbstractContext

  class CommandContextImpl(override val commandName: String, override val commandId: Long)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext
}
