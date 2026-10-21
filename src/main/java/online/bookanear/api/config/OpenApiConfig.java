package online.bookanear.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI bookanearOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Bookanear API")
                        .description("Multi-tenant appointment booking API. Backoffice endpoints require a bearer JWT; public registration and booking endpoints do not.")
                        .version("v1"))
                .addTagsItem(new Tag().name("01 - Authentication").description("Email/password login and bearer-token issuance."))
                .addTagsItem(new Tag().name("02 - Registration").description("Organization registration and email verification."))
                .addTagsItem(new Tag().name("03 - Public Catalog").description("Public organization information."))
                .addTagsItem(new Tag().name("04 - Bookings").description("Public reservation creation and token-based management."))
                .addTagsItem(new Tag().name("05 - Backoffice").description("Authenticated organization administration, catalog, schedules and reservations."))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
