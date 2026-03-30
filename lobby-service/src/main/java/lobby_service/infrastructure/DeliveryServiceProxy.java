package lobby_service.infrastructure;

import common.hexagonal.Adapter;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import kafka.InputEventChannel;
import kafka.OutputEventChannel;
import lobby_service.domain.Address;
import io.vertx.core.json.JsonObject;
import lobby_service.application.*;
import lobby_service.domain.DeliveryId;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Adapter
public class DeliveryServiceProxy implements DeliveryService {

    static Logger logger = Logger.getLogger("[DeliveryServiceProxy]");

    /* Static channels */
    static final String CREATE_DELIVERY_REQUESTS_EVC = "create-delivery-requests";
    static final String CREATE_DELIVERY_REQUESTS_APPROVED_EVC = "create-delivery-requests-approved";
    static final String CREATE_DELIVERY_REQUESTS_REJECTED_EVC = "create-delivery-requests-rejected";
    //static final String NEW_DELIVERY_CREATED_EVC = "new-delivery-created";

    /* Dynamic channels */
    static final String DELIVERY_TRACKING_REQUESTS_EVC = "delivery-{id}-tracking-requests";
    static final String DELIVERY_TRACKING_REQUESTS_APPROVED_EVC = "delivery-{id}-tracking-requests-approved";
    static final String DELIVERY_TRACKING_REQUESTS_REJECTED_EVC = "delivery-{id}-tracking-requests-rejected";

    private final OutputEventChannel createDeliveryRequests;

    private final Map<String, OutputEventChannel> deliveryTrackingRequests = new HashMap<>();

    private final Map<String, Promise<DeliveryId>> deliveryIds = new HashMap<>();
    private final Map<String, Promise<String>> trackingSessionIds = new HashMap<>();

    private final Vertx vertx;
    private int count = 0;

    public DeliveryServiceProxy(final Vertx vertx, final String evChannelsLocation)  {
        final InputEventChannel createDeliveryRequestsApproved = new InputEventChannel(vertx,
                CREATE_DELIVERY_REQUESTS_APPROVED_EVC, evChannelsLocation);
        final InputEventChannel createDeliveryRequestsRejected = new InputEventChannel(vertx,
                CREATE_DELIVERY_REQUESTS_REJECTED_EVC, evChannelsLocation);
        this.vertx = vertx;
        this.createDeliveryRequests = new OutputEventChannel(vertx, CREATE_DELIVERY_REQUESTS_EVC, evChannelsLocation);
        createDeliveryRequestsApproved.init(e -> onCreateDeliveryRequestApproved(e, evChannelsLocation));
        createDeliveryRequestsRejected.init(this::onCreateDeliveryRequestRejected);
    }

    @Override
    public DeliveryId createNewDelivery(final double weight, final Address startingPlace,
                                        final Address destinationPlace, final Optional<Calendar> expectedShippingMoment)
            throws CreateDeliveryFailedException, ServiceNotAvailableException {
        this.count++;
        final String requestId = "lobby-" + this.count;
        logger.info("createNewDelivery: " + requestId);
        final JsonObject createDeliveryEvent = DeliveryJsonConverter.toJson(weight, startingPlace, destinationPlace,
                expectedShippingMoment);
        createDeliveryEvent.put("requestId", requestId);
        this.createDeliveryRequests.postEvent(createDeliveryEvent);

        final Promise<DeliveryId> promise = Promise.promise();
        this.deliveryIds.put(requestId, promise);
        try {
            return promise.future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            this.deliveryIds.remove(requestId);
            final Throwable cause = e.getCause();
            logger.info("CreateDeliveryRequestRejected with cause: " + cause.getMessage());
            if (cause instanceof CreateDeliveryFailedException) {
                throw (CreateDeliveryFailedException) cause;
            }
            throw new CreateDeliveryFailedException();
        } catch (final Exception e) {
            this.deliveryIds.remove(requestId);
            throw new ServiceNotAvailableException();
        }
    }

    private void onCreateDeliveryRequestApproved(final JsonObject event, final String evChannelsLocation) {
        logger.info("onCreateDeliveryRequestApproved");
        final String deliveryId = event.getString("deliveryId");
        logger.info("Received: " + deliveryId);
        final String requestId = event.getString("requestId");
        this.deliveryIds.get(requestId).complete(new DeliveryId(deliveryId));
        this.deliveryTrackingRequests.put(
                deliveryId,
                new OutputEventChannel(vertx, DELIVERY_TRACKING_REQUESTS_EVC.replace("{id}", deliveryId),
                        evChannelsLocation)
        );
        new InputEventChannel(vertx, DELIVERY_TRACKING_REQUESTS_APPROVED_EVC.replace("{id}", deliveryId),
                evChannelsLocation).init(this::onDeliveryTrackingRequestsApproved);
        new InputEventChannel(vertx, DELIVERY_TRACKING_REQUESTS_REJECTED_EVC.replace("{id}", deliveryId),
                evChannelsLocation).init(this::onDeliveryTrackingRequestsRejected);
    }

    private void onCreateDeliveryRequestRejected(final JsonObject event) {
        logger.info("onCreateDeliveryRequestRejected");
        final String requestId = event.getString("requestId");
        final String error = event.getString("error");
        logger.severe(error);
        this.deliveryIds.get(requestId).fail(new CreateDeliveryFailedException(
                error.contains("shipping-moment") ? ("Invalid shipping time: " + error) : error
        ));
    }

    @Override
    public String trackDelivery(final DeliveryId deliveryId) throws TrackDeliveryFailedException,
            ServiceNotAvailableException {
        this.count++;
        final String requestId = "lobby-" + this.count;
        final JsonObject trackDeliveryEvent = new JsonObject();
        trackDeliveryEvent.put("deliveryId", deliveryId.id());
        trackDeliveryEvent.put("requestId", requestId);
        try {
            final Promise<String> promise = Promise.promise();
            this.deliveryTrackingRequests.get(deliveryId.id()).postEvent(trackDeliveryEvent);
            this.trackingSessionIds.put(requestId, promise);
            return promise.future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (final ExecutionException e) {
            this.trackingSessionIds.remove(requestId);
            final Throwable cause = e.getCause();
            logger.info("TrackDeliveryFailedException with cause: " + cause.getMessage());
            if (cause instanceof TrackDeliveryFailedException) {
                throw (TrackDeliveryFailedException) cause;
            }
            throw new TrackDeliveryFailedException();
        } catch (final NullPointerException e) {
            throw new TrackDeliveryFailedException("Delivery not found");
        } catch (final Exception e) {
            this.trackingSessionIds.remove(requestId);
            throw new ServiceNotAvailableException();
        }
    }

    private void onDeliveryTrackingRequestsApproved(final JsonObject event) {
        logger.info("onDeliveryTrackingRequestsApproved");
        final String trackingSessionId = event.getString("trackingSessionId");
        logger.info("Received: " + trackingSessionId);
        final String requestId = event.getString("requestId");
        this.trackingSessionIds.get(requestId).complete(trackingSessionId);
    }

    private void onDeliveryTrackingRequestsRejected(final JsonObject event) {
        logger.info("onDeliveryTrackingRequestsRejected");
        final String requestId = event.getString("requestId");
        final String error = event.getString("error");
        logger.severe(error);
        this.trackingSessionIds.get(requestId).fail(new TrackDeliveryFailedException(error));
    }
}
