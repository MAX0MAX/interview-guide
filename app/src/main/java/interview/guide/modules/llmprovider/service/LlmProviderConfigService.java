package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.ModuleDefaultsDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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

    private final LlmProviderProperties properties;
    private final LlmProviderRegistry registry;
    private final String yamlPath;
    private final String envPath;
    private final Object configLock = new Object();

    public LlmProviderConfigService(
            LlmProviderProperties properties,
            LlmProviderRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        this.yamlPath = properties.getConfigYamlPath();
        this.envPath = properties.getConfigEnvPath();
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
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .requestFactory(requestFactory)
                .build();

            restClient.get().uri("/models").retrieve().toEntity(String.class);
            return ProviderTestResult.builder()
                .success(true)
                .message("连接成功")
                .model(config.getModel())
                .build();
        } catch (Exception e) {
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
