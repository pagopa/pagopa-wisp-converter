package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.RPTRequestRepository;
import it.gov.pagopa.wispconverter.repository.model.RPTRequestEntity;
import it.gov.pagopa.wispconverter.service.model.CommonRPTFieldsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static it.gov.pagopa.wispconverter.util.Constants.NODO_INVIA_CARRELLO_RPT;
import static it.gov.pagopa.wispconverter.util.Constants.NODO_INVIA_RPT;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConverterService {

    private final RPTExtractorService rptExtractorService;

    private final DebtPositionService debtPositionService;

    private final CacheService cacheService;

    private final CheckoutService checkoutService;

    private final RPTRequestRepository rptRequestRepository;

    public String convert(String sessionId) {

        // get RPT request entity from database
        RPTRequestEntity rptRequestEntity = getRPTRequestEntity(sessionId);

        // unmarshalling and mapping RPT content from request entity
        CommonRPTFieldsDTO commonRPTFieldsDTO = this.rptExtractorService.extractRPTContentDTOs(rptRequestEntity.getPrimitive(), rptRequestEntity.getPayload());

        // calling GPD creation API in order to generate the debt position associated to RPTs
        this.debtPositionService.createDebtPositions(commonRPTFieldsDTO);

        // call APIM policy for save key for decoupler and save in Redis cache the mapping of the request identifier needed for RT generation in next steps
        this.cacheService.storeRequestMappingInCache(commonRPTFieldsDTO, sessionId);

        // execute communication with Checkout service and set the redirection URI as response
        return this.checkoutService.executeCall(commonRPTFieldsDTO);
    }

    private RPTRequestEntity getRPTRequestEntity(String sessionId) {
        Optional<RPTRequestEntity> optRPTReqEntity = this.rptRequestRepository.findById(sessionId);
        return optRPTReqEntity.orElseThrow(() -> new AppException(AppErrorCodeMessageEnum.PERSISTENCE_RPT_NOT_FOUND, sessionId));

        // TODO RE
    }
}
