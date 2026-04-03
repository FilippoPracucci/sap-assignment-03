package lobby_service.infrastructure;

import delivery_service.application.DeliveryServiceMock;
import delivery_service.infrastructure.DeliveryServiceController;
import io.vertx.core.Vertx;
import lobby_service.application.DeliveryService;
import lobby_service.domain.Address;
import lobby_service.domain.DeliveryId;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class DeliveryServiceProxyTest {

    private final static Logger logger = Logger.getLogger("[DeliveryServiceProxyTest]");
    private static final String EV_CHANNELS_LOCATION = "broker:9092";

    private static DeliveryServiceController deliveryServiceController;
    private static DeliveryServiceProxy proxy;
    private static Vertx vertx;

    @BeforeAll
    public static void setUp() {
        final Synchronizer sync = new Synchronizer();
        final DeliveryService deliveryService = new DeliveryServiceMock();
        vertx = Vertx.vertx();
        deliveryServiceController = new DeliveryServiceController(deliveryService, EV_CHANNELS_LOCATION);
        vertx.deployVerticle(deliveryServiceController)
                .onSuccess((res) -> sync.notifySync());
        try {
            sync.awaitSync();
            logger.info("setup completed.");
        } catch (Exception ex) {
            logger.info("sync failed.");
            ex.printStackTrace();
        }
        proxy = new DeliveryServiceProxy(Vertx.vertx(), EV_CHANNELS_LOCATION);
        logger.info("setup completed.");
    }

    @Test
    public void testCreateNewDelivery() {
        try {
            final DeliveryId deliveryId = proxy.createNewDelivery(
                    10.0,
                    new Address("Via Emilia", 1),
                    new Address("Via Roma", 20),
                    Optional.empty()
            );
            assertEquals("delivery-0", deliveryId.id());
        } catch (final Exception ex) {
            ex.printStackTrace();
            fail("Delivery creation failed.");
        }
    }

    @Test
    public void testTrackDelivery() {
        try {
            final DeliveryId deliveryId = proxy.createNewDelivery(
                    10.0,
                    new Address("Via Emilia", 1),
                    new Address("Via Roma", 20),
                    Optional.empty()
            );
            assertEquals("tracking-session-0", proxy.trackDelivery(deliveryId));
        } catch (final Exception ex) {
            ex.printStackTrace();
            fail("Tracking delivery failed.");
        }
    }

    @AfterAll
    public static void tearDown() {
        vertx.undeploy(deliveryServiceController.deploymentID());
    }
}
