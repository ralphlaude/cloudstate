package io.cloudstate.samples.shoppingcart;

import com.example.stateless.shoppingcart.Shoppingcart;
import io.cloudstate.javasupport.CloudState;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerStatelessFunction(
            ShoppingCartStateless.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"))
        .start()
        .toCompletableFuture()
        .get();
  }
}
