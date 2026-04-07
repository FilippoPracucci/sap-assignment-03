package api_gateway.infrastructure;

public interface ControllerObserver {

	void notifyNewRESTRequest(long responseTimeInMillis, boolean success);

	void notifyAccountCircuitStatus(boolean isCircuitOpen);
}
