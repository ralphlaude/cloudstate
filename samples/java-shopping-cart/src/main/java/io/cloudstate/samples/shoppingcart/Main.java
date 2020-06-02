package io.cloudstate.samples.shoppingcart;

import com.example.stateless.metricservice.Metricservice;
import io.cloudstate.javasupport.CloudState;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerStatelessFunction(
            MetricsService.class, Metricservice.getDescriptor().findServiceByName("MetricService"))
        .start()
        .toCompletableFuture()
        .get();
  }
}
