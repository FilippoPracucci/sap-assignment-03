package delivery_service.infrastructure;

import delivery_service.application.*;
import delivery_service.domain.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;

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
	private static Logger logger = Logger.getLogger("[Delivery Service Controller]");

	/* Health check endpoint */
	private static final String HEALTH_CHECK_ENDPOINT = "/api/v1/health";

	/* Static channels */
	private static final String CREATE_DELIVERY_REQUESTS_EVC = "create-delivery-requests";
	private static final String CREATE_DELIVERY_REQUESTS_APPROVED_EVC = "create-delivery-requests-approved";
	private static final String CREATE_DELIVERY_REQUESTS_REJECTED_EVC = "create-delivery-requests-rejected";
	private static final String NEW_DELIVERY_CREATED_EVC = "new-delivery-created";
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_EVC = "stop-tracking-requests";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_EVC = "get-delivery-detail-requests";
	private static final String GET_DELIVERY_STATUS_REQUESTS_EVC = "get-delivery-status-requests";

	/* Dynamic channels */
	private static final String DELIVERY_TRACKING_REQUESTS_EVC = "delivery-{id}-tracking-requests";
	private static final String DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "delivery-{id}-tracking-requests-approved";
	private static final String DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "delivery-{id}-tracking-requests-rejected";
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "stop-tracking-{id}-requests-approved";
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "stop-tracking-{id}-requests-rejected";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_APPROVED_EVC = "get-delivery-{id}-detail-requests-approved";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_REJECTED_EVC = "get-delivery-{id}-detail-requests-rejected";
	private static final String GET_DELIVERY_STATUS_REQUESTS_APPROVED_EVC = "get-delivery-{id}-status-requests-approved";
	private static final String GET_DELIVERY_STATUS_REQUESTS_REJECTED_EVC = "get-delivery-{id}-status-requests-rejected";
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
		new InputEventChannel(this.vertx, STOP_DELIVERY_TRACKING_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::stopTrackingDelivery);
		new InputEventChannel(this.vertx, GET_DELIVERY_DETAIL_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::getDeliveryDetail);
		new InputEventChannel(this.vertx, GET_DELIVERY_STATUS_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::getDeliveryStatus);
		return Promise.promise().future();
	}

	protected void healthCheckHandler(final RoutingContext context) {
		logger.info("Health check request " + context.currentRoute().getPath());
		final JsonObject reply = new JsonObject();
		reply.put("status", "UP");
		sendReply(context.response(), reply);
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
	 * Get delivery detail
	 *
	 */
	protected void getDeliveryDetail(final JsonObject getDeliveryDetailEvent) {
		logger.info("get delivery detail");
		final  String requestId = getDeliveryDetailEvent.getString("requestId");
		final String deliveryId = getDeliveryDetailEvent.getString("deliveryId");
		final OutputEventChannel getDeliveryDetailRequestsApproved = new OutputEventChannel(
				this.vertx,
				replaceWithId(GET_DELIVERY_DETAIL_REQUESTS_APPROVED_EVC, deliveryId),
				this.evChannelsLocation
		);
		final OutputEventChannel getDeliveryDetailRequestsRejected = new OutputEventChannel(
				this.vertx,
				replaceWithId(GET_DELIVERY_DETAIL_REQUESTS_REJECTED_EVC, deliveryId),
				this.evChannelsLocation
		);
		try {
			final JsonObject evGetDeliveryDetailApproved = new JsonObject();
			evGetDeliveryDetailApproved.put("deliveryDetail",
					DeliveryJsonConverter.toJson(this.deliveryService.getDeliveryDetail(new DeliveryId(deliveryId))));
			evGetDeliveryDetailApproved.put("requestId", requestId);
			getDeliveryDetailRequestsApproved.postEvent(evGetDeliveryDetailApproved)
					.onSuccess(v -> logger.info("Get delivery detail request approved"))
					.onFailure(v -> logger.info("Get delivery detail request approval failed"));
		} catch (final Exception e) {
			final JsonObject evGetDeliveryDetailRejected = new JsonObject();
			evGetDeliveryDetailRejected.put("requestId", requestId);
			if (e instanceof DeliveryNotFoundException) {
				evGetDeliveryDetailRejected.put("error", e.getMessage());
			} else {
				evGetDeliveryDetailRejected.put("error", "get-delivery-detail-error");
			}
			getDeliveryDetailRequestsRejected.postEvent(evGetDeliveryDetailRejected)
					.onSuccess(v -> logger.info("Get delivery detail request rejected"))
					.onFailure(v -> logger.info("Get delivery detail request reject failed"));
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
			final String trackingSessionId = this.deliveryService.trackDelivery(new DeliveryId(deliveryId),
					new KafkaTrackingSessionEventObserver()).getId();
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
			if (e instanceof DeliveryNotFoundException) {
				evTrackingDeliveryRejected.put("error", e.getMessage());
			} else {
				evTrackingDeliveryRejected.put("error", "tracking-error");
			}
			deliveryTrackingRequestsRejected.postEvent(evTrackingDeliveryRejected)
					.onSuccess(v -> logger.info("Track delivery request rejected"))
					.onFailure(v -> logger.info("Track delivery request reject failed"));
        }
	}

	protected void stopTrackingDelivery(final JsonObject stopTrackingDeliveryEvent) {
		logger.info("Stop tracking delivery request");
		final  String requestId = stopTrackingDeliveryEvent.getString("requestId");
		final String deliveryId = stopTrackingDeliveryEvent.getString("deliveryId");
		final String trackingSessionId = stopTrackingDeliveryEvent.getString("trackingSessionId");
		final OutputEventChannel stopTrackingDeliveryRequestsApproved = new OutputEventChannel(
				this.vertx,
				replaceWithId(STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC, trackingSessionId),
				this.evChannelsLocation
		);
		final OutputEventChannel stopTrackingDeliveryRequestsRejected = new OutputEventChannel(
				this.vertx,
				replaceWithId(STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC, trackingSessionId),
				this.evChannelsLocation
		);
		try {
			this.deliveryService.stopTrackingDelivery(new DeliveryId(deliveryId), trackingSessionId);
			final JsonObject evTrackingDeliveryApproved = new JsonObject();
			evTrackingDeliveryApproved.put("requestId", requestId);
			stopTrackingDeliveryRequestsApproved.postEvent(evTrackingDeliveryApproved)
							.onSuccess(v -> logger.info("Stop tracking delivery request approved"))
							.onFailure(v -> logger.info("Stop tracking delivery request approval failed"));
		} catch (final Exception e) {
			final JsonObject evTrackingDeliveryRejected = new JsonObject();
			evTrackingDeliveryRejected.put("requestId", requestId);
			if (e instanceof DeliveryNotFoundException) {
				evTrackingDeliveryRejected.put("error", e.getMessage());
			} else if (e instanceof TrackingSessionNotFoundException) {
				evTrackingDeliveryRejected.put("error", "tracking-session-not-found");
			} else {
				evTrackingDeliveryRejected.put("error", "stop-tracking-error");
			}
			stopTrackingDeliveryRequestsRejected.postEvent(evTrackingDeliveryRejected)
					.onSuccess(v -> logger.info("Stop tracking delivery request rejected"))
					.onFailure(v -> logger.info("Stop tracking delivery request reject failed"));
		}
	}
	
	/**
	 * 
	 * Get delivery status - by users tracking a delivery (with a TrackingSession)
	 *
	 */
	protected void getDeliveryStatus(final JsonObject getDeliveryStatusEvent) {
		logger.info("GetDeliveryStatus request");
		final  String requestId = getDeliveryStatusEvent.getString("requestId");
		final String deliveryId = getDeliveryStatusEvent.getString("deliveryId");
		final String trackingSessionId = getDeliveryStatusEvent.getString("trackingSessionId");
		final OutputEventChannel getDeliveryStatusRequestsApproved = new OutputEventChannel(
				this.vertx,
				replaceWithId(GET_DELIVERY_STATUS_REQUESTS_APPROVED_EVC, trackingSessionId),
				this.evChannelsLocation
		);
		final OutputEventChannel getDeliveryStatusRequestsRejected = new OutputEventChannel(
				this.vertx,
				replaceWithId(GET_DELIVERY_STATUS_REQUESTS_REJECTED_EVC, trackingSessionId),
				this.evChannelsLocation
		);
		try {
			final JsonObject evGetDeliveryStatusApproved = new JsonObject();
			evGetDeliveryStatusApproved.put(
					"deliveryStatus",
					DeliveryJsonConverter.toJson(
							this.deliveryService.getDeliveryStatus(new DeliveryId(deliveryId), trackingSessionId)
					)
			);
			evGetDeliveryStatusApproved.put("requestId", requestId);
			getDeliveryStatusRequestsApproved.postEvent(evGetDeliveryStatusApproved)
					.onSuccess(v -> logger.info("Get delivery status request approved"))
					.onFailure(v -> logger.info("Get delivery status request approval failed"));
		} catch (final Exception e) {
			final JsonObject evGetDeliveryStatusRejected = new JsonObject();
			evGetDeliveryStatusRejected.put("requestId", requestId);
            switch (e) {
                case DeliveryNotFoundException deliveryNotFoundException ->
                        evGetDeliveryStatusRejected.put("error", e.getMessage());
                case TrackingSessionNotFoundException trackingSessionNotFoundException ->
                        evGetDeliveryStatusRejected.put("error", "tracking-session-not-found");
                case DeliveryNotShippedYetException deliveryNotShippedYetException ->
                        evGetDeliveryStatusRejected.put("error", "delivery-not-shipped-yet");
                default -> evGetDeliveryStatusRejected.put("error", "get-delivery-status-error");
            }
			getDeliveryStatusRequestsRejected.postEvent(evGetDeliveryStatusRejected)
					.onSuccess(v -> logger.info("Get delivery status request rejected"))
					.onFailure(v -> logger.info("Get delivery status request reject failed"));
		}
	}

	/* Handling delivery tracking events using kafka */
	protected void handleDeliveryTrackingEvents(final JsonObject deliveryEvent, final String trackingSessionId) {
		this.deliveryTrackingExternalChannels.get(trackingSessionId)
				.postEvent(deliveryEvent)
				.onSuccess(v -> logger.info("Post delivery tracking event succeeded"))
				.onFailure(v -> logger.info("Post delivery tracking event failed"));
		/*try {
			final TrackingSession trackingSession = this.deliveryService.getTrackingSession(trackingSessionId);
			trackingSession.getTrackingSessionEventNotifier().enableEventNotification(trackingSessionId);
		} catch (final TrackingSessionNotFoundException e) {
			throw new RuntimeException(e);
		}*/
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
	
	/* Aux methods */

	private void sendReply(final HttpServerResponse response, final JsonObject reply) {
		response.putHeader("content-type", "application/json");
		response.end(reply.toString());
	}
	
	private void sendError(final HttpServerResponse response) {
		response.setStatusCode(500);
		response.putHeader("content-type", "application/json");
		response.end();
	}


}
