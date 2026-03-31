package api_gateway.infrastructure;

import api_gateway.domain.*;
import io.vertx.core.json.JsonObject;

import java.util.Calendar;
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

    public static JsonObject toJson(final double weight, final Address startingPlace,
                              final Address destinationPlace, final Optional<Calendar> expectedShippingMoment) {
        final JsonObject obj = new JsonObject();
        obj.put("weight", weight);
        obj.put("startingPlace", new JsonObject(Map.of(
                "street", startingPlace.street(),
                "number", startingPlace.number())
        ));
        obj.put("destinationPlace", new JsonObject(Map.of(
                "street", destinationPlace.street(),
                "number", destinationPlace.number())
        ));
        expectedShippingMoment.ifPresent(time -> obj.put("expectedShippingMoment", new JsonObject(Map.of(
                "year", time.get(Calendar.YEAR),
                "month", time.get(Calendar.MONTH) + 1,
                "day", time.get(Calendar.DAY_OF_MONTH),
                "hours", time.get(Calendar.HOUR_OF_DAY),
                "minutes", time.get(Calendar.MINUTE))))
        );
        return obj;
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

    public static DeliveryDetail fromJsonToDeliveryDetail(final JsonObject json) {
        return new DeliveryDetailImpl(
                new DeliveryId(json.getString("deliveryId")),
                json.getDouble("weight"),
                getAddress(json, "startingPlace"),
                getAddress(json, "destinationPlace"),
                getExpectedShippingMoment(json).orElseThrow(IllegalStateException::new)
        );
    }

    public static DeliveryStatus fromJsonToDeliveryStatus(final JsonObject json) {
        return new DeliveryStatusImpl(
                new DeliveryId(json.getString("deliveryId")),
                DeliveryState.valueOfLabel(json.getString("deliveryState")),
                json.containsKey("timeLeft")
                        ? Optional.of(
                                new DeliveryTime(Integer.parseInt(json.getString("timeLeft").split(" ")[0]), 0)
                        )
                        : Optional.empty()
        );
    }
}
