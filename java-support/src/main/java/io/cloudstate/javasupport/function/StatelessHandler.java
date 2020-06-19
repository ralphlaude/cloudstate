package io.cloudstate.javasupport.function;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import com.google.protobuf.Any;

import java.util.List;
import java.util.Optional;

/**
 * Low level interface for handling commands and stream of commands for a stateless function.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * CommandHandler} and similar annotations should be used.
 */
public interface StatelessHandler {

  /**
   * Handle the given command in unary and full duplex streamed case.
   *
   * @param command The command to handle.
   * @param context The context for the command.
   * @return A reply to the command, if any is sent.
   */
  Optional<Any> handleCommand(Any command, CommandContext context);

  /**
   * Handle the given stream of commands.
   *
   * @param commands The commands to handle.
   * @param context The context for the command.
   * @return A reply to the command, if any is sent.
   */
  Optional<Any> handleStreamedInCommand(List<Any> commands, CommandContext context);

  /**
   * Handle the given command and return a stream of replies.
   *
   * @param command The command to handle.
   * @param context The context for the command.
   * @return A stream of replies to the command.
   */
  Source<Any, NotUsed> handleStreamedOutCommand(Any command, CommandContext context);
}
