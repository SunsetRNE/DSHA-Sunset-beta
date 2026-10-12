/**
 * DSHA：explorer / preview 列「升起」动画的所有权与减弱动态效果。
 *
 * 基线（上游）只把 Animation 丢掉、只记一个 bool 可见性，于是：
 *   · 不看 prefers-reduced-motion（CSS 的 animation:none 管不到 WAAPI）；
 *   · 同列重开后旧动画还在跑，两条 fill:backwards 互相抢手态；
 *   · 列被 React 换掉 / 摘掉 / 任务卸载后旧动画无人取消。
 *
 * 本段与交接产物（1a974de979530038）的同名函数保持同一语义，只补一处：**关闭取消**。
 * 本插件的关闭路径是 `visibility: hidden`（见 misc.css：列 position:fixed，靠
 * [data-aionui-*-open] 标记切 visibility，节点留在 DOM 里），节点既不脱离文档也不换身份，
 * 所以只按 isConnected / 节点身份清扫的版本不会取消关闭后仍在跑的那条动画。
 *
 * 其余边界与交接产物一致，逐条对齐以免真机验收结论失效：
 *   · 只登记本任务启动的 Animation（宿主 / 第三方创建的一律不进表 ⇒ 永远不被我们 cancel）；
 *   · 有界 observer 只观察列的直接父节点 childList（不是全树、不是 subtree，
 *     也不给 task 加 `*`，避免流式文本每次都跑 ensure）；
 *   · 槽位缺席只释放所有权记录，刻意不动 seen —— 「摘掉再挂回来是否重播」沿用既有语义；
 *   · reduce 在播放那一刻读，用户中途改系统开关也生效。
 *
 * 由 tools/apply-mobile-client-patches.mjs 整段替换上游函数；请勿直接编辑随包产物。
 */
function createSheetRiseTask() {
    const cols = ['[data-aionui-explorer-col]', '[data-aionui-preview-col]'];
    const seen = new Map();
    // 每列我们自己启动的那一条 Animation。宿主 / 第三方创建的动画从不进这张表，
    // 因此永远不会被我们 cancel。
    const own = new Map();
    let observer = null;
    const observedParents = new Set();
    const reduceMotion = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const cancelOwn = (sel) => {
        const rec = own.get(sel);
        if (rec === undefined)
            return;
        own.delete(sel);
        try {
            rec.anim.cancel();
        }
        catch {
            /* 已自然结束或已取消 */
        }
    };
    /** 释放失效记录：元素已脱离文档，或该槽位已经换成别的节点。 */
    const sweep = () => {
        for (const sel of cols) {
            const rec = own.get(sel);
            if (rec === undefined)
                continue;
            if (!rec.el.isConnected) {
                cancelOwn(sel);
                continue;
            }
            const current = document.querySelector(sel);
            if (current !== null && current !== rec.el)
                cancelOwn(sel);
        }
    };
    const watchParentOf = (el) => {
        const parent = el.parentElement;
        if (parent === null || observedParents.has(parent))
            return;
        if (typeof MutationObserver === 'undefined')
            return; // 测试 / 老环境：退化为 ensure 期清扫。
        observedParents.add(parent);
        if (observer === null)
            observer = new MutationObserver(() => { sweep(); });
        observer.observe(parent, { childList: true });
    };
    const play = (sel, el) => {
        // 同列重开：先取消上一条自有动画，避免两条 fill:backwards 叠在一起互相抢手态。
        cancelOwn(sel);
        if (reduceMotion())
            return; // reduce：不启动，列保持原样完整可见。
        const anim = el.animate([
            { opacity: 0, transform: 'translateY(28px)' },
            { opacity: 1, transform: 'none' },
        ], { duration: 280, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'backwards' });
        own.set(sel, { el, anim });
        // 自然结束后摘掉记录；cancel 造成的 finished 拒绝在此静默。
        anim.finished.then(() => {
            if (own.get(sel)?.anim === anim)
                own.delete(sel);
        }).catch(() => { });
    };
    return {
        name: 'sheet-rise-replay',
        // The flush runs on the next frame, by which time React has rendered the
        // opened col, so the frame markers / inline style / class changes are
        // reliable triggers — no '*'.
        scopes: [
            'style',
            'class',
            'data-aionui-explorer-open',
            'data-aionui-preview-open',
            'data-mobile-preview-full',
        ],
        ensure: () => {
            sweep();
            for (const sel of cols) {
                const el = document.querySelector(sel);
                // 槽位缺席：释放该列的所有权记录。刻意不动 seen ——「摘掉再挂回来是否
                // 重播」沿用既有语义，不做行为变更。
                if (el === null) {
                    cancelOwn(sel);
                    continue;
                }
                watchParentOf(el);
                const rec = own.get(sel);
                if (rec !== undefined && rec.el !== el)
                    cancelOwn(sel);
                const visible = getComputedStyle(el).visibility === 'visible';
                // 关闭（visibility 转 hidden，节点不摘）必须取消还在跑的自己那条。
                if (!visible)
                    cancelOwn(sel);
                const prev = seen.get(sel) ?? false;
                if (visible && !prev)
                    play(sel, el);
                seen.set(sel, visible);
            }
        },
        dispose: () => {
            // 停用 / 卸载：取消自己启动的动画（fill:backwards 的起手态否则会留在元素上）。
            for (const sel of cols)
                cancelOwn(sel);
            own.clear();
            seen.clear();
            observer?.disconnect();
            observer = null;
            observedParents.clear();
        },
    };
}
