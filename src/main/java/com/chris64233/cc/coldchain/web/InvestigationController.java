package com.chris64233.cc.coldchain.web;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.DispositionRecord;
import com.chris64233.cc.coldchain.service.InvestigationService;
import com.chris64233.cc.coldchain.web.dto.ApprovalRequest;
import com.chris64233.cc.coldchain.web.dto.CreateInvestigationRequest;
import com.chris64233.cc.coldchain.web.dto.DispositionRequest;
import com.chris64233.cc.coldchain.web.dto.InvestigationDetailResponse;
import com.chris64233.cc.coldchain.web.dto.InvestigationResponse;
import com.chris64233.cc.coldchain.web.dto.PackagingUnitResponse;
import com.chris64233.cc.coldchain.web.dto.RegisterUnitRequest;
import com.chris64233.cc.coldchain.web.dto.SubmitConclusionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shipments/{waybillNo}")
public class InvestigationController {

    private final InvestigationService investigationService;

    public InvestigationController(InvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    // ---- 包装单元 ----

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    public PackagingUnitResponse registerUnit(@PathVariable String waybillNo,
                                              @Valid @RequestBody RegisterUnitRequest request) {
        return PackagingUnitResponse.from(
                investigationService.registerUnit(waybillNo, request.unitCode(), request.quantity()));
    }

    @GetMapping("/units")
    public List<PackagingUnitResponse> listUnits(@PathVariable String waybillNo) {
        return investigationService.listUnits(waybillNo).stream()
                .map(PackagingUnitResponse::from)
                .toList();
    }

    // ---- 调查 ----

    @PostMapping("/investigations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestigationResponse createInvestigation(@PathVariable String waybillNo,
                                                     @Valid @RequestBody CreateInvestigationRequest request) {
        return InvestigationResponse.from(
                investigationService.createInvestigation(waybillNo, request.investigationNo()));
    }

    @GetMapping("/investigations")
    public List<InvestigationResponse> listInvestigations(@PathVariable String waybillNo) {
        return investigationService.listInvestigations(waybillNo).stream()
                .map(InvestigationResponse::from)
                .toList();
    }

    @GetMapping("/investigations/{investigationNo}")
    public InvestigationDetailResponse getInvestigation(@PathVariable String waybillNo,
                                                        @PathVariable String investigationNo) {
        assertBelongsToShipment(waybillNo, investigationNo);
        return InvestigationDetailResponse.from(
                investigationService.getInvestigationDetail(investigationNo));
    }

    @PostMapping("/investigations/{investigationNo}/conclusions")
    public InvestigationResponse submitConclusion(@PathVariable String waybillNo,
                                                  @PathVariable String investigationNo,
                                                  @Valid @RequestBody SubmitConclusionRequest request) {
        assertBelongsToShipment(waybillNo, investigationNo);
        return InvestigationResponse.from(investigationService.submitConclusion(
                investigationNo, request.decision(), request.unitCodes(),
                request.validUntil(), request.usageRestrictions(), request.decidedBy()));
    }

    @PostMapping("/investigations/{investigationNo}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestigationDetailResponse.ApprovalDto approve(@PathVariable String waybillNo,
                                                           @PathVariable String investigationNo,
                                                           @Valid @RequestBody ApprovalRequest request) {
        assertBelongsToShipment(waybillNo, investigationNo);
        ApprovalRecord record = investigationService.approve(
                investigationNo, request.approvalNo(), request.approver(), request.role());
        return new InvestigationDetailResponse.ApprovalDto(
                record.getId(), record.getApprovalNo(), record.getApprover(), record.getRole(),
                record.getDecisionSeq(), record.getEvidenceVersion(), record.getCreatedAt());
    }

    @PostMapping("/investigations/{investigationNo}/scrap")
    public InvestigationDetailResponse.DispositionDto scrap(@PathVariable String waybillNo,
                                                            @PathVariable String investigationNo,
                                                            @Valid @RequestBody DispositionRequest request) {
        assertBelongsToShipment(waybillNo, investigationNo);
        DispositionRecord record = investigationService.scrap(
                investigationNo, request.dispositionNo(), request.unitCode(), request.actedBy());
        return toDisposition(record);
    }

    @PostMapping("/investigations/{investigationNo}/release")
    public InvestigationDetailResponse.DispositionDto release(@PathVariable String waybillNo,
                                                              @PathVariable String investigationNo,
                                                              @Valid @RequestBody DispositionRequest request) {
        assertBelongsToShipment(waybillNo, investigationNo);
        DispositionRecord record = investigationService.release(
                investigationNo, request.dispositionNo(), request.unitCode(), request.actedBy());
        return toDisposition(record);
    }

    private void assertBelongsToShipment(String waybillNo, String investigationNo) {
        if (!waybillNo.equals(investigationService.getInvestigation(investigationNo).getWaybillNo())) {
            throw com.chris64233.cc.coldchain.service.ApiException.notFound(
                    "调查不存在: " + investigationNo);
        }
    }

    private static InvestigationDetailResponse.DispositionDto toDisposition(DispositionRecord record) {
        return new InvestigationDetailResponse.DispositionDto(
                record.getId(), record.getDispositionNo(), record.getType().name(),
                record.getUnitCode(), record.getEvidenceVersion(), record.getDecisionSeq(),
                record.getActedBy(), record.getValidUntil(), record.getUsageRestrictions(),
                record.getCreatedAt());
    }
}
