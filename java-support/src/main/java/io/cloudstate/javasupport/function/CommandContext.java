package io.cloudstate.javasupport.function;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.EffectContext;

/**
 * An event sourced command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows emitting
 * new events in response to a command, along with forwarding the result to other entities, and
 * performing side effects on other entities.
 */
public interface CommandContext extends ClientActionContext, EffectContext, Context {

  /**
   * The name of the command being executed.
   *
   * @return The name of the command.
   */
  String commandName();
}
