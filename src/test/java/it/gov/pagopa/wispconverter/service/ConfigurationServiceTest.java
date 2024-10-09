package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.controller.model.ConfigurationModel;
import it.gov.pagopa.wispconverter.repository.ConfigurationRepository;
import it.gov.pagopa.wispconverter.repository.model.ConfigurationEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles(profiles = "test")
@SpringBootTest
class ConfigurationServiceTest {

    @MockBean
    ConfigurationRepository configurationRepository;
    @MockBean
    private RecoveryService recoveryService;
    @MockBean
    private PaaInviaRTSenderService paaInviaRTSenderService;

    @Autowired
    @InjectMocks
    ConfigurationService configurationService;


    @Captor
    ArgumentCaptor<ConfigurationEntity> argumentCaptor;


    @Test
    void getCreditorInstitutionsConfiguration() {
        when(configurationRepository.findById(any())).thenReturn(Optional.of(ConfigurationEntity.builder().id("cis").content("12345678910").build()));
        String configurationCreditorInstitutions = configurationService.getCreditorInstitutionConfiguration();
        assertEquals("12345678910", configurationCreditorInstitutions);
    }

    @Test
    void getStationsConfiguration() {
        when(configurationRepository.findById(any())).thenReturn(Optional.of(ConfigurationEntity.builder().id("stations").content("12345678910").build()));
        String configurationCreditorInstitutions = configurationService.getCreditorInstitutionConfiguration();
        assertEquals("12345678910", configurationCreditorInstitutions);
    }

    @Test
    void createCreditorInstitutionsConfiguration() {
        configurationService.createCreditorInstitutionsConfiguration(ConfigurationModel.builder().build());
        verify(configurationRepository, times(1)).save(argumentCaptor.capture());
        assertEquals("cis", argumentCaptor.getValue().getId());

    }

    @Test
    void createStationsConfiguration() {
        configurationService.createStationsConfiguration(ConfigurationModel.builder().build());
        verify(configurationRepository, times(1)).save(argumentCaptor.capture());
        assertEquals("stations", argumentCaptor.getValue().getId());
    }
}