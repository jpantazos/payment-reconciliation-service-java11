package com.fintech.reconciliation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentReconciliationOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Reconciliation Service API")
                        .description("REST API for reconciling payment transactions between internal ledger and external payment providers (e.g., Stripe, Adyen). Identifies and resolves ghost transactions.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("FinTech Team")
                                .email("fintech@example.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Development server"),
                        new Server().url("http://localhost:8080").description("Docker environment")
                ));
    }
}
