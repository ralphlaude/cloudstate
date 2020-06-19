package io.cloudstate.javasupport.impl.function

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.example.stateless.metricservice.Metricservice
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString, Any => JavaPbAny}
import akka.stream.javadsl.{Source => JavaSource}
import java.util.{List => JavaList, Collections => JavaCollections}
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, CommandHandler, Stateless, StatelessHandler}
import io.cloudstate.javasupport.impl.{AnnotationBasedStatelessSupport, AnySupport, ResolvedServiceMethod, ResolvedType}
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class AnnotationBasedStatelessSupportSpec
    extends TestKit(ActorSystem("AnnotationBasedStatelessSupportSpec"))
    with WordSpecLike
    with Matchers {

  private implicit val materializer: Materializer = ActorMaterializer()

  trait BaseContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = new ServiceCallFactory {
      override def lookup[T](serviceName: String, methodName: String, messageType: Class[T]): ServiceCallRef[T] =
        throw new NoSuchElementException
    }
  }

  object MockContext extends BaseContext

  class MockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "Collect"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  class StreamingInMockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "CollectStreamIn"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  class StreamingOutMockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "CollectStreamOut"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  object WrappedResolvedType extends ResolvedType[Wrapped] {
    override def typeClass: Class[Wrapped] = classOf[Wrapped]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/wrapped"
    override def parseFrom(bytes: ByteString): Wrapped = Wrapped(bytes.toStringUtf8)
    override def toByteString(value: Wrapped): ByteString = ByteString.copyFromUtf8(value.value)
  }

  object StringResolvedType extends ResolvedType[String] {
    override def typeClass: Class[String] = classOf[String]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/string"
    override def parseFrom(bytes: ByteString): String = bytes.toStringUtf8
    override def toByteString(value: String): ByteString = ByteString.copyFromUtf8(value)
  }

  case class Wrapped(value: String)
  val anySupport = new AnySupport(Array(Metricservice.getDescriptor), this.getClass.getClassLoader)
  val method = ResolvedServiceMethod(
    Metricservice.getDescriptor.findServiceByName("MetricService").findMethodByName("Collect"),
    StringResolvedType,
    WrappedResolvedType
  )

  val streamingInMethod = ResolvedServiceMethod(
    Metricservice.getDescriptor.findServiceByName("MetricService").findMethodByName("CollectStreamIn"),
    StringResolvedType,
    WrappedResolvedType
  )

  val streamingOutMethod = ResolvedServiceMethod(
    Metricservice.getDescriptor.findServiceByName("MetricService").findMethodByName("CollectStreamOut"),
    StringResolvedType,
    WrappedResolvedType
  )

  def create(behavior: AnyRef, methods: ResolvedServiceMethod[_, _]*): StatelessHandler =
    new AnnotationBasedStatelessSupport(behavior.getClass,
                                        anySupport,
                                        methods.map(m => m.descriptor.getName -> m).toMap,
                                        Some(_ => behavior)).create(MockContext)

  def create(clazz: Class[_]): StatelessHandler =
    new AnnotationBasedStatelessSupport(clazz, anySupport, Map.empty, None).create(MockContext)

  def command(str: String) =
    ScalaPbAny.toJavaProto(ScalaPbAny(StringResolvedType.typeUrl, StringResolvedType.toByteString(str)))

  def decodeWrapped(any: JavaPbAny): Wrapped = {
    any.getTypeUrl should ===(WrappedResolvedType.typeUrl)
    WrappedResolvedType.parseFrom(any.getValue)
  }

  "Stateless annotation support" should {
    "support entity construction" when {

      "there is a noarg constructor" in {
        create(classOf[NoArgConstructorTest])
      }

      "fail if the constructor contains an unsupported parameter" in {
        a[RuntimeException] should be thrownBy create(classOf[UnsupportedConstructorParameter])
      }

    }
    "support unary command handlers" when {

      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collect() = Wrapped("blah")
        }, method)
        decodeWrapped(handler.handleCommand(command("nothing"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collect(msg: String) = Wrapped(msg)
        }, method)
        decodeWrapped(
          handler.handleCommand(command("blah"), new MockCommandContext).get
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collect(msg: String, ctx: CommandContext) = {
            ctx.commandName() should ===("Collect")
            Wrapped(msg)
          }
        }, method)
        val ctx = new MockCommandContext
        decodeWrapped(handler.handleCommand(command("blah"), ctx).get) should ===(Wrapped("blah"))
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def collect(msg: String, ctx: MockCommandContext) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def collect(msg: String, ctx: CommandContext) =
            Wrapped(msg)
          @CommandHandler
          def collect(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's a Event Sourced command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.eventsourced.CommandHandler
            def collect(msg: String): Wrapped = Wrapped(msg)
          }, streamingOutMethod)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def collect(msg: String) =
              Wrapped(msg)
          }, method)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def collect(): Wrapped = throw new RuntimeException("foo")
        }, method)
        val ex = the[RuntimeException] thrownBy handler.handleCommand(command("nothing"), new MockCommandContext)
        ex.getMessage should ===("foo")
      }
    }

    "support streaming in command handlers" when {
      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collectStreamIn(): Wrapped = Wrapped("blah")
        }, streamingInMethod)
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(handler.handleStreamedInCommand(JavaCollections.singletonList(command("nothing")), ctx).get) should ===(
          Wrapped("blah")
        )
      }

      "single arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def collectStreamIn(msg: JavaList[String]): Wrapped = Wrapped(msg.get(0))
          },
          streamingInMethod
        )
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(
          handler.handleStreamedInCommand(JavaCollections.singletonList(command("blah")), ctx).get
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def collectStreamIn(msg: JavaList[String], ctx: CommandContext): Wrapped = {
              ctx.commandName() should ===("CollectStreamIn")
              Wrapped(msg.get(0))
            }
          },
          streamingInMethod
        )
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(handler.handleStreamedInCommand(JavaCollections.singletonList(command("blah")), ctx).get) should ===(
          Wrapped("blah")
        )
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def collectStreamIn(msg: JavaList[String], ctx: StreamingInMockCommandContext): Wrapped =
              Wrapped(msg.get(0))
          },
          streamingInMethod
        )
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def collectStreamIn(msg: JavaList[String], ctx: CommandContext): Wrapped = Wrapped(msg.get(0))

            @CommandHandler
            def collectStreamIn(msg: JavaList[String]): Wrapped = Wrapped(msg.get(0))
          },
          streamingInMethod
        )
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def wrongName(msg: JavaList[String]): Wrapped = Wrapped(msg.get(0))
          },
          streamingInMethod
        )
      }

      "fail if there's a Event Sourced command handler" in {
        val ex = the[RuntimeException] thrownBy create(
            new {
              @io.cloudstate.javasupport.eventsourced.CommandHandler
              def collectStreamIn(msg: JavaList[String]): Wrapped = Wrapped(msg.get(0))
            },
            streamingOutMethod
          )
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(
            new {
              @io.cloudstate.javasupport.crdt.CommandHandler
              def collectStreamIn(msg: JavaList[String]): Wrapped = Wrapped(msg.get(0))
            },
            streamingInMethod
          )
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def collectStreamIn(): Wrapped = throw new RuntimeException("foo")
        }, streamingInMethod)
        val ctx = new StreamingInMockCommandContext
        val ex = the[RuntimeException] thrownBy handler.handleStreamedInCommand(
            JavaCollections.singletonList(command("nothing")),
            ctx
          )
        ex.getMessage should ===("foo")
      }
    }

    "support streaming out command handlers" when {
      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collectStreamOut(): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped("blah"))
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(
          Await.result(handler.handleStreamedOutCommand(command("nothing"), ctx).asScala.runWith(Sink.head[JavaPbAny]),
                       100.millis)
        ) should ===(
          Wrapped("blah")
        )
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def collectStreamOut(msg: String): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped(msg))
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(
          Await.result(handler.handleStreamedOutCommand(command("blah"), ctx).asScala.runWith(Sink.head[JavaPbAny]),
                       100.millis)
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def collectStreamOut(msg: String, ctx: CommandContext): JavaSource[Wrapped, NotUsed] = {
              ctx.commandName() should ===("CollectStreamOut")
              JavaSource.single(Wrapped(msg))
            }
          },
          streamingOutMethod
        )
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(
          Await.result(handler.handleStreamedOutCommand(command("blah"), ctx).asScala.runWith(Sink.head[JavaPbAny]),
                       100.millis)
        ) should ===(
          Wrapped("blah")
        )
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def collectStreamOut(msg: String, ctx: StreamingOutMockCommandContext): JavaSource[Wrapped, NotUsed] =
              JavaSource.single(Wrapped(msg))
          },
          streamingOutMethod
        )
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def collectStreamOut(msg: String, ctx: CommandContext): JavaSource[Wrapped, NotUsed] =
              JavaSource.single(Wrapped(msg))

            @CommandHandler
            def collectStreamOut(msg: String): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped(msg))
          },
          streamingOutMethod
        )
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped(msg))
        }, streamingOutMethod)
      }

      "fail if there's a Event Sourced command handler" in {
        val ex = the[RuntimeException] thrownBy create(
            new {
              @io.cloudstate.javasupport.eventsourced.CommandHandler
              def collectStreamOut(msg: String): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped(msg))
            },
            streamingOutMethod
          )
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(
            new {
              @io.cloudstate.javasupport.crdt.CommandHandler
              def collectStreamOut(msg: String): JavaSource[Wrapped, NotUsed] = JavaSource.single(Wrapped(msg))
            },
            streamingOutMethod
          )
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def collectStreamOut(): JavaSource[Wrapped, NotUsed] = throw new RuntimeException("foo")
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        val ex = the[RuntimeException] thrownBy handler.handleStreamedOutCommand(command("nothing"), ctx)
        ex.getMessage should ===("foo")
      }
    }
  }
}

@Stateless
private class NoArgConstructorTest() {}

@Stateless
private class UnsupportedConstructorParameter(foo: String)
