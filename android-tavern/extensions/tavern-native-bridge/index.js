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

    if (runtime.initialized) {
        window.__tavernNativeBridgeExtensionReady = true;
        return;
    }

    runtime.initialized = true;
    window.__tavernNativeBridgeExtensionReady = true;

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

    function attachListeners(context, eventTypes) {
        if (runtime.listenersAttached) return true;
        if (!context?.eventSource || !eventTypes.GENERATION_STARTED) return false;

        runtime.listenersAttached = true;

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

        if (eventTypes.GENERATION_AFTER_COMMANDS) {
            // 这时还在组 prompt、等首包，只算「准备中」；「在想了」等首包（见 installFirstPacketHook）。
            context.eventSource.on(eventTypes.GENERATION_AFTER_COMMANDS, (type, params, dryRun) => {
                if (dryRun) return;
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
