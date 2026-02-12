package com.hctamlyniv.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.DiscogsNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import Server.session.RedisConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RedisCuratedLinkStore implements CuratedLinkStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCuratedLinkStore.class);
    private static final String KEY_PREFIX = "curated:";
    private static final String BARCODE_PREFIX = "curated:barcode:";
    private static final String INDEX_KEY = "curated:index";
    
    private final ObjectMapper mapper;
    private final Map<String, CuratedLink> localFallback = new ConcurrentHashMap<>();
    
    public RedisCuratedLinkStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    
    private boolean isRedisAvailable() {
        return RedisConfig.isAvailable();
    }

    @Override
    public Optional<CuratedLink> find(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return Optional.empty();
        }
        
        String redisKey = KEY_PREFIX + normalizedKey;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String json = jedis.get(redisKey);
                    if (json != null) {
                        CuratedLink link = mapper.readValue(json, CuratedLink.class);
                        return Optional.of(link);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error reading curated link: {}", e.getMessage());
            }
        }
        
        return Optional.ofNullable(localFallback.get(normalizedKey));
    }

    @Override
    public Optional<CuratedLink> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        
        String redisKey = BARCODE_PREFIX + barcode;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String normalizedKey = jedis.get(redisKey);
                    if (normalizedKey != null) {
                        return find(normalizedKey);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error reading curated link by barcode: {}", e.getMessage());
            }
        }
        
        for (CuratedLink link : localFallback.values()) {
            if (barcode.equals(link.barcode())) {
                return Optional.of(link);
            }
        }
        
        return Optional.empty();
    }

    @Override
    public void save(CuratedLink link) {
        if (link == null || link.cacheKey() == null || link.cacheKey().isBlank()) {
            throw new IllegalArgumentException("Link and cacheKey are required");
        }
        
        String normalizedKey = link.cacheKey();
        String redisKey = KEY_PREFIX + normalizedKey;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String json = mapper.writeValueAsString(link);
                    jedis.set(redisKey, json);
                    jedis.sadd(INDEX_KEY, normalizedKey);
                    
                    if (link.barcode() != null && !link.barcode().isBlank()) {
                        jedis.set(BARCODE_PREFIX + link.barcode(), normalizedKey);
                    }
                    
                    log.info("Saved curated link to Redis: {}", normalizedKey);
                    return;
                }
            } catch (Exception e) {
                log.warn("Redis error saving curated link, falling back to memory: {}", e.getMessage());
            }
        }
        
        localFallback.put(normalizedKey, link);
        log.info("Saved curated link to memory: {}", normalizedKey);
    }

    @Override
    public void delete(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return;
        }
        
        String redisKey = KEY_PREFIX + normalizedKey;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    CuratedLink link = find(normalizedKey).orElse(null);
                    
                    jedis.del(redisKey);
                    jedis.srem(INDEX_KEY, normalizedKey);
                    
                    if (link != null && link.barcode() != null && !link.barcode().isBlank()) {
                        jedis.del(BARCODE_PREFIX + link.barcode());
                    }
                    
                    log.info("Deleted curated link from Redis: {}", normalizedKey);
                    return;
                }
            } catch (Exception e) {
                log.warn("Redis error deleting curated link: {}", e.getMessage());
            }
        }
        
        localFallback.remove(normalizedKey);
    }

    @Override
    public List<CuratedLink> listAll() {
        List<CuratedLink> result = new ArrayList<>();
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    Set<String> keys = jedis.smembers(INDEX_KEY);
                    for (String key : keys) {
                        find(key).ifPresent(result::add);
                    }
                    return result;
                }
            } catch (Exception e) {
                log.warn("Redis error listing curated links: {}", e.getMessage());
            }
        }
        
        result.addAll(localFallback.values());
        return result;
    }
}
