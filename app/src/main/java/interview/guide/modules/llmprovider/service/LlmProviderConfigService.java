package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.ModuleDefaultsDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class LlmProviderConfigService {

    /**
     * ASR apiKey 的 env 占位符名称。用独立 env key 隔离 ASR/TTS/LLM 的密钥域，
     * 避免 UI 修改语音服务密钥时顺带覆盖 DashScope LLM provider 的密钥。
     */
    private static final String VOICE_ASR_ENV_KEY = "VOICE_ASR_API_KEY";
    private static final String VOICE_TTS_ENV_KEY = "VOICE_TTS_API_KEY";

    private final LlmProviderProperties properties;
    private final LlmProviderRegistry registry;
    private final String yamlPath;
    private final String envPath;
    private final Object configLock = new Object();
    private final VoiceInterviewProperties voiceProperties;
    private final QwenAsrService asrService;
    private final QwenTtsService ttsService;

    public LlmProviderConfigService(
            LlmProviderProperties properties,
            LlmProviderRegistry registry,
            VoiceInterviewProperties voiceProperties,
            QwenAsrService asrService,
            QwenTtsService ttsService) {
        this.properties = properties;
        this.registry = registry;
        this.yamlPath = properties.getConfigYamlPath();
        this.envPath = properties.getConfigEnvPath();
        this.voiceProperties = voiceProperties;
        this.asrService = asrService;
        this.ttsService = ttsService;
    }

    /**
     * 启动校验：写路径必须配置并且目录可写。
     * 没配置 → UI 保存看似成功但磁盘没落盘，重启即丢；这里通过 fail-fast 替代静默日志告警。
     */
    @PostConstruct
    void validateWritablePaths() {
        if (yamlPath == null || yamlPath.isBlank()) {
            throw new IllegalStateException(
                "app.ai.config-yaml-path 未配置：UI 修改 provider 配置将无法持久化。请在 application.yml 或环境变量 APP_AI_CONFIG_YAML_PATH 中指定一个可写的 YAML 文件路径。"
            );
        }
        if (envPath == null || envPath.isBlank()) {
            throw new IllegalStateException(
                "app.ai.config-env-path 未配置：UI 新建 provider 的 apiKey 将无法持久化。请在 application.yml 或环境变量 APP_AI_CONFIG_ENV_PATH 中指定一个可写的 .env 文件路径。"
            );
        }
        Path yamlFile = Path.of(yamlPath);
        Path envFile = Path.of(envPath);
        assertParentWritable(yamlFile, "app.ai.config-yaml-path");
        assertParentWritable(envFile, "app.ai.config-env-path");
        log.info("LlmProviderConfigService initialized: yamlPath={}, envPath={}", yamlPath, envPath);
    }

    private void assertParentWritable(Path file, String propertyName) {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalStateException(propertyName + " 路径无效: " + file);
        }
        if (Files.exists(file)) {
            if (!Files.isWritable(file)) {
                throw new IllegalStateException(propertyName + " 对应的文件不可写: " + file);
            }
            return;
        }
        if (!Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException(
                    propertyName + " 对应的父目录不存在且无法创建: " + parent + "（" + e.getMessage() + "）",
                    e
                );
            }
        }
        if (!Files.isWritable(parent)) {
            throw new IllegalStateException(propertyName + " 对应的父目录不可写: " + parent);
        }
    }

    public List<ProviderDTO> listProviders() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) return List.of();

        return providers.entrySet().stream()
            .map(e -> ProviderDTO.builder()
                .id(e.getKey())
                .baseUrl(e.getValue().getBaseUrl())
                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))
                .model(e.getValue().getModel())
                .embeddingModel(e.getValue().getEmbeddingModel())
                .enabled(e.getValue().isEnabled())
                .build())
            .toList();
    }

    public ProviderDTO getProvider(String id) {
        ProviderConfig config = getProviderConfigOrThrow(id);
        return ProviderDTO.builder()
            .id(id)
            .baseUrl(config.getBaseUrl())
            .maskedApiKey(maskApiKey(config.getApiKey()))
            .model(config.getModel())
            .embeddingModel(config.getEmbeddingModel())
            .enabled(config.isEnabled())
            .build();
    }

    public void createProvider(CreateProviderRequest request) {
        synchronized (configLock) {
            Map<String, ProviderConfig> providers = getProvidersOrThrow();
            if (providers.containsKey(request.id())) {
                throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
                    "Provider '" + request.id() + "' 已存在");
            }

            ProviderConfig config = new ProviderConfig();
            config.setBaseUrl(request.baseUrl());
            config.setApiKey(request.apiKey());
            config.setModel(request.model());
            config.setEmbeddingModel(request.embeddingModel());
            config.setEnabled(true);

            providers.put(request.id(), config);

            String envKey = toEnvKey(request.id());
            writeProviderToYaml(request.id(), config, envKey);
            appendToEnv(envKey, request.apiKey());
            registry.reload();
            log.info("Created provider: id={}, baseUrl={}, model={}", request.id(), request.baseUrl(), request.model());
        }
    }

    public void updateProvider(String id, UpdateProviderRequest request) {
        synchronized (configLock) {
            ProviderConfig config = getProviderConfigOrThrow(id);

            if (request.baseUrl() != null) config.setBaseUrl(request.baseUrl());
            if (request.model() != null) config.setModel(request.model());
            if (request.embeddingModel() != null) config.setEmbeddingModel(request.embeddingModel());
            if (request.enabled() != null) config.setEnabled(request.enabled());
            if (request.apiKey() != null) {
                config.setApiKey(request.apiKey());
                String envKey = toEnvKey(id);
                updateEnvValue(envKey, request.apiKey());
            }

            String envKey = toEnvKey(id);
            writeProviderToYaml(id, config, envKey);
            registry.reload();
            log.info("Updated provider: id={}", id);
        }
    }

    public void deleteProvider(String id) {
        synchronized (configLock) {
            if (id.equals(properties.getDefaultProvider())) {
                throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
                    "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
            }
            getProviderConfigOrThrow(id);
            getProvidersOrThrow().remove(id);
            removeProviderFromModuleDefaults(id);

            String envKey = toEnvKey(id);
            removeProviderFromYaml(id);
            removeFromEnv(envKey);
            registry.reload();
            log.info("Deleted provider: id={}", id);
        }
    }

    public ProviderTestResult testProvider(String id) {
        ProviderConfig config = getProviderConfigOrThrow(id);
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(5000);
            requestFactory.setReadTimeout(10000);

            RestClient restClient = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .requestFactory(requestFactory)
                .build();

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Reply with OK only."
            )));
            requestBody.put("max_tokens", 1);
            requestBody.put("temperature", 0);

            List<String> candidateUrls = buildConnectivityTestUrls(config.getBaseUrl());
            String lastFailureMessage = "Unknown error";

            for (String targetUrl : candidateUrls) {
                try {
                    restClient.post()
                        .uri(URI.create(targetUrl))
                        .body(requestBody)
                        .retrieve()
                        .toEntity(String.class);
                    log.info("Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
                        id, config.getBaseUrl(), targetUrl, config.getModel());
                    return ProviderTestResult.builder()
                        .success(true)
                        .message("连接成功")
                        .model(config.getModel())
                        .build();
                } catch (RestClientResponseException e) {
                    String responseBody = abbreviate(e.getResponseBodyAsString());
                    lastFailureMessage = String.format(
                        "HTTP %s on %s, body=%s",
                        e.getStatusCode().value(),
                        targetUrl,
                        responseBody
                    );
                    log.warn(
                        "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
                        id,
                        config.getBaseUrl(),
                        targetUrl,
                        config.getModel(),
                        e.getStatusCode().value(),
                        responseBody,
                        e
                    );
                } catch (Exception e) {
                    lastFailureMessage = String.format(
                        "%s on %s: %s",
                        e.getClass().getSimpleName(),
                        targetUrl,
                        e.getMessage()
                    );
                    log.warn(
                        "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
                        id,
                        config.getBaseUrl(),
                        targetUrl,
                        config.getModel(),
                        e.getMessage(),
                        e
                    );
                }
            }
            return ProviderTestResult.builder()
                .success(false)
                .message("连接失败: " + lastFailureMessage)
                .model(config.getModel())
                .build();
        } catch (Exception e) {
            log.warn("Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
                id, config.getBaseUrl(), config.getModel(), e.getMessage(), e);
            return ProviderTestResult.builder()
                .success(false)
                .message("连接失败: " + e.getMessage())
                .model(config.getModel())
                .build();
        }
    }

    public ModuleDefaultsDTO getModuleDefaults() {
        return new ModuleDefaultsDTO(properties.getModuleDefaults());
    }

    public void updateModuleDefaults(ModuleDefaultsDTO request) {
        Map<String, String> validatedDefaults = validateAndNormalizeModuleDefaults(request.moduleDefaults());
        properties.setModuleDefaults(validatedDefaults);
        writeModuleDefaultsToYaml(validatedDefaults);
        registry.reload();
        log.info("Updated module defaults: {}", validatedDefaults);
    }

    public void reloadProviders() {
        registry.reload();
        log.info("Manual provider reload triggered");
    }

    public AsrConfigDTO getAsrConfig() {
        VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
        return AsrConfigDTO.builder()
            .url(asr.getUrl())
            .model(asr.getModel())
            .maskedApiKey(maskApiKey(asr.getApiKey()))
            .language(asr.getLanguage())
            .format(asr.getFormat())
            .sampleRate(asr.getSampleRate())
            .enableTurnDetection(asr.isEnableTurnDetection())
            .turnDetectionType(asr.getTurnDetectionType())
            .turnDetectionThreshold(asr.getTurnDetectionThreshold())
            .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
            .build();
    }

    public TtsConfigDTO getTtsConfig() {
        VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
        return TtsConfigDTO.builder()
            .model(tts.getModel())
            .maskedApiKey(maskApiKey(tts.getApiKey()))
            .voice(tts.getVoice())
            .format(tts.getFormat())
            .sampleRate(tts.getSampleRate())
            .mode(tts.getMode())
            .languageType(tts.getLanguageType())
            .speechRate(tts.getSpeechRate())
            .volume(tts.getVolume())
            .build();
    }

    public void updateAsrConfig(AsrConfigRequest request) {
        synchronized (configLock) {
            VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
            if (request.url() != null) asr.setUrl(request.url());
            if (request.model() != null) asr.setModel(request.model());
            if (request.language() != null) asr.setLanguage(request.language());
            if (request.format() != null) asr.setFormat(request.format());
            if (request.sampleRate() != null) asr.setSampleRate(request.sampleRate());
            if (request.enableTurnDetection() != null) asr.setEnableTurnDetection(request.enableTurnDetection());
            if (request.turnDetectionType() != null) asr.setTurnDetectionType(request.turnDetectionType());
            if (request.turnDetectionThreshold() != null) asr.setTurnDetectionThreshold(request.turnDetectionThreshold());
            if (request.turnDetectionSilenceDurationMs() != null) asr.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
            if (request.apiKey() != null) {
                asr.setApiKey(request.apiKey());
                updateEnvValue(VOICE_ASR_ENV_KEY, request.apiKey());
            }

            writeAsrConfigToYaml(asr);
            asrService.reload(voiceProperties);
            log.info("Updated ASR config");
        }
    }

    public void updateTtsConfig(TtsConfigRequest request) {
        synchronized (configLock) {
            VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
            if (request.model() != null) tts.setModel(request.model());
            if (request.voice() != null) tts.setVoice(request.voice());
            if (request.format() != null) tts.setFormat(request.format());
            if (request.sampleRate() != null) tts.setSampleRate(request.sampleRate());
            if (request.mode() != null) tts.setMode(request.mode());
            if (request.languageType() != null) tts.setLanguageType(request.languageType());
            if (request.speechRate() != null) tts.setSpeechRate(request.speechRate());
            if (request.volume() != null) tts.setVolume(request.volume());
            if (request.apiKey() != null) {
                tts.setApiKey(request.apiKey());
                updateEnvValue(VOICE_TTS_ENV_KEY, request.apiKey());
            }

            writeTtsConfigToYaml(tts);
            ttsService.reload(voiceProperties);
            log.info("Updated TTS config");
        }
    }

    public ProviderTestResult testAsrConfig() {
        VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
        try {
            java.net.URI wsUri = java.net.URI.create(asr.getUrl());
            String host = wsUri.getHost();
            int port = wsUri.getPort() > 0 ? wsUri.getPort() : (wsUri.getScheme().equals("wss") ? 443 : 80);
            java.net.InetSocketAddress address = new java.net.InetSocketAddress(host, port);
            java.net.Socket socket = new java.net.Socket();
            socket.connect(address, 5000);
            socket.close();
            return ProviderTestResult.builder()
                .success(true)
                .message("ASR WebSocket 连接成功: " + host)
                .model(asr.getModel())
                .build();
        } catch (Exception e) {
            return ProviderTestResult.builder()
                .success(false)
                .message("ASR 连接失败: " + e.getMessage())
                .model(asr.getModel())
                .build();
        }
    }

    // ===== 内部方法 =====

    private Map<String, ProviderConfig> getProvidersOrThrow() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider 配置未初始化");
        }
        return providers;
    }

    ProviderConfig getProviderConfigOrThrow(String id) {
        Map<String, ProviderConfig> providers = getProvidersOrThrow();
        ProviderConfig config = providers.get(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                "Provider '" + id + "' 不存在");
        }
        return config;
    }

    String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 6) {
            return "***";
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
    }

    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "[no body]";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    private List<String> buildConnectivityTestUrls(String baseUrl) {
        String normalizedBaseUrl = stripTrailingSlash(baseUrl);
        LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

        candidateUrls.add(normalizedBaseUrl + "/chat/completions");
        if (!normalizedBaseUrl.endsWith("/v1")) {
            candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
        }

        return List.copyOf(candidateUrls);
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String toEnvKey(String providerId) {
        return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
    }

    private void removeProviderFromModuleDefaults(String providerId) {
        Map<String, String> currentDefaults = properties.getModuleDefaults();
        if (currentDefaults == null || currentDefaults.isEmpty()) {
            return;
        }

        Map<String, String> updatedDefaults = new LinkedHashMap<>(currentDefaults);
        boolean changed = updatedDefaults.entrySet().removeIf(entry -> providerId.equals(entry.getValue()));
        if (!changed) {
            return;
        }

        properties.setModuleDefaults(updatedDefaults);
        writeModuleDefaultsToYaml(updatedDefaults);
    }

    private Map<String, String> validateAndNormalizeModuleDefaults(Map<String, String> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, ProviderConfig> providers = getProvidersOrThrow();
        Map<String, String> normalizedDefaults = new LinkedHashMap<>();
        Set<String> missingProviders = new LinkedHashSet<>();
        Set<String> disabledProviders = new LinkedHashSet<>();

        defaults.forEach((module, providerId) -> {
            if (module == null || module.isBlank() || providerId == null || providerId.isBlank()) {
                return;
            }

            String normalizedModule = module.trim();
            String normalizedProviderId = providerId.trim();
            ProviderConfig config = providers.get(normalizedProviderId);

            if (config == null) {
                missingProviders.add(normalizedProviderId);
                return;
            }
            if (!config.isEnabled()) {
                disabledProviders.add(normalizedProviderId);
                return;
            }

            normalizedDefaults.put(normalizedModule, normalizedProviderId);
        });

        if (!missingProviders.isEmpty()) {
            throw new BusinessException(
                ErrorCode.PROVIDER_NOT_FOUND,
                "Provider 不存在: " + String.join(", ", missingProviders)
            );
        }
        if (!disabledProviders.isEmpty()) {
            throw new BusinessException(
                ErrorCode.PROVIDER_DISABLED,
                "Provider 已被禁用: " + String.join(", ", disabledProviders)
            );
        }

        return normalizedDefaults;
    }

    private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
        if (yamlPath == null || yamlPath.isBlank()) {
            log.warn("YAML path not configured, skip writing");
            return;
        }
        try {
            Yaml yaml = createYaml();
            Map<String, Object> data;
            Path path = Path.of(yamlPath);

            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    data = yaml.load(reader);
                }
            } else {
                data = new LinkedHashMap<>();
            }

            Map<String, Object> app = getOrCreateMap(data, "app");
            Map<String, Object> ai = getOrCreateMap(app, "ai");
            Map<String, Object> providers = getOrCreateMap(ai, "providers");
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("base-url", config.getBaseUrl());
            provider.put("api-key", "${" + envKey + "}");
            provider.put("model", config.getModel());
            if (config.getEmbeddingModel() != null) {
                provider.put("embedding-model", config.getEmbeddingModel());
            }
            provider.put("enabled", config.isEnabled());
            providers.put(id, provider);

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                yaml.dump(data, writer);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                "写入 YAML 配置失败: " + e.getMessage());
        }
    }

    private void removeProviderFromYaml(String id) {
        if (yamlPath == null || yamlPath.isBlank()) return;
        try {
            Yaml yaml = createYaml();
            Path path = Path.of(yamlPath);
            if (!Files.exists(path)) return;

            Map<String, Object> data;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                data = yaml.load(reader);
            }
            if (data == null) return;

            Map<String, Object> providers = navigateMap(data, "app", "ai", "providers");
            if (providers != null) {
                providers.remove(id);
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    yaml.dump(data, writer);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                "删除 YAML 配置失败: " + e.getMessage());
        }
    }

    private void writeModuleDefaultsToYaml(Map<String, String> defaults) {
        if (yamlPath == null || yamlPath.isBlank()) return;
        try {
            Yaml yaml = createYaml();
            Path path = Path.of(yamlPath);
            Map<String, Object> data;

            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    data = yaml.load(reader);
                }
            } else {
                data = new LinkedHashMap<>();
            }

            Map<String, Object> app = getOrCreateMap(data, "app");
            Map<String, Object> ai = getOrCreateMap(app, "ai");
            ai.put("module-defaults", defaults);

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                yaml.dump(data, writer);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                "写入模块默认配置失败: " + e.getMessage());
        }
    }

    private void appendToEnv(String key, String value) {
        if (envPath == null || envPath.isBlank()) return;
        try {
            Path path = Path.of(envPath);
            String line = key + "=" + value + "\n";
            if (Files.exists(path)) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.contains(key + "=")) {
                    updateEnvValue(key, value);
                    return;
                }
            }
            Files.writeString(path, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入 .env 失败: {}", e.getMessage());
        }
    }

    private void updateEnvValue(String key, String value) {
        if (envPath == null || envPath.isBlank()) return;
        try {
            Path path = Path.of(envPath);
            if (!Files.exists(path)) {
                appendToEnv(key, value);
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*", Matcher.quoteReplacement(key + "=" + value));
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("更新 .env 失败: {}", e.getMessage());
        }
    }

    private void removeFromEnv(String key) {
        if (envPath == null || envPath.isBlank()) return;
        try {
            Path path = Path.of(envPath);
            if (!Files.exists(path)) return;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*\\R?", "");
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("删除 .env 条目失败: {}", e.getMessage());
        }
    }

    private void writeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
        try {
            Yaml yaml = createYaml();
            Path path = Path.of(yamlPath);
            Map<String, Object> data = loadYamlOrEmpty(yaml, path);

            Map<String, Object> qwen = getOrCreateMap(
                getOrCreateMap(getOrCreateMap(data, "app"), "voice-interview"), "qwen");
            Map<String, Object> asrMap = getOrCreateMap(qwen, "asr");
            asrMap.put("url", asr.getUrl());
            asrMap.put("model", asr.getModel());
            asrMap.put("api-key", "${" + VOICE_ASR_ENV_KEY + "}");
            asrMap.put("language", asr.getLanguage());
            asrMap.put("format", asr.getFormat());
            asrMap.put("sample-rate", asr.getSampleRate());
            asrMap.put("enable-turn-detection", asr.isEnableTurnDetection());
            asrMap.put("turn-detection-type", asr.getTurnDetectionType());
            asrMap.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
            asrMap.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                yaml.dump(data, writer);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VOICE_CONFIG_WRITE_FAILED,
                "写入 ASR 配置失败: " + e.getMessage());
        }
    }

    private void writeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
        try {
            Yaml yaml = createYaml();
            Path path = Path.of(yamlPath);
            Map<String, Object> data = loadYamlOrEmpty(yaml, path);

            Map<String, Object> qwen = getOrCreateMap(
                getOrCreateMap(getOrCreateMap(data, "app"), "voice-interview"), "qwen");
            Map<String, Object> ttsMap = getOrCreateMap(qwen, "tts");
            ttsMap.put("model", tts.getModel());
            ttsMap.put("api-key", "${" + VOICE_TTS_ENV_KEY + "}");
            ttsMap.put("voice", tts.getVoice());
            ttsMap.put("format", tts.getFormat());
            ttsMap.put("sample-rate", tts.getSampleRate());
            ttsMap.put("mode", tts.getMode());
            ttsMap.put("language-type", tts.getLanguageType());
            ttsMap.put("speech-rate", tts.getSpeechRate());
            ttsMap.put("volume", tts.getVolume());

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                yaml.dump(data, writer);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VOICE_CONFIG_WRITE_FAILED,
                "写入 TTS 配置失败: " + e.getMessage());
        }
    }

    /**
     * 读取 YAML 文件。文件不存在或为空返回空 map，避免对首次写入时的不存在情况抛 NoSuchFileException。
     */
    private Map<String, Object> loadYamlOrEmpty(Yaml yaml, Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Object> loaded = yaml.load(reader);
            return loaded != null ? loaded : new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (child instanceof Map) return (Map<String, Object>) child;
        Map<String, Object> map = new LinkedHashMap<>();
        parent.put(key, map);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> navigateMap(Map<String, Object> data, String... keys) {
        Map<String, Object> current = data;
        for (String key : keys) {
            Object child = current.get(key);
            if (!(child instanceof Map)) return null;
            current = (Map<String, Object>) child;
        }
        return current;
    }

    private Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Representer representer = new Representer(options);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        return new Yaml(representer, options);
    }
}
