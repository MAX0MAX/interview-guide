package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {

    private final LlmProviderConfigService configService;

    @GetMapping("/list")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<ProviderDTO>> listProviders() {
        return Result.success(configService.listProviders());
    }

    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<ProviderDTO> getProvider(@PathVariable String id) {
        return Result.success(configService.getProvider(id));
    }

    @PostMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
        configService.createProvider(request);
        return Result.success();
    }

    @PutMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateProvider(@PathVariable String id,
                                       @RequestBody UpdateProviderRequest request) {
        configService.updateProvider(id, request);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> deleteProvider(@PathVariable String id) {
        configService.deleteProvider(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<ProviderTestResult> testProvider(@PathVariable String id) {
        return Result.success(configService.testProvider(id));
    }

    @PostMapping("/reload")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> reloadProviders() {
        configService.reloadProviders();
        return Result.success();
    }

    @GetMapping("/module-defaults")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<ModuleDefaultsDTO> getModuleDefaults() {
        return Result.success(configService.getModuleDefaults());
    }

    @PutMapping("/module-defaults")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    public Result<Void> updateModuleDefaults(@RequestBody ModuleDefaultsDTO request) {
        configService.updateModuleDefaults(request);
        return Result.success();
    }
}
