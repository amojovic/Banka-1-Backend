package com.banka1.userService.scheduled;

import com.banka1.userService.repository.ConfirmationTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TokenCleanupScheduler}.
 */
@ExtendWith(MockitoExtension.class)
class TokenCleanupSchedulerTest {

    @Mock
    private ConfirmationTokenRepository confirmationTokenRepository;

    @InjectMocks
    private TokenCleanupScheduler scheduler;

    @Test
    void cleanUpExpiredTokensDelegatesToRepository() {
        scheduler.cleanUpExpiredTokens();

        verify(confirmationTokenRepository)
                .deleteAllByExpirationDateTimeNotNullAndExpirationDateTimeBefore(any(LocalDateTime.class));
    }

    @Test
    void cleanUpExpiredTokensPassesTimestampBeforeOrEqualNow() {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);

        LocalDateTime before = LocalDateTime.now();
        scheduler.cleanUpExpiredTokens();
        LocalDateTime after = LocalDateTime.now();

        verify(confirmationTokenRepository)
                .deleteAllByExpirationDateTimeNotNullAndExpirationDateTimeBefore(captor.capture());

        LocalDateTime captured = captor.getValue();
        assertThat(captured).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(captured).isBeforeOrEqualTo(after.plusSeconds(1));
    }

    @Test
    void cleanUpExpiredTokensCanBeCalledMultipleTimes() {
        scheduler.cleanUpExpiredTokens();
        scheduler.cleanUpExpiredTokens();
        scheduler.cleanUpExpiredTokens();

        // Verify the repository method was called exactly 3 times, no exceptions thrown
        verify(confirmationTokenRepository, org.mockito.Mockito.times(3))
                .deleteAllByExpirationDateTimeNotNullAndExpirationDateTimeBefore(any(LocalDateTime.class));
    }
}

