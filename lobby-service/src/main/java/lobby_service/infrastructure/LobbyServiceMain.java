package lobby_service.infrastructure;

import lobby_service.application.*;
import io.vertx.core.Vertx;

/**
 * @author Bedeschi Federica   federica.bedeschi4@studio.unibo.it
 * @author Pracucci Filippo    filippo.pracucci@studio.unibo.it
 */

public class LobbyServiceMain {

	private static final int LOBBY_SERVICE_PORT = 9001;

	/* addresses to be used when using a manual deployment */
	/*static final String ACCOUNT_SERVICE_URI = "http://localhost:9000";
	static final String DELIVERY_SERVICE_URI = "http://localhost:9002";*/

	/* addresses to be used when deploying with Docker */
	private static final String ACCOUNT_SERVICE_URI = "http://account-service:9000";
	//static final String DELIVERY_SERVICE_URI = "http://delivery-service:9002";
	private static final String EV_CHANNELS_LOCATION = "broker:9092";

	public static void main(String[] args) {

		final Vertx vertx = Vertx.vertx();

		final var lobby = new LobbyServiceImpl();
		final AccountService accountService =  new AccountServiceProxy(ACCOUNT_SERVICE_URI);
		final DeliveryService deliveryService =  new DeliveryServiceProxy(vertx, EV_CHANNELS_LOCATION);

		lobby.bindAccountService(accountService);
		lobby.bindDeliveryService(deliveryService);

		vertx.deployVerticle(new LobbyServiceController(lobby, LOBBY_SERVICE_PORT));
	}

}

