package interview.guide.modules.llmprovider.controller;

import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LlmProviderController.class)
@DisplayName("LlmProviderController Test")
class LlmProviderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private LlmProviderConfigService configService;

    @Test
    @DisplayName("GET /api/llm-provider/list returns provider list")
    void testListProviders() throws Exception {
        var dto = ProviderDTO.builder()
            .id("dashscope").baseUrl("http://test").maskedApiKey("sk-***key")
            .model("qwen").embeddingModel("text-embedding-v3").enabled(true).build();
        when(configService.listProviders()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/llm-provider/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].id").value("dashscope"));
    }

    @Test
    @DisplayName("GET /api/llm-provider/module-defaults returns defaults")
    void testGetModuleDefaults() throws Exception {
        when(configService.getModuleDefaults())
            .thenReturn(new ModuleDefaultsDTO(Map.of("interview", "dashscope")));

        mockMvc.perform(get("/api/llm-provider/module-defaults"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.moduleDefaults.interview").value("dashscope"));
    }

    @Test
    @DisplayName("DELETE /api/llm-provider/{id} calls service")
    void testDeleteProvider() throws Exception {
        doNothing().when(configService).deleteProvider("lmstudio");

        mockMvc.perform(delete("/api/llm-provider/lmstudio"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(configService).deleteProvider("lmstudio");
    }
}
