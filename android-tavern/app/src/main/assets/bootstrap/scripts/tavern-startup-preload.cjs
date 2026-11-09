'use strict';

// SillyDroid 冷启动加速预加载脚本：由 tavern-entrypoint.sh 通过 `node --require` 只挂到 Tavern server 进程上。
// server-source.zip 是原样打包的上游源码，这里不改任何文件，只在进程内做三件事：
//   1. 打开 Node compile cache，并在启动完成后主动落盘。宿主停服务用的是 kill，
//      进程走不到正常退出，不主动 flush 的话缓存目录永远是空的。
//   2. 把启动阶段用不上的大依赖（vectra / sillytavern-transformers / archiver）换成惰性桩，
//      第一次真正用到时才加载。只对验证过的酒馆版本生效，升级酒馆后要重新核对用法再放开版本号。
//   3. 前端库产物已存在时跳过每次启动都跑的 webpack 编译。
// 任何一步失败都只打日志并退回上游原始行为，不能让加速逻辑把服务启动弄崩。
// 排查问题时可设置 SILLYDROID_STARTUP_PRELOAD=off 整体关闭。

const fs = require('node:fs');
const path = require('node:path');
const nodeModule = require('node:module');
const { pathToFileURL } = require('node:url');

const LOG_PREFIX = 'sillydroid_startup_preload';
// 惰性桩按下面这些文件在该版本里的具体用法写的，版本不在列表里就不替换。
const LAZY_SUPPORTED_TAVERN_VERSIONS = ['1.18.0'];
const LAZY_SCHEME = 'sillydroid-lazy:';
const COMPILE_CACHE_FLUSH_DELAYS_MS = [15_000, 300_000];

function log(message) {
    console.log(`${LOG_PREFIX} ${message}`);
}

function describeError(error) {
    return String(error?.message ?? error).replace(/\s+/g, ' ').slice(0, 200);
}

function enableCompileCache(serverDirectory) {
    if (typeof nodeModule.enableCompileCache !== 'function') {
        return 'unsupported';
    }

    // 缓存放在 server 目录里：酒馆源码重新解包时会连同缓存一起清掉，不会留下对不上的旧缓存。
    const result = nodeModule.enableCompileCache(path.join(serverDirectory, '.cache', 'node-compile-cache'));
    if (typeof nodeModule.flushCompileCache === 'function') {
        for (const delayMs of COMPILE_CACHE_FLUSH_DELAYS_MS) {
            setTimeout(() => {
                try {
                    nodeModule.flushCompileCache();
                } catch (error) {
                    log(`event=compile_cache_flush_failed error=${describeError(error)}`);
                }
            }, delayMs).unref();
        }
    }

    return `status_${result?.status ?? 'unknown'}`;
}

function readTavernVersion(serverDirectory) {
    try {
        return String(JSON.parse(fs.readFileSync(path.join(serverDirectory, 'package.json'), 'utf8')).version ?? '');
    } catch {
        return '';
    }
}

/**
 * 惰性桩定义：importerSuffix 是上游里唯一 import 该包的文件，只替换它的这一处导入，
 * 第三方 server plugin 自己 import 同名包时仍然拿到真实模块。
 */
function buildLazyStubs(serverPackageUrl) {
    const requireFromServer = `import { createRequire } from 'node:module';\nconst require = createRequire(${JSON.stringify(serverPackageUrl)});\n`;

    return [
        {
            specifier: 'vectra',
            importerSuffix: '/src/endpoints/vectors.js',
            // 上游用法：import vectra from 'vectra'; new vectra.LocalIndex(path)
            source: `${requireFromServer}
let real;
const load = () => real ??= require('vectra');
export default new Proxy({}, {
    get: (_target, key) => load()[key],
    has: (_target, key) => key in load(),
});
`,
        },
        {
            specifier: 'archiver',
            importerSuffix: '/src/users.js',
            // 上游用法：import archiver from 'archiver'; archiver('zip')
            source: `${requireFromServer}
let real;
const load = () => real ??= require('archiver');
export default new Proxy(function archiver() {}, {
    apply: (_target, thisArg, args) => Reflect.apply(load(), thisArg, args),
    get: (_target, key) => load()[key],
    has: (_target, key) => key in load(),
});
`,
        },
        {
            specifier: 'sillytavern-transformers',
            importerSuffix: '/src/transformers.js',
            // 上游用法：模块顶层给 env.backends.onnx.wasm 赋值；之后只在 async 函数里
            // await pipeline(...) 和 await RawImage.fromBlob(...)。env 先记在桩对象上，真实模块加载后再合并过去。
            source: `
let real;
const pendingEnv = { backends: { onnx: { wasm: {} } } };
const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
function assignDeep(target, source) {
    for (const [key, value] of Object.entries(source)) {
        if (isPlainObject(value) && isPlainObject(target[key])) {
            assignDeep(target[key], value);
        } else {
            target[key] = value;
        }
    }
}
const load = () => real ??= import('sillytavern-transformers').then((module) => {
    assignDeep(module.env, pendingEnv);
    return module;
});
export const env = pendingEnv;
export const pipeline = async (...args) => (await load()).pipeline(...args);
export const RawImage = {
    fromBlob: async (...args) => (await load()).RawImage.fromBlob(...args),
};
`,
        },
    ];
}

const WEBPACK_SERVE_SUFFIX = '/src/middleware/webpack-serve.js';
const WEBPACK_COMPILE_ANCHOR = `        console.log();
        console.log('Compiling frontend libraries...');

        const publicLibConfig = getPublicLibConfig({ forceDist, pruneCache });
        const compiler = webpack(publicLibConfig);
`;

/**
 * 输出目录按酒馆版本 + webpack 版本区分，产物已存在就说明这个版本编译过，直接跳过。
 * 锚点对不上（上游改了写法）时返回 null，保持原始行为。
 */
function patchWebpackServeSource(source) {
    if (!source.includes(WEBPACK_COMPILE_ANCHOR)) {
        return null;
    }

    const patched = source.replace(WEBPACK_COMPILE_ANCHOR, `        const publicLibConfig = getPublicLibConfig({ forceDist, pruneCache });
        const sillydroidOutputFile = path.join(publicLibConfig.output?.path ?? '', publicLibConfig.output?.filename ?? '');
        console.log();
        if (publicLibConfig.output?.path && sillydroidFs.statSync(sillydroidOutputFile, { throwIfNoEntry: false })?.size > 0) {
            console.log(\`Frontend libraries are up to date: \${sillydroidOutputFile}\`);
            return Promise.resolve();
        }
        console.log('Compiling frontend libraries...');
        const compiler = webpack(publicLibConfig);
`);
    return `import sillydroidFs from 'node:fs';\n${patched}`;
}

function isUpstreamSourceUrl(url, suffix) {
    return typeof url === 'string' && url.startsWith('file:') && url.endsWith(suffix) && !url.includes('/node_modules/');
}

function registerHooks(serverDirectory, lazyEnabled) {
    if (typeof nodeModule.registerHooks !== 'function') {
        return { lazy: 'unsupported', webpackSkip: 'unsupported' };
    }

    const serverPackageUrl = pathToFileURL(path.join(serverDirectory, 'package.json')).href;
    const lazyStubs = lazyEnabled ? buildLazyStubs(serverPackageUrl) : [];

    nodeModule.registerHooks({
        resolve(specifier, context, nextResolve) {
            const parentURL = context.parentURL;
            if (typeof parentURL === 'string' && parentURL.startsWith(LAZY_SCHEME)) {
                // 桩模块自己去加载真实包时，要按 server 目录的 node_modules 解析。
                return nextResolve(specifier, { ...context, parentURL: serverPackageUrl });
            }

            for (const stub of lazyStubs) {
                if (specifier === stub.specifier && isUpstreamSourceUrl(parentURL, stub.importerSuffix)) {
                    return { url: `${LAZY_SCHEME}${stub.specifier}`, format: 'module', shortCircuit: true };
                }
            }

            return nextResolve(specifier, context);
        },
        load(url, context, nextLoad) {
            if (url.startsWith(LAZY_SCHEME)) {
                const stub = lazyStubs.find((candidate) => url === `${LAZY_SCHEME}${candidate.specifier}`);
                if (stub) {
                    return { format: 'module', source: stub.source, shortCircuit: true };
                }
            }

            const result = nextLoad(url, context);
            if (isUpstreamSourceUrl(url, WEBPACK_SERVE_SUFFIX) && result.source != null) {
                const patched = patchWebpackServeSource(String(result.source));
                if (patched !== null) {
                    return { ...result, source: patched };
                }
                log('event=webpack_skip_anchor_missing');
            }

            return result;
        },
    });

    return {
        lazy: lazyStubs.length > 0 ? lazyStubs.map((stub) => stub.specifier).join(',') : 'off',
        webpackSkip: 'on',
    };
}

function main() {
    if (/^(off|false|0|disabled)$/i.test(process.env.SILLYDROID_STARTUP_PRELOAD ?? '')) {
        log('event=disabled reason=env');
        return;
    }

    const serverDirectory = process.env.TAVERN_SERVER_DIR || process.cwd();
    let compileCache = 'failed';
    try {
        compileCache = enableCompileCache(serverDirectory);
    } catch (error) {
        log(`event=compile_cache_failed error=${describeError(error)}`);
    }

    const tavernVersion = readTavernVersion(serverDirectory);
    const lazyEnabled = LAZY_SUPPORTED_TAVERN_VERSIONS.includes(tavernVersion);
    let hooks = { lazy: 'failed', webpackSkip: 'failed' };
    try {
        hooks = registerHooks(serverDirectory, lazyEnabled);
    } catch (error) {
        log(`event=register_hooks_failed error=${describeError(error)}`);
    }

    log(`event=ready tavern_version=${tavernVersion || 'unknown'} compile_cache=${compileCache} lazy=${hooks.lazy} webpack_skip=${hooks.webpackSkip}`);
}

try {
    main();
} catch (error) {
    log(`event=failed error=${describeError(error)}`);
}
