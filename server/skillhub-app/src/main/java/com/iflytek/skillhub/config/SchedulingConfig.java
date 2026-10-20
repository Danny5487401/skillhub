package com.iflytek.skillhub.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(prefix = "skillhub.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
