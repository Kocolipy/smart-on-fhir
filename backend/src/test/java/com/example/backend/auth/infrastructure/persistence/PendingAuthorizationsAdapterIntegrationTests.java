package com.example.backend.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.domain.PendingAuthorizations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The pending authorization request's store, against the real Redis (D27): what is held is taken
 * once, by exactly one caller however many race for it, and by no other session.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class PendingAuthorizationsAdapterIntegrationTests {

    @Autowired
    private PendingAuthorizations pending;

    @Test
    void whatIsHeldIsTaken() {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "pending-request", Duration.ofMinutes(1));

        assertThat(pending.take(session)).contains("pending-request");
    }

    @Test
    void whatWasTakenCannotBeTakenAgain() {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "pending-request", Duration.ofMinutes(1));
        pending.take(session);

        assertThat(pending.take(session)).isEmpty();
    }

    @Test
    void aSessionHoldingNothingHasNothingToTake() {
        assertThat(pending.take(UUID.randomUUID().toString())).isEmpty();
    }

    /** Session-scoped: another session's request is not this one's to take. */
    @Test
    void anotherSessionCannotTakeIt() {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "pending-request", Duration.ofMinutes(1));

        assertThat(pending.take(UUID.randomUUID().toString())).isEmpty();
        assertThat(pending.take(session)).contains("pending-request");
    }

    /** A later authorize hop in the same session replaces the request it held. */
    @Test
    void aSecondHoldReplacesTheFirst() {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "first", Duration.ofMinutes(1));
        pending.hold(session, "second", Duration.ofMinutes(1));

        assertThat(pending.take(session)).contains("second");
    }

    @Test
    void anExpiredRequestCannotBeTaken() throws InterruptedException {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "pending-request", Duration.ofMillis(50));
        Thread.sleep(300);

        assertThat(pending.take(session)).isEmpty();
    }

    /** Of many concurrent takes of one held request, exactly one receives it. */
    @Test
    void ofConcurrentTakesExactlyOneReceivesIt() throws Exception {
        String session = UUID.randomUUID().toString();
        pending.hold(session, "pending-request", Duration.ofMinutes(1));
        int racers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Optional<String>> taken = new ArrayList<>();
        try {
            Callable<Optional<String>> take = () -> {
                start.await();
                return pending.take(session);
            };
            List<Future<Optional<String>>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(take));
            }
            start.countDown();
            for (Future<Optional<String>> future : futures) {
                taken.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(taken.stream().filter(Optional::isPresent)).hasSize(1);
    }
}
