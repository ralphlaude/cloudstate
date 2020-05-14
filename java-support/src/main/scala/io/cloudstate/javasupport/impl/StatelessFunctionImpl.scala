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
import com.google.protobuf.Descriptors
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport._
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
                            rootContext: Context)
    extends StatelessFunction {
  // where to get the serviceName from?

  private final val system = _system
  private implicit val ec = _system.dispatcher
  private final val services = _services.iterator.toMap

  private val service =
    services.getOrElse("init.serviceName", throw new RuntimeException(s"Service not found: init.serviceName"))
  private val handler: StatelessHandler = service.factory.create(new ContextImpl())

  override def handleUnary(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] =
    in.payload match {
      case Some(value) =>
        Future {
          val reply = handler.handleCommand(ScalaPbAny.toJavaProto(value), null)
          val scalaReply = ScalaPbAny.fromJavaProto(reply.get()) // FIXME reply empty?
          FunctionReply(Response.Reply(Reply(Some(scalaReply))))
        } // FIXME deal with exception empty?

      case None =>
        Future.successful(
          FunctionReply(Response.Failure(Failure(description = "payload cannot be empty")))
        )
    }

  override def handleStreamedIn(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] = ???
  override def handleStreamedOut(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = ???
  override def handleStreamed(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = ???

  trait AbstractContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  class ContextImpl() extends AbstractContext
}
