package io.cloudstate.samples.shoppingcart;

import com.example.stateless.metricservice.Metricservice.AverageResult;
import com.example.stateless.metricservice.Metricservice.Metric;
import io.cloudstate.javasupport.function.CommandContext;
import io.cloudstate.javasupport.function.CommandHandler;
import io.cloudstate.javasupport.function.Stateless;

import java.util.List;

/** An stateless function. */
@Stateless
public class MetricsService {

  private long count = 0;
  private long sum = 0;

  public MetricsService() {}

  @CommandHandler
  public AverageResult average(List<Metric> metrics, CommandContext ctx) {
    long sum = metrics.stream().map(Metric::getMetric).reduce(0L, (acc, value) -> acc + value);
    return AverageResult.newBuilder().setValue(sum / metrics.size()).build();
  }

  @CommandHandler
  public AverageResult streamAverage(Metric metric, CommandContext ctx) {
    count++;
    sum += metric.getMetric();
    return AverageResult.newBuilder().setValue(sum / count).build();
  }
}
