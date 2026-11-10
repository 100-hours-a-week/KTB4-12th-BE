package com.gift.gift.infrastructure.ai.profiling.amqp;

public interface ProfilingResultControlMBean {
    String getInstanceId();
    String getStatus();
    void pause(String expectedInstanceId);
    void resume(String expectedInstanceId);
}
