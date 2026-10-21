package com.gift.gift.infrastructure.payment;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.infrastructure.payment.client.PaymentCommandClient;
import com.gift.gift.infrastructure.payment.client.PaymentQueryClient;
import com.gift.gift.infrastructure.payment.client.PaymentServerErrorHandler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentClientProperties.class)
public class PaymentClientConfig {

    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Bean
    public PaymentServerErrorHandler paymentServerErrorHandler(ObjectMapper objectMapper) {
        return new PaymentServerErrorHandler(objectMapper);
    }

    @Bean
    public RestClient paymentCommandRestClient(PaymentClientProperties properties, PaymentServerErrorHandler errorHandler) {
        return createClient(
                properties,
                properties.commandTimeout(),
                errorHandler
        );
    }

    @Bean
    public RestClient paymentQueryRestClient(PaymentClientProperties properties, PaymentServerErrorHandler errorHandler) {
        return createClient(
                properties,
                properties.queryTimeout(),
                errorHandler
        );
    }

    @Bean
    public PaymentCommandClient paymentCommandClient(
            @Qualifier("paymentCommandRestClient")
            RestClient restClient
    ) {
        return createProxy(restClient, PaymentCommandClient.class);
    }

    @Bean
    public PaymentQueryClient paymentQueryClient(
            @Qualifier("paymentQueryRestClient")
            RestClient restClient
    ) {
        return createProxy(restClient, PaymentQueryClient.class);
    }

    private RestClient createClient(
            PaymentClientProperties properties,
            Duration readTimeout,
            PaymentServerErrorHandler errorHandler
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(
                        INTERNAL_API_KEY_HEADER,
                        properties.apiKey()
                )
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .defaultStatusHandler(errorHandler)
                .build();
    }

    private <T> T createProxy(RestClient restClient, Class<T> clientType) {

        RestClientAdapter adapter = RestClientAdapter.create(restClient);

        HttpServiceProxyFactory proxyFactory =
                HttpServiceProxyFactory
                        .builderFor(adapter)
                        .build();

        return proxyFactory.createClient(clientType);
    }
}
