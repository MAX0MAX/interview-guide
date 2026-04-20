export interface ProviderItem {
  id: string;
  baseUrl: string;
  maskedApiKey: string;
  model: string;
  embeddingModel: string | null;
  enabled: boolean;
}

export interface CreateProviderRequest {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  embeddingModel?: string;
}

export interface UpdateProviderRequest {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  embeddingModel?: string;
  enabled?: boolean;
}

export interface ProviderTestResult {
  success: boolean;
  message: string;
  model: string;
}

export interface ModuleDefaults {
  moduleDefaults: Record<string, string>;
}
