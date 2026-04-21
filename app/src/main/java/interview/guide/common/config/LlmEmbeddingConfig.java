package interview.guide.common.config;

import interview.guide.common.ai.ApiPathResolver;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Configuration
public class LlmEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(
            LlmProviderProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        String providerId = properties.getDefaultProvider();
        Map<String, ProviderConfig> providers = properties.getProviders();
        ProviderConfig config = providers != null ? providers.get(providerId) : null;

        if (config == null) {
            throw new BusinessException(
                ErrorCode.PROVIDER_NOT_FOUND,
                "Default provider '" + providerId + "' 不存在，无法初始化 EmbeddingModel"
            );
        }
        if (config.getEmbeddingModel() == null || config.getEmbeddingModel().isBlank()) {
            throw new BusinessException(
                ErrorCode.AI_SERVICE_UNAVAILABLE,
                "Provider '" + providerId + "' 未配置 embedding-model"
            );
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(300000);

        RestClient.Builder restClientBuilder = RestClient.builder()
            .requestFactory(requestFactory);

        // 见 LlmProviderRegistry 中的注释：base-url 自带版本段时需要改写路径前缀，避免 /v1/v1/... 404。
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .restClientBuilder(restClientBuilder);
        if (ApiPathResolver.baseUrlContainsVersion(config.getBaseUrl())) {
            apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
        }
        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.getEmbeddingModel())
            .build();
        ObservationRegistry observationRegistry = observationRegistryProvider
            .getIfAvailable(() -> ObservationRegistry.NOOP);

        return new OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            options,
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry
        );
    }
}
