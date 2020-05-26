package io.cloudstate.javasupport.impl

import java.lang.reflect.{Constructor, InvocationTargetException}
import java.util
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.function._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.{Context, ServiceCallFactory}

/**
 * Annotation based implementation of the [[StatelessFactory]].
 */
private[impl] class AnnotationBasedStatelessSupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[StatelessCreationContext => AnyRef] = None
) extends StatelessFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = EventBehaviorReflection(entityClass, resolvedMethods)

  override def create(context: Context): StatelessHandler =
    new EntityHandler(context)

  private val constructor: StatelessCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on CRUD entities: $entityClass")
    }
  }

  private class EntityHandler(context: Context) extends StatelessHandler {
    private val entity = {
      constructor(new DelegatingCrudContext(context) with StatelessCreationContext)
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      behavior.commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      } getOrElse {
        throw new RuntimeException(
          s"No command handler found for command [${context.commandName()}] on $behaviorsString"
        )
      }
    }

    override def handleStreamInCommand(commands: util.List[JavaPbAny], context: CommandContext): Optional[JavaPbAny] =
      unwrap {
        behavior.commandHandlers.get(context.commandName()).map { handler =>
          handler.invoke(entity, commands, context)
        } getOrElse {
          throw new RuntimeException(
            s"No command handler found for command [${context.commandName()}] on $behaviorsString"
          )
        }
      }

    override def handleStreamOutCommand(command: JavaPbAny, context: CommandContext): util.List[JavaPbAny] =
      unwrap {
        behavior.commandHandlers.get(context.commandName()).map { handler =>
          handler.invokeWithResults(entity, command, context)
        } getOrElse {
          throw new RuntimeException(
            s"No command handler found for command [${context.commandName()}] on $behaviorsString"
          )
        }
      }

    private def unwrap[T](block: => T): T =
      try {
        block
      } catch {
        case ite: InvocationTargetException if ite.getCause != null =>
          throw ite.getCause
      }

    private def behaviorsString = entity.getClass.toString
  }

  private abstract class DelegatingCrudContext(delegate: Context) extends Context {
    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  }
}

private class EventBehaviorReflection(
    val commandHandlers: Map[String, ReflectionHelper.CommandHandlerInvoker[CommandContext]]
) {}

private object EventBehaviorReflection {
  def apply(behaviorClass: Class[_],
            serviceMethods: Map[String, ResolvedServiceMethod[_, _]]): EventBehaviorReflection = {

    val allMethods = ReflectionHelper.getAllDeclaredMethods(behaviorClass)
    val commandHandlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command by that name."
          )
        })

        new ReflectionHelper.CommandHandlerInvoker[CommandContext](ReflectionHelper.ensureAccessible(method),
                                                                   serviceMethod)
      }
      .groupBy(_.serviceMethod.name)
      .map {
        case (commandName, Seq(invoker)) => commandName -> invoker
        case (commandName, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}"
          )
      }

    ReflectionHelper.validateNoBadMethods(
      allMethods,
      classOf[Stateless],
      Set(classOf[CommandHandler])
    )

    new EventBehaviorReflection(commandHandlers)
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_]) extends (StatelessCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[StatelessCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: StatelessCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}
