package kafka;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class InputEventChannel {
    static Logger logger = Logger.getLogger("[InputEventChannel]");

    private String name;
    private KafkaConsumer<String, String> consumer;
    private Vertx vertx;

    public InputEventChannel(Vertx vertx, String name, String address) {
        this.name = name;
        this.vertx = vertx;
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", address);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("group.id", "test");
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", "true");

		/*
	      config.put("max.poll.records", "1");
	      config.put("enable.auto.commit", "true");
	      config.put("auto.commit.interval.ms", "1000");
	      config.put("session.timeout.ms", "30000");
		*/

        consumer = KafkaConsumer.create(vertx, config);
        consumer.pollTimeout(Duration.ofMillis(100));

    }


    public Future<Object> init(Consumer<JsonObject> handler) {
        var prom = Promise.promise();
        consumer.handler(record -> {
            log("Processing key=" + record.key() + ",value=" + record.value() +
                    ",partition=" + record.partition() + ",offset=" + record.offset());
            JsonObject ev = new JsonObject(record.value());
            handler.accept(ev);
        });
        consumer
                .subscribe(name)
                .onSuccess(v -> {
                    log("subscribed");
                    prom.succeed();
                }).onFailure(cause -> {
                    log("Could not subscribe " + cause.getMessage());
                    prom.fail(cause.getMessage());
                });
        return prom.future();
    }

    private void log(String msg) {
        logger.info("["+name+"] " + msg);
    }

}
