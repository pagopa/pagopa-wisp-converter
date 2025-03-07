package it.gov.pagopa.wispconverter.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Component
@Slf4j
public class CacheRepository {

    @Autowired
    @Qualifier("redisSimpleTemplate")
    private RedisTemplate<String, Object> redisSimpleTemplate;

    @Autowired
    @Qualifier("redisBinaryTemplate")
    private RedisTemplate<String, byte[]> redisBinaryTemplate;

    public void insert(String key, String value, long ttlInMinutes) {
        this.redisSimpleTemplate.opsForValue().set(key, value, Duration.ofMinutes(ttlInMinutes));
    }

    public void insert(String key, String value, long ttl, ChronoUnit chronoUnit) {
        this.redisSimpleTemplate.opsForValue().set(key, value, Duration.of(ttl, chronoUnit));
    }

    public <T> T read(String key, Class<T> clazz) {
        T result = null;
        try {
            Object value = this.redisSimpleTemplate.opsForValue().get(key);
            result = clazz.cast(value);
        } catch (ClassCastException e) {
            log.error(String.format("Cannot correctly parse the object retrieved with key [%s] in [%s] class", key, clazz.getCanonicalName()));
        }
        return result;
    }

    public byte[] readByte(String key) {
        byte[] result = {};
        try {
            result = this.redisBinaryTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error(String.format("Cannot correctly extract binary object retrieved with key [%s]", key));
        }
        return result;
    }

    public boolean delete(String key) {
        Boolean isDeleted = this.redisSimpleTemplate.delete(key);
        return isDeleted != null && isDeleted;
    }

    public Boolean hasKey(String key) {
        return this.redisSimpleTemplate.hasKey(key);
    }
}
