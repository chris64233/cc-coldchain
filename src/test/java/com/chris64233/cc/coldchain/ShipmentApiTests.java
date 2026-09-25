package com.chris64233.cc.coldchain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShipmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    private void createShipment(String waybillNo) throws Exception {
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "waybillNo": "%s",
                                  "tempMin": 2.0,
                                  "tempMax": 8.0,
                                  "maxBreachSeconds": 600,
                                  "initialHolder": "alice",
                                  "departureTime": "2026-01-01T08:00:00Z"
                                }
                                """.formatted(waybillNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.currentHolder").value("alice"));
    }

    private void recordEvent(String waybillNo, String eventId, String sampledAt, double temperature,
                             int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/shipments/{waybillNo}/temperature-events", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "%s", "sampledAt": "%s", "temperature": %s}
                                """.formatted(eventId, sampledAt, temperature)))
                .andExpect(status().is(expectedStatus));
    }

    private long initiateHandover(String waybillNo, String fromHolder, String toHolder) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/shipments/{waybillNo}/handovers", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromHolder": "%s", "toHolder": "%s"}
                                """.formatted(fromHolder, toHolder)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void confirmHandover(String waybillNo, long handoverId, String confirmedBy,
                                 int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/shipments/{waybillNo}/handovers/{id}/confirm", waybillNo, handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "%s"}
                                """.formatted(confirmedBy)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createShipmentValidatesRangeDurationAndUniqueWaybill() throws Exception {
        createShipment("WB-CREATE-1");
        mockMvc.perform(get("/api/shipments/WB-CREATE-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waybillNo").value("WB-CREATE-1"))
                .andExpect(jsonPath("$.tempMin").value(2.0))
                .andExpect(jsonPath("$.tempMax").value(8.0))
                .andExpect(jsonPath("$.maxBreachSeconds").value(600));

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waybillNo": "WB-BAD-RANGE", "tempMin": 8.0, "tempMax": 2.0,
                                 "maxBreachSeconds": 600, "initialHolder": "alice",
                                 "departureTime": "2026-01-01T08:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPERATURE_RANGE"));

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waybillNo": "WB-BAD-DURATION", "tempMin": 2.0, "tempMax": 8.0,
                                 "maxBreachSeconds": 0, "initialHolder": "alice",
                                 "departureTime": "2026-01-01T08:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BREACH_DURATION"));

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waybillNo": "WB-MISSING", "tempMin": 2.0, "tempMax": 8.0,
                                 "maxBreachSeconds": 600,
                                 "departureTime": "2026-01-01T08:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waybillNo": "WB-CREATE-1", "tempMin": 2.0, "tempMax": 8.0,
                                 "maxBreachSeconds": 600, "initialHolder": "bob",
                                 "departureTime": "2026-01-01T08:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_WAYBILL"));
    }

    @Test
    void outOfOrderEventsAreRecomputedAndQuarantineTriggers() throws Exception {
        createShipment("WB-TEMP-1");

        recordEvent("WB-TEMP-1", "e1", "2026-01-01T10:00:00Z", 5.0, 201);
        recordEvent("WB-TEMP-1", "e3", "2026-01-01T10:20:00Z", 9.5, 201);
        mockMvc.perform(get("/api/shipments/WB-TEMP-1"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        recordEvent("WB-TEMP-1", "e2", "2026-01-01T10:10:00Z", 9.0, 201);
        mockMvc.perform(get("/api/shipments/WB-TEMP-1"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        mockMvc.perform(get("/api/shipments/WB-TEMP-1/temperature-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].eventId").value("e1"))
                .andExpect(jsonPath("$[1].eventId").value("e2"))
                .andExpect(jsonPath("$[2].eventId").value("e3"));
    }

    @Test
    void breachEndingAtRecoverySampleTriggersQuarantine() throws Exception {
        createShipment("WB-TEMP-2");
        recordEvent("WB-TEMP-2", "e1", "2026-01-01T10:00:00Z", 10.0, 201);
        mockMvc.perform(get("/api/shipments/WB-TEMP-2"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        recordEvent("WB-TEMP-2", "e2", "2026-01-01T10:10:00Z", 4.0, 201);
        mockMvc.perform(get("/api/shipments/WB-TEMP-2"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }

    @Test
    void temperatureEventReplayIsIdempotentAndConflictIsRejected() throws Exception {
        createShipment("WB-TEMP-3");
        recordEvent("WB-TEMP-3", "e1", "2026-01-01T10:00:00Z", 5.0, 201);
        recordEvent("WB-TEMP-3", "e1", "2026-01-01T10:00:00Z", 5.0, 201);
        mockMvc.perform(get("/api/shipments/WB-TEMP-3/temperature-events"))
                .andExpect(jsonPath("$", hasSize(1)));

        recordEvent("WB-TEMP-3", "e1", "2026-01-01T10:00:00Z", 6.5, 409);
        mockMvc.perform(post("/api/shipments/WB-TEMP-3/temperature-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "e1", "sampledAt": "2026-01-01T10:05:00Z", "temperature": 5.0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));
        mockMvc.perform(get("/api/shipments/WB-TEMP-3/temperature-events"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void handoverLifecycleUpdatesHolderAndIsAuditable() throws Exception {
        createShipment("WB-HO-1");

        mockMvc.perform(post("/api/shipments/WB-HO-1/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromHolder": "mallory", "toHolder": "bob"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CURRENT_HOLDER"));

        long handoverId = initiateHandover("WB-HO-1", "alice", "bob");

        mockMvc.perform(post("/api/shipments/WB-HO-1/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromHolder": "alice", "toHolder": "carol"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HANDOVER_PENDING"));

        mockMvc.perform(post("/api/shipments/WB-HO-1/handovers/{id}/confirm", handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "mallory"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_DESIGNATED_RECEIVER"));

        mockMvc.perform(post("/api/shipments/WB-HO-1/handovers/{id}/confirm", handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "bob"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").exists());

        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("bob"));

        mockMvc.perform(post("/api/shipments/WB-HO-1/handovers/{id}/confirm", handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "bob"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("bob"));
        mockMvc.perform(get("/api/shipments/WB-HO-1/handovers"))
                .andExpect(jsonPath("$", hasSize(1)));

        long secondHandover = initiateHandover("WB-HO-1", "bob", "carol");
        confirmHandover("WB-HO-1", secondHandover, "carol", 200);
        mockMvc.perform(get("/api/shipments/WB-HO-1"))
                .andExpect(jsonPath("$.currentHolder").value("carol"));
        mockMvc.perform(get("/api/shipments/WB-HO-1/handovers"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].toHolder").value("bob"))
                .andExpect(jsonPath("$[1].toHolder").value("carol"));
    }

    @Test
    void quarantinedShipmentCannotHandover() throws Exception {
        createShipment("WB-HO-2");
        recordEvent("WB-HO-2", "e1", "2026-01-01T10:00:00Z", 10.0, 201);
        recordEvent("WB-HO-2", "e2", "2026-01-01T10:10:00Z", 10.0, 201);
        mockMvc.perform(get("/api/shipments/WB-HO-2"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        mockMvc.perform(post("/api/shipments/WB-HO-2/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromHolder": "alice", "toHolder": "bob"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_QUARANTINED"));
    }

    @Test
    void pendingHandoverCannotBeConfirmedAfterQuarantine() throws Exception {
        createShipment("WB-HO-3");
        long handoverId = initiateHandover("WB-HO-3", "alice", "bob");

        recordEvent("WB-HO-3", "e1", "2026-01-01T10:00:00Z", 10.0, 201);
        recordEvent("WB-HO-3", "e2", "2026-01-01T10:10:00Z", 10.0, 201);

        mockMvc.perform(post("/api/shipments/WB-HO-3/handovers/{id}/confirm", handoverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy": "bob"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_QUARANTINED"));
        mockMvc.perform(get("/api/shipments/WB-HO-3"))
                .andExpect(jsonPath("$.currentHolder").value("alice"));
    }

    @Test
    void unknownShipmentReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/shipments/WB-UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/shipments/WB-UNKNOWN/temperature-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "e1", "sampledAt": "2026-01-01T10:00:00Z", "temperature": 5.0}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
