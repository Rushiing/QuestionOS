package com.questionos.backend.config;

import com.questionos.backend.api.SandboxController;
import com.questionos.backend.domain.ConversationSession;
import com.questionos.backend.domain.SessionMode;
import com.questionos.backend.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Instant;
import java.util.Map;
import static org.mockito.Mockito.*;

class SessionCorsTest {
    @Test void configuredOriginCreatesSessionButUnknownOriginIsRejected() {
        SessionService service = mock(SessionService.class);
        when(service.createSession("", SessionMode.CALIBRATION, "cors test"))
                .thenReturn(new ConversationSession("test", "", SessionMode.CALIBRATION,
                        Instant.now(), Instant.now().plusSeconds(3600)));
        var config = new CorsOriginEvaluator("https://questionos.daydreamer.world,https://second.example.test");
        var client = WebTestClient.bindToController(new SandboxController(service))
                .corsMappings(config::addCorsMappings).configureClient().baseUrl("http://backend:8080").build();
        for (String origin : new String[]{"https://questionos.daydreamer.world", "https://second.example.test"}) {
            client.post().uri("/api/v1/sandbox/sessions").header("Origin", origin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("mode", "CALIBRATION", "question", "cors test"))
                    .exchange().expectStatus().isCreated()
                    .expectHeader().valueEquals("Access-Control-Allow-Origin", origin);
        }
        clearInvocations(service);
        client.post().uri("/api/v1/sandbox/sessions").header("Origin", "https://untrusted.example.test")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", "CALIBRATION", "question", "cors test"))
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }
}
