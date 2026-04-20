package interview.guide.modules.llmprovider.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.ModuleDefaultsDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderController Test")
class LlmProviderControllerTest {

    @Mock private LlmProviderConfigService configService;
    @InjectMocks private LlmProviderController controller;

    @Test
    @DisplayName("listProviders returns provider list")
    void testListProviders() {
        var dto = ProviderDTO.builder()
            .id("dashscope").baseUrl("http://test").maskedApiKey("sk-***key")
            .model("qwen").embeddingModel("text-embedding-v3").enabled(true).build();
        when(configService.listProviders()).thenReturn(List.of(dto));

        Result<List<ProviderDTO>> result = controller.listProviders();

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("dashscope", result.getData().get(0).id());
    }

    @Test
    @DisplayName("getModuleDefaults returns defaults")
    void testGetModuleDefaults() {
        when(configService.getModuleDefaults())
            .thenReturn(new ModuleDefaultsDTO(Map.of("interview", "dashscope")));

        Result<ModuleDefaultsDTO> result = controller.getModuleDefaults();

        assertEquals(200, result.getCode());
        assertEquals("dashscope", result.getData().moduleDefaults().get("interview"));
    }

    @Test
    @DisplayName("deleteProvider calls service")
    void testDeleteProvider() {
        doNothing().when(configService).deleteProvider("lmstudio");

        Result<Void> result = controller.deleteProvider("lmstudio");

        assertEquals(200, result.getCode());
        verify(configService).deleteProvider("lmstudio");
    }
}
