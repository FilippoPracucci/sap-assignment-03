package delivery_service.domain.drone.env;

/**
 *  
 *  Basic interface for environment observers
 * 
 */
public interface EnvironmentObserver {

	void notifyEvent(EnvironmentEvent event);
}
