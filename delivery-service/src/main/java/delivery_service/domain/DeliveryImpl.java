package delivery_service.domain;

import delivery_service.domain.drone.DroneAgent;
import delivery_service.domain.drone.DroneEnvironment;
import delivery_service.domain.drone.env.EnvironmentEvent;
import delivery_service.domain.drone.env.EnvironmentObserver;

import java.time.temporal.ChronoUnit;
import java.util.*;

public class DeliveryImpl implements Delivery, EnvironmentObserver {

    private final DeliveryId id;
    private DeliveryDetail deliveryDetail;
    private MutableDeliveryStatus deliveryStatus;
    private final List<DeliveryObserver> observers;
    private final DroneEnvironment droneEnvironment;

    public DeliveryImpl(final DroneEnvironment droneEnvironment, final DeliveryId deliveryId) {
        this.droneEnvironment = droneEnvironment;
        this.id = deliveryId;
        this.observers = new ArrayList<>();
    }

    public DeliveryImpl(
            final DroneEnvironment droneEnvironment,
            final DeliveryId deliveryId,
            final double weight,
            final Address startingPlace,
            final Address destinationPlace,
            final Optional<Calendar> expectedShippingMoment,
            final List<DeliveryObserver> observers
    ) {
        this.droneEnvironment =  droneEnvironment;
        this.id = deliveryId;
        this.deliveryDetail = new DeliveryDetailImpl(this.id, weight, startingPlace, destinationPlace,
                expectedShippingMoment.orElseGet(TimeConverter::getNowAsCalendar));
        this.deliveryStatus = new DeliveryStatusImpl(this.id);
        this.observers = new ArrayList<>(observers);
        this.observers.forEach(obs ->
                obs.notifyDeliveryEvent(new DeliveryCreated(this.id, this.deliveryDetail)));
        this.startDeliveringProcess();
    }

    @Override
    public DeliveryDetail getDeliveryDetail() {
        return this.deliveryDetail;
    }

    @Override
    public DeliveryStatus getDeliveryStatus() {
        return this.deliveryStatus;
    }

    @Override
    public synchronized void addDeliveryObserver(final DeliveryObserver observer) {
        this.observers.add(observer);
    }

    @Override
    public synchronized void removeDeliveryObserver(final DeliveryObserver observer) {
        this.observers.remove(observer);
    }

    @Override
    public void applyEvent(final DeliveryEvent event) {
        switch (event) {
            case DeliveryCreated deliveryCreated -> {
                this.deliveryDetail = deliveryCreated.deliveryDetail();
                this.deliveryStatus = new DeliveryStatusImpl(event.id());
            }
            case Shipped shipped -> {
                this.deliveryStatus.setDeliveryState(DeliveryState.SHIPPING);
                this.deliveryStatus.setTimeLeft(shipped.timeLeft());
            }
            case TimeElapsed timeElapsed -> this.deliveryStatus.subDeliveryTime(timeElapsed.time());
            case Delivered delivered -> this.deliveryStatus.setDeliveryState(DeliveryState.DELIVERED);
            default -> throw new IllegalArgumentException("Event type not supported");
        }
    }

    @Override
    public DeliveryId getId() {
        return this.id;
    }

    @Override
    public synchronized void notifyEvent(final EnvironmentEvent event) {
        if (event instanceof DeliveryEvent) {
            this.applyEvent((DeliveryEvent) event);
            this.observers.forEach(obs -> obs.notifyDeliveryEvent((DeliveryEvent) event));
        }
    }

    @Override
    public void startDeliveringProcess() {
        Optional<DeliveryTime> deliveryTime = Optional.empty();
        try {
            deliveryTime = Optional.of(this.deliveryStatus.getTimeLeft());
        } catch (final DeliveryNotShippedYetException ignored) {
        }
        final DroneAgent drone = new DroneAgent(this.droneEnvironment, this.deliveryDetail, deliveryTime);
        this.droneEnvironment.register(this);
        this.droneEnvironment.register(drone);
        Thread.ofVirtual().start(() -> {
            try {
                if (TimeConverter.getZonedDateTime(this.deliveryDetail.expectedShippingMoment())
                        .isAfter(TimeConverter.getNowAsZonedDateTime())) {
                    Thread.sleep(TimeConverter.getNowAsZonedDateTime().until(
                            TimeConverter.getZonedDateTime(this.deliveryDetail.expectedShippingMoment()),
                            ChronoUnit.MILLIS
                    ));
                }
                drone.startDrone();
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
