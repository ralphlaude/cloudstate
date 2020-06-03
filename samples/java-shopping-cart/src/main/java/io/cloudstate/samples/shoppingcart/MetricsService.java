package io.cloudstate.samples.shoppingcart;

import com.example.stateless.metricservice.Metricservice.Average;
import com.example.stateless.metricservice.Metricservice.Metric;
import io.cloudstate.javasupport.function.CommandContext;
import io.cloudstate.javasupport.function.CommandHandler;
import io.cloudstate.javasupport.function.Stateless;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** An stateless function. */
@Stateless
public class MetricsService {

  private long count = 0;
  private long sum = 0;

  public MetricsService() {}

  @CommandHandler
  public Average collect(Metric metric, CommandContext ctx) {
    return Average.newBuilder().setValue(metric.getValue()).build();
  }

  @CommandHandler
  public Average collectStreamIn(List<Metric> metrics, CommandContext ctx) {
    long sum = metrics.stream().map(Metric::getValue).reduce(0L, (acc, value) -> acc + value);
    return Average.newBuilder().setValue(sum / metrics.size()).build();
  }

  @CommandHandler
  public List<Average> collectStreamOut(Metric metric, CommandContext ctx) {
    return Stream.iterate(1L, i -> i <= 100, i -> i + 1)
        .map(
            i -> {
              long avg = (metric.getValue() + i) / i;
              return Average.newBuilder().setValue(avg).build();
            })
        .collect(Collectors.toList());
  }

  @CommandHandler
  public Average collectStreamed(Metric metric, CommandContext ctx) {
    count++;
    sum += metric.getValue();
    return Average.newBuilder().setValue(sum / count).build();
  }
}
