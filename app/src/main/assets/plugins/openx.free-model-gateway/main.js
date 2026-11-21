/**
 * 免费模型网关 — AI 工具入口（main.js）
 *
 * 注册一个工具让 AI 能查询当前网关可用的免费模型清单。
 * 网关本身的 HTTP 服务由 service/gateway.js 提供，在插件详情页「启动服务」后运行。
 */
exports.tools = {
    freemodelgateway_list_models: {
        description: '列出免费模型网关当前可用的模型清单（免注册免 Key，含 DeepSeek/GLM/Kimi/Nemotron 等）。',
        parameters: {
            type: 'object',
            properties: {
                source: {
                    type: 'string',
                    enum: ['opencode', 'kilo', 'all'],
                    description: '模型来源：opencode（OpenCode Zen 网关）、kilo（Kilo 免费池）、all（全部，默认）'
                }
            }
        }
    }
};

exports.handlers = {
    freemodelgateway_list_models: function (params) {
        var source = (params && params.source) || 'all';
        var models = [];
        if (source === 'opencode' || source === 'all') {
            models = models.concat(MODELS_OPENCODE);
        }
        if (source === 'kilo' || source === 'all') {
            models = models.concat(MODELS_KILO);
        }
        return {
            source: source,
            count: models.length,
            models: models,
            usage: '在插件详情页点「启动服务」后，将提供商 baseUrl 设为 http://127.0.0.1:18899 即可使用这些模型。'
        };
    }
};

// 静态模型清单（与 service/gateway.js 同步；运行时由 service 实时拉取上游刷新）
var MODELS_OPENCODE = [
    'deepseek-v4.1-flash-free',
    'glm-5.3-flash-free',
    'mimo-v2.6-flash-free',
    'nemotron-3.5-super-vl-free',
    'nemotron-3.5-lightning-free',
    'longcat-2.5-preview-free',
    'beam-free',
    'jev-1.13-free',
    'big-pickle',
    'space-bunny-free'
];

var MODELS_KILO = [
    'kilo-auto/free',
    'nvidia/nemotron-3.5-super-vl:free',
    'reflection/beam-free',
    'dots-studio/dots-3-note-preview:free',
    'liquid/lfm-2.5-2.6b:free',
    'nvidia/nemotron-3.5-lightning:free',
    'thinkingmachines/inkling-small:free',
    'poolside/laguna-s-2.1:free',
    'poolside/laguna-xs-2.1:free',
    'cohere/north-mini-code:free',
    'openrouter/free'
];
