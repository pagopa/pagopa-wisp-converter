package it.gov.pagopa.wispconverter.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = CacheRepository.class)
class CacheRepositoryTest {

    @MockBean
    @Qualifier("redisSimpleTemplate")
    private RedisTemplate<String, Object> redisSimpleTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Autowired
    @InjectMocks
    CacheRepository cacheRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisSimpleTemplate.opsForValue()).thenReturn(valueOperations); // Mock ValueOperations
    }

    @Test
    void hasKey() {
        when(redisSimpleTemplate.hasKey(anyString())).thenReturn(true); // Mock ValueOperations
        cacheRepository.hasKey("key");
        verify(redisSimpleTemplate, times(1)).hasKey(eq("key"));
    }
}