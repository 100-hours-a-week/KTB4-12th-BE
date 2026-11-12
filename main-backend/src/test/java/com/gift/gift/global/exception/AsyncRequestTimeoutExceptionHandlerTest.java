package com.gift.gift.global.exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncRequestTimeoutExceptionHandlerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("실제 Servlet 타임아웃은 시작한 SSE에 JSON을 쓰지 않고 미시작 응답에는 503을 반환한다")
    void asyncTimeoutPreservesCommittedStreamAndReturns503BeforeCommit() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(temporaryDirectory.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context web = tomcat.addContext("", Files.createDirectory(temporaryDirectory.resolve("web")).toString());
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(web.getServletContext());
        context.register(WebConfig.class);
        Wrapper servlet = Tomcat.addServlet(web, "api", new DispatcherServlet(context));
        servlet.setAsyncSupported(true);
        servlet.setLoadOnStartup(1);
        web.addServletMappingDecoded("/", "api");
        try (HttpClient client = HttpClient.newHttpClient()) {
            tomcat.start();
            String endpoint = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
            HttpResponse<String> stream = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/stream"))
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("Content-Type")).hasValue("text/event-stream");
            assertThat(stream.body()).isEqualTo("data:connected\n\n");

            HttpResponse<String> uncommitted = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/timeout"))
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(uncommitted.statusCode()).isEqualTo(503);
            assertThat(uncommitted.body()).isEmpty();
        } finally {
            tomcat.stop();
            tomcat.destroy();
            context.close();
        }
    }

    @Configuration
    @EnableWebMvc
    static class WebConfig {
        @Bean
        TimeoutController controller() { return new TimeoutController(); }

        @Bean
        GlobalExceptionHandler exceptionHandler() { return new GlobalExceptionHandler(); }
    }

    @RestController
    static class TimeoutController {
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() throws Exception {
            SseEmitter emitter = new SseEmitter(100L);
            emitter.send(SseEmitter.event().data("connected"));
            return emitter;
        }

        @GetMapping(value = "/timeout", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter timeout() { return new SseEmitter(100L); }
    }
}
