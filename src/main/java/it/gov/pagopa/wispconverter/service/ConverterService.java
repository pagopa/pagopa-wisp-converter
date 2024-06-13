package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConverterService {

    private final RPTExtractorService rptExtractorService;

    private final DebtPositionService debtPositionService;

    private final DecouplerService decouplerService;

    private final CheckoutService checkoutService;

    private final RptCosmosService rptCosmosService;

    public String convert(String sessionId) {

        // set sessionId in thread context
        MDC.put(Constants.MDC_SESSION_ID, sessionId);

        // get RPT request entity from database
        RPTRequestEntity rptRequestEntity = rptCosmosService.getRPTRequestEntity(sessionId);

        // unmarshalling and mapping RPT content from request entity, generating session data
        SessionDataDTO sessionData = this.rptExtractorService.extractSessionData(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());

        // calling GPD creation API in order to generate the debt position associated to RPTs
        this.debtPositionService.createDebtPositions(sessionData); // TODO <--------- QUI

        // call APIM policy for save key for decoupler and save in Redis cache the mapping of the request identifier needed for RT generation in next steps
        this.decouplerService.storeRequestMappingInCache(sessionData, sessionId); // TODO <--------- QUI

        // execute communication with Checkout service and set the redirection URI as response
        return this.checkoutService.executeCall(sessionData); // TODO <--------- QUI
    }
}
