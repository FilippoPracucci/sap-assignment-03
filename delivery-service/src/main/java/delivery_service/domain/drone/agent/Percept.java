package delivery_service.domain.drone.agent;

import delivery_service.domain.drone.env.EnvironmentEvent;

public record Percept(EnvironmentEvent environmentEvent, long timestamp) {}
