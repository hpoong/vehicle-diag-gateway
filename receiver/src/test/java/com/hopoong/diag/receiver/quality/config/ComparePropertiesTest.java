package com.hopoong.diag.receiver.quality.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ComparePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(QualityConfig.class);
    @Test
    void defaultsToFalseAndBindsTrue() {
        runner.run(ctx -> assertThat(ctx.getBean(CompareProperties.class).naiveInsert()).isFalse());
        runner.withPropertyValues("receiver.compare.naive-insert=true")
                .run(ctx -> assertThat(ctx.getBean(CompareProperties.class).naiveInsert()).isTrue());
    }
}
