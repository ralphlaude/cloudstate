package io.cloudstate.javasupport.function;

import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.eventsourced.CommandHandler;
import io.cloudstate.javasupport.eventsourced.EventHandler;

/**
 * Low level interface for handling commands and stream of commands for a stateless function.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * CommandHandler} and similar annotations should be used.
 */
public interface StatelessFactory {
  /**
   * Create an stateless handler for the given context.
   *
   * @param context The context.
   * @return The handler for the given context.
   */
  StatelessHandler create(Context context);
}
