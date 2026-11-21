/**
 * 演示包：工具部分。
 *
 * 工具名前缀统一 sampletoolkit_（对应包名 sampletoolkit），
 * 卸载插件时宿主按这个前缀整包撤销。
 */
var $ptools = $ptools || [];

$ptools.push({
    name: 'sampletoolkit_info',
    description: '说明「功能演示包」覆盖了哪些插件能力，作为写插件的参考。',
    parameters: { type: 'object', properties: {}, required: [] },
    handler: function () {
        return JSON.stringify({
            ok: true,
            plugin: 'openx.sample-toolkit',
            components: {
                layout: ['Column', 'Row', 'Box', 'Spacer', 'LazyColumn', 'LazyRow', 'Card', 'Surface'],
                text: ['Text'],
                input: ['TextField', 'Button', 'TextButton', 'IconButton', 'Switch', 'Checkbox',
                        'RadioButton', 'Slider', 'Dropdown'],
                display: ['Image', 'Icon', 'ListItem', 'Chip', 'Tabs',
                          'HorizontalDivider', 'VerticalDivider',
                          'LinearProgressIndicator', 'CircularProgressIndicator'],
                overlay: ['Dialog', 'ModalBottomSheet']
            },
            ctx: [
                'useState / useMemo / useRef',
                'getEnv / setEnv / store.*  (env)',
                'showToast / reportError   (toast)',
                'callTool                  (call_tool)',
                'http / fetch              (http)',
                'copy / paste              (clipboard)',
                'notify                    (notify)',
                'readFile / writeFile / listFiles / pickFile / saveFile / pickImage  (file)'
            ]
        });
    }
});

$ptools.push({
    name: 'sampletoolkit_echo',
    description: '回显传入的文本，用于测试工具调用链路。',
    parameters: {
        type: 'object',
        properties: { text: { type: 'string', description: '要回显的文本' } },
        required: ['text']
    },
    handler: function (args) {
        return JSON.stringify({ ok: true, echo: String((args && args.text) || '') });
    }
});
