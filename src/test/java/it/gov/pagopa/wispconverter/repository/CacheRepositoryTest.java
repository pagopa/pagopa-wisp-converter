package it.gov.pagopa.wispconverter.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = CacheRepository.class)
class CacheRepositoryTest {

    @MockBean
    @Qualifier("redisSimpleTemplate")
    private RedisTemplate<String, Object> redisSimpleTemplate;

    @MockBean
    @Qualifier("redisBinaryTemplate")
    private RedisTemplate<String, byte[]> redisBinaryTemplate;

    @MockBean
    private ValueOperations<String, Object> valueOperations;

    @MockBean
    private ValueOperations<String, byte[]> binaryValueOperations;

    @Autowired
    CacheRepository cacheRepository;

    @BeforeEach
    void setUp() {
        when(redisSimpleTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisBinaryTemplate.opsForValue()).thenReturn(binaryValueOperations);
    }

    @Test
    void hasKey() {
        when(redisSimpleTemplate.hasKey(anyString())).thenReturn(true);
        boolean result = cacheRepository.hasKey("key");
        verify(redisSimpleTemplate, times(1)).hasKey(eq("key"));
        assertTrue(result, "The hasKey method should return true");
    }

    @Test
    void readByte() {
        byte[] expectedBytes = "test".getBytes();
        when(binaryValueOperations.get(anyString())).thenReturn(expectedBytes);
        byte[] result = cacheRepository.readByte("binary-key");
        verify(binaryValueOperations, times(1)).get(eq("binary-key"));
        assertArrayEquals(expectedBytes, result, "The readByte method should return the expected byte array");
    }
}