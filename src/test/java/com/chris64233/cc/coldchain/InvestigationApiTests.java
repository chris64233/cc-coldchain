package com.chris64233.cc.coldchain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                                  "departureTime": "2026-06-01T08:00:00Z"
                                }
                                """.formatted(waybillNo)))
                .andExpect(status().isCreated());
    }

    private void event(String waybillNo, String eventId, String sampledAt, double temperature) throws Exception {
        mockMvc.perform(post("/api/shipments/{waybillNo}/temperature-events", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "%s", "sampledAt": "%s", "temperature": %s}
                                """.formatted(eventId, sampledAt, temperature)))
                .andExpect(status().isCreated());
    }

    private void quarantine(String waybillNo) throws Exception {
        createShipment(waybillNo);
        event(waybillNo, "e1", "2026-06-01T10:00:00Z", 10.0);
        event(waybillNo, "e2", "2026-06-01T10:10:00Z", 10.0);
        mockMvc.perform(get("/api/shipments/{w}", waybillNo))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));
    }

    private void createInvestigation(String waybillNo, String investigationNo) throws Exception {
        mockMvc.perform(post("/api/shipments/{w}/investigations", waybillNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "%s"}
                                """.formatted(investigationNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.investigationNo").value(investigationNo))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.evidenceVersion").value(1))
                .andExpect(jsonPath("$.custodian").value("alice"));
    }

    @Test
    void fullConditionalReleaseFlowWithUnitsApprovalsAndDetail() throws Exception {
        String w = "WB-API-1";
        createShipment(w);
        mockMvc.perform(post("/api/shipments/{w}/units", w)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitCode": "U1", "quantity": 10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
        mockMvc.perform(post("/api/shipments/{w}/units", w)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitCode": "U2", "quantity": 20}
                                """))
                .andExpect(status().isCreated());
        // 重复单元编号
        mockMvc.perform(post("/api/shipments/{w}/units", w)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitCode": "U1", "quantity": 1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_UNIT"));
        event(w, "e1", "2026-06-01T10:00:00Z", 10.0);
        event(w, "e2", "2026-06-01T10:10:00Z", 10.0);

        createInvestigation(w, "INV-API-1");

        // 调查号幂等
        createInvestigation(w, "INV-API-1");
        mockMvc.perform(get("/api/shipments/{w}/investigations", w))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // 放行结论缺有效期/限制
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/conclusions", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "CONDITIONAL_RELEASE", "unitCodes": ["U1"],
                                 "usageRestrictions": "限用", "decidedBy": "qa"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALID_UNTIL_REQUIRED"));

        // 提交有条件放行结论（U1）
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/conclusions", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "CONDITIONAL_RELEASE", "unitCodes": ["U1"],
                                 "validUntil": "2027-01-01T00:00:00Z",
                                 "usageRestrictions": "仅限工业用途", "decidedBy": "qa-lead"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_PENDING"))
                .andExpect(jsonPath("$.decisionSeq").value(1))
                .andExpect(jsonPath("$.decisionUnitCodes[0]").value("U1"));

        // 只有一名审批时放行拒绝
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-1", "approver": "alice", "role": "QA"}
                                """))
                .andExpect(status().isCreated());
        // 同角色重复审批
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-2", "approver": "carol", "role": "QA"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_ALREADY_APPROVED"));
        // 审批号冲突
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-1", "approver": "alice", "role": "SAFETY"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_CONFLICT"));
        // 审批号幂等重放
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-1", "approver": "alice", "role": "QA"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-3", "approver": "bob", "role": "SAFETY"}
                                """))
                .andExpect(status().isCreated());

        // 双角色齐备，放行 U1
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/release", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-1", "unitCode": "U1", "actedBy": "w"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RELEASE"))
                .andExpect(jsonPath("$.usageRestrictions").value("仅限工业用途"));
        // 处置号幂等重放
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/release", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-1", "unitCode": "U1", "actedBy": "w"}
                                """))
                .andExpect(status().isOk());
        // 已放行单元不能报废
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/scrap", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-X", "unitCode": "U1", "actedBy": "w"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_ALREADY_DISPOSED"));

        // 运单仍有 HELD 单元，保持隔离
        mockMvc.perform(get("/api/shipments/{w}", w))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        // 详情聚合
        mockMvc.perform(get("/api/shipments/{w}/investigations/{i}", w, "INV-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.evidenceVersion").value(1))
                .andExpect(jsonPath("$.lockedExcursionIntervals", hasSize(1)))
                .andExpect(jsonPath("$.liveExcursionIntervals", hasSize(1)))
                .andExpect(jsonPath("$.conclusions", hasSize(1)))
                .andExpect(jsonPath("$.approvalChain", hasSize(2)))
                .andExpect(jsonPath("$.dispositions", hasSize(1)))
                .andExpect(jsonPath("$.units", hasSize(2)))
                .andExpect(jsonPath("$.units[0].unitCode").value("U1"))
                .andExpect(jsonPath("$.units[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.units[1].status").value("HELD"))
                .andExpect(jsonPath("$.temperatureTimeline", hasSize(2)));

        // U2 报废完成调查
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/conclusions", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "SCRAP", "unitCodes": ["U2"], "decidedBy": "qa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCRAP_PENDING"))
                .andExpect(jsonPath("$.decisionSeq").value(2));
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/scrap", w, "INV-API-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-2", "unitCode": "U2", "actedBy": "w"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SCRAP"));
        mockMvc.perform(get("/api/shipments/{w}/investigations/{i}", w, "INV-API-1"))
                .andExpect(jsonPath("$.investigation.status").value("COMPLETED"));
        mockMvc.perform(get("/api/shipments/{w}", w))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void lateHistoryEventInvalidatesConclusionViaApi() throws Exception {
        String w = "WB-API-2";
        quarantine(w);
        createInvestigation(w, "INV-API-2");

        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/conclusions", w, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "CONTINUE_QUARANTINE", "decidedBy": "qa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTINUED"));

        // 后到历史事件把区间从 10:10 延长到 10:30
        event(w, "e3", "2026-06-01T10:30:00Z", 10.0);

        mockMvc.perform(get("/api/shipments/{w}/investigations/{i}", w, "INV-API-2"))
                .andExpect(jsonPath("$.investigation.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.investigation.evidenceVersion").value(2))
                .andExpect(jsonPath("$.evidenceExpanded").value(true))
                .andExpect(jsonPath("$.investigation.invalidationReason").isNotEmpty());

        // 失效结论不能直接处置
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/scrap", w, "INV-API-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-STALE", "unitCode": "DEFAULT", "actedBy": "w"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCLUSION_INVALIDATED"));
    }

    @Test
    void cannotCreateInvestigationForInTransitShipmentAndUnknownRoutes404() throws Exception {
        createShipment("WB-API-3");
        mockMvc.perform(post("/api/shipments/WB-API-3/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"investigationNo": "INV-API-3"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_QUARANTINED"));

        mockMvc.perform(get("/api/shipments/WB-API-3/investigations/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void releasedShipmentCanBeHandedOver() throws Exception {
        String w = "WB-API-4";
        quarantine(w);
        createInvestigation(w, "INV-API-4");
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/conclusions", w, "INV-API-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "CONDITIONAL_RELEASE",
                                 "validUntil": "2027-01-01T00:00:00Z",
                                 "usageRestrictions": "限用", "decidedBy": "qa"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-4-1", "approver": "alice", "role": "QA"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/approvals", w, "INV-API-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalNo": "A-API-4-2", "approver": "bob", "role": "SAFETY"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/shipments/{w}/investigations/{i}/release", w, "INV-API-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dispositionNo": "D-API-4", "unitCode": "DEFAULT", "actedBy": "w"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/shipments/{w}/handovers", w)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromHolder": "alice", "toHolder": "carol"}
                                """))
                .andExpect(status().isCreated());
    }
}
