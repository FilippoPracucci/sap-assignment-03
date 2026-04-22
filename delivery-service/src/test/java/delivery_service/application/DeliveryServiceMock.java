package delivery_service.application;

import delivery_service.domain.*;
import delivery_service.domain.drone.DroneEnvironment;

import java.util.Calendar;
import java.util.Optional;

public class DeliveryServiceMock implements DeliveryService {

    private final Delivery delivery;
    private final DeliveryId deliveryId;

    public DeliveryServiceMock() {
        this.deliveryId = new DeliveryId("delivery-0");
        this.delivery = new DeliveryImpl(new DroneEnvironment(), this.deliveryId);
        this.delivery.applyEvent(new DeliveryCreated(
                this.deliveryId,
                new DeliveryDetailImpl(
                        this.deliveryId,
                        10.0,
                        new Address("Via Emilia", 1),
                        new Address("Via Roma", 20),
                        TimeConverter.getNowAsCalendar()
                )
        ));
    }

    @Override
    public DeliveryDetail getDeliveryDetail(final DeliveryId deliveryId) {
        return this.delivery.getDeliveryDetail();
    }

    @Override
    public DeliveryStatus getDeliveryStatus(final DeliveryId deliveryId, final String trackingSessionId) {
        return this.delivery.getDeliveryStatus();
    }

    @Override
    public TrackingSession getTrackingSession(String trackingSessionId) {
        return null;
    }

    @Override
    public DeliveryId createNewDelivery(
            final double weight,
            final Address startingPlace,
            final Address destinationPlace,
            final Optional<Calendar> expectedShippingMoment
    ) {
        return this.deliveryId;
    }

    @Override
    public TrackingSession trackDelivery(final DeliveryId deliveryId, final TrackingSessionEventObserver observer) {
        return new TrackingSession("tracking-session-0");
    }

    @Override
    public void stopTrackingDelivery(final DeliveryId deliveryId, final String trackingSessionId) {

    }
}
