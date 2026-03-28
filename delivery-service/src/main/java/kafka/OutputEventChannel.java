package kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;

public class OutputEventChannel {
    static Logger logger = Logger.getLogger("[OutputEventChannel]");

    private String name;
    private KafkaProducer<String, String> producer;
    private Vertx vertx;

    public OutputEventChannel(Vertx vertx, String name, String address) {
        this.name = name;
        this.vertx = vertx;
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", address);
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("acks", "1");
        producer = KafkaProducer.create(vertx, config);
    }

    public Future<Object> postEvent(JsonObject ev) {
        var prom = Promise.promise();
        ev.put("timestamp", System.currentTimeMillis());
        KafkaProducerRecord<String, String> record =
                KafkaProducerRecord.create(name, ev.toString());
        producer.send(record)
                .onSuccess(recordMetadata -> {
                    log("Message " + record.value() + " written on topic=" + recordMetadata.getTopic() +
                            ", partition=" + recordMetadata.getPartition() +
                            ", offset=" + recordMetadata.getOffset());
                    prom.succeed();
                })
                .onFailure(cause -> {
                    prom.fail(cause.getMessage());
                });
        return prom.future();
    }

    private void log(String msg) {
        logger.info("["+name+"]" + msg);
    }
}
