package delivery_service.domain.drone;

import delivery_service.domain.*;
import delivery_service.domain.drone.agent.BasicAgentArch;
import delivery_service.domain.drone.agent.Percept;
import delivery_service.domain.drone.env.EnvTimeElapsed;
import delivery_service.domain.drone.env.EnvironmentEvent;

import java.util.Optional;

public class DroneAgent extends BasicAgentArch implements Drone {

	private static final int DURATION_MULTIPLIER = 5;
	private static final int HOUR_IN_MILLISECONDS = 3_600_000;
	private static final int HOURS_IN_A_DAY = 24;
	private static final int PERIOD_IN_HOURS = 1;

	/* internal beliefs */
	private final DeliveryDetail deliveryDetail;
	private final int deliveryDurationInHours;
	private final boolean isRestarted;
	private int hoursElapsed = 0;
	private long previousTime;
	private DeliveryState currentState;

	/* beliefs about the environment */
	private long currentTime;
	
	public DroneAgent(final DroneEnvironment env, final DeliveryDetail deliveryDetail,
					  final Optional<DeliveryTime> timeLeft) {
		super(deliveryDetail.getId().id(), env);
		this.deliveryDetail = deliveryDetail;
		this.deliveryDurationInHours = timeLeft.map(DeliveryTime::toHours)
				.orElse(DURATION_MULTIPLIER * ((int) this.deliveryDetail.weight()));
		this.isRestarted = timeLeft.isPresent();
		this.currentState = DeliveryState.READY_TO_SHIP;
	}

	@Override
	public void startDrone() {
		Thread.ofVirtual().start(this);
	}

	@Override
	protected void init() {}

	@Override
	protected void updateStateWithPercept(final Percept percept) {
		final EnvironmentEvent event = percept.environmentEvent();
		if (event instanceof EnvTimeElapsed) {
			this.currentTime = ((EnvTimeElapsed) event).currentTimeMillis();
		}
	}

	@Override
	protected void plan() {
		switch (this.currentState) {
			case READY_TO_SHIP:
				if (!this.isRestarted) {
					this.shipDelivery();
					this.previousTime = this.currentTime;
					this.currentState = DeliveryState.SHIPPING;
				}
				break;
			case SHIPPING:
				if ((this.currentTime - this.previousTime >= PERIOD_IN_HOURS * HOUR_IN_MILLISECONDS)) {
					this.hoursElapsed++;
					this.previousTime = this.currentTime;
					if (this.hoursElapsed < this.deliveryDurationInHours) {
						this.timeElapsed();
					} else {
						this.currentState = DeliveryState.DELIVERED;
					}
				}
				break;
			case DELIVERED:
				this.delivered();
				this.stop();
		}
	}

	/* actions (scheduled during the plan stage) */

	protected void shipDelivery() {
		final DroneEnvironment droneEnvironment = (DroneEnvironment) this.getEnv();
		this.scheduleAction(() -> droneEnvironment.shipDelivery(this.deliveryDetail.getId(), new DeliveryTime(
                this.deliveryDurationInHours / HOURS_IN_A_DAY,
                this.deliveryDurationInHours % HOURS_IN_A_DAY
        )));
	}

	protected void timeElapsed() {
		final DroneEnvironment droneEnvironment = (DroneEnvironment) this.getEnv();
		this.scheduleAction(() -> droneEnvironment.deliveryTimeElapsed(
				this.deliveryDetail.getId(),
				new DeliveryTime(0, PERIOD_IN_HOURS)
		));
	}

	protected void delivered() {
		final DroneEnvironment droneEnvironment = (DroneEnvironment) this.getEnv();
		this.scheduleAction(() -> droneEnvironment.delivered(this.deliveryDetail.getId()));
	}
}
