import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PerformanceTest extends Setup {

    private static final String ACCOUNTS_RESOURCE_PATH = "/api/" + API_VERSION + "/accounts";

    @Test
    public void testAvgResponseTime() {
        final int nConcurrentRequests = 50;
        final int responseTimeThresholdInMs = 2000;
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < nConcurrentRequests; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
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
        try {
            threads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            double nRequests = this.getMetricValue("api_gateway_num_rest_requests_total");
            double totalResponseTimeInMs = this.getMetricValue("api_gateway_request_response_time_ms_total");
            double averageResponseTimeInMs = (totalResponseTimeInMs / nRequests);
            assertEquals(nConcurrentRequests, nRequests);
            System.out.println("Average response time in ms: " + averageResponseTimeInMs);
            double throughput = nConcurrentRequests / (averageResponseTimeInMs / 1000);
            System.out.println("Throughput (requests per second): " + throughput);
            assertTrue(averageResponseTimeInMs <= responseTimeThresholdInMs);
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }
}
