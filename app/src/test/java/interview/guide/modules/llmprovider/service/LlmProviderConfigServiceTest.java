package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.ModuleDefaultsDTO;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderConfigService 测试")
class LlmProviderConfigServiceTest {

    @Mock private LlmProviderProperties properties;
    @Mock private LlmProviderRegistry registry;
    @Mock private VoiceInterviewProperties voiceProperties;
    @Mock private QwenAsrService asrService;
    @Mock private QwenTtsService ttsService;

    private LlmProviderConfigService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path tempYaml = tempDir.resolve("application.yml");
        Path tempEnv = tempDir.resolve(".env");
        Files.writeString(tempYaml, """
            app:
              ai:
                providers: {}
            """);
        Files.writeString(tempEnv, "");

        when(properties.getConfigYamlPath()).thenReturn(tempYaml.toString());
        when(properties.getConfigEnvPath()).thenReturn(tempEnv.toString());

        service = new LlmProviderConfigService(
            properties,
            registry,
            voiceProperties,
            asrService,
            ttsService
        );
    }

    @Nested
    @DisplayName("启动校验")
    class Bootstrap {

        @Test
        @DisplayName("validateWritablePaths 对不可创建的父目录 fail-fast")
        void validateWritablePathsFailsFastWhenParentUnwritable(@TempDir Path tempDir) throws IOException {
            Path sentinel = tempDir.resolve("not-a-dir");
            Files.writeString(sentinel, "");
            Path unreachableYaml = sentinel.resolve("child/llm-providers.yml");

            when(properties.getConfigYamlPath()).thenReturn(unreachableYaml.toString());
            when(properties.getConfigEnvPath()).thenReturn(tempDir.resolve(".env").toString());

            LlmProviderConfigService failing = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            assertThrows(BusinessException.class, failing::validateWritablePaths);
        }
    }

    @Nested
    @DisplayName("基础行为")
    class BasicBehavior {

        @Test
        @DisplayName("maskApiKey 返回脱敏值")
        void maskApiKeyReturnsMaskedValue() {
            assertEquals("sk-***xyz", service.maskApiKey("sk-abcdefxyz"));
            assertEquals("***", service.maskApiKey("ab"));
            assertEquals("abc***fgh", service.maskApiKey("abcdefgh"));
        }

        @Test
        @DisplayName("listProviders 在 providers 为空时返回空列表")
        void listProvidersReturnsEmptyWhenProvidersNull() {
            when(properties.getProviders()).thenReturn(null);

            assertTrue(service.listProviders().isEmpty());
        }

        @Test
        @DisplayName("getProvider 对未知 provider 抛出异常")
        void getProviderThrowsWhenProviderMissing() {
            when(properties.getProviders()).thenReturn(new HashMap<>());

            assertThrows(BusinessException.class, () -> service.getProvider("unknown"));
        }
    }

    @Nested
    @DisplayName("Provider 管理")
    class ProviderManagement {

        @Test
        @DisplayName("createProvider 对重复 id 抛出异常")
        void createProviderThrowsForDuplicateId() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new HashMap<>();
            providers.put("existing", createProviderConfig("http://localhost:1234", "key", "model", null));
            when(properties.getProviders()).thenReturn(providers);

            CreateProviderRequest request = new CreateProviderRequest(
                "existing",
                "http://localhost:1234",
                "key",
                "model",
                null
            );

            assertThrows(BusinessException.class, () -> service.createProvider(request));
        }

        @Test
        @DisplayName("deleteProvider 删除默认 provider 时抛出异常")
        void deleteProviderThrowsForDefaultProvider() {
            when(properties.getDefaultProvider()).thenReturn("dashscope");

            assertThrows(BusinessException.class, () -> service.deleteProvider("dashscope"));
        }

        @Test
        @DisplayName("deleteProvider 会同步移除引用它的模块默认值")
        void deleteProviderRemovesReferencedModuleDefaults() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            providers.put("kimi", createProviderConfig("https://api.moonshot.cn/v1", "key", "kimi", null));

            Map<String, String> moduleDefaults = new LinkedHashMap<>();
            moduleDefaults.put("interview", "kimi");
            moduleDefaults.put("resume", "dashscope");
            moduleDefaults.put("knowledge-base", "kimi");

            when(properties.getDefaultProvider()).thenReturn("dashscope");
            when(properties.getProviders()).thenReturn(providers);
            when(properties.getModuleDefaults()).thenReturn(moduleDefaults);

            service.deleteProvider("kimi");

            verify(properties).setModuleDefaults(argThat(defaults ->
                defaults.size() == 1 && "dashscope".equals(defaults.get("resume"))
            ));
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 允许清空 embedding model")
        void updateProviderAllowsClearingEmbeddingModel() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, ""));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 对纯空白 embedding model 等价于清空")
        void updateProviderTreatsBlankEmbeddingModelAsClear() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, "   "));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 拒绝空串 baseUrl / model / apiKey")
        void updateProviderRejectsBlankRequiredFields() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope",
                createProviderConfig("https://dashscope.aliyuncs.com", "secret", "qwen-plus", null));
            when(properties.getProviders()).thenReturn(providers);

            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("", null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("   ", null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, null, "", null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, "  ", null, null)));
        }
    }

    @Nested
    @DisplayName("模块默认值")
    class ModuleDefaultsBehavior {

        @Test
        @DisplayName("updateModuleDefaults 拒绝未知 provider")
        void updateModuleDefaultsRejectsUnknownProvider() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            when(properties.getProviders()).thenReturn(providers);

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateModuleDefaults(new ModuleDefaultsDTO(Map.of("interview", "unknown")))
            );

            assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), exception.getCode());
            verify(properties, never()).setModuleDefaults(anyMap());
            verify(registry, never()).reload();
        }

        @Test
        @Disabled(
            "Pending: ProviderConfig.enabled flag + ErrorCode.PROVIDER_DISABLED not yet implemented; "
                + "restore assertion once 'disable provider but keep config' feature is introduced"
        )
        @DisplayName("updateModuleDefaults 拒绝已禁用 provider（占位，待实现）")
        void updateModuleDefaultsRejectsDisabledProvider() {
            // 占位：记录缺失能力，避免重新引入 enabled 字段时漏掉回归断言。
        }

        @Test
        @DisplayName("updateModuleDefaults 会跳过空白 provider")
        void updateModuleDefaultsSkipsBlankProviderIds() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            when(properties.getProviders()).thenReturn(providers);

            service.updateModuleDefaults(new ModuleDefaultsDTO(Map.of(
                "interview", "dashscope",
                "resume", " "
            )));

            verify(properties).setModuleDefaults(argThat(defaults ->
                defaults.size() == 1 && "dashscope".equals(defaults.get("interview"))
            ));
            verify(registry).reload();
        }
    }

    private LlmProviderProperties.ProviderConfig createProviderConfig(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel
    ) {
        LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setEmbeddingModel(embeddingModel);
        return config;
    }
}
