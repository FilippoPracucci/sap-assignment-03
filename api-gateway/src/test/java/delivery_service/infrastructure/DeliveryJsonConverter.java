package delivery_service.infrastructure;

import api_gateway.domain.DeliveryDetail;
import api_gateway.domain.DeliveryNotShippedYetException;
import api_gateway.domain.DeliveryStatus;
import io.vertx.core.json.JsonObject;

import java.util.Calendar;
import java.util.Map;

public class DeliveryJsonConverter {

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
}
