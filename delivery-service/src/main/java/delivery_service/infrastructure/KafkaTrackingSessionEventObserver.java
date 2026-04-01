package delivery_service.infrastructure;

import delivery_service.application.TrackingSessionEventObserver;
import delivery_service.domain.DeliveryTime;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import kafka.OutputEventChannel;
import java.util.logging.Logger;

public class KafkaTrackingSessionEventObserver implements TrackingSessionEventObserver {

    static Logger logger = Logger.getLogger("[KafkaEventNotifierAdapter]");

    private final Vertx vertx;
    private final String evChannelsLocation;
    private final String deliveryTrackingInternalChannelName;
    private OutputEventChannel deliveryTrackingInternalChannel;

    public KafkaTrackingSessionEventObserver(
            final Vertx vertx,
            final String evChannelsLocation,
            final String channelName
    ) {
        this.deliveryTrackingInternalChannelName = channelName;
        this.vertx = vertx;
        this.evChannelsLocation = evChannelsLocation;
    }

    @Override
    public void enableEventNotification(final String trackingSessionId) {
        this.deliveryTrackingInternalChannel = new OutputEventChannel(
                this.vertx,
                this.deliveryTrackingInternalChannelName.replace("{id}", trackingSessionId),
                this.evChannelsLocation
        );
    }

    @Override
    public void shipped(final String trackingSessionId) {
        logger.info("package shipped for " + trackingSessionId);
        final JsonObject shippedEvent = new JsonObject();
        shippedEvent.put("event", "shipped");
        if (this.deliveryTrackingInternalChannel != null) {
            this.deliveryTrackingInternalChannel.postEvent(shippedEvent);
        }
    }

    @Override
    public void delivered(final String trackingSessionId) {
        logger.info("package delivered for " + trackingSessionId);
        final JsonObject deliveredEvent = new JsonObject();
        deliveredEvent.put("event", "delivered");
        if (this.deliveryTrackingInternalChannel != null) {
            this.deliveryTrackingInternalChannel.postEvent(deliveredEvent);
        }
    }

    @Override
    public void timeElapsed(final String trackingSessionId, final DeliveryTime timeElapsed) {
        logger.info("Elapsed " + timeElapsed + " for " + trackingSessionId);
        final JsonObject timeElapsedEvent = new JsonObject();
        timeElapsedEvent.put("event", "time-elapsed");
        timeElapsedEvent.put("timeElapsed", timeElapsed.toString());
        if (this.deliveryTrackingInternalChannel != null) {
            this.deliveryTrackingInternalChannel.postEvent(timeElapsedEvent);
        }
    }
}
