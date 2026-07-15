package com.questionos.backend.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxSceneRoutingTest {

    @Test
    void engineeringRoutingKeepsDeliveryAndRollbackGrounding() {
        String context = SandboxSceneRouting.contextBlockFor(SandboxDeliberationScene.ENGINEERING);

        assertTrue(context.contains("工程与架构"));
        assertTrue(context.contains("部署"));
        assertTrue(context.contains("回滚"));
    }

    @Test
    void everySceneProducesAConcreteContextBlock() {
        for (SandboxDeliberationScene scene : SandboxDeliberationScene.values()) {
            assertTrue(SandboxSceneRouting.contextBlockFor(scene).length() > 40, scene.name());
        }
    }
}
