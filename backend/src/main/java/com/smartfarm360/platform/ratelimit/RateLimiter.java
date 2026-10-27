package com.smartfarm360.platform.ratelimit;

import com.smartfarm360.platform.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter for sign-in, registration and other abuse-prone endpoints. Counters are kept in PostgreSQL
 * (SaaS operations design, O13), so they survive restarts and are shared by every application instance. Each count is
 * committed on its own, whether or not the request it belongs to succeeds.
 */
@Component
public class RateLimiter {
    /** Where the counts live: PostgreSQL in the application, memory in unit tests. */
    interface Counters {
        /** Adds one hit to the key's current window and returns the window's hits. */
        int hit(String key, Instant windowStart, Instant expiresAt);

        void clear();
    }

    private final Counters counters;
    private final Clock clock;

    @Autowired
    public RateLimiter(JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        this(clock, new PostgresCounters(jdbc, transactions));
    }

    RateLimiter(Clock clock) { this(clock, new MemoryCounters()); }

    RateLimiter(Clock clock, Counters counters) {
        this.clock = clock;
        this.counters = counters;
    }

    /** Throws 429 RATE_LIMITED when {@code key} has been used more than {@code limit} times in {@code window}. */
    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please wait and try again.");
        }
    }

    boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long size = window.toMillis();
        Instant start = Instant.ofEpochMilli(now - Math.floorMod(now, size));
        return counters.hit(key, start, start.plusMillis(size)) <= limit;
    }

    /** Removes every count (tests). */
    public void reset() { counters.clear(); }

    static final class PostgresCounters implements Counters {
        private final JdbcClient jdbc;
        private final TransactionTemplate own;

        PostgresCounters(JdbcClient jdbc, PlatformTransactionManager transactions) {
            this.jdbc = jdbc;
            this.own = new TransactionTemplate(transactions);
            this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        public int hit(String key, Instant windowStart, Instant expiresAt) {
            Integer hits = own.execute(status -> {
                // Old windows are removed now and then, so the table stays small
                if (Math.floorMod(windowStart.toEpochMilli() / 1000 + key.hashCode(), 50) == 0) {
                    jdbc.sql("delete from sf_rate_limit_counters where expires_at < now()").update();
                }
                return jdbc.sql("""
                                insert into sf_rate_limit_counters (limit_key, window_start, hits, expires_at) values (:k, :s, 1, :e)
                                on conflict (limit_key, window_start) do update set hits = sf_rate_limit_counters.hits + 1
                                returning hits""")
                        .param("k", key.length() > 300 ? key.substring(0, 300) : key).param("s", Timestamp.from(windowStart))
                        .param("e", Timestamp.from(expiresAt)).query(Integer.class).single();
            });
            return hits == null ? Integer.MAX_VALUE : hits;
        }

        @Override
        public void clear() { own.executeWithoutResult(status -> jdbc.sql("delete from sf_rate_limit_counters").update()); }
    }

    static final class MemoryCounters implements Counters {
        private final Map<String, Integer> hits = new ConcurrentHashMap<>();

        @Override
        public int hit(String key, Instant windowStart, Instant expiresAt) { return hits.merge(key + "|" + windowStart.toEpochMilli(), 1, Integer::sum); }

        @Override
        public void clear() { hits.clear(); }
    }
}
