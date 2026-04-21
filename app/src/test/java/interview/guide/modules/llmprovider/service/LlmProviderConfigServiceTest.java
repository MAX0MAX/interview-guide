package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.ModuleDefaultsDTO;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderConfigService Test")
class LlmProviderConfigServiceTest {

    @Mock private LlmProviderProperties properties;
    @Mock private LlmProviderRegistry registry;
    @Mock private QwenAsrService asrService;
    @Mock private QwenTtsService ttsService;

    private LlmProviderConfigService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path tempYaml = tempDir.resolve("application.yml");
        Path tempEnv = tempDir.resolve(".env");
        when(properties.getConfigYamlPath()).thenReturn(tempYaml.toString());
        when(properties.getConfigEnvPath()).thenReturn(tempEnv.toString());
        VoiceInterviewProperties voiceProperties = new VoiceInterviewProperties();
        service = new LlmProviderConfigService(
            properties, registry, voiceProperties, asrService, ttsService);
    }

    @Test
    @DisplayName("maskApiKey returns correct format")
    void testMaskApiKey() {
        assertEquals("sk-***xyz", service.maskApiKey("sk-abcdefxyz"));
        assertEquals("***", service.maskApiKey("ab"));
        assertEquals("abc***fgh", service.maskApiKey("abcdefgh"));
    }

    @Test
    @DisplayName("maskApiKey handles null")
    void testMaskApiKey_null() {
        assertEquals("***", service.maskApiKey(null));
    }

    @Test
    @DisplayName("listProviders returns masked keys")
    void testListProviders() {
        LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl("http://localhost:1234");
        config.setApiKey("sk-test-key-123");
        config.setModel("test-model");
        config.setEnabled(true);

        Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("test", config);

        when(properties.getProviders()).thenReturn(providers);

        var result = service.listProviders();
        assertEquals(1, result.size());
        assertEquals("test", result.get(0).id());
        assertEquals("sk-***123", result.get(0).maskedApiKey());
    }

    @Test
    @DisplayName("listProviders returns empty for null providers")
    void testListProviders_null() {
        when(properties.getProviders()).thenReturn(null);
        assertTrue(service.listProviders().isEmpty());
    }

    @Test
    @DisplayName("deleteProvider throws for default provider")
    void testDeleteDefaultProvider() {
        when(properties.getDefaultProvider()).thenReturn("dashscope");
        assertThrows(BusinessException.class, () -> service.deleteProvider("dashscope"));
    }

    @Test
    @DisplayName("createProvider throws for duplicate id")
    void testCreateDuplicateProvider() {
        Map<String, LlmProviderProperties.ProviderConfig> providers = new HashMap<>();
        providers.put("existing", new LlmProviderProperties.ProviderConfig());
        when(properties.getProviders()).thenReturn(providers);

        var request = new CreateProviderRequest(
            "existing", "http://localhost:1234", "key", "model", null);
        assertThrows(BusinessException.class, () -> service.createProvider(request));
    }

    @Test
    @DisplayName("getProvider throws for unknown id")
    void testGetProvider_notFound() {
        when(properties.getProviders()).thenReturn(new HashMap<>());
        assertThrows(BusinessException.class, () -> service.getProvider("unknown"));
    }

    @Test
    @DisplayName("deleteProvider removes module defaults referencing deleted provider")
    void testDeleteProvider_removesModuleDefaults() {
        Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("dashscope", createProviderConfig(true));
        providers.put("kimi", createProviderConfig(true));

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
    @DisplayName("updateModuleDefaults rejects unknown providers")
    void testUpdateModuleDefaults_rejectsUnknownProvider() {
        Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("dashscope", createProviderConfig(true));
        when(properties.getProviders()).thenReturn(providers);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.updateModuleDefaults(new ModuleDefaultsDTO(Map.of("interview", "unknown")))
        );

        assertEquals(ErrorCode.PROVIDER_NOT_FOUND, exception.getErrorCode());
        verify(properties, never()).setModuleDefaults(anyMap());
        verify(registry, never()).reload();
    }

    @Test
    @DisplayName("updateModuleDefaults rejects disabled providers")
    void testUpdateModuleDefaults_rejectsDisabledProvider() {
        Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("dashscope", createProviderConfig(true));
        providers.put("kimi", createProviderConfig(false));
        when(properties.getProviders()).thenReturn(providers);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.updateModuleDefaults(new ModuleDefaultsDTO(Map.of("interview", "kimi")))
        );

        assertEquals(ErrorCode.PROVIDER_DISABLED, exception.getErrorCode());
        verify(properties, never()).setModuleDefaults(anyMap());
        verify(registry, never()).reload();
    }

    @Test
    @DisplayName("updateModuleDefaults normalizes blank provider ids")
    void testUpdateModuleDefaults_normalizesBlankProviderIds() {
        Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("dashscope", createProviderConfig(true));
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

    private LlmProviderProperties.ProviderConfig createProviderConfig(boolean enabled) {
        LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl("http://localhost:1234");
        config.setApiKey("sk-test-key-123");
        config.setModel("test-model");
        config.setEnabled(enabled);
        return config;
    }
}
