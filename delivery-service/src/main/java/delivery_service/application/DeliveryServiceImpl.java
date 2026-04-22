package delivery_service.application;

import delivery_service.domain.*;
import delivery_service.domain.drone.DroneEnvironment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 
 * Implementation of the Delivery Service entry point at the application layer
 * 
 */
public class DeliveryServiceImpl implements DeliveryService, DeliveryObserver {
	static Logger logger = Logger.getLogger("[Delivery Service]");

    private DeliveryEventStore deliveryEventStore;
	private final Deliveries deliveries;
    private final TrackingSessions trackingSessionRepository;
	private final List<DeliveryServiceEventObserver> observers;
	private final DroneEnvironment droneEnvironment;

    public DeliveryServiceImpl() {
    	this.trackingSessionRepository = new TrackingSessions();
		this.deliveries = new Deliveries();
		this.observers = new ArrayList<>();
		this.droneEnvironment = new DroneEnvironment();
    }

	@Override
	public DeliveryDetail getDeliveryDetail(final DeliveryId deliveryId) throws DeliveryNotFoundException {
		logger.info("get delivery " + deliveryId + " detail");
		if (!this.deliveries.isPresent(deliveryId)) {
			throw new DeliveryNotFoundException();
		}
		return this.deliveries.getDelivery(deliveryId).getDeliveryDetail();
	}

	@Override
	public DeliveryStatus getDeliveryStatus(final DeliveryId deliveryId, final String trackingSessionId)
			throws DeliveryNotFoundException, TrackingSessionNotFoundException {
		logger.info("get delivery " + deliveryId.id() + " status");
		if (!this.deliveries.isPresent(deliveryId)) {
			throw new DeliveryNotFoundException();
		}
		if (!this.trackingSessionRepository.isPresent(trackingSessionId)) {
			throw new TrackingSessionNotFoundException();
		}
		return this.deliveries.getDelivery(deliveryId).getDeliveryStatus();
	}

	@Override
	public TrackingSession getTrackingSession(final String trackingSessionId) throws TrackingSessionNotFoundException {
		return this.trackingSessionRepository.getSession(trackingSessionId);
	}

	@Override
	public DeliveryId createNewDelivery(final double weight, final Address startingPlace,
										final Address destinationPlace, final Optional<Calendar> expectedShippingMoment) {
		final Delivery delivery = new DeliveryImpl(this.droneEnvironment, this.deliveryEventStore.getNextId(), weight, startingPlace,
				destinationPlace, expectedShippingMoment, List.of(this));
		logger.info("create New Delivery " + delivery.getId().id());
		this.deliveries.addDelivery(delivery);
		this.observers.forEach(obs -> {
			obs.notifyNewDeliveryCreated();
			delivery.addDeliveryObserver(obs);
		});
        return delivery.getId();
	}

	@Override
	public TrackingSession trackDelivery(final DeliveryId deliveryId, final TrackingSessionEventObserver observer)
			throws DeliveryNotFoundException {
		logger.info("Track delivery " + deliveryId);
		if (!this.deliveries.isPresent(deliveryId)) {
			throw new DeliveryNotFoundException();
		}
		final TrackingSession trackingSession = this.trackingSessionRepository.createSession();
		trackingSession.bindTrackingSessionEventNotifier(observer);
		this.deliveries.getDelivery(deliveryId).addDeliveryObserver(trackingSession);
		return trackingSession;
	}

	@Override
	public void stopTrackingDelivery(final DeliveryId deliveryId, final String trackingSessionId)
			throws DeliveryNotFoundException, TrackingSessionNotFoundException {
		logger.info("Stop tracking delivery " + deliveryId);
		if (!this.deliveries.isPresent(deliveryId)) {
			throw new DeliveryNotFoundException();
		}
		this.deliveries.getDelivery(deliveryId)
				.removeDeliveryObserver(this.trackingSessionRepository.getSession(trackingSessionId));
		this.trackingSessionRepository.removeSession(trackingSessionId);
	}

	@Override
	public void notifyDeliveryEvent(final DeliveryEvent event) {
		logger.info("DeliveryService: event " + event);
		this.deliveryEventStore.storeDeliveryEvent(event);
	}

	public void bindDeliveryRepository(final DeliveryEventStore store) {
		this.deliveryEventStore = store;
		this.deliveryEventStore.retrieveDeliveryEvents().forEach((deliveryId, events) -> {
			final Delivery delivery = new DeliveryImpl(this.droneEnvironment, deliveryId);
			events.forEach(delivery::applyEvent);
			if (!delivery.getDeliveryStatus().getState().equals(DeliveryState.DELIVERED)) {
				delivery.addDeliveryObserver(this);
				delivery.startDeliveringProcess();
			}
			this.deliveries.addDelivery(delivery);
		});
	}

	public void addObserver(final DeliveryServiceEventObserver obs) {
		this.observers.add(obs);
	}
}
