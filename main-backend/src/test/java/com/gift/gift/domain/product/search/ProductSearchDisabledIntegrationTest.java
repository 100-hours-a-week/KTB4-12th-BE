package com.gift.gift.domain.product.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.product-search.enabled=false",
        "spring.elasticsearch.uris=http://127.0.0.1:1"
})
class ProductSearchDisabledIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("ES가 비활성화되면 ES 연결 없이 서버 상태와 MySQL 상품 검색이 정상 동작한다")
    void disabledSearch_keepsHealthAndMysqlSearchAvailable() throws Exception {
        assertThat(context.getBeansOfType(ProductSearchRepository.class)).isEmpty();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(jsonMapper.readTree(health.body()).get("status").asString()).isEqualTo("UP");

            HttpResponse<String> search = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/products?query=es-disabled-check-8f21b7&sort=POPULAR"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(search.statusCode()).isEqualTo(200);
            var data = jsonMapper.readTree(search.body()).get("data");
            assertThat(data.get("products").isArray()).isTrue();
            assertThat(data.get("products").size()).isZero();
            assertThat(data.get("pagination").get("hasNext").asBoolean()).isFalse();
            assertThat(data.get("pagination").get("nextCursor").isNull()).isTrue();
        }
    }
}
