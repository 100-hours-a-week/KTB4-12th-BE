package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.lang.management.ManagementFactory;
import java.util.Objects;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

public final class ProfilingResultControl implements ProfilingResultControlMBean, AutoCloseable {

    private final ProfilingResultListener listener;
    private final String instanceId;
    private final MBeanServer server;
    private final ObjectName name;

    public ProfilingResultControl(
            ProfilingResultListener listener,
            String instanceId
    ) throws Exception {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("인스턴스 ID는 필수입니다.");
        }

        if (System.getProperty("com.sun.management.jmxremote.port") != null) {
            throw new IllegalStateException(
                    "이 운영 제어는 원격 JMX 포트를 사용하지 않습니다."
            );
        }

        this.listener = listener;
        this.instanceId = instanceId;
        this.server = ManagementFactory.getPlatformMBeanServer();
        this.name = new ObjectName(
                "com.gift.gift:type=ProfilingResultConsumer,instance=" + ObjectName.quote(instanceId)
        );

        server.registerMBean(
                new StandardMBean(
                        this,
                        ProfilingResultControlMBean.class
                ),
                name
        );
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getStatus() {
        return listener.status();
    }

    @Override
    public void pause(String expectedInstanceId) {
        requireInstance(expectedInstanceId);
        listener.pause();
    }

    @Override
    public void resume(String expectedInstanceId) {
        requireInstance(expectedInstanceId);
        listener.resume();
    }

    private void requireInstance(String expectedInstanceId) {
        if (!Objects.equals(instanceId, expectedInstanceId)) {
            throw new IllegalArgumentException("대상 인스턴스가 다릅니다.");
        }
    }

    @Override
    public void close() throws Exception {
        server.unregisterMBean(name);
    }
}
