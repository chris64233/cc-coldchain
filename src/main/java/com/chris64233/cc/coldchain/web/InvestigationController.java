package com.chris64233.cc.coldchain.web;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.PackingUnit;
import com.chris64233.cc.coldchain.service.ColdChainService;
import com.chris64233.cc.coldchain.service.InvestigationService;
import com.chris64233.cc.coldchain.service.InvestigationView;
import com.chris64233.cc.coldchain.web.dto.ApprovalRequest;
import com.chris64233.cc.coldchain.web.dto.ApprovalResponse;
import com.chris64233.cc.coldchain.web.dto.ConcludeRequest;
import com.chris64233.cc.coldchain.web.dto.CreateInvestigationRequest;
import com.chris64233.cc.coldchain.web.dto.InvestigationDetailResponse;
import com.chris64233.cc.coldchain.web.dto.InvestigationResponse;
import com.chris64233.cc.coldchain.web.dto.PackingUnitResponse;
import com.chris64233.cc.coldchain.web.dto.RegisterUnitRequest;
import com.chris64233.cc.coldchain.web.dto.ReleaseRequest;
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

    private final ColdChainService coldChainService;
    private final InvestigationService investigationService;

    public InvestigationController(ColdChainService coldChainService,
                                   InvestigationService investigationService) {
        this.coldChainService = coldChainService;
        this.investigationService = investigationService;
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    public PackingUnitResponse registerUnit(@PathVariable String waybillNo,
                                            @Valid @RequestBody RegisterUnitRequest request) {
        return PackingUnitResponse.from(coldChainService.registerPackingUnit(waybillNo, request.unitCode()));
    }

    @GetMapping("/units")
    public List<PackingUnitResponse> listUnits(@PathVariable String waybillNo) {
        return coldChainService.listPackingUnits(waybillNo).stream()
                .map(PackingUnitResponse::from)
                .toList();
    }

    @PostMapping("/investigations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestigationResponse createInvestigation(@PathVariable String waybillNo,
                                                     @Valid @RequestBody CreateInvestigationRequest request) {
        Investigation investigation = investigationService.createInvestigation(
                waybillNo, request.investigationNo(), request.unitCodes(), request.reason());
        return InvestigationResponse.summary(investigation);
    }

    @GetMapping("/investigations")
    public List<InvestigationResponse> listInvestigations(@PathVariable String waybillNo) {
        return investigationService.listInvestigations(waybillNo).stream()
                .map(InvestigationResponse::summary)
                .toList();
    }

    @GetMapping("/investigations/{investigationNo}")
    public InvestigationDetailResponse getInvestigation(@PathVariable String waybillNo,
                                                        @PathVariable String investigationNo) {
        InvestigationView view = investigationService.getInvestigationView(waybillNo, investigationNo);
        return InvestigationDetailResponse.from(view);
    }

    @PostMapping("/investigations/{investigationNo}/conclusion")
    public InvestigationResponse conclude(@PathVariable String waybillNo,
                                          @PathVariable String investigationNo,
                                          @Valid @RequestBody ConcludeRequest request) {
        Investigation investigation = investigationService.conclude(
                waybillNo, investigationNo, request.dispositionEventId(), request.dispositionType(),
                request.validUntil(), request.usageRestrictions(), request.operator());
        return InvestigationResponse.summary(investigation);
    }

    @PostMapping("/investigations/{investigationNo}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalResponse approve(@PathVariable String waybillNo,
                                    @PathVariable String investigationNo,
                                    @Valid @RequestBody ApprovalRequest request) {
        ApprovalRecord record = investigationService.approve(
                waybillNo, investigationNo, request.approvalEventId(), request.approver(), request.role());
        return ApprovalResponse.from(record);
    }

    @PostMapping("/investigations/{investigationNo}/release")
    public InvestigationResponse release(@PathVariable String waybillNo,
                                         @PathVariable String investigationNo,
                                         @Valid @RequestBody ReleaseRequest request) {
        Investigation investigation = investigationService.executeRelease(
                waybillNo, investigationNo, request.dispositionEventId(), request.operator());
        return InvestigationResponse.summary(investigation);
    }
}
