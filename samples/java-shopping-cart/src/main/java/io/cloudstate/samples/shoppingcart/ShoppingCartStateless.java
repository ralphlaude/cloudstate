package io.cloudstate.samples.shoppingcart;

import com.example.stateless.shoppingcart.Shoppingcart.AddLineItem;
import com.example.stateless.shoppingcart.Shoppingcart.ItemAdded;
import io.cloudstate.javasupport.function.CommandContext;
import io.cloudstate.javasupport.function.CommandHandler;
import io.cloudstate.javasupport.function.Stateless;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/** An stateless function. */
@Stateless
public class ShoppingCartStateless {

  private final Logger logger = LoggerFactory.getLogger(ShoppingCartStateless.class);

  public ShoppingCartStateless() {}

  @CommandHandler
  public ItemAdded addItem(AddLineItem item, CommandContext ctx) {
    logger.info("addItem called item - " + item.getName());
    return ItemAdded.newBuilder().build();
  }

  @CommandHandler
  public ItemAdded addItemStreamIn(List<AddLineItem> items, CommandContext ctx) {
    logger.info("addItemStreamIn called items size - " + items.size());
    logger.info("addItemStreamIn called items first item - " + items.get(0).getName());
    return ItemAdded.newBuilder().build();
  }

  @CommandHandler
  public List<ItemAdded> addItemStreamOut(AddLineItem item, CommandContext ctx) {
    logger.info("addItemStreamOut called item - " + item.getName());
    return Collections.singletonList(ItemAdded.newBuilder().build());
  }

  @CommandHandler
  public ItemAdded addItemStreamed(AddLineItem item, CommandContext ctx) {
    logger.info("addItemStreamOut called item - " + item.getName());
    return ItemAdded.newBuilder().build();
  }
}
