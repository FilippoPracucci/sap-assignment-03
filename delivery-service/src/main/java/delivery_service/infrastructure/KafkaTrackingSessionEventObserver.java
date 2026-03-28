package delivery_service.infrastructure;

import delivery_service.application.TrackingSessionEventObserver;
import delivery_service.domain.DeliveryTime;

public class KafkaTrackingSessionEventObserver implements TrackingSessionEventObserver {

    @Override
    public void enableEventNotification(final String trackingSessionId) {
        // TODO

    }

    @Override
    public void shipped(final String trackingSessionId) {
        // TODO

    }

    @Override
    public void delivered(final String trackingSessionId) {
        // TODO

    }

    @Override
    public void timeElapsed(final String trackingSessionId, final DeliveryTime timeElapsed) {
        // TODO

    }
}
