package com.codepulse_backend.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class Judge0WebClientConfig {

    @Bean
    public WebClient judge0WebClient(Judge0Properties props) {

        HttpClient httpClient = HttpClient.create()
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) props.getConnectTimeoutMs()
                )
                .responseTimeout(
                        Duration.ofMillis(props.getReadTimeoutMs())
                );

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient)
                )
                .defaultHeader(
                        props.getAuthHeader(),
                        props.getAuthToken()
                )
                .build();
    }
}