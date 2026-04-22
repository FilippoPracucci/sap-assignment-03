package delivery_service.domain.drone.env;

import delivery_service.domain.DeliveryEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * Abstract class for Environments where agents are situated
 * 
 */
public abstract class AbstractEnvironment {

	private final List<EnvironmentObserver> observersList;
	private final String name;
	
	protected AbstractEnvironment(final String name) {
		this.name = name;
		this.observersList = new ArrayList<>();
	}
	
	public void register(final EnvironmentObserver agent) {
		this.observersList.add(agent);
	}

	public void unregister(final EnvironmentObserver agent) {
		this.observersList.remove(agent);
	}
	
	protected void notifyEvent(final EnvironmentEvent event) {
		for (final EnvironmentObserver obs: this.observersList) {
			obs.notifyEvent(event);
		}
	}
	
	protected String getName() {
		return this.name;
	}
	
	protected void log(final String msg) {
		System.out.println("[ " + this.name + " ] " + msg);
	}	
	
	
}
