package io.cloudstate.javasupport;

import com.google.protobuf.Any;
import io.cloudstate.javasupport.crud.CommandContext;
import io.cloudstate.javasupport.crud.CommandHandler;
import io.cloudstate.javasupport.crud.EventHandler;
import io.cloudstate.javasupport.crud.SnapshotContext;

import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * EventHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface StatelessHandler {

  Optional<Any> handleCommand(Any command, CommandContext context);
}
