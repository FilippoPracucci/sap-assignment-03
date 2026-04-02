package api_gateway.domain;

import common.ddd.Entity;

import java.util.Optional;

public interface DeliveryStatus extends Entity<DeliveryId> {

    DeliveryState getState();

    DeliveryTime getTimeLeft() throws DeliveryNotShippedYetException;

    boolean isTimeLeftAvailable();
}