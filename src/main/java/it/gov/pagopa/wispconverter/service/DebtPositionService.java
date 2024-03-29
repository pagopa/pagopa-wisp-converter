package it.gov.pagopa.wispconverter.service;

import feign.FeignException;
import it.gov.pagopa.wispconverter.client.gpd.GPDClient;
import it.gov.pagopa.wispconverter.client.gpd.model.MultiplePaymentPosition;
import it.gov.pagopa.wispconverter.client.gpd.model.PaymentPosition;
import it.gov.pagopa.wispconverter.exception.AppClientException;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.service.mapper.DebtPositionMapper;
import it.gov.pagopa.wispconverter.service.model.RPTContentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DebtPositionService {

    private final GPDClient gpdClient;

    private final DebtPositionMapper mapper;

    public void executeBulkCreation(List<RPTContentDTO> rptContentDTOs) {

        try {
            Map<String, List<RPTContentDTO>> paymentPositionsByDomain = rptContentDTOs.stream().collect(Collectors.groupingBy(RPTContentDTO::getIdDominio));

            paymentPositionsByDomain.forEach((key, value) -> {
                List<PaymentPosition> paymentPositions = value.stream().map(mapper::toPaymentPosition).toList();

                // generating request
                MultiplePaymentPosition request = MultiplePaymentPosition.builder()
                        .paymentPositions(paymentPositions)
                        .build();

                // communicating with GPD-core service in order to execute the operation
                this.gpdClient.executeBulkCreation(key, request);
            });

        } catch (FeignException e) {
            throw new AppClientException(e.status(), AppErrorCodeMessageEnum.CLIENT_);
        }
    }


}
