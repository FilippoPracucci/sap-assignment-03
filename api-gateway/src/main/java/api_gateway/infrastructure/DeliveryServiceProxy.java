package api_gateway.infrastructure;

import api_gateway.application.*;
import api_gateway.domain.*;
import common.hexagonal.Adapter;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Adapter
public class DeliveryServiceProxy implements DeliveryServiceVertx {

    private static final Logger logger = Logger.getLogger("[DeliveryServiceProxy]");

    /* Static channels */
    private static final String GET_DELIVERY_DETAIL_REQUESTS_EVC = "get-delivery-detail-requests";
    private static final String GET_DELIVERY_STATUS_REQUESTS_EVC = "get-delivery-status-requests";
    private static final String STOP_DELIVERY_TRACKING_REQUESTS_EVC = "stop-tracking-requests";

    /* Dynamic channels */
    private static final String GET_DELIVERY_DETAIL_REQUESTS_APPROVED_EVC = "get-delivery-{id}-detail-requests-approved";
    private static final String GET_DELIVERY_DETAIL_REQUESTS_REJECTED_EVC = "get-delivery-{id}-detail-requests-rejected";
    private static final String GET_DELIVERY_STATUS_REQUESTS_APPROVED_EVC = "get-delivery-{id}-status-requests-approved";
    private static final String GET_DELIVERY_STATUS_REQUESTS_REJECTED_EVC = "get-delivery-{id}-status-requests-rejected";
    private static final String STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "stop-tracking-{id}-requests-approved";
    private static final String STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "stop-tracking-{id}-requests-rejected";
    private static final String DELIVERY_TRACKING_EXTERNAL_EVC = "delivery-tracking-{id}-external-events";

    private final OutputEventChannel getDeliveryDetailRequests;
    private final Map<String, Promise<DeliveryDetail>> deliveryDetails = new HashMap<>();
    private final OutputEventChannel getDeliveryStatusRequests;
    private final Map<String, Promise<DeliveryStatus>> deliveryStatus = new HashMap<>();
    private final OutputEventChannel stopDeliveryTrackingRequests;
    private final Map<String, Promise<Void>> stopDeliveryTrackingResults = new HashMap<>();
    private final Vertx vertx;
    private final String evChannelsLocation;
    private int count = 0;

    public DeliveryServiceProxy(final Vertx vertx, final String evChannelsLocation)  {
        this.vertx = vertx;
        this.evChannelsLocation = evChannelsLocation;
        this.getDeliveryDetailRequests = new OutputEventChannel(this.vertx, GET_DELIVERY_DETAIL_REQUESTS_EVC,
                this.evChannelsLocation);
        this.getDeliveryStatusRequests = new OutputEventChannel(this.vertx, GET_DELIVERY_STATUS_REQUESTS_EVC,
                this.evChannelsLocation);
        this.stopDeliveryTrackingRequests = new OutputEventChannel(this.vertx, STOP_DELIVERY_TRACKING_REQUESTS_EVC,
                this.evChannelsLocation);
    }

    @Override
    public DeliveryDetail getDeliveryDetail(final DeliveryId deliveryId) throws DeliveryNotFoundException,
            ServiceNotAvailableException {
        this.count++;
        final String requestId = "api-gateway-" + this.count;
        final JsonObject getDeliveryDetailEvent = new JsonObject();
        getDeliveryDetailEvent.put("deliveryId", deliveryId.id());
        getDeliveryDetailEvent.put("requestId", requestId);
        try {
            final Promise<DeliveryDetail> promise = Promise.promise();
            this.deliveryDetails.put(requestId, promise);
            new InputEventChannel(
                    this.vertx,
                    GET_DELIVERY_DETAIL_REQUESTS_APPROVED_EVC.replace("{id}", deliveryId.id()),
                    this.evChannelsLocation
            ).init(this::getDeliveryDetailRequestApproved);
            new InputEventChannel(
                    this.vertx,
                    GET_DELIVERY_DETAIL_REQUESTS_REJECTED_EVC.replace("{id}", deliveryId.id()),
                    this.evChannelsLocation
            ).init(this::getDeliveryDetailRequestRejected);
            this.getDeliveryDetailRequests.postEvent(getDeliveryDetailEvent);
            return promise.future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            this.deliveryDetails.remove(requestId);
            final Throwable cause = e.getCause();
            if (cause instanceof DeliveryNotFoundException) {
                throw (DeliveryNotFoundException) cause;
            }
            throw (IllegalStateException) cause;
        } catch (final Exception e) {
            this.deliveryDetails.remove(requestId);
            throw new ServiceNotAvailableException();
        }
    }

    private void getDeliveryDetailRequestApproved(final JsonObject event) {
        logger.info("getDeliveryDetailRequestApproved");
        final String requestId = event.getString("requestId");
        this.deliveryDetails.get(requestId)
                .complete(DeliveryJsonConverter.fromJsonToDeliveryDetail(event.getJsonObject("deliveryDetail")));
    }

    private void getDeliveryDetailRequestRejected(final JsonObject event) {
        logger.info("getDeliveryDetailRequestRejected");
        final String requestId = event.getString("requestId");
        final String error = event.getString("error");
        logger.severe(error);
        if (error.equals(new DeliveryNotFoundException().getMessage())) {
            this.deliveryDetails.get(requestId).fail(new DeliveryNotFoundException());
        } else {
            this.deliveryDetails.get(requestId).fail(new IllegalStateException(error));
        }
    }

    @Override
    public DeliveryStatus getDeliveryStatus(final DeliveryId deliveryId, final String trackingSessionId)
            throws DeliveryNotFoundException, TrackingSessionNotFoundException, ServiceNotAvailableException {
        this.count++;
        final String requestId = "api-gateway-" + this.count;
        final JsonObject getDeliveryStatusEvent = new JsonObject();
        getDeliveryStatusEvent.put("deliveryId", deliveryId.id());
        getDeliveryStatusEvent.put("trackingSessionId", trackingSessionId);
        getDeliveryStatusEvent.put("requestId", requestId);
        try {
            final Promise<DeliveryStatus> promise = Promise.promise();
            this.deliveryStatus.put(requestId, promise);
            new InputEventChannel(
                    this.vertx,
                    GET_DELIVERY_STATUS_REQUESTS_APPROVED_EVC.replace("{id}", trackingSessionId),
                    this.evChannelsLocation
            ).init(this::getDeliveryStatusRequestApproved);
            new InputEventChannel(
                    this.vertx,
                    GET_DELIVERY_STATUS_REQUESTS_REJECTED_EVC.replace("{id}", trackingSessionId),
                    this.evChannelsLocation
            ).init(this::getDeliveryStatusRequestRejected);
            this.getDeliveryStatusRequests.postEvent(getDeliveryStatusEvent);
            return promise.future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            this.deliveryStatus.remove(requestId);
            final Throwable cause = e.getCause();
            if (cause instanceof DeliveryNotFoundException) {
                throw (DeliveryNotFoundException) cause;
            } else if (cause instanceof TrackingSessionNotFoundException) {
                throw (TrackingSessionNotFoundException) cause;
            }
            throw (IllegalStateException) cause;
        } catch (final Exception e) {
            this.deliveryStatus.remove(requestId);
            throw new ServiceNotAvailableException();
        }
    }

    private void getDeliveryStatusRequestApproved(final JsonObject event) {
        logger.info("getDeliveryStatusRequestApproved");
        final String requestId = event.getString("requestId");
        this.deliveryStatus.get(requestId)
                .complete(DeliveryJsonConverter.fromJsonToDeliveryStatus(event.getJsonObject("deliveryStatus")));
    }

    private void getDeliveryStatusRequestRejected(final JsonObject event) {
        logger.info("getDeliveryStatusRequestRejected");
        final String requestId = event.getString("requestId");
        final String error = event.getString("error");
        logger.severe(error);
        if (error.equals(new DeliveryNotFoundException().getMessage())) {
            this.deliveryStatus.get(requestId).fail(new DeliveryNotFoundException());
        } else if (error.equals("tracking-session-not-found")) {
            this.deliveryStatus.get(requestId).fail(new TrackingSessionNotFoundException(error));
        } else {
            this.deliveryStatus.get(requestId).fail(new IllegalStateException(error));
        }
    }

    @Override
    public void stopTrackingDelivery(final DeliveryId deliveryId, final String trackingSessionId)
            throws DeliveryNotFoundException, TrackingSessionNotFoundException, ServiceNotAvailableException {
        this.count++;
        final String requestId = "api-gateway-" + this.count;
        final JsonObject stopDeliveryTrackingEvent = new JsonObject();
        stopDeliveryTrackingEvent.put("deliveryId", deliveryId.id());
        stopDeliveryTrackingEvent.put("trackingSessionId", trackingSessionId);
        stopDeliveryTrackingEvent.put("requestId", requestId);
        try {
            final Promise<Void> promise = Promise.promise();
            this.stopDeliveryTrackingResults.put(requestId, promise);
            new InputEventChannel(
                    this.vertx,
                    STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC.replace("{id}", trackingSessionId),
                    this.evChannelsLocation
            ).init(this::onStopDeliveryTrackingRequestApproved);
            new InputEventChannel(
                    this.vertx,
                    STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC.replace("{id}", trackingSessionId),
                    this.evChannelsLocation
            ).init(this::onStopDeliveryTrackingRequestRejected);
            this.stopDeliveryTrackingRequests.postEvent(stopDeliveryTrackingEvent);
            promise.future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            this.stopDeliveryTrackingResults.remove(requestId);
            final Throwable cause = e.getCause();
            if (cause instanceof DeliveryNotFoundException) {
                throw (DeliveryNotFoundException) cause;
            } else {
                throw (TrackingSessionNotFoundException) cause;
            }
        } catch (final Exception e) {
            this.stopDeliveryTrackingResults.remove(requestId);
            throw new ServiceNotAvailableException();
        }
    }

    private void onStopDeliveryTrackingRequestApproved(final JsonObject event) {
        logger.info("onStopDeliveryTrackingRequestsApproved");
        final String requestId = event.getString("requestId");
        this.stopDeliveryTrackingResults.get(requestId).complete();
    }

    private void onStopDeliveryTrackingRequestRejected(final JsonObject event) {
        logger.info("onStopDeliveryTrackingRequestRejected");
        final String requestId = event.getString("requestId");
        final String error = event.getString("error");
        logger.severe(error);
        if (error.equals(new DeliveryNotFoundException().getMessage())) {
            this.stopDeliveryTrackingResults.get(requestId).fail(new DeliveryNotFoundException());
        } else {
            this.stopDeliveryTrackingResults.get(requestId).fail(new TrackingSessionNotFoundException(error));
        }
    }

    @Override
    public void createAnEventChannel(final String trackingSessionId, final Vertx vertx) {
        new InputEventChannel(
                this.vertx,
                DELIVERY_TRACKING_EXTERNAL_EVC.replace("{id}", trackingSessionId),
                this.evChannelsLocation
        ).init(e -> vertx.eventBus().publish(trackingSessionId, e));
    }
}
