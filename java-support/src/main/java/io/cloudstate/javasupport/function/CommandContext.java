package io.cloudstate.javasupport.function;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.EffectContext;

/**
 * A stateless command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows
 * forwarding the result to other entities, performing side effects on other entities and failing
 * the request.
 */
public interface CommandContext extends ClientActionContext, EffectContext, Context {

  /**
   * The name of the command being executed.
   *
   * @return The name of the command.
   */
  String commandName();
}
