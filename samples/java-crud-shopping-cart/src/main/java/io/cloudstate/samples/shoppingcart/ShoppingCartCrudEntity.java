/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.samples.shoppingcart;

import com.example.crud.shoppingcart.Shoppingcart;
import com.example.crud.shoppingcart.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.crud.CommandContext;
import io.cloudstate.javasupport.crud.CommandHandler;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.crud.DeleteStateHandler;
import io.cloudstate.javasupport.crud.UpdateStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A CRUD entity. */
@CrudEntity
public class ShoppingCartCrudEntity {

  private final Logger logger = LoggerFactory.getLogger(ShoppingCartCrudEntity.class);

  private final String entityId;
  private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();

  public ShoppingCartCrudEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @UpdateStateHandler
  public void handleUpdateState(Domain.Cart cart) {
    logger.info(
        "ShoppingCartCrudEntity handleUpdateState called with cart.values() - "
            + this.cart.values().size());
    this.cart.clear();
    for (Domain.LineItem item : cart.getItemsList()) {
      this.cart.put(item.getProductId(), convert(item));
    }
  }

  @DeleteStateHandler
  public void handleDeleteState() {
    logger.info(
        "ShoppingCartCrudEntity handleDeleteState called with cart.values() - "
            + cart.values().size());
    this.cart.clear();
  }

  @CommandHandler
  public Shoppingcart.Cart getCart() {
    logger.info(
        "ShoppingCartCrudEntity getCart called with cart.values() - " + cart.values().size());
    return Shoppingcart.Cart.newBuilder().addAllItems(cart.values()).build();
  }

  @CommandHandler
  public Empty removeCart(Shoppingcart.RemoveShoppingCart cartItem, CommandContext ctx) {
    if (!entityId.equals(cartItem.getUserId())) {
      ctx.fail("Cannot remove unknown cart " + cartItem.getUserId());
    }
    logger.info("ShoppingCartCrudEntity removeCart called with item - " + cart);
    cart.clear();

    ctx.delete();
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to item " + item.getProductId());
    }

    Shoppingcart.LineItem lineItem = cart.get(item.getProductId());
    if (lineItem == null) {
      lineItem =
          Shoppingcart.LineItem.newBuilder()
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(item.getQuantity())
              .build();
    } else {
      lineItem =
          lineItem.toBuilder().setQuantity(lineItem.getQuantity() + item.getQuantity()).build();
    }
    logger.info("ShoppingCartCrudEntity addItem called with item - " + item);
    cart.put(item.getProductId(), lineItem);

    List<Domain.LineItem> lineItems =
        cart.values().stream().map(this::convert).collect(Collectors.toList());
    ctx.update(Domain.Cart.newBuilder().addAllItems(lineItems).build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    if (!cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }
    logger.info("ShoppingCartCrudEntity removeItem called with item - " + item);
    cart.remove(item.getProductId());

    List<Domain.LineItem> lineItems =
        cart.values().stream().map(this::convert).collect(Collectors.toList());
    ctx.update(Domain.Cart.newBuilder().addAllItems(lineItems).build());
    return Empty.getDefaultInstance();
  }

  private Shoppingcart.LineItem convert(Domain.LineItem item) {
    return Shoppingcart.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }

  private Domain.LineItem convert(Shoppingcart.LineItem item) {
    return Domain.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }
}
