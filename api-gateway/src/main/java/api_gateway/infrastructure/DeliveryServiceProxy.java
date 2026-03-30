package api_gateway.infrastructure;

import api_gateway.application.*;
import api_gateway.domain.*;
import common.hexagonal.Adapter;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
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

    static Logger logger = Logger.getLogger("[DeliveryServiceProxy]");

    static final String STOP_DELIVERY_TRACKING_REQUESTS_EVC = "stop-tracking-requests";
    static final String STOP_DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "stop-tracking-{id}-requests-approved";
    static final String STOP_DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "stop-tracking-{id}-requests-rejected";

    private final OutputEventChannel stopDeliveryTrackingRequests;
    private final Map<String, Promise<Void>> stopDeliveryTrackingResults = new HashMap<>();
    private final Vertx vertx;
    private final String evChannelsLocation;
    private int count = 0;

    public DeliveryServiceProxy(final Vertx vertx, final String evChannelsLocation)  {
        this.vertx = vertx;
        this.evChannelsLocation = evChannelsLocation;
        this.stopDeliveryTrackingRequests = new OutputEventChannel(this.vertx, STOP_DELIVERY_TRACKING_REQUESTS_EVC,
                this.evChannelsLocation);
    }

    @Override
    public DeliveryDetail getDeliveryDetail(final DeliveryId deliveryId) throws DeliveryNotFoundException,
            ServiceNotAvailableException {
        return null;
        /*final HttpResponse<String> response = doGet("/api/v1/deliveries/" + deliveryId.id());
        if (response.statusCode() == 200) {
            this.incrementSuccessfulRequests();
            final JsonObject responseBody = new JsonObject(response.body());
            if (responseBody.getString("result").equals("error")) {
                throw new DeliveryNotFoundException();
            }
            final JsonObject deliveryDetail = responseBody.getJsonObject("deliveryDetail");
            return new DeliveryDetailImpl(
                    deliveryId,
                    deliveryDetail.getNumber("weight").doubleValue(),
                    DeliveryJsonConverter.getAddress(deliveryDetail,"startingPlace"),
                    DeliveryJsonConverter.getAddress(deliveryDetail,"destinationPlace"),
                    DeliveryJsonConverter.getExpectedShippingMoment(deliveryDetail)
                            .orElseThrow(RuntimeException::new)
            );
        } else {
            this.incrementFailedRequests();
            throw new ServiceNotAvailableException();
        }*/
    }

    @Override
    public DeliveryStatus getDeliveryStatus(final DeliveryId deliveryId, final String trackingSessionId)
            throws DeliveryNotFoundException, TrackingSessionNotFoundException, ServiceNotAvailableException {
        return null;
        /*final HttpResponse<String> response = doGet("/api/v1/deliveries/" + deliveryId.id()
                + "/" + trackingSessionId);
        if (response.statusCode() == 200) {
            this.incrementSuccessfulRequests();
            final JsonObject responseBody = new JsonObject(response.body());
            if (responseBody.getString("result").equals("error")) {
                if (responseBody.getString("error") != null
                        && responseBody.getString("error").equals("tracking-session-not-present")) {
                    throw new TrackingSessionNotFoundException();
                }
                throw new DeliveryNotFoundException();
            }
            final JsonObject deliveryStatus = responseBody.getJsonObject("deliveryStatus");
            return new DeliveryStatusImpl(
                    deliveryId,
                    DeliveryState.valueOfLabel(deliveryStatus.getString("deliveryState")),
                    deliveryStatus.containsKey("timeLeft")
                            ? Optional.of(new DeliveryTime(
                                Integer.parseInt(deliveryStatus.getString("timeLeft").split(" ")[0]), 0
                            ))
                            : Optional.empty()
            );
        } else {
            this.incrementFailedRequests();
            throw new ServiceNotAvailableException();
        }*/
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
        /*var eventBus = vertx.eventBus();
        vertx.createWebSocketClient()
                .connect(this.wsPort, this.wsAddress, "/api/v1/events")
                .onSuccess(ws -> {
                    System.out.println("Connected!");

                    ws.textMessageHandler(msg -> eventBus.publish(trackingSessionId, msg));

                    *//* first message *//*
                    final JsonObject obj = new JsonObject();
                    obj.put("trackingSessionId", trackingSessionId);
                    ws.writeTextMessage(obj.toString());
                })
                .onFailure(err -> eventBus.publish(trackingSessionId, "error"));*/
    }
}
