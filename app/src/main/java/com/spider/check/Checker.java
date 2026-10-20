package com.spider.check;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 测活链路（对齐 FongMi SiteApi 语义）：首页 → 分类 → 详情 → 播放。
 *
 * 关键设计：
 * 1) 分层测活：depth 决定测到哪一级，用户可只测「首页有内容」，不必每次都全链路。
 * 2) 每级独立超时：死站不会挂住整轮。
 * 3) 单站异常隔离：任何 Throwable 只影响该站。
 * 4) 非 spider 源（type 0/1/4 的 JSON CMS 接口）走 HTTP 探活分支，不再一律「加载失败」。
 */
public class Checker {

    public static final int DEPTH_HOME = 1;
    public static final int DEPTH_CATEGORY = 2;
    public static final int DEPTH_DETAIL = 3;
    public static final int DEPTH_PLAY = 4;

    private static final int T_HOME = 15;
    private static final int T_CAT = 12;
    private static final int T_DETAIL = 15;
    // py 源的播放解析常要先登录/签名/解密，12 秒不够（实测有源要 17s）
    private static final int T_PLAY = 20;
    private static final int T_HTTP = 15;
    /** 苹果 CMS 接口首屏可能不给内容，这几个参数形态轮流试一次 */
    private static final String[] HOME_QUERIES = {"ac=list", "ac=videolist&pg=1", "ac=detail&pg=1"};

    private static final ExecutorService TIMER = Executors.newCachedThreadPool();

    public static void check(Site site, int depth) {
        long t0 = System.currentTimeMillis();
        try {
            if (isJsonCms(site.getApi())) checkJson(site, depth);
            else checkSpider(site, depth);
        } catch (Throwable e) {
            site.setGrade("D");
            site.setResult("异常：" + msg(e));
        } finally {
            site.setElapsedMs(System.currentTimeMillis() - t0);
            if (site.getGrade().isEmpty()) site.setGrade("D");
        }
    }

    /**
     * 接口拉取：部分站点/接口校验 User-Agent，这里走 Ua 的候选 UA 嗅探；
     * 空内容（被拦/超时）视为失败并带上候选 UA 的逐条结果，便于定位。
     */
    private static String http(String url) throws Exception {
        String s = Ua.text(url, 12000);
        if (TextUtils.isEmpty(s)) throw new Exception("拉取为空（" + Ua.lastReason() + "）");
        return s;
    }

    /** 非 spider 源：type 0/1/4 的 http(s) JSON 接口（苹果 CMS / 自定义 API）。 */
    public static boolean isJsonCms(String api) {
        if (TextUtils.isEmpty(api)) return true;
        String a = api.toLowerCase();
        if (a.startsWith("csp_")) return false;
        if (a.contains(".py") || a.contains(".js") || a.contains(".jar")) return false;
        return a.startsWith("http");
    }

    private static void checkJson(Site site, int depth) {
        String api = site.getApi();
        String body;
        try {
            body = call(() -> http(api), T_HTTP, "接口");
        } catch (Throwable e) {
            site.setGrade("D");
            site.setResult("接口请求失败：" + msg(e));
            return;
        }
        JSONObject h = toJson(body);
        if (h == null) {
            site.setGrade("D");
            site.setResult("接口" + whyNotJson(body));
            return;
        }
        boolean home = false;
        String catErr = "", detailErr = "", playInfo = "未测", firstId = "", firstTid = "";
        String[] picked = pickOne(h);
        firstTid = picked[0];
        firstId = picked[1];
        home = !TextUtils.isEmpty(firstTid) || !TextUtils.isEmpty(firstId);
        if (!home) {
            // 苹果 CMS 家族各家参数不一：首屏没给 class/list 时再显式问一次，
            // 否则会把「要带 ac=list 才有内容」的接口误判成 D 级空接口
            for (String q : HOME_QUERIES) {
                try {
                    String url = join(api, q);
                    JSONObject alt = toJson(call(() -> http(url), T_HTTP, "接口"));
                    String[] again = pickOne(alt);
                    if (!TextUtils.isEmpty(again[0]) || !TextUtils.isEmpty(again[1])) {
                        h = alt;
                        firstTid = again[0];
                        firstId = again[1];
                        home = true;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (!home) {
            String msg = h.optString("msg", "");
            site.setGrade("D");
            site.setResult("接口无内容（" + (TextUtils.isEmpty(msg) ? "class/list 均空" : msg) + "）");
            return;
        }
        boolean category = false, detail = false, play = false;
        int max = 1;

        if (depth >= DEPTH_CATEGORY) {
            max = 2;
            if (TextUtils.isEmpty(firstTid)) {
                catErr = "无 type_id";
            } else {
                // 分类接口参数各家不同（ac=videolist/detail/list，t/type_id），逐个试
                for (String q : new String[]{"ac=videolist&t=" + firstTid + "&pg=1",
                        "ac=detail&t=" + firstTid + "&pg=1",
                        "ac=list&t=" + firstTid + "&pg=1",
                        "ac=videolist&t=" + firstTid}) {
                    try {
                        String url = join(api, q);
                        JSONObject c = toJson(call(() -> http(url), T_HTTP, "分类"));
                        JSONArray cl = listOf(c);
                        if (cl != null && cl.length() > 0) {
                            category = true;
                            if (TextUtils.isEmpty(firstId)) firstId = firstOf(cl);
                            break;
                        }
                        catErr = "分类无 list";
                    } catch (Throwable e) {
                        catErr = msg(e);
                    }
                }
            }
        }

        if (depth >= DEPTH_DETAIL) {
            max = 3;
            if (TextUtils.isEmpty(firstId)) {
                detailErr = "无 vod_id";
            } else {
                // 详情参数同样不同（ids / id），且有的接口要 ac=videolist
                for (String q : new String[]{"ac=detail&ids=" + firstId,
                        "ac=videolist&ids=" + firstId,
                        "ac=detail&id=" + firstId}) {
                    try {
                        String url = join(api, q);
                        JSONObject d = toJson(call(() -> http(url), T_HTTP, "详情"));
                        JSONArray dl = listOf(d);
                        if (dl == null || dl.length() == 0) {
                            detailErr = "详情无 list";
                            continue;
                        }
                        String pu = dl.optJSONObject(0).optString("vod_play_url");
                        if (TextUtils.isEmpty(pu)) {
                            detailErr = "vod_play_url 空";
                            continue;
                        }
                        detail = true;
                        play = true; // CMS 接口给出播放地址即视为可播（App 负责解析）
                        playInfo = "CMS 直出";
                        break;
                    } catch (Throwable e) {
                        detailErr = msg(e);
                    }
                }
            }
        }

        if (depth >= DEPTH_PLAY) max = 4;

        int score = (home ? 1 : 0) + (category ? 1 : 0) + (detail ? 1 : 0) + (play ? 1 : 0);
        if (score > max) score = max;
        site.setGrade(grade(score, max));
        StringBuilder sb = new StringBuilder();
        sb.append("接口 ✓");
        if (depth >= DEPTH_CATEGORY) {
            sb.append(" · 分类 ").append(category ? "✓" : "✗");
            if (!category && !TextUtils.isEmpty(catErr)) sb.append("(").append(catErr).append(")");
        }
        if (depth >= DEPTH_DETAIL) {
            sb.append(" · 详情 ").append(detail ? "✓" : "✗");
            if (!detail && !TextUtils.isEmpty(detailErr)) sb.append("(").append(detailErr).append(")");
        }
        if (depth >= DEPTH_PLAY) sb.append(" · 播放 ").append(play ? "✓ " + playInfo : "✗");
        site.setResult(sb.toString());
    }

    private static void checkSpider(Site site, int depth) {
        Spider spider = SiteLoader.get().getSpider(site.getKey(), site.getApi(), site.getExt0(), site.getJar0());
        if (spider == null || spider instanceof SpiderNull) {
            String err = SiteLoader.get().lastError(site.getKey());
            site.setGrade("D");
            site.setResult(TextUtils.isEmpty(err) ? "加载失败" : err);
            return;
        }
        boolean home = false, category = false, detail = false, play = false;
        String firstId = "", firstTid = "", playInfo = "—", catErr = "", detailErr = "";
        int max = 1;

        // ---- 首页 ----
        try {
            String raw = call(() -> spider.homeContent(true), T_HOME, "首页");
            JSONObject h = toJson(raw);
            if (h == null) throw new Exception(whyNotJson(raw));
            JSONArray cls = h.optJSONArray("class");
            JSONArray list = h.optJSONArray("list");
            if (cls != null && cls.length() > 0) {
                firstTid = cls.optJSONObject(0).optString("type_id");
            }
            if (list != null && list.length() > 0 && !TextUtils.isEmpty(list.optJSONObject(0).optString("vod_id"))) {
                firstId = list.optJSONObject(0).optString("vod_id");
            } else {
                JSONObject hv = toJson(call(() -> spider.homeVideoContent(), T_HOME, "首页推荐"));
                JSONArray vl = hv == null ? null : hv.optJSONArray("list");
                if (vl != null && vl.length() > 0 && !TextUtils.isEmpty(vl.optJSONObject(0).optString("vod_id"))) {
                    firstId = vl.optJSONObject(0).optString("vod_id");
                }
            }
            // 首页判据放宽：有 class 或 有 list 都算有内容（很多 App 源只有其一）。
            // 直播型源（home 全空但能搜索/直出列表）不算失败，标 C 而非 D。
            home = (cls != null && cls.length() > 0) || !TextUtils.isEmpty(firstId)
                    || (list != null && list.length() > 0);
            if (!home) {
                // 首页确实空：再给一次搜索机会，能搜到就算「仅搜索型」可用
                boolean searchable = false;
                try {
                    JSONObject s = toJson(call(() -> spider.searchContent("测试", false, "1"), T_CAT, "搜索"));
                    JSONArray sl = s == null ? null : s.optJSONArray("list");
                    searchable = sl != null && sl.length() > 0;
                } catch (Throwable ignored) {
                }
                if (searchable) {
                    site.setGrade("C");
                    site.setResult("首页空 · 仅搜索可用");
                } else {
                    site.setGrade("D");
                    site.setResult("首页无内容（class/list 均空）");
                }
                done(spider, site);
                return;
            }
        } catch (Throwable e) {
            site.setGrade("D");
            site.setResult("首页失败：" + msg(e));
            done(spider, site);
            return;
        }

        if (depth < DEPTH_CATEGORY) {
            site.setGrade("A");
            site.setResult("首页 ✓");
            done(spider, site);
            return;
        }

        // ---- 分类 ----
        max = 2;
        if (TextUtils.isEmpty(firstTid)) {
            catErr = "无 type_id";
        } else {
            final String tid = firstTid;
            try {
                JSONObject c = toJson(call(() -> spider.categoryContent(tid, "1", true, new HashMap<>()), T_CAT, "分类"));
                JSONArray cl = c == null ? null : c.optJSONArray("list");
                if (cl != null && cl.length() > 0) {
                    category = true;
                    if (TextUtils.isEmpty(firstId)) {
                        for (int i = 0; i < cl.length(); i++) {
                            String id = cl.optJSONObject(i).optString("vod_id");
                            if (!TextUtils.isEmpty(id)) {
                                firstId = id;
                                break;
                            }
                        }
                    }
                } else {
                    catErr = "无 list";
                }
            } catch (Throwable e) {
                catErr = msg(e);
            }
        }

        if (depth < DEPTH_DETAIL) {
            int score = 1 + (category ? 1 : 0);
            site.setGrade(grade(score, 2));
            StringBuilder sb = new StringBuilder("首页 ✓ · 分类 ").append(category ? "✓" : "✗");
            if (!category && !TextUtils.isEmpty(catErr)) sb.append("(").append(catErr).append(")");
            site.setResult(sb.toString());
            done(spider, site);
            return;
        }

        // ---- 详情 ----
        max = 3;
        if (TextUtils.isEmpty(firstId)) {
            detailErr = "无 vod_id 可进详情";
        } else {
            final String id = firstId;
            try {
                JSONObject d = toJson(call(() -> spider.detailContent(Collections.singletonList(id)), T_DETAIL, "详情"));
                JSONArray dl = d == null ? null : d.optJSONArray("list");
                if (dl == null || dl.length() == 0) {
                    detailErr = "详情无 list";
                } else {
                    JSONObject vod = dl.optJSONObject(0);
                    String playFrom = vod.optString("vod_play_from");
                    String playUrl = vod.optString("vod_play_url");
                    if (TextUtils.isEmpty(playUrl)) {
                        detailErr = "vod_play_url 空";
                    } else {
                        String[] flags = playUrl.split("\\$\\$\\$");
                        String[] flagNames = playFrom.split("\\$\\$\\$");
                        String firstEp = "", firstFlag = "";
                        for (int i = 0; i < flags.length; i++) {
                            for (String ep : flags[i].split("#")) {
                                int p = ep.lastIndexOf("$");
                                if (p > 0 && ep.length() > p + 1) {
                                    firstEp = ep.substring(p + 1);
                                    firstFlag = i < flagNames.length ? flagNames[i] : flagNames[0];
                                    break;
                                }
                            }
                            if (!TextUtils.isEmpty(firstEp)) break;
                        }
                        if (TextUtils.isEmpty(firstEp)) {
                            detailErr = "无剧集地址";
                        } else {
                            detail = true;
                            final String fFlag = TextUtils.isEmpty(firstFlag) ? flagNames[0] : firstFlag;
                            final String fEp = firstEp;
                            if (depth >= DEPTH_PLAY) {
                                max = 4;
                                try {
                                    JSONObject p = toJson(call(() -> spider.playerContent(fFlag, fEp, new ArrayList<>()), T_PLAY, "播放"));
                                    if (p == null) {
                                        detailErr = "播放返回非 JSON";
                                    } else {
                                        String url = p.optString("url");
                                        int parse = p.optInt("parse", 0);
                                        int jx = p.optInt("jx", 0);
                                        if (!TextUtils.isEmpty(url)) {
                                            play = true;
                                            playInfo = "直链";
                                        } else if (parse == 1 || jx == 1) {
                                            play = true;
                                            playInfo = "待嗅探";
                                        } else {
                                            detailErr = "播放 url 空且非嗅探";
                                        }
                                    }
                                } catch (Throwable e) {
                                    detailErr = msg(e);
                                }
                            } else {
                                playInfo = "未测";
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                detailErr = msg(e);
            }
        }

        int score = (home ? 1 : 0) + (category ? 1 : 0) + (detail ? 1 : 0) + (play ? 1 : 0);
        if (score > max) score = max;
        site.setGrade(grade(score, max));
        StringBuilder sb = new StringBuilder();
        sb.append("首页 ✓ · 分类 ").append(category ? "✓" : "✗");
        if (!category && !TextUtils.isEmpty(catErr)) sb.append("(").append(catErr).append(")");
        sb.append(" · 详情 ").append(detail ? "✓" : "✗");
        if (!detail && !TextUtils.isEmpty(detailErr)) sb.append("(").append(detailErr).append(")");
        if (depth >= DEPTH_PLAY) {
            sb.append(" · 播放 ").append(play ? "✓ " + playInfo : "✗");
        }
        site.setResult(sb.toString());
        done(spider, site);
    }

    /**
     * 按「已测深度」归一评级：测满 → A，差一级 → B（深度≥3 时），通到部分 → C，全不通 → D。
     * 这样只测首页时 home 通过就是 A（在「首页可用」这个语义下是对的）。
     */
    private static String grade(int score, int max) {
        if (score <= 0) return "D";
        if (score >= max) return "A";
        if (max >= 3 && score == max - 1) return "B";
        return "C";
    }

    /** 带超时的调用。 */
    private static <T> T call(Callable<T> task, int seconds, String stage) throws Exception {
        Future<T> future = TIMER.submit(task);
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception(stage + " 超时(" + seconds + "s)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Exception(stage + " 失败: " + msg(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("被中断");
        }
    }

    private static String join(String api, String q) {
        return api + (api.contains("?") ? "&" : "?") + q;
    }

    /** 取 list 数组，兼容 list / vod_list / data 三种键名 */
    private static JSONArray listOf(JSONObject o) {
        if (o == null) return null;
        JSONArray list = o.optJSONArray("list");
        if (list == null) list = o.optJSONArray("vod_list");
        if (list == null) list = o.optJSONArray("data");
        return list;
    }

    /** 从接口响应里挑第一组「分类 id / 影片 id」 */
    private static String[] pickOne(JSONObject o) {
        String tid = "", id = "";
        if (o == null) return new String[]{tid, id};
        JSONArray cls = o.optJSONArray("class");
        if (cls == null) cls = o.optJSONArray("class_list");
        if (cls != null && cls.length() > 0) {
            JSONObject first = cls.optJSONObject(0);
            if (first != null) {
                tid = first.optString("type_id", "");
                if (TextUtils.isEmpty(tid)) tid = first.optString("id", "");
            }
        }
        JSONArray list = listOf(o);
        if (list != null && list.length() > 0) id = firstOf(list);
        return new String[]{tid, id};
    }

    private static String firstOf(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("vod_id");
            if (TextUtils.isEmpty(id)) id = o.optString("id");
            if (!TextUtils.isEmpty(id)) return id;
        }
        return "";
    }

    private static JSONObject toJson(String raw) {
        try {
            if (raw == null) return null;
            String t = raw.trim();
            if (!t.startsWith("{")) return null;
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * toJson 失败时的可读原因。必须区分「真返回了别的东西」和「返回 null」：
     * py 源普遍写 `except: self.host = ''` 静默吞错，最后 homeContent 返回 None，
     * 一律显示「返回非 JSON」会让人以为是格式问题，其实多半是 init 没成功
     * （宿主缓存接口缺失、接口/登录失败等）。
     */
    private static String whyNotJson(String raw) {
        if (raw == null) return "源返回空（null）";
        String t = raw.trim();
        if (t.isEmpty()) return "源返回空字符串";
        if (t.equals("null") || t.equals("None")) return "源返回 null（多半 init 未成功：host 为空或接口/登录失败）";
        if (t.startsWith("[")) return "源返回数组（应为对象）：" + snippet(t);
        if (t.startsWith("\"")) return "源返回字符串：" + snippet(t);
        return "返回非 JSON：" + snippet(t);
    }

    private static String snippet(String body) {
        if (body == null) return "空响应";
        String t = body.trim().replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private static void destroy(Spider spider) {
        try {
            spider.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void done(Spider spider, Site site) {
        destroy(spider);
        SiteLoader.get().evict(site.getKey());
    }
}
