// drpy 兼容层
// ---------------------------------------------------------------------------
// drpy 系的源（典型：直接以 drpy2.min.js 当 api 的「官解」源）在 init 阶段就要用
// pdfh / pdfa / pd / pdfl / pdfv 这些全局函数。FongMi 里它们是站点 jar（catvod 的
// com.github.catvod.js.Function）注入的；测活 APK 不会加载那个 jar，于是报
//   UnhandledPromiseRejectionException: 'pdfh' is not defined
// 这里用自带的 cheerio 在 JS 层把这几个函数补回来，语义对齐 catvod：
//   parse 用 && 分段，最后一段是 Text / Html / outerHtml 或 @属性名，其余都是选择器；
//   裸词一律按选择器处理（catvod 里属性名必须带 @）。
import { load } from 'assets://js/lib/cheerio.min.js'

const __VALUE_NAMES = ['Text', 'text', 'Html', 'html', 'OuterHtml', 'outerHtml', 'outerhtml']

function __isValue(name) {
    if (__VALUE_NAMES.indexOf(name) >= 0) return true
    // 裸词一律当选择器，只有 @xxx 才是属性名。
    // 否则 pdfh(html, "a") 这种纯标签选择器会被误当成取 "a" 属性，结果恒为空。
    return name.charAt(0) === '@'
}

function __segments(parse) {
    const list = String(parse === undefined || parse === null ? '' : parse).split('&&')
    const out = []
    for (let i = 0; i < list.length; i++) {
        const one = list[i].trim()
        if (one.length > 0) out.push(one)
    }
    return out
}

function __source(html) {
    if (html === undefined || html === null) return ''
    if (typeof html === 'string') return html
    try {
        return html.toString() || ''
    } catch (e) {
        return ''
    }
}

/**
 * 逐段选择。注意 cheerio 的 load() 返回的是 $ 函数本身，第一段必须用 $(sel) 调用，
 * 后面才能链 .find(sel)。
 */
function __select(html, selectors) {
    const $ = load(__source(html))
    let cur = $(selectors[0])
    for (let i = 1; i < selectors.length; i++) cur = cur.find(selectors[i])
    return cur
}

function __outerOf(node) {
    try {
        return node.toString() || ''
    } catch (e) {
        return ''
    }
}

function __pick(node, mode) {
    if (mode === 'Text' || mode === 'text') return (node.text() || '').trim()
    if (mode === 'Html' || mode === 'html') return node.html() || ''
    if (/outer/i.test(mode)) return __outerOf(node)
    return node.attr(mode.replace(/^@/, '')) || ''
}

function __split(parse) {
    const parts = __segments(parse)
    if (parts.length === 0) return null
    const last = parts[parts.length - 1]
    const byValue = __isValue(last)
    const selectors = byValue ? parts.slice(0, parts.length - 1) : parts
    if (selectors.length === 0) return null
    return { selectors: selectors, byValue: byValue, mode: byValue ? last : 'Text' }
}

function pdfh(html, parse) {
    try {
        const plan = __split(parse)
        if (!plan) return ''
        const set = __select(html, plan.selectors)
        if (!set.length) return ''
        return __pick(set.eq(0), plan.mode)
    } catch (e) {
        return ''
    }
}

function pdfa(html, parse) {
    try {
        const plan = __split(parse)
        if (!plan) return []
        const set = __select(html, plan.selectors)
        const out = []
        set.each((index) => {
            const node = set.eq(index)
            out.push(plan.byValue ? __pick(node, plan.mode) : __outerOf(node))
        })
        return out
    } catch (e) {
        return []
    }
}

function pd(html, parse, uri) {
    const value = pdfh(html, parse)
    try {
        return joinUrl(uri || '', value)
    } catch (e) {
        return value
    }
}

function pdfv(html, parse) {
    return pdfh(html, parse)
}

globalThis.pdfh = pdfh
globalThis.pdfa = pdfa
globalThis.pd = pd
globalThis.pdfl = pdfa
globalThis.pdfv = pdfv
