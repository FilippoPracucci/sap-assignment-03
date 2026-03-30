package api_gateway.infrastructure;

import io.vertx.core.Vertx;
import api_gateway.application.*;

import java.util.logging.Logger;

/**
 * @author Bedeschi Federica   federica.bedeschi4@studio.unibo.it
 * @author Pracucci Filippo    filippo.pracucci@studio.unibo.it
 */

public class APIGatewayMain {

    static final int BACKEND_PORT = 8080;

    /* addresses to be used when using a manual deployment */

    /*static final String ACCOUNT_SERVICE_ADDRESS = "http://localhost:9000";
    static final String LOBBY_SERVICE_ADDRESS = "http://localhost:9001";
    static final String DELIVERY_SERVICE_ADDRESS = "http://localhost:9002";

    static final String DELIVERY_SERVICE_WS_ADDRESS = "localhost";
    static final int DELIVERY_SERVICE_WS_PORT = 9002;*/

    /* addresses to be used when deploying with Docker */

    static final String ACCOUNT_SERVICE_ADDRESS = "http://account-service:9000";
    static final String LOBBY_SERVICE_ADDRESS = "http://lobby-service:9001";
    static final String DELIVERY_SERVICE_ADDRESS = "http://delivery-service:9002";

    private static final String EV_CHANNELS_LOCATION = "broker:9092";

    static final int METRICS_SERVER_EXPOSED_PORT = 9401;

    public static void main(String[] args) {
        final Vertx vertx = Vertx.vertx();

        final AccountService accountService = new AccountServiceProxy(ACCOUNT_SERVICE_ADDRESS);
        final LobbyService lobbyService = new LobbyServiceProxy(LOBBY_SERVICE_ADDRESS);
        final DeliveryServiceVertx deliveryService = new DeliveryServiceProxy(vertx, EV_CHANNELS_LOCATION);

        var server = new APIGatewayController(accountService, lobbyService, deliveryService, BACKEND_PORT);
        try {
            server.addControllerObserver(new PrometheusControllerObserver(METRICS_SERVER_EXPOSED_PORT));
        } catch (final ObservabilityMetricServerException e) {
            Logger.getLogger("[APIGatewayMain]").info("Observability metric server failed to start");
        }
        Vertx.vertx().deployVerticle(server);
    }
}

