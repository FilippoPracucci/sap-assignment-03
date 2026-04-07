package api_gateway.infrastructure;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import api_gateway.domain.DeliveryId;
import api_gateway.domain.DeliveryNotShippedYetException;
import api_gateway.domain.UserId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.*;
import io.vertx.ext.web.*;
import io.vertx.ext.web.handler.StaticHandler;
import api_gateway.application.*;

/**
*
* API Gateway Controller
* 
*/
public class APIGatewayController extends VerticleBase  {

	private final int port;
	static Logger logger = Logger.getLogger("[APIGatewayController]");

	/* for account */
	static final String API_VERSION = "v1";
	static final String ACCOUNTS_RESOURCE_PATH = "/api/" + API_VERSION + "/accounts";
	static final String ACCOUNT_RESOURCE_PATH = ACCOUNTS_RESOURCE_PATH + "/:accountId";

	/* for lobby */
	static final String LOGIN_RESOURCE_PATH = ACCOUNT_RESOURCE_PATH + "/login";
	static final String USER_SESSIONS_RESOURCE_PATH = "/api/" + API_VERSION + "/user-sessions";
	static final String CREATE_DELIVERY_RESOURCE_PATH = USER_SESSIONS_RESOURCE_PATH + "/:sessionId/create-delivery";
	static final String TRACK_DELIVERY_RESOURCE_PATH = USER_SESSIONS_RESOURCE_PATH + "/:sessionId/track-delivery";

	/* for delivery */
	static final String DELIVERIES_RESOURCE_PATH = "/api/" + API_VERSION + "/deliveries";
	static final String DELIVERY_RESOURCE_PATH =  DELIVERIES_RESOURCE_PATH +   "/:deliveryId";
	static final String TRACKING_RESOURCE_PATH = DELIVERY_RESOURCE_PATH + "/:trackingSessionId";
	static final String STOP_TRACKING_RESOURCE_PATH = TRACKING_RESOURCE_PATH + "/stop";
	static final String WS_EVENT_CHANNEL_PATH = "/api/" + API_VERSION + "/events";

	/* Health check endpoint */
	static final String HEALTH_CHECK_ENDPOINT = "/api/" + API_VERSION + "/health";
	
	/* proxies to interact with the services */
	private final AccountService accountService;
	private final LobbyService lobbyService;
	private final DeliveryServiceVertx deliveryService;

	/* observability */
	private final List<ControllerObserver> observers;

	public APIGatewayController(final AccountService accountService, final LobbyService lobbyService,
								final DeliveryServiceVertx deliveryService, final int port) {
		this.port = port;
		logger.setLevel(Level.INFO);
		this.accountService = accountService;
		this.lobbyService = lobbyService;
		this.deliveryService = deliveryService;
		this.observers = new ArrayList<>();
	}

	public void addControllerObserver(final ControllerObserver observer) {
		observers.add(observer);
	}

	public Future<?> start() {
		logger.info("API Gateway initializing...");
		HttpServer server = vertx.createHttpServer();

		/* REST API routes */
		Router router = Router.router(vertx);

		router.route(HttpMethod.POST, ACCOUNTS_RESOURCE_PATH).handler(this::createNewAccount);
		router.route(HttpMethod.GET, ACCOUNT_RESOURCE_PATH).handler(this::getAccountInfo);

		router.route(HttpMethod.POST, LOGIN_RESOURCE_PATH).handler(this::login);
		router.route(HttpMethod.POST, CREATE_DELIVERY_RESOURCE_PATH).handler(this::createNewDelivery);
		router.route(HttpMethod.POST, TRACK_DELIVERY_RESOURCE_PATH).handler(this::trackDelivery);

		router.route(HttpMethod.GET, DELIVERY_RESOURCE_PATH).handler(this::getDeliveryDetail);
		router.route(HttpMethod.GET, TRACKING_RESOURCE_PATH).handler(this::getDeliveryStatus);
		router.route(HttpMethod.POST, STOP_TRACKING_RESOURCE_PATH).handler(this::stopTrackingDelivery);

		router.route(HttpMethod.GET, HEALTH_CHECK_ENDPOINT).handler(this::healthCheckHandler);
		handleEventSubscription(server, WS_EVENT_CHANNEL_PATH);

		/* static files */
		router.route("/public/*").handler(StaticHandler.create());
		
		/* start the server */
		var fut = server
			.requestHandler(router)
			.listen(port);
		fut.onSuccess(res -> logger.info("API Gateway ready - port: " + this.port));
		return fut;
	}

	protected void healthCheckHandler(final RoutingContext context) {
		logger.info("Health check request " + context.currentRoute().getPath());
		final JsonObject reply = new JsonObject();
		reply.put("status", "UP");
		sendReply(context.response(), reply);
	}


	/* List of handlers mapping the API */
	
	/**
	 * 
	 * Register a new user
	 * 
	 * @param context
	 */
	protected void createNewAccount(final RoutingContext context) {
		logger.info("create a new account");
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().handler(buf -> {
				JsonObject userInfo = buf.toJsonObject();
				logger.info("Payload: " + userInfo);
				var userName = userInfo.getString("userName");
				var password = userInfo.getString("password");
				var reply = new JsonObject();
				this.vertx.executeBlocking(() -> this.accountService.registerUser(userName, password))
						.onSuccess((account) -> {
							reply.put("result", "ok");
							reply.put("accountId", account.getId().id());
							reply.put("loginLink", LOGIN_RESOURCE_PATH.replace(":accountId", account.getId().id()));
							reply.put("accountLink", ACCOUNT_RESOURCE_PATH.replace(":accountId", account.getId().id()));
							sendReply(ctx.response(), reply);
							promise.complete();
							this.notifyAccountCircuitStatus(false);
						}).onFailure((f) -> {
							if (f instanceof ServiceNotAvailableException) {
								this.notifyAccountCircuitStatus(((ServiceNotAvailableException) f).isCircuitOpen());
							}
							reply.put("result", "error");
							reply.put("error", f.getMessage());
							sendReply(ctx.response(), reply);
							promise.fail(f);
						});
			});
			return promise.future();
		}, context);
	}

	/**
	 * 
	 * Get account info
	 * 
	 * @param context
	 */
	protected void getAccountInfo(final RoutingContext context) {
		logger.info("get account info");
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			var userId = ctx.pathParam("accountId");
			var reply = new JsonObject();
			this.vertx.executeBlocking(() -> this.accountService.getAccountInfo(new UserId(userId)))
					.onSuccess((account) -> {
						reply.put("result", "ok");
						var accJson = new JsonObject();
						accJson.put("userName", account.getUserName());
						accJson.put("password", account.getPassword());
						accJson.put("whenCreated", account.getWhenCreated());
						reply.put("accountInfo", accJson);
						sendReply(ctx.response(), reply);
						promise.complete();
						this.notifyAccountCircuitStatus(false);
					}).onFailure((f) -> {
						if (f instanceof ServiceNotAvailableException) {
							this.notifyAccountCircuitStatus(((ServiceNotAvailableException) f).isCircuitOpen());
						}
						reply.put("result", "error");
						reply.put("error", f.getMessage());
						sendReply(ctx.response(), reply);
						promise.fail(f);
					});
			return promise.future();
		}, context);
	}
	
	/**
	 * 
	 * Login a user
	 * 
	 * It creates a User Session
	 * 
	 * @param context
	 */
	protected void login(final RoutingContext context) {
		logger.info("Login request");
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().handler(buf -> {
				JsonObject userInfo = buf.toJsonObject();
				logger.info("Payload: " + userInfo);
				String userId = ctx.pathParam("accountId");
				String password = userInfo.getString("password");
				var reply = new JsonObject();
				this.vertx.executeBlocking(() -> this.lobbyService.login(new UserId(userId), password))
						.onSuccess((userSessionId) -> {
							reply.put("result", "ok");
							var createPath = CREATE_DELIVERY_RESOURCE_PATH.replace(":sessionId", userSessionId);
							var trackPath = TRACK_DELIVERY_RESOURCE_PATH.replace(":sessionId", userSessionId);
							reply.put("sessionId", userSessionId);
							reply.put("sessionLink", USER_SESSIONS_RESOURCE_PATH + "/" + userSessionId);
							reply.put("createDeliveryLink", createPath);
							reply.put("trackDeliveryLink", trackPath);
							sendReply(ctx.response(), reply);
							promise.complete();
						}).onFailure((f) -> {
							reply.put("result", "login-failed");
							reply.put("error", f.getMessage());
							sendReply(ctx.response(), reply);
							promise.fail(f);
						});
			});
			return promise.future();
		}, context);
	}
	
	
	/**
	 * 
	 * Create a delivery
	 * 
	 * @param context
	 */
	protected void createNewDelivery(final RoutingContext context) {
		logger.info("Create delivery request - " + context.currentRoute().getPath());
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().handler(buf -> {
				final JsonObject deliveryDetailJson = buf.toJsonObject();
				String userSessionId = ctx.pathParam("sessionId");
				var reply = new JsonObject();
				final Optional<Calendar> expectedShippingMoment =
						DeliveryJsonConverter.getExpectedShippingMoment(deliveryDetailJson);
				vertx.executeBlocking(() -> this.lobbyService.createNewDelivery(
						userSessionId,
						deliveryDetailJson.getNumber("weight").doubleValue(),
						DeliveryJsonConverter.getAddress(deliveryDetailJson, "startingPlace"),
						DeliveryJsonConverter.getAddress(deliveryDetailJson, "destinationPlace"),
						expectedShippingMoment
				)).onSuccess((deliveryId) -> {
					reply.put("result", "ok");
					reply.put("deliveryId", deliveryId.id());
					reply.put("deliveryLink", DELIVERIES_RESOURCE_PATH + "/" + deliveryId.id());
					reply.put("trackDeliveryLink",
							TRACK_DELIVERY_RESOURCE_PATH.replace(":sessionId", userSessionId));
					sendReply(ctx.response(), reply);
					promise.complete();
				}).onFailure((f) -> {
					reply.put("result", "error");
					reply.put("error", f.getMessage());
					sendReply(ctx.response(), reply);
					promise.fail(f);
				});
			});
			return promise.future();
		}, context);
	}

	/**
	 * 
	 * Track a delivery
	 * 
	 * @param context
	 */
	protected void trackDelivery(final RoutingContext context) {
		logger.info("Track delivery request - " + context.currentRoute().getPath());
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().handler(buf -> {
				final String userSessionId = ctx.pathParam("sessionId");
				final String deliveryId = buf.toJsonObject().getString("deliveryId");
				final JsonObject reply = new JsonObject();
				vertx.executeBlocking(() -> this.lobbyService.trackDelivery(userSessionId, new DeliveryId(deliveryId)))
						.onSuccess((trackingSessionId) -> {
							reply.put("result", "ok");
							reply.put("trackingSessionId", trackingSessionId);
							reply.put("trackingSessionLink",
									DELIVERIES_RESOURCE_PATH + "/" + deliveryId + "/" + trackingSessionId);
							sendReply(ctx.response(), reply);
							promise.complete();
						}).onFailure((f) -> {
							reply.put("result", "error");
							reply.put("error", f.getMessage());
							sendReply(ctx.response(), reply);
							promise.fail(f);
						});
			});
			return promise.future();
		}, context);
	}
	
	/**
	 * 
	 * Get delivery detail
	 * 
	 * @param context
	 */
	protected void getDeliveryDetail(final RoutingContext context) {
		logger.info("get delivery detail");
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().endHandler(h -> {
				final DeliveryId deliveryId = new DeliveryId(ctx.pathParam("deliveryId"));
				var reply = new JsonObject();
				this.vertx.executeBlocking(() -> this.deliveryService.getDeliveryDetail(deliveryId))
						.onSuccess((deliveryDetail) -> {
							reply.put("result", "ok");
							reply.put("deliveryDetail", DeliveryJsonConverter.toJson(deliveryDetail));
							sendReply(ctx.response(), reply);
							promise.complete();
						}).onFailure((f) -> {
							reply.put("result", "error");
							reply.put("error", f.getMessage());
							sendReply(ctx.response(), reply);
							promise.fail(f);
						});
			});
			return promise.future();
		}, context);
	}

	/**
	 *
	 * Get delivery status - by users tracking a delivery (with a TrackingSession)
	 *
	 * @param context
	 */
	protected void getDeliveryStatus(final RoutingContext context) {
		logger.info("GetDeliveryStatus request - " + context.currentRoute().getPath());
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().endHandler(h -> {
				final JsonObject reply = new JsonObject();
				final DeliveryId deliveryId = new DeliveryId(ctx.pathParam("deliveryId"));
				final String trackingSessionId = ctx.pathParam("trackingSessionId");
				this.vertx.executeBlocking(() -> this.deliveryService.getDeliveryStatus(deliveryId, trackingSessionId))
						.onSuccess((deliveryStatus) -> {
							reply.put("result", "ok");
							final JsonObject deliveryJson = new JsonObject();
							deliveryJson.put("deliveryId", deliveryId.id());
							deliveryJson.put("deliveryState", deliveryStatus.getState().getLabel());
							try {
								deliveryJson.put("timeLeft", deliveryStatus.getTimeLeft().days() + " days left");
							} catch (final DeliveryNotShippedYetException ignored) {
							}
							reply.put("deliveryStatus", deliveryJson);
							sendReply(ctx.response(), reply);
							promise.complete();
						}).onFailure(f -> {
							if (f instanceof TrackingSessionNotFoundException) {
								reply.put("result", "error");
								reply.put("error", "tracking-session-not-present");
								sendReply(ctx.response(), reply);
							} else {
								reply.put("result", "error");
								reply.put("error", f.getMessage());
								sendReply(ctx.response(), reply);
							}
							promise.fail(f);
						});
			});
			return promise.future();
		}, context);
	}

	/**
	 *
	 * Stop tracking a delivery
	 *
	 * @param context
	 */
	protected void stopTrackingDelivery(final RoutingContext context) {
		logger.info("Stop tracking delivery request - " + context.currentRoute().getPath());
		this.handleRequest(ctx -> {
			final Promise<Void> promise = Promise.promise();
			ctx.request().endHandler(h -> {
				final DeliveryId deliveryId = new DeliveryId(ctx.pathParam("deliveryId"));
				final String trackingSessionId = ctx.pathParam("trackingSessionId");
				logger.info("Stop tracking delivery " + deliveryId.id());
				var reply = new JsonObject();
				this.vertx.executeBlocking(() -> {
					this.deliveryService.stopTrackingDelivery(deliveryId, trackingSessionId);
					return null;
				}).onSuccess((x) -> {
					reply.put("result", "ok");
					sendReply(ctx.response(), reply);
					promise.complete();
				}).onFailure(f -> {
					reply.put("result", "error");
					reply.put("error", f.getMessage());
					sendReply(ctx.response(), reply);
					promise.fail(f);
				});
			});
			return promise.future();
		}, context);
	}

	/**
	 * 
	 * Handling subscribers using web sockets
	 * 
	 * @param server
	 * @param path
	 */
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

					this.deliveryService.createAnEventChannel(trackingSessionId, vertx);

					eventBus.consumer(trackingSessionId, msg -> {
						/* bridge between web sockets */
						webSocket.writeTextMessage(msg.body().toString());
					});
				});
			}
		});
	}

	private void handleRequest(final Function<RoutingContext, Future<Void>> handleContext, final RoutingContext context) {
		final long initialTime = System.nanoTime();
		handleContext.apply(context).onComplete(res ->
				this.notifyNewRESTRequest(System.nanoTime() - initialTime, res.succeeded()));
	}

	private void notifyNewRESTRequest(final long responseTimeInNanoseconds, boolean success) {
		this.observers.forEach(obs ->
				obs.notifyNewRESTRequest(TimeUnit.NANOSECONDS.toMillis(responseTimeInNanoseconds), success));
	}

	private void notifyAccountCircuitStatus(final boolean isCircuitOpen) {
		this.observers.forEach(obs -> obs.notifyAccountCircuitStatus(isCircuitOpen));
	}
	
	/* Aux methods */

	private void sendReply(HttpServerResponse response, JsonObject reply) {
		response.putHeader("content-type", "application/json");
		response.end(reply.toString());
	}
	
	private void sendError(HttpServerResponse response) {
		response.setStatusCode(500);
		response.putHeader("content-type", "application/json");
		response.end();
	}
}
