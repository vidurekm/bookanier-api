package online.bookanear.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(prefix = "app.media", name = "enabled", havingValue = "true")
public class MediaStorageConfig {
    @Bean(destroyMethod = "close")
    public S3Client mediaS3Client(
            @Value("${app.media.endpoint}") String endpoint,
            @Value("${app.media.region}") String region,
            @Value("${app.media.access-key}") String accessKey,
            @Value("${app.media.secret-key}") String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("MEDIA_S3_ACCESS_KEY and MEDIA_S3_SECRET_KEY are required");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
