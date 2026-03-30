package api_gateway.application;

public class TrackingSessionNotFoundException extends Exception {

    public TrackingSessionNotFoundException(final String message) {
        super(message);
    }

    public TrackingSessionNotFoundException() {}
}
