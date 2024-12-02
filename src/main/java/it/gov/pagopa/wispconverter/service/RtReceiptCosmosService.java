package it.gov.pagopa.wispconverter.service;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import it.gov.pagopa.wispconverter.repository.RTRepository;
import it.gov.pagopa.wispconverter.repository.model.RTEntity;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptStatusEnum;
import it.gov.pagopa.wispconverter.repository.model.enumz.ReceiptTypeEnum;
import it.gov.pagopa.wispconverter.service.model.session.RPTContentDTO;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import it.gov.pagopa.wispconverter.util.AppBase64Util;
import it.gov.pagopa.wispconverter.util.ZipUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RtReceiptCosmosService {

    private static final String ILLEGAL_CHARS_FOR_ID = "[/\\\\#]";
    private final RTRepository rtRepository;

    @Transactional
    public void saveRTEntity(SessionDataDTO sessionData, ReceiptStatusEnum status) {
        String sessionId = sessionData.getCommonFields().getSessionId();
        for (RPTContentDTO rptContentDTO : sessionData.getAllRPTs()) {
            try {
                rtRepository.save(createRTEntity(sessionId, rptContentDTO, status, null, null, null));
            } catch (CosmosException e) {
                log.error("An exception occurred while saveRTEntity: " + e.getMessage());
            }
        }
    }

    @Transactional
    public void saveRTEntity(String sessionId, RPTContentDTO rptContentDTO, ReceiptStatusEnum status, String rawRt, ReceiptTypeEnum receiptType) {
        try {
            String encodedRt = AppBase64Util.base64Encode(ZipUtil.zip(rawRt));
            rtRepository.save(createRTEntity(sessionId, rptContentDTO, status, encodedRt, receiptType, ZonedDateTime.now().toInstant().toEpochMilli()));
        } catch (IOException | CosmosException e) {
            log.error("An exception occurred while saveRTEntity: " + e.getMessage());
        }
    }

    @Transactional
    public boolean updateReceiptStatus(RPTContentDTO rptContentDTO, ReceiptStatusEnum status) {
        String domainId = rptContentDTO.getRpt().getDomain().getDomainId();
        String iuv = rptContentDTO.getIuv();
        String ccp = rptContentDTO.getCcp();

        return this.updateReceiptStatus(domainId, iuv, ccp, status);
    }

    @Transactional
    public boolean updateReceiptStatus(String ci, String iuv, String ccp, ReceiptStatusEnum status) {
        try {
            String id = getId(ci, iuv, ccp);
            Optional<RTEntity> rtEntityOptional = rtRepository.findById(id, new PartitionKey(id));

            if (rtEntityOptional.isEmpty())
                return false;

            RTEntity rtEntity = rtEntityOptional.get();
            rtEntity.setReceiptStatus(status);
            rtRepository.save(rtEntity);

            return true;
        } catch (CosmosException e) {
            log.error("An exception occurred while saveRTEntity: " + e.getMessage());
            return false;
        }
    }

    @Transactional
    public boolean updateReceiptStatus(RTEntity rtEntity, ReceiptStatusEnum status) {
        try {
            rtEntity.setReceiptStatus(status);
            rtRepository.save(rtEntity);

            return true;
        } catch (CosmosException e) {
            log.error("An exception occurred while saveRTEntity: " + e.getMessage());
            return false;
        }
    }

    public boolean updateStatusToPaying(RPTContentDTO rptContentDTO) {
        String fiscalCode = rptContentDTO.getRpt().getDomain().getDomainId();
        Optional<RTEntity> rtEntityOpt = this.findById(fiscalCode, rptContentDTO.getIuv(), rptContentDTO.getCcp());
        if(rtEntityOpt.isPresent()) {
            RTEntity rtEntity = rtEntityOpt.get();
            // change status only if is REDIRECT, if it is equals or next to PAYING (ie PAYING, SENDING, SENT)
            // must not go back or overwritten with the same value
            if(rtEntity.getReceiptStatus().equals(ReceiptStatusEnum.REDIRECT)) {
                this.updateReceiptStatus(rtEntity, ReceiptStatusEnum.PAYING);
                log.debug("Receipt-rt with id = {} has been updated with status PAYING", rtEntity.getId());
                return true;
            }
            log.warn("Attempt to update receipt-rt with id = {} has been failed because is the status is {}",
                    rtEntity.getId(), rtEntity.getReceiptStatus());
        }

        return false;
    }

    public Optional<RTEntity> findById(String domainId, String iuv, String ccp) {
        String id = String.format("%s_%s_%s", domainId, iuv, ccp);
        // Remove illegal characters ['/', '\', '#'] because cannot be used in Resource ID
        return rtRepository.findById(id, new PartitionKey(id));
    }

    public boolean receiptRtExist(String domainId, String iuv, String ccp) {
        String id = getId(domainId, iuv, ccp);
        return rtRepository.findById(id, new PartitionKey(id)).isPresent();
    }

    private String getId(String domainId, String iuv, String ccp) {
        String id = String.format("%s_%s_%s", domainId, iuv, ccp);
        // Remove illegal characters ['/', '\', '#'] because cannot be used in Resource ID
        return id.replaceAll(ILLEGAL_CHARS_FOR_ID, "");
    }

    private RTEntity createRTEntity(String sessionId, RPTContentDTO rptContentDTO, ReceiptStatusEnum status, String rt, ReceiptTypeEnum receiptType, Long rtTimestamp) {
        String domainId = rptContentDTO.getRpt().getDomain().getDomainId();
        String iuv = rptContentDTO.getIuv();
        String ccp = rptContentDTO.getCcp();
        String id = getId(domainId, iuv, ccp);

        return RTEntity.builder()
                .id(id)
                .partitionKey(id)
                .iuv(iuv)
                .ccp(ccp)
                .sessionId(sessionId)
                .receiptStatus(status)
                .domainId(domainId)
                .rt(rt)
                .receiptType(receiptType)
                .rtTimestamp(rtTimestamp)
                .build();
    }
}
