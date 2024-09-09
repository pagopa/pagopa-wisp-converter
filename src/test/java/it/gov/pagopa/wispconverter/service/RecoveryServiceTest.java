package it.gov.pagopa.wispconverter.service;

import it.gov.pagopa.wispconverter.controller.model.RecoveryReceiptResponse;
import it.gov.pagopa.wispconverter.exception.AppException;
import it.gov.pagopa.wispconverter.repository.RTRepository;
import it.gov.pagopa.wispconverter.repository.model.RTEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RecoveryServiceTest {

    @Mock
    private RTRepository rtRepository;

    @InjectMocks
    private RecoveryService recoveryService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        recoveryService.receiptGenerationWaitTime = 60L;
    }

    @Test
    public void testRecoverReceiptKOForCreditorInstitution_Success() throws Exception {
        String creditorInstitution = "77777777777";
        String dateFrom = "2024-09-05";
        String dateTo = "2024-09-09";
        List<RTEntity> mockRTEntities = List.of();

        when(rtRepository.findByOrganizationId(anyString(), anyString(), anyString())).thenReturn(mockRTEntities);

        RecoveryReceiptResponse response = recoveryService.recoverReceiptKOForCreditorInstitution(creditorInstitution, dateFrom, dateTo);

        assertNotNull(response);
        assertEquals(0, response.getPayments().size());
    }

    @Test
    public void testRecoverReceiptKOForCreditorInstitution_LowerBoundFailure() {
        String creditorInstitution = "77777777777";
        String dateFrom = "2024-09-01";  // Date earlier than valid start date
        String dateTo = "2024-09-09";

        AppException exception = assertThrows(
                AppException.class, () -> recoveryService.recoverReceiptKOForCreditorInstitution(creditorInstitution, dateFrom, dateTo)
        );

        assertEquals("The lower bound cannot be lower than [2024-09-03]", exception.getMessage());
    }

    @Test
    public void testRecoverReceiptKOForCreditorInstitution_UpperBoundFailure() {
        String creditorInstitution = "77777777777";
        String dateFrom = "2024-09-05";
        String dateTo = LocalDate.now().plusDays(1).toString();  // Future date, should fail

        AppException exception = assertThrows(
                AppException.class, () -> recoveryService.recoverReceiptKOForCreditorInstitution(creditorInstitution, dateFrom, dateTo)
        );

        String expectedMessage = String.format("The upper bound cannot be higher than [%s]", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        assertEquals(expectedMessage, exception.getMessage());
    }
}