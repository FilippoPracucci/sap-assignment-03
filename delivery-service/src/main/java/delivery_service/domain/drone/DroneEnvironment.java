package delivery_service.domain.drone;

import delivery_service.domain.*;
import delivery_service.domain.drone.env.AbstractEnvironment;
import delivery_service.domain.drone.env.EnvTimeElapsed;

public class DroneEnvironment extends AbstractEnvironment {

	private long currentTime;

	public DroneEnvironment(){
		super("drone-env");
		this.currentTime = System.currentTimeMillis();
		Thread.ofVirtual().start(() -> {
			while (true) {
				try {
					Thread.sleep(1000);
					this.currentTime = System.currentTimeMillis();
					this.timeElapsed();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
	}

	public synchronized void timeElapsed() {
		this.notifyEvent(new EnvTimeElapsed(this.currentTime));
	}

	public synchronized void shipDelivery(final DeliveryId deliveryId, final DeliveryTime timeLeft) {
		this.notifyEvent(new Shipped(deliveryId, timeLeft));
	}

	public synchronized void deliveryTimeElapsed(final DeliveryId deliveryId, final DeliveryTime time) {
		this.notifyEvent(new TimeElapsed(deliveryId, time));
	}

	public synchronized void delivered(final DeliveryId deliveryId) {
		this.notifyEvent(new Delivered(deliveryId));
	}
}
