package api_gateway.infrastructure;

import api_gateway.domain.DeliveryDetail;
import api_gateway.domain.DeliveryId;
import api_gateway.domain.DeliveryStatus;
import delivery_service.application.DeliveryServiceMock;
import delivery_service.infrastructure.DeliveryServiceController;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class DeliveryServiceProxyTest {

    private final static Logger logger = Logger.getLogger("[DeliveryServiceProxyTest]");

    private static final String EV_CHANNELS_LOCATION = "broker:9092";

    private DeliveryServiceController deliveryServiceController;
    private DeliveryServiceProxy proxy;
    private Vertx vertx;

    @BeforeEach
    public void setUp() {
        final Synchronizer sync = new Synchronizer();
        final DeliveryServiceVertx deliveryService = new DeliveryServiceMock();
        this.vertx = Vertx.vertx();
        this.deliveryServiceController = new DeliveryServiceController(deliveryService, EV_CHANNELS_LOCATION);
        vertx.deployVerticle(this.deliveryServiceController)
                .onSuccess((res) -> sync.notifySync());
        try {
            sync.awaitSync();
            logger.info("setup completed.");
        } catch (Exception ex) {
            logger.info("sync failed.");
            ex.printStackTrace();
        }
        this.proxy = new DeliveryServiceProxy(Vertx.vertx(), EV_CHANNELS_LOCATION);
        logger.info("setup completed.");
    }

    @Test
    public void testGetDeliveryDetail() {
        try {
            final DeliveryId deliveryId = new DeliveryId("delivery-0");
            final DeliveryDetail deliveryDetail = this.proxy.getDeliveryDetail(deliveryId);
            assertEquals(deliveryId, deliveryDetail.getId());
        } catch (final Exception ex) {
            ex.printStackTrace();
            fail("Delivery detail retrieval failed.");
        }
    }

    @Test
    public void testGetDeliveryStatus() {
        try {
            final DeliveryId deliveryId = new DeliveryId("delivery-0");
            final DeliveryStatus deliveryStatus = this.proxy.getDeliveryStatus(deliveryId, "tracking-session-0");
            assertEquals(deliveryId, deliveryStatus.getId());
        } catch (final Exception ex) {
            ex.printStackTrace();
            fail("Delivery status retrieval failed.");
        }
    }

    @Test
    public void testStopTrackingDelivery() {
        try {
            final DeliveryId deliveryId = new DeliveryId("delivery-0");
            this.proxy.stopTrackingDelivery(deliveryId, "tracking-session-0");
        } catch (final Exception ex) {
            ex.printStackTrace();
            fail("Stop tracking delivery failed.");
        }
    }

    @AfterEach
    public void tearDown() {
        this.vertx.undeploy(this.deliveryServiceController.deploymentID());
    }
}
