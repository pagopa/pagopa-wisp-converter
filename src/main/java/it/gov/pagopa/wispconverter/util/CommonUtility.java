package it.gov.pagopa.wispconverter.util;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import it.gov.pagopa.gen.wispconverter.client.cache.model.ServiceDto;
import it.gov.pagopa.gen.wispconverter.client.cache.model.StationDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentOptionModelDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentOptionModelResponseDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentPositionModelBaseResponseDto;
import it.gov.pagopa.gen.wispconverter.client.gpd.model.PaymentPositionModelDto;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.ConfigCacheService;
import it.gov.pagopa.wispconverter.service.model.session.SessionDataDTO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;


@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonUtility {

    /**
     * @param value value to deNullify.
     * @return return empty string if value is null
     */
    public static String deNull(String value) {
        return Optional.ofNullable(value).orElse("");
    }

    /**
     * @param value value to deNullify.
     * @return return empty string if value is null
     */
    public static String deNull(Object value) {
        return Optional.ofNullable(value).orElse("").toString();
    }

    /**
     * @param value value to deNullify.
     * @return return false if value is null
     */
    public static Boolean deNull(Boolean value) {
        return Optional.ofNullable(value).orElse(false);
    }

    public static String getExecutionTime(String startTime) {
        if (startTime != null) {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - Long.parseLong(startTime);
            return String.valueOf(executionTime);
        }
        return "-";
    }


    public static String getAppCode(AppErrorCodeMessageEnum error) {
        return String.format("%s-%s", Constants.SERVICE_CODE_APP, error.getCode());
    }

    public static String constructUrl(String protocol, String hostname, int port, String path, String query, String fragment) {
        try {
            String pathMod = null;
            if (null != path) {
                pathMod = path.startsWith("/") ? path : ("/" + path);
            }

            return new URI(
                    protocol.toLowerCase(),
                    null,
                    hostname,
                    port,
                    pathMod,
                    query,
                    fragment).toString();
        } catch (Exception e) {
            throw new AppException(AppErrorCodeMessageEnum.PARSING_GENERIC_ERROR);
        }
    }

    public static String getConfigKeyValueCache(Map<String, it.gov.pagopa.gen.wispconverter.client.cache.model.ConfigurationKeyDto> configurations, String key) {
        try {
            return configurations.get(key).getValue();
        } catch (NullPointerException e) {
            throw new AppException(AppErrorCodeMessageEnum.ERROR, "ConfigurationKey '" + key + "' not found in cache");
        }
    }

    public static PaymentOptionModelDto getSinglePaymentOption(PaymentPositionModelDto paymentPosition) {
        if (paymentPosition == null || paymentPosition.getPaymentOption() == null || paymentPosition.getPaymentOption().isEmpty()) {
            throw new AppException(AppErrorCodeMessageEnum.PAYMENT_OPTION_NOT_EXTRACTABLE);
        }
        PaymentOptionModelDto paymentOption = paymentPosition.getPaymentOption().get(0);
        if (paymentOption == null) {
            throw new AppException(AppErrorCodeMessageEnum.PAYMENT_OPTION_NOT_EXTRACTABLE);
        }
        return paymentOption;
    }

    public static PaymentOptionModelResponseDto getSinglePaymentOption(PaymentPositionModelBaseResponseDto paymentPosition) {
        if (paymentPosition == null || paymentPosition.getPaymentOption() == null || paymentPosition.getPaymentOption().isEmpty()) {
            throw new AppException(AppErrorCodeMessageEnum.PAYMENT_OPTION_NOT_EXTRACTABLE);
        }
        PaymentOptionModelResponseDto paymentOption = paymentPosition.getPaymentOption().get(0);
        if (paymentOption == null) {
            throw new AppException(AppErrorCodeMessageEnum.PAYMENT_OPTION_NOT_EXTRACTABLE);
        }
        return paymentOption;
    }

    public static ServiceBusProcessorClient getServiceBusProcessorClient(String connectionString,
                                                                         String queueName, Consumer<ServiceBusReceivedMessageContext> processMessage, Consumer<ServiceBusErrorContext> processError) {
        return new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(queueName)
                .processMessage(processMessage)
                .processError(processError)
                .buildProcessorClient();
    }

    public static void checkStationValidity(ConfigCacheService configCacheService, SessionDataDTO sessionData) {

        checkStation(configCacheService, sessionData.getCommonFields().getStationId(), false, null);
    }

    public static boolean isStationOnboardedOnGpd(ConfigCacheService configCacheService, SessionDataDTO sessionData, String gpdPath) {

        return checkStation(configCacheService, sessionData.getCommonFields().getStationId(), true, gpdPath);
    }

    private static boolean checkStation(ConfigCacheService configCacheService, String stationId, boolean checkIfOnboardedInGPD, String gpdPath) {

        boolean isOk = true;

        // retrieving station by station identifier
        StationDto station = configCacheService.getStationByIdFromCache(stationId);

        // check if station is correctly configured for a valid service
        ServiceDto service = station.getService();
        if (service == null || service.getPath() == null) {
            throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_STATION_REDIRECT_URL, station.getStationCode());
        }

        // check if station is onboarded on GPD and is correctly configured for v2 primitives
        if (checkIfOnboardedInGPD) {
            isOk = service.getPath().contains(gpdPath);
            if (isOk && station.getPrimitiveVersion() != 2) {
                throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_GPD_STATION, station.getStationCode());
            }
        }

        return isOk;
    }
}
