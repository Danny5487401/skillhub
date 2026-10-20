package com.iflytek.skillhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

class AsyncConfigTest {

    @Test
    void asyncExecutionAndConditionalSchedulingHaveSeparateConfigurations() {
        assertThat(AsyncConfig.class).hasAnnotation(EnableAsync.class);
        assertThat(SchedulingConfig.class).hasAnnotation(EnableScheduling.class);
        assertThat(SchedulingConfig.class).hasAnnotation(ConditionalOnProperty.class);
    }
}
