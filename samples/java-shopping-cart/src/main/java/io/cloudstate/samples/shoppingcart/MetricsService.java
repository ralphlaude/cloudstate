package io.cloudstate.samples.shoppingcart;

import com.example.stateless.metricservice.Metricservice;
import com.example.stateless.metricservice.Metricservice.AverageResult;
import com.example.stateless.metricservice.Metricservice.Metric;
import io.cloudstate.javasupport.function.CommandContext;
import io.cloudstate.javasupport.function.CommandHandler;
import io.cloudstate.javasupport.function.Stateless;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** An stateless function. */
@Stateless
public class MetricsService {

  private final Logger logger = LoggerFactory.getLogger(MetricsService.class);
  private long count = 0;
  private long sum = 0;

  public MetricsService() {}

  @CommandHandler
  public AverageResult average(List<Metric> metrics, CommandContext ctx) {
    logger.info("average called metrics size - " + metrics.size());
    logger.info("average called metrics first item - " + metrics.get(0));
    long sum =
        metrics.stream()
            .map(Metricservice.Metric::getMetric)
            .reduce(0L, (acc, value) -> acc + value);
    return AverageResult.newBuilder().setValue(sum / metrics.size()).build();
  }

  @CommandHandler
  public AverageResult streamedAverage(Metric metric, CommandContext ctx) {
    logger.info("streamedAverage called count - " + count);
    logger.info("streamedAverage called sum - " + sum);
    logger.info("streamedAverage called metric - " + metric.getMetric());
    count++;
    sum += metric.getMetric();
    return AverageResult.newBuilder().setValue(sum / count).build();
  }
}
