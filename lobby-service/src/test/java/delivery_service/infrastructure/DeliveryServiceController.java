package delivery_service.infrastructure;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;
import lobby_service.application.DeliveryService;
import lobby_service.application.TrackDeliveryFailedException;
import lobby_service.domain.DeliveryId;
import lobby_service.domain.TimeConverter;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
*
* Delivery Service controller
*
*/
public class DeliveryServiceController extends VerticleBase  {

	private final String evChannelsLocation;
	private static final Logger logger = Logger.getLogger("[Delivery Service Controller]");

	/* Static channels */
	private static final String CREATE_DELIVERY_REQUESTS_EVC = "create-delivery-requests";
	private static final String CREATE_DELIVERY_REQUESTS_APPROVED_EVC = "create-delivery-requests-approved";
	private static final String CREATE_DELIVERY_REQUESTS_REJECTED_EVC = "create-delivery-requests-rejected";
	private static final String NEW_DELIVERY_CREATED_EVC = "new-delivery-created";

	/* Dynamic channels */
	private static final String DELIVERY_TRACKING_REQUESTS_EVC = "delivery-{id}-tracking-requests";
	private static final String DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "delivery-{id}-tracking-requests-approved";
	private static final String DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "delivery-{id}-tracking-requests-rejected";
	private static final String DELIVERY_TRACKING_INTERNAL_EVC = "delivery-tracking-{id}-internal-events";
	private static final String DELIVERY_TRACKING_EXTERNAL_EVC = "delivery-tracking-{id}-external-events";

	/* Ref. to the application layer */
	private final DeliveryService deliveryService;

	private OutputEventChannel createDeliveryRequestsApproved;
	private OutputEventChannel createDeliveryRequestsRejected;
	private OutputEventChannel newDeliveryCreated;
	private final Map<String, OutputEventChannel> deliveryTrackingExternalChannels = new HashMap<>();

	public DeliveryServiceController(final DeliveryService deliveryService, final String evChannelsLocation) {
		this.deliveryService = deliveryService;
		this.evChannelsLocation = evChannelsLocation;
	}

	public Future<?> start() {
		logger.info("Delivery Service initializing...");
		new InputEventChannel(this.vertx, CREATE_DELIVERY_REQUESTS_EVC,
				this.evChannelsLocation).init(this::createNewDelivery);
		this.createDeliveryRequestsApproved = new OutputEventChannel(this.vertx, CREATE_DELIVERY_REQUESTS_APPROVED_EVC,
				this.evChannelsLocation);
		this.createDeliveryRequestsRejected = new OutputEventChannel(this.vertx, CREATE_DELIVERY_REQUESTS_REJECTED_EVC,
				this.evChannelsLocation);
		this.newDeliveryCreated = new OutputEventChannel(this.vertx, NEW_DELIVERY_CREATED_EVC, this.evChannelsLocation);
		return Future.succeededFuture();
	}

	/**
	 *
	 * Create a New Delivery - by users logged in (with a UserSession)
	 *
	 */
	protected void createNewDelivery(final JsonObject createDeliveryEvent) {
		logger.info("CreateNewDelivery request");
		final String requestId = createDeliveryEvent.getString("requestId");
		try {
			final Optional<Calendar> expectedShippingMoment = DeliveryJsonConverter
					.getExpectedShippingMoment(createDeliveryEvent);
			if (expectedShippingMoment.isPresent() && TimeConverter.getZonedDateTime(expectedShippingMoment.get())
					.isBefore(TimeConverter.getNowAsZonedDateTime())
			) {
				this.postCreateDeliveryRequestRejected(requestId, "past-shipping-moment");
			} else if (expectedShippingMoment.isPresent()
					&& TimeConverter.getZonedDateTime(expectedShippingMoment.get()).isAfter(
					TimeConverter.getNowAsZonedDateTime().plusDays(14))
			) {
				this.postCreateDeliveryRequestRejected(requestId, "shipping-moment-too-far");
			} else {
				final String deliveryId = this.deliveryService.createNewDelivery(
						createDeliveryEvent.getNumber("weight").doubleValue(),
						DeliveryJsonConverter.getAddress(createDeliveryEvent, "startingPlace"),
						DeliveryJsonConverter.getAddress(createDeliveryEvent, "destinationPlace"),
						expectedShippingMoment
				).id();
				final JsonObject evDeliveryCreated = new JsonObject();
				evDeliveryCreated.put("deliveryId", deliveryId);
				this.newDeliveryCreated.postEvent(evDeliveryCreated)
						.onSuccess(v -> logger.info("Post event about new delivery succeeded"))
						.onFailure(v -> logger.info("Post event about new delivery failed"));
				final JsonObject evDeliveryApproved = new JsonObject();
				evDeliveryApproved.put("deliveryId", deliveryId);
				evDeliveryApproved.put("requestId", requestId);
				this.createDeliveryRequestsApproved.postEvent(evDeliveryApproved)
						.onSuccess(v -> {
							logger.info("Create delivery request approved");
							final InputEventChannel deliveryTrackingRequests = new InputEventChannel(vertx,
									replaceWithId(DELIVERY_TRACKING_REQUESTS_EVC, deliveryId), this.evChannelsLocation);
							final OutputEventChannel deliveryTrackingRequestsApproved = new OutputEventChannel(vertx,
									replaceWithId(DELIVERY_TRACKING_REQUESTS_APPROVED_EVC, deliveryId),
									this.evChannelsLocation);
							final OutputEventChannel deliveryTrackingRequestsRejected = new OutputEventChannel(vertx,
									replaceWithId(DELIVERY_TRACKING_REQUESTS_REJECTED_EVC, deliveryId),
									this.evChannelsLocation);
							deliveryTrackingRequests.init(e -> this.trackDelivery(e,
									deliveryTrackingRequestsApproved, deliveryTrackingRequestsRejected));
						});
			}
		} catch (final Exception e) {
			this.postCreateDeliveryRequestRejected(requestId, "create-delivery-error");
		}
	}

	/**
	 *
	 * Track a Delivery - by user logged in (with a UserSession)
	 *
	 * It creates a TrackingSession
	 *
	 */
	protected void trackDelivery(
			final JsonObject trackDeliveryEvent,
			final OutputEventChannel deliveryTrackingRequestsApproved,
			final OutputEventChannel deliveryTrackingRequestsRejected
	) {
		logger.info("TrackDelivery request");
		final String requestId = trackDeliveryEvent.getString("requestId");
		final String deliveryId = trackDeliveryEvent.getString("deliveryId");
		try {
			final String trackingSessionId = this.deliveryService.trackDelivery(new DeliveryId(deliveryId));
			final JsonObject evTrackingDeliveryApproved = new JsonObject();
			evTrackingDeliveryApproved.put("trackingSessionId", trackingSessionId);
			evTrackingDeliveryApproved.put("requestId", requestId);
			deliveryTrackingRequestsApproved.postEvent(evTrackingDeliveryApproved)
					.onSuccess(v -> {
						logger.info("Track delivery request approved");
						new InputEventChannel(
								this.vertx,
								replaceWithId(DELIVERY_TRACKING_INTERNAL_EVC, trackingSessionId),
								this.evChannelsLocation
						).init(e -> handleDeliveryTrackingEvents(e, trackingSessionId));
						this.deliveryTrackingExternalChannels.put(
								trackingSessionId,
								new OutputEventChannel(
										this.vertx,
										replaceWithId(DELIVERY_TRACKING_EXTERNAL_EVC, trackingSessionId),
										this.evChannelsLocation
								)
						);
					})
					.onFailure(v -> logger.info("Track delivery request approval failed"));
		} catch (final Exception e) {
			final JsonObject evTrackingDeliveryRejected = new JsonObject();
			evTrackingDeliveryRejected.put("requestId", requestId);
			if (e instanceof TrackDeliveryFailedException) {
				evTrackingDeliveryRejected.put("error", e.getMessage());
			} else {
				evTrackingDeliveryRejected.put("error", "tracking-error");
			}
			deliveryTrackingRequestsRejected.postEvent(evTrackingDeliveryRejected)
					.onSuccess(v -> logger.info("Track delivery request rejected"))
					.onFailure(v -> logger.info("Track delivery request reject failed"));
		}
	}

	/* Handling delivery tracking events using kafka */
	protected void handleDeliveryTrackingEvents(final JsonObject deliveryEvent, final String trackingSessionId) {
		this.deliveryTrackingExternalChannels.get(trackingSessionId)
				.postEvent(deliveryEvent)
				.onSuccess(v -> logger.info("Post delivery tracking event succeeded"))
				.onFailure(v -> logger.info("Post delivery tracking event failed"));
	}

	private String replaceWithId(final String channelName, final String id) {
		return channelName.replace("{id}", id);
	}

	private void postCreateDeliveryRequestRejected(final String requestId, final String message) {
		final JsonObject createDeliveryRequestRejected = new JsonObject();
		createDeliveryRequestRejected.put("requestId", requestId);
		createDeliveryRequestRejected.put("error", message);
		this.createDeliveryRequestsRejected.postEvent(createDeliveryRequestRejected)
				.onSuccess(v -> logger.info("Create delivery request rejected"))
				.onFailure(v -> logger.info("Create delivery request reject failed"));
	}
}
