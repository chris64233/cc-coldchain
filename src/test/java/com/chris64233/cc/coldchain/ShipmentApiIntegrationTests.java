package com.chris64233.cc.coldchain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShipmentApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    private String createShipmentJson(String waybillNo) {
        return """
                {
                  "waybillNo": "%s",
                  "tempMin": 2.0,
                  "tempMax": 8.0,
                  "maxBreachMinutes": 30,
                  "initialHolder": "alice",
                  "departureTime": "2026-01-01T00:00:00Z"
                }
                """.formatted(waybillNo);
    }

    private void createShipment(String waybillNo) throws Exception {
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createShipmentJson(waybillNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waybillNo").value(waybillNo))
                .andExpect(jsonPath("$.currentHolder").value("alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private void postTemperature(String waybillNo, String eventId, String sampledAt, double temperature)
            throws Exception {
        postTemperature(waybillNo, eventId, sampledAt, temperature, 201);
    }

    private void postTemperature(String waybillNo, String eventId, String sampledAt, double temperature,
                                 int expectedStatus) throws Exception {
        String body = """
                {"deviceEventId": "%s", "sampledAt": "%s", "temperature": %s}
                """.formatted(eventId, sampledAt, temperature);
        mockMvc.perform(post("/api/shipments/{waybillNo}/temperature-events", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private MvcResult initiateHandover(String waybillNo, String from, String to, int expectedStatus)
            throws Exception {
        String body = """
                {"fromHolder": "%s", "toHolder": "%s"}
                """.formatted(from, to);
        return mockMvc.perform(post("/api/shipments/{waybillNo}/handovers", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void confirmHandover(String waybillNo, long handoverId, String confirmedBy, int expectedStatus)
            throws Exception {
        String body = """
                {"confirmedBy": "%s"}
                """.formatted(confirmedBy);
        mockMvc.perform(post("/api/shipments/{waybillNo}/handovers/{id}/confirm", waybillNo, handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private long extractHandoverId(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String idToken = "\"id\":";
        int start = json.indexOf(idToken) + idToken.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    @Test
    void createShipmentValidatesRulesAndUniqueWaybill() throws Exception {
        createShipment("WB-CREATE-1");

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createShipmentJson("WB-CREATE-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").exists());

        String invalidRange = createShipmentJson("WB-CREATE-2").replace("\"tempMax\": 8.0", "\"tempMax\": 1.0");
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRange))
                .andExpect(status().isBadRequest());

        String invalidDuration = createShipmentJson("WB-CREATE-3")
                .replace("\"maxBreachMinutes\": 30", "\"maxBreachMinutes\": 0");
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidDuration))
                .andExpect(status().isBadRequest());
    }

    @Test
    void outOfOrderEventsAreRecomputedAndQuarantineWhenLimitReached() throws Exception {
        createShipment("WB-TEMP-1");

        postTemperature("WB-TEMP-1", "e2", "2026-01-01T00:40:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/WB-TEMP-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        postTemperature("WB-TEMP-1", "e1", "2026-01-01T00:10:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/WB-TEMP-1"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        mockMvc.perform(get("/api/shipments/WB-TEMP-1/temperature-events"))
                .andExpect(jsonPath("$[0].deviceEventId").value("e1"))
                .andExpect(jsonPath("$[1].deviceEventId").value("e2"));
    }

    @Test
    void continuousBreachReachingLimitQuarantines() throws Exception {
        createShipment("WB-TEMP-2");

        postTemperature("WB-TEMP-2", "e1", "2026-01-01T00:00:00Z", 10.0);
        postTemperature("WB-TEMP-2", "e2", "2026-01-01T00:50:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/WB-TEMP-2"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }

    @Test
    void lateInRangeSampleSplitsBreachInterval() throws Exception {
        createShipment("WB-TEMP-3");

        postTemperature("WB-TEMP-3", "e1", "2026-01-01T00:00:00Z", 10.0);
        postTemperature("WB-TEMP-3", "e3", "2026-01-01T00:50:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/WB-TEMP-3"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        postTemperature("WB-TEMP-3", "e2", "2026-01-01T00:20:00Z", 5.0);
        mockMvc.perform(get("/api/shipments/WB-TEMP-3"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }

    @Test
    void replayedEventIsIdempotentAndDifferentContentConflicts() throws Exception {
        createShipment("WB-TEMP-4");

        postTemperature("WB-TEMP-4", "e1", "2026-01-01T00:00:00Z", 5.0);
        postTemperature("WB-TEMP-4", "e1", "2026-01-01T00:00:00Z", 5.0);

        MvcResult result = mockMvc.perform(get("/api/shipments/WB-TEMP-4/temperature-events"))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.split("\"deviceEventId\"").length - 1).isEqualTo(1);

        postTemperature("WB-TEMP-4", "e1", "2026-01-01T00:00:00Z", 6.5, 409);
        postTemperature("WB-TEMP-4", "e1", "2026-01-01T00:05:00Z", 5.0, 409);
    }

    @Test
    void handoverFlowUpdatesHolderAndKeepsImmutableRecords() throws Exception {
        createShipment("WB-HO-1");

        initiateHandover("WB-HO-1", "mallory", "bob", 403);
        MvcResult created = initiateHandover("WB-HO-1", "alice", "bob", 201);
        long handoverId = extractHandoverId(created);

        initiateHandover("WB-HO-1", "alice", "carol", 409);
        confirmHandover("WB-HO-1", handoverId, "mallory", 403);
        confirmHandover("WB-HO-1", handoverId, "bob", 200);

        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("bob"));

        confirmHandover("WB-HO-1", handoverId, "bob", 409);
        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("bob"));

        mockMvc.perform(get("/api/shipments/WB-HO-1/handovers"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].fromHolder").value("alice"))
                .andExpect(jsonPath("$[0].toHolder").value("bob"))
                .andExpect(jsonPath("$[0].confirmedAt").exists());

        MvcResult second = initiateHandover("WB-HO-1", "bob", "carol", 201);
        confirmHandover("WB-HO-1", extractHandoverId(second), "carol", 200);
        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("carol"));
        mockMvc.perform(get("/api/shipments/WB-HO-1/handovers"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void quarantinedShipmentCannotInitiateOrConfirmHandover() throws Exception {
        createShipment("WB-HO-2");

        MvcResult created = initiateHandover("WB-HO-2", "alice", "bob", 201);
        long handoverId = extractHandoverId(created);

        postTemperature("WB-HO-2", "e1", "2026-01-01T00:00:00Z", 10.0);
        postTemperature("WB-HO-2", "e2", "2026-01-01T00:40:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/WB-HO-2"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        confirmHandover("WB-HO-2", handoverId, "bob", 409);
        initiateHandover("WB-HO-2", "alice", "bob", 409);
        mockMvc.perform(get("/api/shipments/WB-HO-2"))
                .andExpect(jsonPath("$.currentHolder").value("alice"));
    }

    @Test
    void lateHistoricalTemperatureEventStillQuarantinesAfterHandover() throws Exception {
        createShipment("WB-HO-3");

        MvcResult created = initiateHandover("WB-HO-3", "alice", "bob", 201);
        confirmHandover("WB-HO-3", extractHandoverId(created), "bob", 200);
        mockMvc.perform(get("/api/shipments/WB-HO-3"))
                .andExpect(jsonPath("$.currentHolder").value("bob"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        postTemperature("WB-HO-3", "e1", "2026-01-01T00:05:00Z", 12.0);
        postTemperature("WB-HO-3", "e2", "2026-01-01T00:50:00Z", 12.0);
        mockMvc.perform(get("/api/shipments/WB-HO-3"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.currentHolder").value("bob"));
    }

    @Test
    void unknownShipmentReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/shipments/WB-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/shipments/WB-MISSING"));
    }
}
