import request from './request';
import type {
  ProviderItem,
  CreateProviderRequest,
  UpdateProviderRequest,
  ProviderTestResult,
  ModuleDefaults,
} from '../types/llmProvider';

export const llmProviderApi = {
  list: () => request.get<ProviderItem[]>('/api/llm-provider/list'),

  get: (id: string) => request.get<ProviderItem>(`/api/llm-provider/${id}`),

  create: (data: CreateProviderRequest) =>
    request.post<void>('/api/llm-provider', data),

  update: (id: string, data: UpdateProviderRequest) =>
    request.put<void>(`/api/llm-provider/${id}`, data),

  delete: (id: string) =>
    request.delete<void>(`/api/llm-provider/${id}`),

  test: (id: string) =>
    request.post<ProviderTestResult>(`/api/llm-provider/${id}/test`),

  reload: () =>
    request.post<void>('/api/llm-provider/reload'),

  getModuleDefaults: () =>
    request.get<ModuleDefaults>('/api/llm-provider/module-defaults'),

  updateModuleDefaults: (data: ModuleDefaults) =>
    request.put<void>('/api/llm-provider/module-defaults', data),
};
