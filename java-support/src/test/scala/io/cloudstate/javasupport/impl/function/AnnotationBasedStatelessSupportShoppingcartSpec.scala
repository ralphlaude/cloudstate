package io.cloudstate.javasupport.impl.function

import java.util.Collections

import com.example.stateless.shoppingcart.Shoppingcart
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString, Any => JavaPbAny}
import io.cloudstate.javasupport._
import io.cloudstate.javasupport.function.{CommandContext, CommandHandler, Stateless, StatelessHandler}
import io.cloudstate.javasupport.impl.{AnnotationBasedStatelessSupport, AnySupport, ResolvedServiceMethod, ResolvedType}
import org.scalatest.{Matchers, WordSpec}

class AnnotationBasedStatelessSupportSpec extends WordSpec with Matchers {

  trait BaseContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = new ServiceCallFactory {
      override def lookup[T](serviceName: String, methodName: String, messageType: Class[T]): ServiceCallRef[T] =
        throw new NoSuchElementException
    }
  }

  object MockContext extends BaseContext

  class MockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "AddItem"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  class StreamingInMockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "AddItemStreamIn"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  class StreamingOutMockCommandContext extends CommandContext with BaseContext {
    override def commandName(): String = "AddItemStreamOut"
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
  val anySupport = new AnySupport(Array(Shoppingcart.getDescriptor), this.getClass.getClassLoader)
  val method = ResolvedServiceMethod(
    Shoppingcart.getDescriptor.findServiceByName("ShoppingCart").findMethodByName("AddItem"),
    StringResolvedType,
    WrappedResolvedType
  )

  val streamingInMethod = ResolvedServiceMethod(
    Shoppingcart.getDescriptor.findServiceByName("ShoppingCart").findMethodByName("AddItemStreamIn"),
    StringResolvedType,
    WrappedResolvedType
  )

  val streamingOutMethod = ResolvedServiceMethod(
    Shoppingcart.getDescriptor.findServiceByName("ShoppingCart").findMethodByName("AddItemStreamOut"),
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
          def addItem() = Wrapped("blah")
        }, method)
        decodeWrapped(handler.handleCommand(command("nothing"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem(msg: String) = Wrapped(msg)
        }, method)
        decodeWrapped(
          handler.handleCommand(command("blah"), new MockCommandContext).get
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem(msg: String, ctx: CommandContext) = {
            ctx.commandName() should ===("AddItem")
            Wrapped(msg)
          }
        }, method)
        val ctx = new MockCommandContext
        decodeWrapped(handler.handleCommand(command("blah"), ctx).get) should ===(Wrapped("blah"))
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: MockCommandContext) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: CommandContext) =
            Wrapped(msg)
          @CommandHandler
          def addItem(msg: String) =
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

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def addItem(msg: String) =
              Wrapped(msg)
          }, method)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def addItem(): Wrapped = throw new RuntimeException("foo")
        }, method)
        val ex = the[RuntimeException] thrownBy handler.handleCommand(command("nothing"), new MockCommandContext)
        ex.getMessage should ===("foo")
      }
    }

    "support streaming in command handlers" when {
      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamIn(): Wrapped = Wrapped("blah")
        }, streamingInMethod)
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(handler.handleStreamInCommand(Collections.singletonList(command("nothing")), ctx).get) should ===(
          Wrapped("blah")
        )
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamIn(msg: java.util.List[String]): Wrapped = Wrapped(msg.get(0))
        }, streamingInMethod)
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(
          handler.handleStreamInCommand(Collections.singletonList(command("blah")), ctx).get
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def addItemStreamIn(msg: java.util.List[String], ctx: CommandContext): Wrapped = {
              ctx.commandName() should ===("AddItemStreamIn")
              Wrapped(msg.get(0))
            }
          },
          streamingInMethod
        )
        val ctx = new StreamingInMockCommandContext
        decodeWrapped(handler.handleStreamInCommand(Collections.singletonList(command("blah")), ctx).get) should ===(
          Wrapped("blah")
        )
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItemStreamIn(msg: java.util.List[String], ctx: StreamingInMockCommandContext): Wrapped =
            Wrapped(msg.get(0))
        }, streamingInMethod)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def addItemStreamIn(msg: String, ctx: CommandContext): Wrapped = Wrapped(msg)

            @CommandHandler
            def addItemStreamIn(msg: String): Wrapped = Wrapped(msg)
          },
          streamingInMethod
        )
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String): Wrapped = Wrapped(msg)
        }, streamingInMethod)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def addItemStreamIn(msg: String): Wrapped = Wrapped(msg)
          }, streamingInMethod)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamIn(): Wrapped = throw new RuntimeException("foo")
        }, streamingInMethod)
        val ctx = new StreamingInMockCommandContext
        val ex = the[RuntimeException] thrownBy handler.handleStreamInCommand(
            Collections.singletonList(command("nothing")),
            ctx
          )
        ex.getMessage should ===("foo")
      }
    }

    "support streaming out command handlers" when {
      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamOut(): Wrapped = Wrapped("blah")
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(handler.handleStreamOutCommand(command("nothing"), ctx).get(0)) should ===(
          Wrapped("blah")
        )
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamOut(msg: String): Wrapped = Wrapped(msg)
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(
          handler.handleStreamOutCommand(command("blah"), ctx).get(0)
        ) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def addItemStreamOut(msg: String, ctx: CommandContext): Wrapped = {
              ctx.commandName() should ===("AddItemStreamOut")
              Wrapped(msg)
            }
          },
          streamingOutMethod
        )
        val ctx = new StreamingOutMockCommandContext
        decodeWrapped(handler.handleStreamOutCommand(command("blah"), ctx).get(0)) should ===(
          Wrapped("blah")
        )
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItemStreamOut(msg: String, ctx: StreamingOutMockCommandContext): Wrapped = Wrapped(msg)
        }, streamingOutMethod)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(
          new {
            @CommandHandler
            def addItemStreamOut(msg: String, ctx: CommandContext): Wrapped = Wrapped(msg)

            @CommandHandler
            def addItemStreamOut(msg: String): Wrapped = Wrapped(msg)
          },
          streamingOutMethod
        )
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String): Wrapped = Wrapped(msg)
        }, streamingOutMethod)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def addItemStreamOut(msg: String): Wrapped = Wrapped(msg)
          }, streamingOutMethod)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def addItemStreamOut(): Wrapped = throw new RuntimeException("foo")
        }, streamingOutMethod)
        val ctx = new StreamingOutMockCommandContext
        val ex = the[RuntimeException] thrownBy handler.handleStreamOutCommand(command("nothing"), ctx)
        ex.getMessage should ===("foo")
      }
    }
  }
}
