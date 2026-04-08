import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PerformanceTest extends Setup {

    private static final String ACCOUNTS_RESOURCE_PATH = "/api/" + API_VERSION + "/accounts";

    private final int nConcurrentRequests = 50;
    private final List<Thread> threads = new ArrayList<>();

    @BeforeEach
    public void setup() {
        super.setup();
        for (int i = 0; i < this.nConcurrentRequests; i++) {
            this.threads.add(Thread.ofVirtual().start(() -> {
                try {
                    doPost(API_GATEWAY_URI + ACCOUNTS_RESOURCE_PATH, new JsonObject(Map.of(
                            "userName", "name",
                            "password", "1234"))
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        this.threads.forEach(t -> {
            try {
                t.join();
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testThroughput() {
        final int throughputThreshold = 40;
        try {
            final double nRequests = this.getMetricValue("api_gateway_num_rest_requests_total");
            final double totalResponseTimeInMs = this.getMetricValue("api_gateway_request_response_time_ms_total");
            final double throughput = this.nConcurrentRequests / (totalResponseTimeInMs / nRequests / 1000);
            System.out.println("Throughput (requests per second): " + throughput);
            assertEquals(this.nConcurrentRequests, nRequests);
            assertTrue(throughput >= throughputThreshold);
        } catch (final Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testAvailability() {
        final double availabilityThreshold = 0.99;
        try {
            final double nRequests = this.getMetricValue("api_gateway_num_rest_requests_total");
            final double nSuccessfulRequests = this.getMetricValue("api_gateway_num_successful_rest_requests_total");
            final double availability = nSuccessfulRequests /  nRequests;
            System.out.println("Availability: " + availability);
            assertEquals(this.nConcurrentRequests, nRequests);
            assertTrue(availability >= availabilityThreshold);
        } catch (final Exception e) {
            e.printStackTrace();
            fail();
        }
    }
}
