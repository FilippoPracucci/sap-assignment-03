package api_gateway.infrastructure;

import common.hexagonal.Adapter;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;

import java.io.IOException;

@Adapter
public class PrometheusControllerObserver implements ControllerObserver {

	private final Counter nTotalNumberOfRESTRequests;
	private final Counter nTotalNumberOfSuccessfulRESTRequests;
	private final Counter totalRequestResponseTime;
	private final Gauge isAccountCircuitOpen;

    public PrometheusControllerObserver(final int port) throws ObservabilityMetricServerException {
		JvmMetrics.builder().register();
		
		this.nTotalNumberOfRESTRequests = Counter.builder()
				.name("api_gateway_num_rest_requests_total")
				.help("Total number of REST requests received")
				.register();

		this.nTotalNumberOfSuccessfulRESTRequests = Counter.builder()
				.name("api_gateway_num_successful_rest_requests_total")
				.help("Total number of successful REST requests")
				.register();

		this.totalRequestResponseTime = Counter.builder()
				.name("api_gateway_request_response_time_ms_total")
				.help("Total request response time in milliseconds")
				.register();

		this.isAccountCircuitOpen = Gauge.builder()
				.name("api_gateway_is_account_circuit_open")
				.help("If the circuit is open (1) then the account service is unavailable")
				.register();

		try {
            HTTPServer.builder()
                    .port(port)
                    .buildAndStart();
		} catch (final IOException e) {
			throw new ObservabilityMetricServerException();
		}
	}

	@Override
	public void notifyNewRESTRequest(final long responseTimeInMillis, boolean success) {
		this.nTotalNumberOfRESTRequests.inc();
		if (success) {
			this.nTotalNumberOfSuccessfulRESTRequests.inc();
		}
		this.totalRequestResponseTime.inc(responseTimeInMillis);
	}

	@Override
	public void notifyAccountCircuitStatus(boolean isCircuitOpen) {
		this.isAccountCircuitOpen.set(isCircuitOpen ? 1 : 0);
	}
}