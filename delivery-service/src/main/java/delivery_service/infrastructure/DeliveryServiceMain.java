package delivery_service.infrastructure;

import delivery_service.application.DeliveryServiceImpl;
import io.vertx.core.Vertx;

import java.util.logging.Logger;

/**
 * @author Bedeschi Federica   federica.bedeschi4@studio.unibo.it
 * @author Pracucci Filippo    filippo.pracucci@studio.unibo.it
 */

public class DeliveryServiceMain {

	private static final String DELIVERY_EVENT_STORE_FILE_NAME = "delivery_event_store.json";

	//static final int DELIVERY_SERVICE_PORT = 9002;
	private static final String EV_CHANNELS_LOCATION = "broker:9092";
	private static final int PROMETHEUS_SERVER_PORT = 9400;

	public static void main(String[] args) {
		final var deliveryService = new DeliveryServiceImpl();
		deliveryService.bindDeliveryRepository(new FileBasedDeliveryEventStore(DELIVERY_EVENT_STORE_FILE_NAME));
		try {
			deliveryService.addObserver(new PrometheusDeliveryServiceObserver(PROMETHEUS_SERVER_PORT));
		} catch (final ObservabilityMetricServerException ex) {
			Logger.getLogger("[DeliveryServiceMain]").info("Observability metric server failed to start");
		}
		Vertx.vertx().deployVerticle(new DeliveryServiceController(deliveryService, EV_CHANNELS_LOCATION));
	}

}

