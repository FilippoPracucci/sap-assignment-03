package delivery_service.domain.drone.agent;

import delivery_service.domain.drone.env.AbstractEnvironment;
import delivery_service.domain.drone.env.EnvironmentObserver;
import delivery_service.domain.drone.env.EnvironmentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 
 * Basic Agent Architecture implementing a simple Sense-Plan-Act reasoning cycle.
 * 
 */
public abstract class BasicAgentArch implements Runnable, EnvironmentObserver {
	
	enum AgentStage { INIT, SENSE, PLAN, ACT}
	
	private final String agentName;
	private final BlockingQueue<Percept> perceptQueue; // queue of all perceptions received
	private final List<Action> actionQueue;
	private final AbstractEnvironment env;
	
	private AgentStage currentStage;
	private long cycle;
	private boolean stop;
	
	public BasicAgentArch(final String name, final AbstractEnvironment env) {
		this.agentName = name;
		this.perceptQueue = new LinkedBlockingQueue<>();
		this.actionQueue = new ArrayList<>();
		this.env = env;
		this.stop = false;
	}
	
	/* basic control loop - "reasoning agent" */
	public void run() {		
		this.currentStage = AgentStage.INIT;
		this.init();
		this.cycle = 1;
		while (!stop) {
			this.currentStage = AgentStage.SENSE;
			this.sense();
			this.currentStage = AgentStage.PLAN;
			this.plan();
			this.currentStage = AgentStage.ACT;
			this.act();
			this.cycle++;
		}		
	}
	
	protected abstract void init();

	final protected void stop() {
		this.stop = true;
		this.getEnv().unregister(this);
	}

	/* sense stage */
    /* update the state with every percept */
	protected void sense() {
		try {
			final Percept percept = this.perceptQueue.poll();
			if (percept != null) {
				log("updating beliefs with percept " + percept);
				this.updateStateWithPercept(percept);
			}
		} catch (final Exception ex) {
			ex.printStackTrace();
		}		
	}
	
	/* beliefs update - defined by the agent program  */
	protected abstract void updateStateWithPercept(final Percept percept);
	
	/* plan stage - defined by the agent program */
	/* decide the action(s), but do not sense. It's atomic	*/
	protected abstract void plan();
	
	/* act stage */
	/* could not block, in order to don't block the reasoning cycle.
	   Asynchronous: just start the action, but don't wait for the result */
	protected void act() {
		for (final Action action: this.actionQueue) {
			try {
				log("executing action " + action);
				action.execute();
			} catch (final Exception ex) {
				ex.printStackTrace();
				log("error executing action " + action);
			}
		}
		this.actionQueue.clear();
	}

	/* predefined internal actions/functions */
	protected long getTime() {
		return System.currentTimeMillis();
	}
	
	/* external actions */
	protected void scheduleAction(final Action action) {
		this.actionQueue.add(action);
	}

	/* interface for the environment to notify events */
	public void notifyEvent(final EnvironmentEvent event) {
		try {
			final Percept percept = new Percept(event, System.currentTimeMillis());
			this.perceptQueue.put(percept);
			log("new percept created " + percept);
		} catch (final Exception ex) {
			ex.printStackTrace();
		}
	}

	protected AbstractEnvironment getEnv() {
		return this.env;
	}

	protected void log(final String msg) {
		final String stage = this.currentStage.equals(AgentStage.SENSE) ? "sense"
			: this.currentStage.equals(AgentStage.PLAN) ? "plan"
			: this.currentStage.equals(AgentStage.ACT) ? "act"
			: "init";
		System.out.println("[ " + this.agentName +" ][ " + this.cycle + "][ " + stage + " ] " + msg);
	}
}
