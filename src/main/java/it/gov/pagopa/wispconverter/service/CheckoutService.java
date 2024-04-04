package it.gov.pagopa.wispconverter.service;

import feign.Response;
import it.gov.pagopa.wispconverter.client.cache.model.ConfigDataV1;
import it.gov.pagopa.wispconverter.client.cache.model.Redirect;
import it.gov.pagopa.wispconverter.client.cache.model.Station;
import it.gov.pagopa.wispconverter.client.checkout.CheckoutClient;
import it.gov.pagopa.wispconverter.client.checkout.model.Cart;
import it.gov.pagopa.wispconverter.client.checkout.model.ReturnURLs;
import it.gov.pagopa.wispconverter.exception.AppErrorCodeMessageEnum;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.service.mapper.CartMapper;
import it.gov.pagopa.wispconverter.service.model.CommonRPTFieldsDTO;
import java.util.Collection;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CheckoutService {

    private final CheckoutClient checkoutClient;

    private final ConfigCacheService configCacheService;

    private final CartMapper mapper;

    public String executeCall(CommonRPTFieldsDTO commonRPTFieldsDTO) {

        String location;

        // execute mapping for Checkout carts invocation
        Cart cart = mapper.toCart(commonRPTFieldsDTO);
        String stationRedirectURL = getRedirectURL(cart.getStationId());
        cart.setReturnURLs(ReturnURLs.builder()
                .returnOkUrl(stationRedirectURL + "/success.html")
                .returnCancelUrl(stationRedirectURL + "/cancel.html")
                .returnErrorUrl(stationRedirectURL + "/error.html")
                .build());

        // call Checkout carts API, receive Checkout response and returns redirection URI
        int status;
        try (Response response = checkoutClient.executeCreation(cart)) {
            status = response.status();
            if (status != 302) {
                throw new AppException(AppErrorCodeMessageEnum.CLIENT_CHECKOUT, status);
            }
            Collection<String> locationHeader = response.headers().get("location");
            if (locationHeader == null) {
                throw new AppException(AppErrorCodeMessageEnum.CLIENT_CHECKOUT_NO_REDIRECT_LOCATION);
            }
            location = locationHeader.stream().findFirst().orElseThrow(() -> new AppException(AppErrorCodeMessageEnum.CLIENT_CHECKOUT_INVALID_REDIRECT_LOCATION));
        }

        return location;
    }

    private String getRedirectURL(String stationId) {
        ConfigDataV1 cache = configCacheService.getCache();
        Map<String, Station> stations = cache.getStations();
        Station station = stations.get(stationId);
        if(station==null){throw new AppException(AppErrorCodeMessageEnum.CONFIGURATION_INVALID_STATION, stationId);}
        Redirect redirect = station.getRedirect();
        String protocol = redirect.getProtocol() == null ? "http" : redirect.getProtocol().getValue().toLowerCase();
        String url = redirect.getIp() + "/" + redirect.getPath();
        url = url.replace("//", "/");

        return protocol + "://" + url;
    }
}
