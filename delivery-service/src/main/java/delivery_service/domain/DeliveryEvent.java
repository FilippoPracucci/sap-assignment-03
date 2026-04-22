package delivery_service.domain;

import common.ddd.DomainEvent;
import delivery_service.domain.drone.env.EnvironmentEvent;

public interface DeliveryEvent extends DomainEvent, EnvironmentEvent {

    DeliveryId id();
}
