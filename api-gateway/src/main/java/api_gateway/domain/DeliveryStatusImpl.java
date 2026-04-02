package api_gateway.domain;

import java.util.Optional;

public class DeliveryStatusImpl implements DeliveryStatus {

    private final DeliveryId id;
    private final DeliveryState state;
    private final Optional<DeliveryTime> timeLeft;

    public DeliveryStatusImpl(final DeliveryId id, final DeliveryState state, final Optional<DeliveryTime> timeLeft) {
        this.id = id;
        this.state = state;
        this.timeLeft = timeLeft;
    }

    @Override
    public DeliveryState getState() {
        return this.state;
    }

    @Override
    public DeliveryTime getTimeLeft() throws DeliveryNotShippedYetException {
        return this.timeLeft.orElseThrow(DeliveryNotShippedYetException::new);
    }

    @Override
    public boolean isTimeLeftAvailable() {
        return this.timeLeft.isPresent();
    }

    @Override
    public DeliveryId getId() {
        return this.id;
    }
}
