package delivery_service.infrastructure;

import api_gateway.application.DeliveryNotFoundException;
import api_gateway.application.TrackingSessionNotFoundException;
import api_gateway.domain.DeliveryId;
import api_gateway.domain.DeliveryNotShippedYetException;
import api_gateway.infrastructure.DeliveryServiceVertx;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

import java.util.logging.Logger;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;

/**
 *
 * Delivery Service controller
 *
 */
public class DeliveryServiceController extends VerticleBase  {

	private final String evChannelsLocation;
	private static final Logger logger = Logger.getLogger("[Delivery Service Controller]");

	/* Static channels */
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_EVC = "stop-tracking-requests";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_EVC = "get-delivery-detail-requests";
	private static final String GET_DELIVERY_STATUS_REQUESTS_EVC = "get-delivery-status-requests";

	/* Dynamic channels */
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "stop-tracking-{id}-requests-approved";
	private static final String STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "stop-tracking-{id}-requests-rejected";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_APPROVED_EVC = "get-delivery-{id}-detail-requests-approved";
	private static final String GET_DELIVERY_DETAIL_REQUESTS_REJECTED_EVC = "get-delivery-{id}-detail-requests-rejected";
	private static final String GET_DELIVERY_STATUS_REQUESTS_APPROVED_EVC = "get-delivery-{id}-status-requests-approved";
	private static final String GET_DELIVERY_STATUS_REQUESTS_REJECTED_EVC = "get-delivery-{id}-status-requests-rejected";

	/* Ref. to the application layer */
	private final DeliveryServiceVertx deliveryService;

	public DeliveryServiceController(final DeliveryServiceVertx deliveryService, final String evChannelsLocation) {
		this.deliveryService = deliveryService;
		this.evChannelsLocation = evChannelsLocation;
	}

	public Future<?> start() {
		logger.info("Delivery Service initializing...");
		new InputEventChannel(this.vertx, STOP_DELIVERY_TRACKING_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::stopTrackingDelivery);
		new InputEventChannel(this.vertx, GET_DELIVERY_DETAIL_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::getDeliveryDetail);
		new InputEventChannel(this.vertx, GET_DELIVERY_STATUS_REQUESTS_EVC, this.evChannelsLocation)
				.init(this::getDeliveryStatus);
		return Future.succeededFuture();
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

	private String replaceWithId(final String channelName, final String id) {
		return channelName.replace("{id}", id);
	}
}
