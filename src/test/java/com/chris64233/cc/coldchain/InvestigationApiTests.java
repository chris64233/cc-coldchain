package com.chris64233.cc.coldchain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvestigationApiTests {

    @Autowired
    private MockMvc mockMvc;

    private void quarantine(String waybill) throws Exception {
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
                                """.formatted(waybill)))
                .andExpect(status().isCreated());
        event(waybill, "e1", "2026-01-01T10:00:00Z", 10.0);
        event(waybill, "e2", "2026-01-01T10:10:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/" + waybill))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.evidenceVersion").value(1));
    }

    private void event(String waybill, String eventId, String sampledAt, double temperature) throws Exception {
        mockMvc.perform(post("/api/shipments/{waybill}/temperature-events", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "%s", "sampledAt": "%s", "temperature": %s}
                                """.formatted(eventId, sampledAt, temperature)))
                .andExpect(status().isCreated());
    }

    private void registerUnit(String waybill, String unitCode) throws Exception {
        mockMvc.perform(post("/api/shipments/{waybill}/units", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitCode": "%s"}""".formatted(unitCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitCode").value(unitCode));
    }

    @Test
    void fullConditionalReleaseFlowThroughApi() throws Exception {
        String waybill = "WB-API-1";
        quarantine(waybill);
        registerUnit(waybill, "u1");
        registerUnit(waybill, "u2");
        // 重放登记幂等
        registerUnit(waybill, "u2");
        mockMvc.perform(get("/api/shipments/{waybill}/units", waybill))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].status").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("QUARANTINED"))));

        String validUntil = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        mockMvc.perform(post("/api/shipments/{waybill}/investigations", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-API-1", "unitCodes": ["u1"],
                                 "reason": "u1 有条件放行"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.evidenceVersion").value(1))
                .andExpect(jsonPath("$.evidenceCurrent").value(true))
                .andExpect(jsonPath("$.lockedHolder").value("alice"))
                .andExpect(jsonPath("$.threshold.tempMin").value(2.0))
                .andExpect(jsonPath("$.lockedBreachIntervals", hasSize(1)))
                .andExpect(jsonPath("$.lockedBreachIntervals[0].start").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.lockedBreachIntervals[0].end").value("2026-01-01T10:10:00Z"))
                .andExpect(jsonPath("$.unitCodes", containsInAnyOrder("u1")));

        // 重复创建（同覆盖范围）幂等
        mockMvc.perform(post("/api/shipments/{waybill}/investigations", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-API-1", "unitCodes": ["u1"]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/shipments/{waybill}/investigations", waybill))
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/conclusion", waybill, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "d1", "dispositionType": "CONDITIONAL_RELEASE",
                                 "validUntil": "%s", "usageRestrictions": "仅限工业用途，不得零售",
                                 "operator": "qa-lead"}""".formatted(validUntil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));

        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/approvals", waybill, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalEventId": "a1", "approver": "bob", "role": "QA_MANAGER"}"""))
                .andExpect(status().isCreated());
        // 同角色审批被拒绝
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/approvals", waybill, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalEventId": "a2", "approver": "carol", "role": "QA_MANAGER"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_DUPLICATE_ROLE"));
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/approvals", waybill, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalEventId": "a2", "approver": "carol", "role": "REGULATORY"}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/release", waybill, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "rel1", "operator": "warehouse"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/shipments/{waybill}/investigations/{no}", waybill, "INV-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.status").value("COMPLETED"))
                .andExpect(jsonPath("$.units[0].unitCode").value("u1"))
                .andExpect(jsonPath("$.units[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.approvalChain", hasSize(2)))
                .andExpect(jsonPath("$.approvalChain[*].role").value(
                        containsInAnyOrder("QA_MANAGER", "REGULATORY")))
                .andExpect(jsonPath("$.dispositionEvents", hasSize(2)))
                .andExpect(jsonPath("$.evidenceEvents", hasSize(2)))
                .andExpect(jsonPath("$.temperatureTimeline", hasSize(2)))
                .andExpect(jsonPath("$.currentBreachIntervals", hasSize(1)));

        mockMvc.perform(get("/api/shipments/{waybill}/units", waybill))
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[1].status").value("QUARANTINED"));
    }

    @Test
    void newBreachEventSupersedesInvestigationAndBlocksRelease() throws Exception {
        String waybill = "WB-API-2";
        quarantine(waybill);
        registerUnit(waybill, "u1");
        String validUntil = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        mockMvc.perform(post("/api/shipments/{waybill}/investigations", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-API-2"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/conclusion", waybill, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "d1", "dispositionType": "CONDITIONAL_RELEASE",
                                 "validUntil": "%s", "usageRestrictions": "限工业用途", "operator": "qa"}"""
                                .formatted(validUntil)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/approvals", waybill, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalEventId": "a1", "approver": "bob", "role": "QA_MANAGER"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/approvals", waybill, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalEventId": "a2", "approver": "carol", "role": "REGULATORY"}"""))
                .andExpect(status().isCreated());

        // 晚到历史事件扩大异常区间
        event(waybill, "e0", "2026-01-01T09:55:00Z", 10.0);

        mockMvc.perform(get("/api/shipments/{waybill}/investigations/{no}", waybill, "INV-API-2"))
                .andExpect(jsonPath("$.investigation.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.investigation.evidenceCurrent").value(false))
                .andExpect(jsonPath("$.investigation.supersededAt").exists())
                .andExpect(jsonPath("$.investigation.supersedeReason").exists());

        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/release", waybill, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "rel1", "operator": "warehouse"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NEW_BREACH_AFTER_APPROVAL"));

        mockMvc.perform(get("/api/shipments/{waybill}/units", waybill))
                .andExpect(jsonPath("$[0].status").value("QUARANTINED"));
    }

    @Test
    void destroyAndValidationErrorsThroughApi() throws Exception {
        String waybill = "WB-API-3";
        quarantine(waybill);
        registerUnit(waybill, "u1");
        registerUnit(waybill, "u2");

        // 非隔离货物不能创建调查
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"waybillNo": "WB-API-3B", "tempMin": 2.0, "tempMax": 8.0,
                                 "maxBreachSeconds": 600, "initialHolder": "alice",
                                 "departureTime": "2026-01-01T08:00:00Z"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/WB-API-3B/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-NONE"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_QUARANTINED"));

        // 指定不存在的单元 -> 400
        mockMvc.perform(post("/api/shipments/{waybill}/investigations", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-BAD-UNIT", "unitCodes": ["ghost"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));

        // 有条件放行缺少使用限制 -> 400
        mockMvc.perform(post("/api/shipments/{waybill}/investigations", waybill)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-API-3", "unitCodes": ["u1"]}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/conclusion", waybill, "INV-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "dbad", "dispositionType": "CONDITIONAL_RELEASE",
                                 "validUntil": "%s", "operator": "qa"}"""
                                .formatted(Instant.now().plus(1, ChronoUnit.DAYS).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_USAGE_RESTRICTIONS"));

        // 报废 u1
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/conclusion", waybill, "INV-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "d1", "dispositionType": "DESTROY",
                                 "operator": "qa"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/shipments/{waybill}/units", waybill))
                .andExpect(jsonPath("$[0].status").value("DESTROYED"))
                .andExpect(jsonPath("$[1].status").value("QUARANTINED"));

        // 已报废不能重复处置
        mockMvc.perform(post("/api/shipments/{waybill}/investigations/{no}/conclusion", waybill, "INV-API-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionEventId": "d2", "dispositionType": "DESTROY",
                                 "operator": "qa"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVESTIGATION_ALREADY_COMPLETED"));
    }
}
