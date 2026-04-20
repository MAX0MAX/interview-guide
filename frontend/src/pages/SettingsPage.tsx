import { useState, useEffect, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Settings, Plus, Trash2, Plug, CheckCircle, XCircle,
  Loader2, Eye, EyeOff, RefreshCw, Server, Edit2,
} from 'lucide-react';
import { llmProviderApi } from '../api/llmProvider';
import type {
  ProviderItem, CreateProviderRequest, UpdateProviderRequest,
  ProviderTestResult,
} from '../types/llmProvider';

const MODULE_LABELS: Record<string, string> = {
  'interview': '模拟面试',
  'resume': '简历评分',
  'knowledge-base': '知识库问答',
  'voice-interview': '语音面试',
  'review': '面试复盘',
  'interview-schedule': '面试日程',
};

type ActiveTab = 'providers' | 'module-defaults';

export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState<ActiveTab>('providers');
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [moduleDefaults, setModuleDefaults] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [editingProvider, setEditingProvider] = useState<ProviderItem | null>(null);
  const [saving, setSaving] = useState(false);

  // Form fields
  const [formId, setFormId] = useState('');
  const [formBaseUrl, setFormBaseUrl] = useState('');
  const [formApiKey, setFormApiKey] = useState('');
  const [formModel, setFormModel] = useState('');
  const [formEmbeddingModel, setFormEmbeddingModel] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);

  // Test state
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<ProviderTestResult | null>(null);

  // Delete confirmation
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Toast notification
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  const loadData = useCallback(async () => {
    try {
      const [providerList, defaults] = await Promise.all([
        llmProviderApi.list(),
        llmProviderApi.getModuleDefaults(),
      ]);
      setProviders(providerList);
      setModuleDefaults(defaults.moduleDefaults);
    } catch (err) {
      console.error('Failed to load settings:', err);
      showToast('加载数据失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // --- Modal helpers ---
  const openCreateModal = () => {
    setEditingProvider(null);
    setFormId('');
    setFormBaseUrl('');
    setFormApiKey('');
    setFormModel('');
    setFormEmbeddingModel('');
    setShowApiKey(false);
    setShowModal(true);
  };

  const openEditModal = (provider: ProviderItem) => {
    setEditingProvider(provider);
    setFormId(provider.id);
    setFormBaseUrl(provider.baseUrl);
    setFormApiKey('');
    setFormModel(provider.model);
    setFormEmbeddingModel(provider.embeddingModel || '');
    setShowApiKey(false);
    setShowModal(true);
  };

  const closeModal = () => {
    setShowModal(false);
    setEditingProvider(null);
    setTestResult(null);
  };

  // --- CRUD handlers ---
  const handleCreate = async () => {
    if (!formId.trim() || !formBaseUrl.trim() || !formApiKey.trim() || !formModel.trim()) {
      showToast('请填写必填字段', 'error');
      return;
    }
    setSaving(true);
    try {
      const data: CreateProviderRequest = {
        id: formId.trim(),
        baseUrl: formBaseUrl.trim(),
        apiKey: formApiKey.trim(),
        model: formModel.trim(),
      };
      if (formEmbeddingModel.trim()) {
        data.embeddingModel = formEmbeddingModel.trim();
      }
      await llmProviderApi.create(data);
      showToast('Provider 创建成功');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to create provider:', err);
      showToast(err instanceof Error ? err.message : '创建失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleUpdate = async () => {
    if (!editingProvider) return;
    if (!formBaseUrl.trim() || !formModel.trim()) {
      showToast('请填写必填字段', 'error');
      return;
    }
    setSaving(true);
    try {
      const data: UpdateProviderRequest = {
        baseUrl: formBaseUrl.trim(),
        model: formModel.trim(),
        embeddingModel: formEmbeddingModel.trim() || undefined,
      };
      if (formApiKey.trim()) {
        data.apiKey = formApiKey.trim();
      }
      await llmProviderApi.update(editingProvider.id, data);
      showToast('Provider 更新成功');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to update provider:', err);
      showToast(err instanceof Error ? err.message : '更新失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirmId) return;
    setDeleting(true);
    try {
      await llmProviderApi.delete(deleteConfirmId);
      showToast('Provider 已删除');
      setDeleteConfirmId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to delete provider:', err);
      showToast(err instanceof Error ? err.message : '删除失败', 'error');
    } finally {
      setDeleting(false);
    }
  };

  const handleTest = async (id: string) => {
    setTestingId(id);
    setTestResult(null);
    try {
      const result = await llmProviderApi.test(id);
      setTestResult(result);
    } catch (err) {
      console.error('Test failed:', err);
      setTestResult({
        success: false,
        message: err instanceof Error ? err.message : '连接测试失败',
        model: '',
      });
    } finally {
      setTestingId(null);
    }
  };

  const handleSetDefault = async (providerId: string) => {
    try {
      const updatedDefaults: Record<string, string> = {};
      for (const key of Object.keys(MODULE_LABELS)) {
        updatedDefaults[key] = providerId;
      }
      await llmProviderApi.updateModuleDefaults({ moduleDefaults: updatedDefaults });
      showToast(`已将 "${providerId}" 设为所有模块默认 Provider`);
      await loadData();
    } catch (err) {
      console.error('Failed to set default:', err);
      showToast(err instanceof Error ? err.message : '设置默认 Provider 失败', 'error');
    }
  };

  const handleModuleDefaultChange = async (module: string, providerId: string) => {
    try {
      const updatedDefaults = { ...moduleDefaults, [module]: providerId };
      await llmProviderApi.updateModuleDefaults({ moduleDefaults: updatedDefaults });
      setModuleDefaults(updatedDefaults);
      showToast(`${MODULE_LABELS[module]} 默认 Provider 已更新`);
    } catch (err) {
      console.error('Failed to update module default:', err);
      showToast(err instanceof Error ? err.message : '更新失败', 'error');
    }
  };

  const handleSaveModal = () => {
    if (editingProvider) {
      handleUpdate();
    } else {
      handleCreate();
    }
  };

  // --- Render ---
  return (
    <div className="max-w-4xl mx-auto">
      {/* Page header */}
      <div className="mb-8">
        <div className="flex items-center gap-4 mb-2">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25">
            <Settings className="w-6 h-6 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white">系统设置</h1>
            <p className="text-slate-500 dark:text-slate-400 mt-0.5 text-sm">管理 LLM Provider 和模块配置</p>
          </div>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 p-1 bg-slate-100 dark:bg-slate-800 rounded-xl mb-6">
        {([
          { key: 'providers' as ActiveTab, label: 'Provider 管理', icon: Server },
          { key: 'module-defaults' as ActiveTab, label: '模块默认', icon: Plug },
        ]).map(tab => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`flex-1 flex items-center justify-center gap-2 py-2.5 px-4 rounded-lg text-sm font-medium transition-all
                ${isActive
                  ? 'bg-white dark:bg-slate-700 text-primary-600 dark:text-primary-400 shadow-sm'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'
                }`}
            >
              <Icon className="w-4 h-4" />
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Loading state */}
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      ) : (
        <AnimatePresence mode="wait">
          {activeTab === 'providers' ? (
            <motion.div
              key="providers"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.15 }}
            >
              {/* Provider header */}
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white">
                  LLM Provider 管理
                </h2>
                <motion.button
                  onClick={openCreateModal}
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  className="flex items-center gap-2 px-4 py-2.5 rounded-xl font-medium text-sm
                    bg-gradient-to-r from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/25
                    hover:from-primary-600 hover:to-primary-700 transition-all"
                >
                  <Plus className="w-4 h-4" />
                  新增 Provider
                </motion.button>
              </div>

              {/* Provider grid */}
              {providers.length === 0 ? (
                <div className="text-center py-16 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700">
                  <Server className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3" />
                  <p className="text-slate-500 dark:text-slate-400 text-sm">暂无 Provider，点击上方按钮新增</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {providers.map((provider, index) => (
                    <motion.div
                      key={provider.id}
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: index * 0.05 }}
                      className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm"
                    >
                      {/* Card header */}
                      <div className="flex items-start justify-between mb-3">
                        <div className="flex items-center gap-2.5">
                          <div className={`p-2 rounded-lg ${
                            provider.enabled
                              ? 'bg-emerald-100 dark:bg-emerald-900/30'
                              : 'bg-slate-100 dark:bg-slate-700'
                          }`}>
                            <Server className={`w-4 h-4 ${
                              provider.enabled
                                ? 'text-emerald-600 dark:text-emerald-400'
                                : 'text-slate-400 dark:text-slate-500'
                            }`} />
                          </div>
                          <div>
                            <h3 className="font-semibold text-slate-800 dark:text-white text-sm">
                              {provider.id}
                            </h3>
                            <span className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
                              provider.enabled
                                ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-300'
                                : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400'
                            }`}>
                              {provider.enabled ? (
                                <><CheckCircle className="w-3 h-3" /> 已启用</>
                              ) : (
                                <><XCircle className="w-3 h-3" /> 已禁用</>
                              )}
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Card details */}
                      <div className="space-y-1.5 mb-4 text-xs">
                        <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
                          <span className="font-medium text-slate-600 dark:text-slate-300 w-16 flex-shrink-0">Base URL</span>
                          <span className="truncate" title={provider.baseUrl}>{provider.baseUrl}</span>
                        </div>
                        <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
                          <span className="font-medium text-slate-600 dark:text-slate-300 w-16 flex-shrink-0">Model</span>
                          <span className="truncate" title={provider.model}>{provider.model}</span>
                        </div>
                        {provider.embeddingModel && (
                          <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
                            <span className="font-medium text-slate-600 dark:text-slate-300 w-16 flex-shrink-0">Embed</span>
                            <span className="truncate" title={provider.embeddingModel}>{provider.embeddingModel}</span>
                          </div>
                        )}
                        <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
                          <span className="font-medium text-slate-600 dark:text-slate-300 w-16 flex-shrink-0">API Key</span>
                          <span className="font-mono">{provider.maskedApiKey}</span>
                        </div>
                      </div>

                      {/* Test result */}
                      {testResult && testingId === null && (
                        <motion.div
                          initial={{ opacity: 0, height: 0 }}
                          animate={{ opacity: 1, height: 'auto' }}
                          className={`mb-3 px-3 py-2 rounded-lg text-xs font-medium ${
                            testResult.success
                              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
                              : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300'
                          }`}
                        >
                          <div className="flex items-center gap-1.5">
                            {testResult.success
                              ? <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
                              : <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
                            }
                            <span>{testResult.message}</span>
                          </div>
                        </motion.div>
                      )}

                      {/* Card actions */}
                      <div className="flex items-center gap-1.5 pt-3 border-t border-slate-100 dark:border-slate-700">
                        <button
                          onClick={() => openEditModal(provider)}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium
                            text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                          title="编辑"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          编辑
                        </button>
                        <button
                          onClick={() => handleTest(provider.id)}
                          disabled={testingId === provider.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium
                            text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors
                            disabled:opacity-50 disabled:cursor-not-allowed"
                          title="测试连接"
                        >
                          {testingId === provider.id
                            ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                            : <RefreshCw className="w-3.5 h-3.5" />
                          }
                          测试
                        </button>
                        <button
                          onClick={() => handleSetDefault(provider.id)}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium
                            text-primary-600 dark:text-primary-400 hover:bg-primary-50 dark:hover:bg-primary-900/20 transition-colors"
                          title="设为所有模块默认"
                        >
                          <Plug className="w-3.5 h-3.5" />
                          设为默认
                        </button>
                        <button
                          onClick={() => setDeleteConfirmId(provider.id)}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium
                            text-red-500 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors
                            ml-auto"
                          title="删除"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </motion.div>
                  ))}
                </div>
              )}
            </motion.div>
          ) : (
            <motion.div
              key="module-defaults"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.15 }}
            >
              <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm">
                <div className="p-5 border-b border-slate-100 dark:border-slate-700">
                  <h2 className="text-lg font-bold text-slate-800 dark:text-white flex items-center gap-2">
                    <Plug className="w-5 h-5 text-primary-500" />
                    模块默认 Provider
                  </h2>
                  <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
                    为每个功能模块指定默认使用的 LLM Provider
                  </p>
                </div>

                <div className="divide-y divide-slate-100 dark:divide-slate-700">
                  {Object.entries(MODULE_LABELS).map(([module, label]) => (
                    <div
                      key={module}
                      className="flex items-center justify-between px-5 py-4"
                    >
                      <div>
                        <p className="text-sm font-medium text-slate-800 dark:text-white">
                          {label}
                        </p>
                        <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                          模块: {module}
                        </p>
                      </div>
                      <select
                        value={moduleDefaults[module] || ''}
                        onChange={(e) => handleModuleDefaultChange(module, e.target.value)}
                        className="px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400
                          transition-shadow min-w-[160px]"
                      >
                        <option value="">未设置</option>
                        {providers.filter(p => p.enabled).map(p => (
                          <option key={p.id} value={p.id}>
                            {p.id} ({p.model})
                          </option>
                        ))}
                      </select>
                    </div>
                  ))}
                </div>

                {providers.filter(p => p.enabled).length === 0 && (
                  <div className="px-5 py-8 text-center">
                    <p className="text-sm text-slate-400 dark:text-slate-500">
                      暂无已启用的 Provider，请先在 Provider 管理中添加并启用
                    </p>
                  </div>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      )}

      {/* Create / Edit Modal */}
      <AnimatePresence>
        {showModal && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={closeModal}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-5">
                  {editingProvider ? '编辑 Provider' : '新增 Provider'}
                </h3>

                <div className="space-y-4">
                  {/* Provider ID */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Provider ID <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formId}
                      onChange={(e) => setFormId(e.target.value)}
                      disabled={!!editingProvider}
                      placeholder="例如: openai, dashscope, deepseek"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow
                        disabled:opacity-50 disabled:cursor-not-allowed"
                    />
                  </div>

                  {/* Base URL */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Base URL <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formBaseUrl}
                      onChange={(e) => setFormBaseUrl(e.target.value)}
                      placeholder="例如: https://api.openai.com/v1"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                    />
                  </div>

                  {/* API Key */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      API Key{' '}
                      {editingProvider && (
                        <span className="text-slate-400 font-normal">(留空则不修改)</span>
                      )}
                      {!editingProvider && <span className="text-red-500">*</span>}
                    </label>
                    <div className="relative">
                      <input
                        type={showApiKey ? 'text' : 'password'}
                        value={formApiKey}
                        onChange={(e) => setFormApiKey(e.target.value)}
                        placeholder={editingProvider ? '留空则保持原值' : '输入 API Key'}
                        className="w-full px-4 py-2.5 pr-10 rounded-xl border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          placeholder:text-slate-400 focus:outline-none focus:ring-2
                          focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                      />
                      <button
                        type="button"
                        onClick={() => setShowApiKey(!showApiKey)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                          hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                      >
                        {showApiKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>

                  {/* Model */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Model <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formModel}
                      onChange={(e) => setFormModel(e.target.value)}
                      placeholder="例如: gpt-4o, qwen-turbo, deepseek-chat"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                    />
                  </div>

                  {/* Embedding Model */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Embedding Model <span className="text-slate-400 font-normal">(可选)</span>
                    </label>
                    <input
                      type="text"
                      value={formEmbeddingModel}
                      onChange={(e) => setFormEmbeddingModel(e.target.value)}
                      placeholder="例如: text-embedding-3-small"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                    />
                  </div>
                </div>

                {/* Modal actions */}
                <div className="flex gap-3 justify-end mt-6">
                  <motion.button
                    onClick={closeModal}
                    disabled={saving}
                    className="px-5 py-2.5 border border-slate-200 dark:border-slate-600
                      text-slate-600 dark:text-slate-300 rounded-xl font-medium text-sm
                      hover:bg-slate-50 dark:hover:bg-slate-700 transition-all
                      disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleSaveModal}
                    disabled={saving}
                    className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                      bg-gradient-to-r from-primary-500 to-primary-600
                      shadow-lg shadow-primary-500/25
                      hover:from-primary-600 hover:to-primary-700
                      transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {saving ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="w-4 h-4 animate-spin" />
                        保存中...
                      </span>
                    ) : (
                      '保存'
                    )}
                  </motion.button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* Delete confirmation dialog */}
      <AnimatePresence>
        {deleteConfirmId && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setDeleteConfirmId(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-6"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
                  删除 Provider
                </h3>
                <p className="text-slate-600 dark:text-slate-300 mb-6">
                  确定要删除 Provider &ldquo;{deleteConfirmId}&rdquo; 吗？删除后无法恢复。
                  如果有模块正在使用此 Provider，请先切换到其他 Provider。
                </p>
                <div className="flex gap-3 justify-end">
                  <motion.button
                    onClick={() => setDeleteConfirmId(null)}
                    disabled={deleting}
                    className="px-5 py-2.5 border border-slate-200 dark:border-slate-600
                      text-slate-600 dark:text-slate-300 rounded-xl font-medium text-sm
                      hover:bg-slate-50 dark:hover:bg-slate-700 transition-all
                      disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleDelete}
                    disabled={deleting}
                    className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                      bg-gradient-to-r from-red-500 to-red-600
                      hover:from-red-600 hover:to-red-700
                      transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {deleting ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="w-4 h-4 animate-spin" />
                        删除中...
                      </span>
                    ) : (
                      '确定删除'
                    )}
                  </motion.button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* Toast notification */}
      <AnimatePresence>
        {toast && (
          <motion.div
            initial={{ opacity: 0, y: 50, x: '-50%' }}
            animate={{ opacity: 1, y: 0, x: '-50%' }}
            exit={{ opacity: 0, y: 50, x: '-50%' }}
            className={`fixed bottom-6 left-1/2 px-5 py-3 rounded-xl shadow-lg text-sm font-medium
              flex items-center gap-2 z-[60] ${
                toast.type === 'success'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-red-600 text-white'
              }`}
          >
            {toast.type === 'success'
              ? <CheckCircle className="w-4 h-4" />
              : <XCircle className="w-4 h-4" />
            }
            {toast.message}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
