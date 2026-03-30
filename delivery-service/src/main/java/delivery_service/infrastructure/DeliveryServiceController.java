package delivery_service.infrastructure;

import delivery_service.application.*;
import delivery_service.domain.*;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;

import java.util.Calendar;
import java.util.Optional;
import java.util.logging.Logger;

/**
*
* Delivery Service controller
*
*/
public class DeliveryServiceController extends VerticleBase  {

	private final String evChannelsLocation;
	static Logger logger = Logger.getLogger("[Delivery Service Controller]");

	/* Health check endpoint */
	static final String HEALTH_CHECK_ENDPOINT = "/api/v1/health";

	/* Static channels */
	static final String CREATE_DELIVERY_REQUESTS_EVC = "create-delivery-requests";
	static final String CREATE_DELIVERY_REQUESTS_APPROVED_EVC = "create-delivery-requests-approved";
	static final String CREATE_DELIVERY_REQUESTS_REJECTED_EVC = "create-delivery-requests-rejected";
	static final String NEW_DELIVERY_CREATED_EVC = "new-delivery-created";
	static final String STOP_DELIVERY_TRACKING_REQUESTS_EVC = "stop-tracking-requests";

	/* Dynamic channels */
	static final String DELIVERY_TRACKING_REQUESTS_EVC = "delivery-{id}-tracking-requests";
	static final String DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "delivery-{id}-tracking-requests-approved";
	static final String DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "delivery-{id}-tracking-requests-rejected";
	static final String STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "stop-tracking-{id}-requests-approved";
	static final String STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "stop-tracking-{id}-requests-rejected";
	static final String TRACKING_DELIVERY_EVC = "tracking-delivery-{id}-events";
	
	/* Ref. to the application layer */
	private final DeliveryService deliveryService;

    private OutputEventChannel createDeliveryRequestsApproved;
	private OutputEventChannel createDeliveryRequestsRejected;
	private OutputEventChannel newDeliveryCreated;

	public DeliveryServiceController(final DeliveryService deliveryService, final String evChannelsLocation) {
		this.deliveryService = deliveryService;
		this.evChannelsLocation = evChannelsLocation;
	}

	public Future<?> start() {
		logger.info("Delivery Service initializing...");
        final InputEventChannel createDeliveryRequests = new InputEventChannel(vertx, CREATE_DELIVERY_REQUESTS_EVC,
                this.evChannelsLocation);
		this.createDeliveryRequestsApproved = new OutputEventChannel(vertx, CREATE_DELIVERY_REQUESTS_APPROVED_EVC,
				this.evChannelsLocation);
		this.createDeliveryRequestsRejected = new OutputEventChannel(vertx, CREATE_DELIVERY_REQUESTS_REJECTED_EVC,
				this.evChannelsLocation);
		this.newDeliveryCreated = new OutputEventChannel(vertx, NEW_DELIVERY_CREATED_EVC, evChannelsLocation);
		new InputEventChannel(this.vertx, STOP_DELIVERY_TRACKING_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::stopTrackingDelivery);
		createDeliveryRequests.init(this::createNewDelivery);
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
	 * @param context
	 */
	protected void getDeliveryDetail(final RoutingContext context) {
		logger.info("get delivery detail");
		context.request().endHandler(h -> {
			final DeliveryId deliveryId = new DeliveryId(context.pathParam("deliveryId"));
			var reply = new JsonObject();
			try {
				reply.put("result", "ok");
				reply.put(
						"deliveryDetail",
						DeliveryJsonConverter.toJson(this.deliveryService.getDeliveryDetail(deliveryId))
				);
				sendReply(context.response(), reply);
			} catch (final DeliveryNotFoundException ex) {
				reply.put("result", "error");
				reply.put("error", ex.getMessage());
				sendReply(context.response(), reply);
			} catch (Exception ex) {
				sendError(context.response());
			}
		});
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
			final TrackingSession trackingSession = this.deliveryService.trackDelivery(new DeliveryId(deliveryId),
					new KafkaTrackingSessionEventObserver());
			final JsonObject evTrackingDeliveryApproved = new JsonObject();
			evTrackingDeliveryApproved.put("trackingSessionId", trackingSession.getId());
			evTrackingDeliveryApproved.put("requestId", requestId);
			deliveryTrackingRequestsApproved.postEvent(evTrackingDeliveryApproved)
					.onSuccess(v -> {
						logger.info("Track delivery request approved");
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
	 * @param context
	 */
	protected void getDeliveryStatus(final RoutingContext context) {
		logger.info("GetDeliveryStatus request - " + context.currentRoute().getPath());
		context.request().endHandler(h -> {
			final JsonObject reply = new JsonObject();
			final DeliveryId deliveryId = new DeliveryId(context.pathParam("deliveryId"));
			final String trackingSessionId = context.pathParam("trackingSessionId");
			try {
				final DeliveryStatus deliveryStatus = this.deliveryService.getDeliveryStatus(deliveryId,
						trackingSessionId);
				reply.put("result", "ok");
				final JsonObject deliveryJson = new JsonObject();
				deliveryJson.put("deliveryId", deliveryId.id());
				deliveryJson.put("deliveryState", deliveryStatus.getState().getLabel());
				if (deliveryStatus.isTimeLeftAvailable()) {
					deliveryJson.put("timeLeft", deliveryStatus.getTimeLeft().days() + " days left");
				}
				reply.put("deliveryStatus", deliveryJson);
				sendReply(context.response(), reply);
			} catch (final DeliveryNotFoundException ex) {
				reply.put("result", "error");
				reply.put("error", ex.getMessage());
				sendReply(context.response(), reply);
			} catch (final TrackingSessionNotFoundException ex) {
				reply.put("result", "error");
				reply.put("error", "tracking-session-not-present");
				sendReply(context.response(), reply);
			} catch (Exception ex) {
				logger.info(ex.getClass().toString());
				sendError(context.response());
			}
		});
	}


	/* Handling subscribers using web sockets */
	
	protected void handleEventSubscription(final HttpServer server, final String path) {
		server.webSocketHandler(webSocket -> {
			if (webSocket.path().equals(path)) {
				logger.info("New subscription accepted.");

				/*
				 *
				 * Receiving a first message including the id of the delivery
				 * to observe
				 *
				 */
				webSocket.textMessageHandler(openMsg -> {
					logger.info("For delivery: " + openMsg);
					JsonObject obj = new JsonObject(openMsg);
					final String trackingSessionId = obj.getString("trackingSessionId");


					/*
					 * Subscribing events on the event bus to receive
					 * events concerning the delivery, to be notified
					 * to the frontend using the websocket
					 *
					 */
					EventBus eventBus = this.vertx.eventBus();

					eventBus.consumer(trackingSessionId, msg -> {
						final JsonObject event = (JsonObject) msg.body();
						logger.info("Event: " + event.encodePrettily());
						webSocket.writeTextMessage(event.encodePrettily());
					});

					try {
						final TrackingSession trackingSession = this.deliveryService.getTrackingSession(trackingSessionId);
						trackingSession.getTrackingSessionEventNotifier().enableEventNotification(trackingSessionId);
					} catch (final TrackingSessionNotFoundException e) {
						throw new RuntimeException(e);
					}
				});
			}
		});
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
