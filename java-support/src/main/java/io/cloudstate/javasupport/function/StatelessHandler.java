package io.cloudstate.javasupport.function;

import com.google.protobuf.Any;

import java.util.List;
import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * CommandHandler} and similar annotations should be used.
 */
public interface StatelessHandler {

  Optional<Any> handleCommand(Any command, CommandContext context);

  Optional<Any> handleStreamInCommand(List<Any> commands, CommandContext context);

  List<Any> handleStreamOutCommand(Any command, CommandContext context);
}
