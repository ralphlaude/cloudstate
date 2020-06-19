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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.javadsl.{Source => JavaSource}
import akka.stream.scaladsl.{Sink, Source}
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, StatelessFactory, StatelessHandler}
import io.cloudstate.protocol.entity.{Failure, Forward, Reply}
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

  private final val system = _system
  private implicit val ec = _system.dispatcher
  private final val services = _services.iterator.toMap

  /*
   * Decision logs.
   *
   * For handleStreamedIn there are two options how to deal with the incoming Akka Source of commands.
   * The first option is run the Akka Source and pass those commands to the User function.
   * The second option is to pass the Akka Source to the User Function and it runs it. I found this very cumbersome
   * on the one hand for the usability because the User Function needs to know how to execute Akka Streams.
   * On the other hand for executing Akka Streams Akka Materializer should be passed around in the CommandContext,
   * this way the proxy is somehow exposed to the User Function.
   * I take the second option this the proxy does the heavy lifting for the Akka Streams and provides the User Function
   * with the right inputs.
   *
   * For all this the io.cloudstate.javasupport.impl.ReflectionHelper was extended to deal with types like
   * java.util.List (method handleStreamedIn) and akka.stream.javadsl.Source (method handleStreamedOut)
   * which are the input and output type of a Stateless User Function. Some invokeXXX methods are added to deal with that.
   *
   *
   * Command Id is still missing in FunctionCommand. This lead to the fact that it is not possible right now
   * to create a FunctionReply.Response.Failure. FunctionReply.Response.Failure needs a command id.
   * The proxy cannot map a Failure to originating FunctionCommand. This is already known as problem.
   * The more general question is how the proxy should generate command id for Stateless FunctionCommand.
   * I see a transient and persistent way for creating command id. The transient way can use an in memory randomizer
   * for that. The persistent way can use an Event Sourced Entity to deal with.
   *
   *
   */

  override def handleUnary(command: FunctionCommand): Future[FunctionReply] = {
    val handler = createHandler(command)

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
        val response = context.createClientAction(reply)
        FunctionReply(response, context.sideEffects)
      }
  }

  override def handleStreamedIn(commandSource: Source[FunctionCommand, NotUsed]): Future[FunctionReply] =
    commandSource
      .runWith(Sink.seq)
      .map { commands =>
        import scala.collection.JavaConverters._
        val handler = createHandler(commands.head)
        val javaAnyCommands = commands.map(command => ScalaPbAny.toJavaProto(command.payload.get))
        val context = new CommandContextImpl(commands.head.name)
        val reply = try {
          handler.handleStreamedInCommand(javaAnyCommands.asJava, context)
        } catch {
          // context.fail(...) was called
          case FailInvoked => Optional.empty[JavaPbAny]
        } finally {
          context.deactivate() // Very important to deactivate the context!
        }

        val clientAction = context.createClientAction(reply)
        FunctionReply(clientAction, context.sideEffects)
      }

  override def handleStreamedOut(command: FunctionCommand): Source[FunctionReply, NotUsed] = {
    val handler = createHandler(command)
    val context = new CommandContextImpl(command.name)
    val replies = try {
      handler.handleStreamedOutCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
    } catch {
      // context.fail(...) was called
      case FailInvoked => JavaSource.empty[JavaPbAny]()
    } finally {
      context.deactivate() // Very important to deactivate the context!
    }

    replies.asScala
      .map { r =>
        val clientAction = context.createClientAction(Optional.of(r))
        FunctionReply(clientAction, context.sideEffects)
      }
  }

  override def handleStreamed(commandSource: Source[FunctionCommand, NotUsed]): Source[FunctionReply, NotUsed] =
    commandSource
      .map { command =>
        val handler = createHandler(command)
        val context = new CommandContextImpl(command.name)
        val reply = try {
          handler.handleCommand(ScalaPbAny.toJavaProto(command.payload.get), context)
        } catch {
          // context.fail(...) was called
          case FailInvoked => Optional.empty[JavaPbAny]()
        } finally {
          context.deactivate() // Very important to deactivate the context!
        }
        val clientAction = context.createClientAction(reply)
        FunctionReply(clientAction, context.sideEffects)
      }

  private def createHandler(command: FunctionCommand): StatelessHandler = {
    val service =
      services.getOrElse(command.serviceName, throw new RuntimeException(s"Service not found: ${command.serviceName}"))
    // fail fast by checking if there a method for the service with command.name
    service.resolvedMethods.getOrElse(
      command.name,
      throw new RuntimeException(s"Service call not found: ${command.serviceName}.${command.name}")
    )
    service.factory.create(StatelessContextImpl)
  }

  trait AbstractContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  case object StatelessContextImpl extends Context with AbstractContext

  class CommandContextImpl(override val commandName: String)
      extends CommandContext
      with AbstractContext
      with StatelessClientActionContext
      with AbstractEffectContext
      with ActivatableContext {
    override def commandId: Long =
      Long.MinValue // FIXME need for response failure. how to deal with commandId? Do we need the commandId for the CommandContextImpl?
  }

  trait StatelessClientActionContext extends ClientActionContext {
    self: ActivatableContext =>

    private final var error: Option[String] = None
    private final var forward: Option[Forward] = None

    def commandId: Long

    override final def fail(errorMessage: String): RuntimeException = {
      checkActive()
      if (error.isEmpty) {
        error = Some(errorMessage)
        throw FailInvoked
      } else throw new IllegalStateException("fail(…) already previously invoked!")
    }

    override final def forward(to: ServiceCall): Unit = {
      checkActive()
      if (forward.isDefined) {
        throw new IllegalStateException("This context has already forwarded.")
      }
      forward = Some(
        Forward(
          serviceName = to.ref().method().getService.getFullName,
          commandName = to.ref().method().getName,
          payload = Some(ScalaPbAny.fromJavaProto(to.message()))
        )
      )
    }

    final def hasError: Boolean = error.isDefined

    final def createClientAction(reply: Optional[JavaPbAny]): FunctionReply.Response =
      error match {
        case Some(msg) => Response.Failure(Failure(commandId, msg))
        case None =>
          if (reply.isPresent) {
            if (forward.isDefined) {
              throw new IllegalStateException(
                "Both a reply was returned, and a forward message was sent, choose one or the other."
              )
            }
            Response.Reply(Reply(Some(ScalaPbAny.fromJavaProto(reply.get()))))
          } else if (forward.isDefined) {
            Response.Forward(forward.get)
          } else {
            throw new RuntimeException("No reply or forward returned by command handler!")
          }
      }
  }
}
