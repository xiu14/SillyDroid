(() => {
    const MODULE_ID = 'third-party/tavern-native-bridge';
    const MODULE_NAME = 'Tavern Native Bridge';
    const RETRY_INTERVAL_MS = 500;
    const MAX_ATTACH_ATTEMPTS = 60;
    const STREAMING_THROTTLE_MS = 1200;
    const INITIAL_SCROLL_DELAYS_MS = [120, 420, 900, 1600];
    // 发送按钮已经报过「准备中」时，本轮开始生成就不再重复报，避免宿主重置计时。
    const PENDING_DEDUPE_MS = 60 * 1000;
    // 生成请求：chat-completions / text-completions / kobold 等后端的 /generate。
    const GENERATE_URL_PATTERN = /\/api\/(?:backends\/[\w-]+|novelai|horde)\/generate(?:[?#]|$)/;
    // 酒馆助手静默生成从 js_generation_requested 到 GENERATION_AFTER_COMMANDS 只隔处理输入的一小段，超时没对上就作废。
    const SILENT_HELPER_REQUEST_TTL_MS = 30 * 1000;

    if (typeof window === 'undefined') return;

    const runtime = window.__tavernNativeBridgeRuntime ??= {
        initialized: false,
        listenersAttached: false,
        pendingHooksInstalled: false,
        fetchHookInstalled: false,
        scrollHooksInstalled: false,
        scrollCancelHooksInstalled: false,
        attempts: 0,
        timer: null,
        initialScrollChatKey: '',
        initialScrollCancelled: false,
        initialScrollTimers: [],
        isGenerating: false,
        hasContentStarted: false,
        lastStreamingAt: 0,
        preContentBuffer: '',
        pendingGeneration: null,
        startedEmitted: false,
    };
    runtime.silentHelperRequests ??= [];
    runtime.lastThemeBackground ??= '';

    if (runtime.initialized) {
        window.__tavernNativeBridgeExtensionReady = true;
        return;
    }

    runtime.initialized = true;
    window.__tavernNativeBridgeExtensionReady = true;
    // 启动时间线：本扩展 loading_order 最靠前，被加载的时刻约等于酒馆前端开始装载第三方扩展的时刻。
    runtime.bridgeEvalMs = performance.now();
    // Resource Timing 缓冲区默认 250 条，页面加载完就用掉两百多条，初始化期间的请求会被丢掉。
    try {
        performance.setResourceTimingBufferSize(2000);
    } catch {
        // ignore
    }

    function emit(name, payload = {}) {
        try {
            const bridge = window.AndroidBridge || window.SillyDroidAndroidHostBridge;
            if (!bridge || typeof bridge.postEvent !== 'function') return false;

            const eventPayload = {
                bridge: MODULE_ID,
                source: MODULE_ID,
                version: '0.1.3-sillydroid.1',
                at: Date.now(),
                href: location.href,
                characterName: getCharacterName(),
                ...payload,
            };

            bridge.postEvent(String(name), JSON.stringify(eventPayload));
            return true;
        } catch (error) {
            console.warn(`[${MODULE_NAME}] emit failed`, error);
            return false;
        }
    }

    // ---- 启动时间线（诊断用） ----
    // 「点开 → 能聊天」分三段：Node 启动、页面加载、酒馆前端初始化（firstLoadInit 里一长串 await）。
    // 前两段宿主日志里已有，这里补第三段：APP_INITIALIZED / APP_READY 的时刻，加上初始化期间
    // 每个 API 请求和第三方扩展脚本的起止时刻（Resource Timing 事后读取，扩展加载之前的请求也在）。
    // 时间都相对页面 timeOrigin，timeOrigin 本身是绝对时间，用来和宿主启动日志对齐。
    const STARTUP_TIMELINE_LINE_LIMIT = 3800;

    function compactResourcePath(url) {
        try {
            const parsed = new URL(url, location.href);
            if (parsed.origin !== location.origin) return `ext-host:${parsed.host}`;
            return parsed.pathname
                .replace(/^\/scripts\/extensions\/third-party\//, 'ext:')
                .replace(/^\/scripts\/extensions\//, 'sysext:')
                .replace(/^\/api\//, 'api:');
        } catch {
            return String(url).slice(0, 80);
        }
    }

    function collectStartupResources(untilMs) {
        const entries = performance.getEntriesByType('resource')
            .filter((entry) => entry.startTime <= untilMs)
            .filter((entry) => {
                const path = compactResourcePath(entry.name);
                return entry.initiatorType === 'fetch'
                    || entry.initiatorType === 'xmlhttprequest'
                    || path.startsWith('ext:')
                    || entry.duration >= 100;
            })
            .sort((left, right) => left.startTime - right.startTime);
        return entries.map((entry) => `${compactResourcePath(entry.name)}@${Math.round(entry.startTime)}+${Math.round(entry.duration)}`);
    }

    function sendStartupTimeline() {
        if (runtime.startupTimelineSent) return;
        runtime.startupTimelineSent = true;
        try {
            const bridge = window.AndroidBridge || window.SillyDroidAndroidHostBridge;
            if (!bridge || typeof bridge.recordWebPerformanceDiagnostic !== 'function') return;
            const nav = performance.getEntriesByType('navigation')[0];
            const round = (value) => (Number.isFinite(value) ? Math.round(value) : -1);
            const head = [
                'event=tavern_startup_timeline',
                `timeOrigin=${Math.round(performance.timeOrigin)}`,
                `responseEnd=${round(nav?.responseEnd)}`,
                `domContentLoaded=${round(nav?.domContentLoadedEventEnd)}`,
                `load=${round(nav?.loadEventEnd)}`,
                `bridgeEval=${round(runtime.bridgeEvalMs)}`,
                `bridgeAttach=${round(runtime.startupSubscribedMs)}`,
                `appInitialized=${round(runtime.appInitializedMs)}`,
                `appReady=${round(runtime.appReadyMs)}`,
                // 订阅时加载遮罩已经没了，说明 APP_READY 早就发过、拿到的是自动补触发的时刻，不准。
                `exact=${runtime.startupOverlayAtSubscribe ? 1 : 0}`,
            ].join(' ');
            bridge.recordWebPerformanceDiagnostic(head);

            const resources = collectStartupResources(runtime.appReadyMs ?? performance.now());
            let line = 'event=tavern_startup_resources part=1';
            let part = 1;
            for (const item of resources) {
                if (line.length + item.length + 1 > STARTUP_TIMELINE_LINE_LIMIT) {
                    bridge.recordWebPerformanceDiagnostic(line);
                    part += 1;
                    line = `event=tavern_startup_resources part=${part}`;
                }
                line += ` ${item}`;
            }
            bridge.recordWebPerformanceDiagnostic(line);
        } catch (error) {
            console.warn(`[${MODULE_NAME}] startup timeline failed`, error);
        }
    }

    function installStartupTimeline(context, eventTypes) {
        if (runtime.startupTimelineInstalled || !eventTypes.APP_READY) return;
        runtime.startupTimelineInstalled = true;
        runtime.startupSubscribedMs = performance.now();
        runtime.startupOverlayAtSubscribe = Boolean(document.querySelector('.splash-screen'));
        if (eventTypes.APP_INITIALIZED) {
            context.eventSource.on(eventTypes.APP_INITIALIZED, () => {
                runtime.appInitializedMs ??= performance.now();
            });
        }
        context.eventSource.on(eventTypes.APP_READY, () => {
            runtime.appReadyMs ??= performance.now();
            // 等 APP_READY 的其他监听器跑完再读 Resource Timing，不占初始化的关键路径。
            setTimeout(sendStartupTimeline, 0);
        });
    }

    function getContext() {
        try {
            return window.SillyTavern?.getContext?.() || null;
        } catch {
            return null;
        }
    }

    function getEventTypes(context) {
        return context?.eventTypes || context?.event_types || {};
    }

    function getCharacterName() {
        try {
            const context = getContext();
            const id = context?.characterId;
            const character = context?.characters?.[id];
            return (character && (character.name || character.avatar)) || '';
        } catch {
            return '';
        }
    }

    function hasPendingInput() {
        try {
            const input = document.querySelector('#send_textarea');
            const value = String(input?.value || '').trim();
            return Boolean(value && value.charAt(0) !== '/');
        } catch {
            return false;
        }
    }

    function emitPending(source, hadPendingInput = false) {
        const now = Date.now();
        if (runtime.lastPendingAt && now - runtime.lastPendingAt < 1200) return;
        if (!hadPendingInput && !hasPendingInput()) return;

        runtime.lastPendingAt = now;
        emit('generation_pending', {
            source: source || 'unknown',
            at: now,
        });
    }

    function installPendingHooks() {
        if (runtime.pendingHooksInstalled || typeof document === 'undefined') return;
        runtime.pendingHooksInstalled = true;

        function schedulePending(source, hadPendingInput) {
            clearTimeout(runtime.pendingTimer);
            runtime.pendingTimer = setTimeout(() => {
                emitPending(source, hadPendingInput);
            }, 120);
        }

        function onSendButtonIntent(event) {
            const target = event.target?.closest?.('#send_but');
            if (target) schedulePending(`send_button_${event.type}`, hasPendingInput());
        }

        document.addEventListener('touchstart', onSendButtonIntent, { capture: true, passive: true });
        document.addEventListener('pointerdown', onSendButtonIntent, { capture: true, passive: true });
        document.addEventListener('mousedown', onSendButtonIntent, true);
        document.addEventListener('click', onSendButtonIntent, true);
    }

    function getRenderedChatState() {
        try {
            const chat = document.querySelector('#chat');
            if (!chat) return null;

            const messages = chat.querySelectorAll('.mes');
            if (!messages.length) return null;

            return {
                chat,
                lastMessage: messages[messages.length - 1],
            };
        } catch {
            return null;
        }
    }

    function scrollChatToLatest() {
        try {
            const state = getRenderedChatState();
            if (!state) return false;

            if (state.lastMessage && typeof state.lastMessage.scrollIntoView === 'function') {
                state.lastMessage.scrollIntoView({ block: 'end', inline: 'nearest', behavior: 'auto' });
            }

            state.chat.scrollTop = state.chat.scrollHeight;
            return true;
        } catch (error) {
            console.warn(`[${MODULE_NAME}] scroll to latest failed`, error);
            return false;
        }
    }

    function normalizeKeyPart(value) {
        if (value === undefined || value === null || value === '') return '';
        return String(value);
    }

    function getActiveChatKey(context = getContext()) {
        try {
            const chatId = normalizeKeyPart(context?.chatId || context?.getCurrentChatId?.());
            const groupId = normalizeKeyPart(context?.groupId);
            const characterId = normalizeKeyPart(context?.characterId);

            if (!chatId) return '';
            if (groupId) return `group:${groupId}:${chatId}`;
            if (characterId) return `character:${characterId}:${chatId}`;
            return '';
        } catch {
            return '';
        }
    }

    function clearInitialScrollTimers() {
        for (const timer of runtime.initialScrollTimers || []) {
            clearTimeout(timer);
        }
        runtime.initialScrollTimers = [];
    }

    function cancelInitialChatScroll() {
        if (!runtime.initialScrollTimers?.length) return;
        runtime.initialScrollCancelled = true;
        clearInitialScrollTimers();
    }

    function runInitialChatScrollAttempt(chatKey) {
        try {
            if (runtime.initialScrollCancelled || runtime.initialScrollChatKey !== chatKey) return;
            const scroll = () => {
                if (runtime.initialScrollCancelled || runtime.initialScrollChatKey !== chatKey) return;
                scrollChatToLatest();
            };

            if (typeof requestAnimationFrame === 'function') {
                requestAnimationFrame(scroll);
            } else {
                setTimeout(scroll, 0);
            }
        } catch (error) {
            console.warn(`[${MODULE_NAME}] initial scroll failed`, error);
        }
    }

    // force：同一个聊天被重新渲染（reloadCurrentChat 等）时也要重新补滚，不能按 chatKey 去重。
    function scheduleInitialChatScroll(context, reason, { force = false } = {}) {
        try {
            const chatKey = getActiveChatKey(context);
            if (!chatKey) {
                runtime.initialScrollChatKey = '';
                runtime.initialScrollCancelled = true;
                clearInitialScrollTimers();
                return;
            }

            if (!force && runtime.initialScrollChatKey === chatKey) return;

            runtime.initialScrollChatKey = chatKey;
            runtime.initialScrollCancelled = false;
            runtime.lastScrollReason = reason || 'unknown';
            clearInitialScrollTimers();

            for (const delay of INITIAL_SCROLL_DELAYS_MS) {
                const timer = setTimeout(() => {
                    runtime.initialScrollTimers = (runtime.initialScrollTimers || []).filter((item) => item !== timer);
                    runInitialChatScrollAttempt(chatKey);
                }, delay);
                runtime.initialScrollTimers.push(timer);
            }
        } catch (error) {
            console.warn(`[${MODULE_NAME}] schedule initial scroll failed`, error);
        }
    }

    function installInitialScrollCancelHooks() {
        if (runtime.scrollCancelHooksInstalled || typeof document === 'undefined') return;
        runtime.scrollCancelHooksInstalled = true;

        const cancel = () => cancelInitialChatScroll();
        document.addEventListener('touchstart', cancel, { capture: true, passive: true });
        document.addEventListener('pointerdown', cancel, { capture: true, passive: true });
        document.addEventListener('wheel', cancel, { capture: true, passive: true });
        document.addEventListener('keydown', cancel, true);
        document.addEventListener('click', cancel, true);
    }

    function installChatScrollHooks(context, eventTypes) {
        if (runtime.scrollHooksInstalled) return;
        runtime.scrollHooksInstalled = true;
        window.__stNativeScrollChatToLatest = scrollChatToLatest;
        installInitialScrollCancelHooks();
        scheduleInitialChatScroll(context, 'attach');

        // ST 只在加载/重载/关闭聊天、重新渲染整页消息后才发 CHAT_CHANGED，每次都补滚；
        // 紧随其后的 CHAT_LOADED 属于同一次加载，仍按 chatKey 去重。
        if (eventTypes.CHAT_CHANGED) {
            context.eventSource.on(eventTypes.CHAT_CHANGED, () => {
                scheduleInitialChatScroll(getContext() || context, 'chat_changed', { force: true });
            });
        }

        if (eventTypes.CHAT_LOADED) {
            context.eventSource.on(eventTypes.CHAT_LOADED, () => {
                scheduleInitialChatScroll(getContext() || context, 'chat_loaded');
            });
        }
    }

    function markContentStarted(text, reason) {
        if (runtime.hasContentStarted) return;
        runtime.hasContentStarted = true;
        emit('generation_content_started', {
            reason: reason || 'content',
            length: String(text || '').length,
        });
    }

    function updatePreContentState(text) {
        const value = String(text || '').trim();
        if (!value) return;
        runtime.preContentBuffer = (runtime.preContentBuffer + value.toLowerCase()).slice(-8000);
    }

    function hasContentTag() {
        return /<\s*content\b[^>]*>/i.test(runtime.preContentBuffer);
    }

    function resetGenerationState() {
        runtime.isGenerating = false;
        runtime.hasContentStarted = false;
        runtime.preContentBuffer = '';
        runtime.lastStreamingAt = 0;
        runtime.pendingGeneration = null;
        runtime.startedEmitted = false;
        runtime.lastPendingAt = 0;
    }

    /** 本轮生成已开始（命令处理完、正在组 prompt 和等首包）：还没报过「准备中」就补报一次。 */
    function emitGenerationPreparing(type) {
        const now = Date.now();
        if (runtime.lastPendingAt && now - runtime.lastPendingAt < PENDING_DEDUPE_MS) return;
        runtime.lastPendingAt = now;
        emit('generation_pending', {
            source: 'generation_after_commands',
            type: type || 'normal',
            at: now,
        });
    }

    /**
     * 生成请求的响应头到达即视为首包。ST 服务端等上游有响应才转发，网关又是读到上游首个数据块后才发响应头，
     * 所以这里的时机就是网关记录的首包；只看响应头，不碰响应体，不影响 ST 读流。
     */
    function installFirstPacketHook() {
        if (runtime.fetchHookInstalled || typeof window.fetch !== 'function') return;
        runtime.fetchHookInstalled = true;

        const originalFetch = window.fetch;
        window.fetch = function (input, init) {
            const promise = originalFetch.apply(this, arguments);
            try {
                const url = typeof input === 'string' ? input : input?.url || String(input || '');
                if (!GENERATE_URL_PATTERN.test(url) || !runtime.isGenerating || runtime.startedEmitted) return promise;
                promise.then((response) => {
                    if (!response?.ok || !runtime.isGenerating) return;
                    const pending = runtime.pendingGeneration || {};
                    emitGenerationStarted(pending.type, pending.params, 'first_packet');
                }, () => {});
            } catch (error) {
                console.warn(`[${MODULE_NAME}] first packet hook failed`, error);
            }
            return promise;
        };
    }

    function emitGenerationStarted(type, params, reason) {
        if (runtime.startedEmitted) return;

        runtime.isGenerating = true;
        runtime.startedEmitted = true;
        emit('generation_started', {
            type: type || 'normal',
            params: params || null,
            reason: reason || 'generation_after_commands',
        });
    }

    /**
     * 这次 GENERATION_AFTER_COMMANDS 是不是酒馆助手静默生成发的。ST 自己的生成 params 带 quiet_prompt 等字段，
     * 酒馆助手固定传空对象，靠这一点区分，免得静默请求排队时把同时发生的正常回复也吞掉。
     */
    function consumeSilentHelperGeneration(params) {
        const now = Date.now();
        const queue = runtime.silentHelperRequests;
        while (queue.length && now - queue[0] > SILENT_HELPER_REQUEST_TTL_MS) queue.shift();
        if (!queue.length) return false;
        if (!params || typeof params !== 'object' || Object.keys(params).length) return false;
        queue.shift();
        return true;
    }

    /** 把 #rgb / #rrggbb / #rrggbbaa / rgb() / rgba() 换成不透明的 #RRGGBB；认不出返回空串。 */
    function toOpaqueHexColor(value) {
        const text = String(value || '').trim().toLowerCase();
        let channels = null;
        const hex = text.match(/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/);
        if (hex) {
            const digits = hex[1].length === 3 ? hex[1].split('').map((digit) => digit + digit).join('') : hex[1].slice(0, 6);
            channels = [0, 2, 4].map((index) => parseInt(digits.slice(index, index + 2), 16));
        } else {
            const rgb = text.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/);
            if (rgb) channels = rgb.slice(1, 4).map((channel) => Math.round(Number(channel)));
        }
        if (!channels || channels.some((channel) => !Number.isFinite(channel) || channel < 0 || channel > 255)) return '';
        return `#${channels.map((channel) => channel.toString(16).padStart(2, '0')).join('').toUpperCase()}`;
    }

    // 酒馆主题套上后，把加载画面和 body 用的底色报给 App，下次启动时启动画面和占位底色就用它。
    function reportThemeBackground() {
        try {
            const raw = getComputedStyle(document.documentElement).getPropertyValue('--SmartThemeBlurTintColor');
            const background = toOpaqueHexColor(raw);
            if (!background || background === runtime.lastThemeBackground) return;
            runtime.lastThemeBackground = background;
            emit('page_theme_background', { background });
        } catch (error) {
            console.warn(`[${MODULE_NAME}] report theme background failed`, error);
        }
    }

    function scheduleThemeBackgroundReport() {
        clearTimeout(runtime.themeBackgroundTimer);
        runtime.themeBackgroundTimer = setTimeout(reportThemeBackground, 300);
    }

    function attachListeners(context, eventTypes) {
        if (runtime.listenersAttached) return true;
        if (!context?.eventSource || !eventTypes.GENERATION_STARTED) return false;

        runtime.listenersAttached = true;

        // APP_READY 是自动补发事件，晚挂上的监听也会收到；换主题会触发 SETTINGS_UPDATED。
        for (const eventType of [eventTypes.APP_READY, eventTypes.SETTINGS_LOADED_AFTER, eventTypes.SETTINGS_UPDATED]) {
            if (eventType) context.eventSource.on(eventType, scheduleThemeBackgroundReport);
        }

        context.eventSource.on(eventTypes.GENERATION_STARTED, (type, params, dryRun) => {
            if (dryRun) return;
            runtime.isGenerating = false;
            runtime.hasContentStarted = false;
            runtime.preContentBuffer = '';
            runtime.lastStreamingAt = 0;
            runtime.pendingGeneration = {
                type: type || 'normal',
                params: params || null,
            };
        });

        // 酒馆助手的 generate / generateRaw 先发 js_generation_requested，再发 GENERATION_AFTER_COMMANDS('normal', {})。
        // should_silence 的后台生成（自动总结之类）不绑停止按钮，结束时不会有 GENERATION_ENDED，流体云会一直挂着，所以整轮忽略。
        context.eventSource.on('js_generation_requested', (generationId, kind, config) => {
            if (!config?.should_silence) return;
            runtime.silentHelperRequests.push(Date.now());
        });

        if (eventTypes.GENERATION_AFTER_COMMANDS) {
            // 这时还在组 prompt、等首包，只算「准备中」；「在想了」等首包（见 installFirstPacketHook）。
            context.eventSource.on(eventTypes.GENERATION_AFTER_COMMANDS, (type, params, dryRun) => {
                if (dryRun) return;
                if (consumeSilentHelperGeneration(params)) return;
                runtime.isGenerating = true;
                runtime.pendingGeneration = { type: type || 'normal', params: params || null };
                emitGenerationPreparing(type);
            });
        }

        if (eventTypes.STREAM_TOKEN_RECEIVED) {
            context.eventSource.on(eventTypes.STREAM_TOKEN_RECEIVED, (text) => {
                if (!runtime.startedEmitted) {
                    const pending = runtime.pendingGeneration || {};
                    emitGenerationStarted(pending.type, pending.params, 'first_stream_token_fallback');
                }

                const value = String(text || '');
                if (!runtime.hasContentStarted) {
                    updatePreContentState(value);
                    if (hasContentTag()) {
                        markContentStarted(value, 'content_tag');
                    }
                }

                if (!runtime.hasContentStarted) return;

                const now = Date.now();
                if (now - runtime.lastStreamingAt < STREAMING_THROTTLE_MS) return;
                runtime.lastStreamingAt = now;
                emit('generation_streaming', {
                    length: value.length,
                });
            });
        }

        if (eventTypes.GENERATION_ENDED) {
            context.eventSource.on(eventTypes.GENERATION_ENDED, (messageId) => {
                emit('generation_ended', {
                    messageId,
                });
                resetGenerationState();
            });
        }

        if (eventTypes.GENERATION_STOPPED) {
            context.eventSource.on(eventTypes.GENERATION_STOPPED, () => {
                emit('generation_stopped');
                resetGenerationState();
            });
        }

        return true;
    }

    function attach() {
        try {
            installPendingHooks();
            installFirstPacketHook();

            const context = getContext();
            const eventTypes = getEventTypes(context);
            if (!context?.eventSource || !eventTypes) return false;

            installStartupTimeline(context, eventTypes);
            installChatScrollHooks(context, eventTypes);
            const attached = attachListeners(context, eventTypes);
            if (attached) {
                emit('st_bridge_ready', {
                    bridge: MODULE_ID,
                    version: '0.1.3',
                });
            }
            return attached;
        } catch (error) {
            console.warn(`[${MODULE_NAME}] attach failed`, error);
            return false;
        }
    }

    function start() {
        if (attach()) return;

        runtime.timer = setInterval(() => {
            runtime.attempts += 1;
            if (attach() || runtime.attempts >= MAX_ATTACH_ATTEMPTS) {
                clearInterval(runtime.timer);
                runtime.timer = null;
            }
        }, RETRY_INTERVAL_MS);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start, { once: true });
    } else {
        start();
    }
})();
