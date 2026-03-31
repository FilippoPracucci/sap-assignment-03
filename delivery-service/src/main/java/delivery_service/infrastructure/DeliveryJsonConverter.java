package delivery_service.infrastructure;

import delivery_service.domain.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DeliveryJsonConverter {

    public static Address getAddress(final JsonObject json, final String key) {
        return new Address(
                json.getJsonObject(key).getString("street"),
                json.getJsonObject(key).getNumber("number").intValue()
        );
    }

    public static Optional<Calendar> getExpectedShippingMoment(final JsonObject json) {
        return json.containsKey("expectedShippingMoment")
                ? Optional.of(new Calendar.Builder().setDate(
                        json.getJsonObject("expectedShippingMoment").getNumber("year").intValue(),
                        json.getJsonObject("expectedShippingMoment").getNumber("month").intValue() - 1,
                        json.getJsonObject("expectedShippingMoment").getNumber("day").intValue()
                ).setTimeOfDay(
                        json.getJsonObject("expectedShippingMoment").getNumber("hours").intValue(),
                        json.getJsonObject("expectedShippingMoment").getNumber("minutes").intValue(),
                        0
                ).build())
                : Optional.empty();
    }

    public static JsonObject toJson(final DeliveryDetail deliveryDetail) {
        final JsonObject obj = new JsonObject();
        obj.put("deliveryId", deliveryDetail.getId().id());
        obj.put("weight", deliveryDetail.weight());
        obj.put("startingPlace", new JsonObject(Map.of(
                "street", deliveryDetail.startingPlace().street(),
                "number", deliveryDetail.startingPlace().number())
        ));
        obj.put("destinationPlace", new JsonObject(Map.of(
                "street", deliveryDetail.destinationPlace().street(),
                "number", deliveryDetail.destinationPlace().number())
        ));
        obj.put("expectedShippingMoment", new JsonObject(Map.of(
                "year", deliveryDetail.expectedShippingMoment().get(Calendar.YEAR),
                "month", deliveryDetail.expectedShippingMoment().get(Calendar.MONTH) + 1,
                "day", deliveryDetail.expectedShippingMoment().get(Calendar.DAY_OF_MONTH),
                "hours", deliveryDetail.expectedShippingMoment().get(Calendar.HOUR_OF_DAY),
                "minutes", deliveryDetail.expectedShippingMoment().get(Calendar.MINUTE))
        ));
        return obj;
    }

    public static JsonObject toJson(final DeliveryStatus deliveryStatus) throws DeliveryNotShippedYetException {
        final JsonObject obj = new JsonObject();
        obj.put("deliveryId", deliveryStatus.getId().id());
        obj.put("deliveryState", deliveryStatus.getState().getLabel());
        if (deliveryStatus.isTimeLeftAvailable()) {
            obj.put("timeLeft", deliveryStatus.getTimeLeft().days() + " days left");
        }
        return obj;
    }

    private static JsonObject toJson(final String key, final DeliveryTime deliveryTime) {
        return new JsonObject(Map.of(key, new JsonObject(Map.of(
                "days", deliveryTime.days(),
                "hours", deliveryTime.hours())))
        );
    }

    public static JsonObject toJson(final Integer eventId, final DeliveryEvent event) {
        return switch (event) {
            case DeliveryCreated deliveryCreated -> new JsonObject(Map.of(
                    "eventId", eventId,
                    "eventType", DeliveryCreated.class.getSimpleName(),
                    "deliveryId", event.id().id(),
                    "eventData", toJson(deliveryCreated.deliveryDetail())
            ));
            case Shipped shipped -> new JsonObject(Map.of(
                    "eventId", eventId,
                    "eventType", Shipped.class.getSimpleName(),
                    "deliveryId", event.id().id(),
                    "eventData", toJson("timeLeft", shipped.timeLeft())
            ));
            case TimeElapsed timeElapsed -> new JsonObject(Map.of(
                    "eventId", eventId,
                    "eventType", TimeElapsed.class.getSimpleName(),
                    "deliveryId", event.id().id(),
                    "eventData", toJson("time", timeElapsed.time())
            ));
            case Delivered delivered -> new JsonObject(Map.of(
                    "eventId", eventId,
                    "eventType", Delivered.class.getSimpleName(),
                    "deliveryId", event.id().id(),
                    "eventData", new JsonObject()
            ));
            default -> throw new IllegalArgumentException("Event type not supported");
        };
    }

    private static DeliveryEvent fromJson(final JsonObject event) {
        final DeliveryId deliveryId = new DeliveryId(event.getString("deliveryId"));
        final JsonObject eventData = event.getJsonObject("eventData");
        return switch (event.getString("eventType")) {
            case "DeliveryCreated" -> new DeliveryCreated(deliveryId, new DeliveryDetailImpl(
                    deliveryId,
                    eventData.getDouble("weight"),
                    getAddress(eventData, "startingPlace"),
                    getAddress(eventData, "destinationPlace"),
                    getExpectedShippingMoment(eventData).orElseThrow(() ->
                            new IllegalStateException("Empty expectedShippingMoment"))
            ));
            case "Shipped" -> new Shipped(deliveryId, new DeliveryTime(
                    eventData.getJsonObject("timeLeft").getInteger("days"),
                    eventData.getJsonObject("timeLeft").getInteger("hours"))
            );
            case "TimeElapsed" -> new TimeElapsed(deliveryId, new DeliveryTime(
                    eventData.getJsonObject("time").getInteger("days"),
                    eventData.getJsonObject("time").getInteger("hours"))
            );
            case "Delivered" -> new Delivered(deliveryId);
            default -> throw new IllegalArgumentException("Event type not supported");
        };
    }

    public static Map<Integer, DeliveryEvent> fromJson(final JsonArray array) {
        final Map<Integer, DeliveryEvent> events = new HashMap<>();
        for (int i = 0; i < array.size(); i++) {
            final JsonObject object = array.getJsonObject(i);
            events.put(object.getInteger("eventId"), fromJson(object));
        }
        return events;
    }
}
