package com.adskip.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 开屏广告跳过服务（重写版）。
 *
 * <p>一次“启动会话”的生命周期：
 * <ol>
 *   <li>目标应用切到前台，建立一个会话；</li>
 *   <li>在用户设定的检测窗口内反复扫描当前窗口，按文字 / 描述 / viewId 找“跳过”类控件；</li>
 *   <li>点击后约 0.9 秒复查，广告还在就逐级升级手段：坐标点击 → 返回键；</li>
 *   <li>点击成功或窗口切换立即收手，绝不在应用正常使用过程中乱点。</li>
 * </ol>
 *
 * <p>安全约束（比“见按钮就点”更重要）：
 * <ul>
 *   <li>只处理前台应用自己启动后的短时间窗，超时即停；</li>
 *   <li>顶层窗口一旦不是目标应用（桌面、通知栏、权限弹窗等），立即结束会话；</li>
 *   <li>坐标点击和返回键只在“确认是广告页”时使用：<br>
 *       要么识别到了跳过控件，要么当前 Activity 类名带 splash / advert / welcome 等特征。</li>
 * </ul>
 *
 * <p>限制：非 root 环境无法阻止广告加载，本服务只能尽快把它关掉；
 * 完全自绘、类名无特征且无无障碍节点的广告，本服务不会盲点。
 */
public class SplashSkipService extends AccessibilityService {

    // ---------------------------------------------------------------- 常量

    /** 会话阶段。 */
    private static final int STAGE_IDLE = 0;     // 尚未动作
    private static final int STAGE_CLICKED = 1;  // 已点击识别到的控件
    private static final int STAGE_TAPPED = 2;   // 已按坐标点击
    private static final int STAGE_DONE = 3;     // 会话结束

    /** 扫描与复查的时间参数。 */
    private static final long SCAN_GAP_MS = 150L;        // 有广告迹象时的重扫间隔
    private static final long QUIET_GAP_MS = 300L;       // 无广告迹象时的重扫间隔
    private static final long CHECK_DELAY_MS = 900L;     // 动作后复查延迟
    private static final long FALLBACK_WAIT_MS = 1500L;  // 兜底动作前的最短等待
    private static final int MIN_SCANS_BEFORE_FALLBACK = 4;
    private static final int MAX_SCANS = 80;
    private static final int MAX_DEPTH = 30;
    private static final int MAX_VISITS = 600;
    private static final int MIN_SCORE = 60;

    /** Activity 类名中出现这些片段，视为“可能是广告页”，仅用于兜底判断。 */
    private static final String[] AD_HINTS = {
            "splash", "advert", "adactivity", "ad_activity", "openad", "open_ad",
            "openscreen", "open_screen", "interstitial", "rewardvideo", "reward_video",
            "welcomeactivity", "welcome", "launchactivity", "launch_activity", "startup"
    };

    /** 常见“关闭”符号。 */
    private static final String[] CLOSE_GLYPHS = {"×", "✕", "✖", "╳", "x"};

    /** “跳过 5”“跳过(3)”“close 3”之类的倒计时文案。 */
    private static final Pattern COUNTDOWN =
            Pattern.compile("(跳过|skip|关闭|close)[^0-9]{0,3}\\d{1,2}", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- 状态

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            scanQueued = false;
            scan();
        }
    };

    private final Runnable checkTask = new Runnable() {
        @Override
        public void run() {
            verify();
        }
    };

    private String launcherPkg;
    private String lastPkg;
    private String sessionPkg;
    private String sessionCls = "";
    private long sessionStart;
    private int scans;
    private int stage = STAGE_DONE;
    private boolean scanQueued;
    private long lastActionAt;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        launcherPkg = resolveLauncher();
        SkipLog.add("服务已连接，等待应用启动");
    }

    @Override
    public void onInterrupt() {
        // 无障碍服务被系统中断时无需特殊处理，这里只做清理
        endSession();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        sessionPkg = null;
        stage = STAGE_DONE;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 事件与会话

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) {
            return;
        }
        final String pkg = pkgCs.toString();
        final boolean stateChanged =
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        final long now = System.currentTimeMillis();
        // 只有“从一个应用切到另一个应用”才算一次启动；同一应用内部换 Activity 不算
        final boolean switched = stateChanged && !pkg.equals(lastPkg);
        if (stateChanged) {
            lastPkg = pkg;
        }

        if (!Prefs.isEnabled(this)) {
            if (stateChanged) {
                endSession();
            }
            return;
        }

        boolean irrelevant = pkg.equals(getPackageName())
                || pkg.equals(launcherPkg)
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.permissioncontroller")
                || pkg.startsWith("com.google.android.permissioncontroller");
        if (irrelevant || !allowed(pkg)) {
            // 桌面、系统界面、自己、白名单之外：都代表离开了目标应用
            if (stateChanged) {
                endSession();
            }
            return;
        }

        CharSequence clsCs = event.getClassName();
        String cls = clsCs == null ? "" : clsCs.toString();

        if (stateChanged) {
            if (switched) {
                startSession(pkg, cls, now);
            } else if (pkg.equals(sessionPkg) && !cls.isEmpty()) {
                sessionCls = cls;
            }
        } else if (sessionPkg == null || !sessionPkg.equals(pkg)) {
            return;
        }

        if (sessionPkg == null || stage != STAGE_IDLE) {
            return;
        }
        if (now - sessionStart > Prefs.getWindow(this)) {
            endSession();
            return;
        }
        requestScan(Prefs.getDelay(this));
    }

    private void startSession(String pkg, String cls, long now) {
        handler.removeCallbacks(scanTask);
        handler.removeCallbacks(checkTask);
        scanQueued = false;
        sessionPkg = pkg;
        sessionCls = cls;
        sessionStart = now;
        scans = 0;
        stage = STAGE_IDLE;
        lastActionAt = 0L;
        requestScan(Prefs.getDelay(this));
    }

    private void endSession() {
        handler.removeCallbacks(scanTask);
        handler.removeCallbacks(checkTask);
        scanQueued = false;
        sessionPkg = null;
        stage = STAGE_DONE;
    }

    private void finish(String note) {
        if (note != null && !note.isEmpty()) {
            SkipLog.add(note);
        }
        endSession();
    }

    // ---------------------------------------------------------------- 核心流程

    private void requestScan(long delay) {
        if (scanQueued || sessionPkg == null) {
            return;
        }
        scanQueued = true;
        handler.postDelayed(scanTask, Math.max(0L, delay));
    }

    private void scan() {
        if (sessionPkg == null || stage != STAGE_IDLE || !Prefs.isEnabled(this)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - sessionStart > Prefs.getWindow(this) || scans >= MAX_SCANS) {
            endSession();
            return;
        }
        scans++;

        AccessibilityNodeInfo root = activeRoot();
        if (root == null) {
            requestScan(SCAN_GAP_MS);
            return;
        }
        String rootPkg = str(root.getPackageName());
        if (!rootPkg.isEmpty() && !rootPkg.equals(sessionPkg)) {
            if (isSystemWindow(rootPkg)) {
                // 启动过渡期偶尔还能取到桌面/系统窗口的根节点，稍后再扫
                requestScan(SCAN_GAP_MS);
                return;
            }
            // 顶层窗口已经换成别的应用（浏览器落地页等），立刻收手
            endSession();
            return;
        }

        Rect screen = screenRect();
        Candidate cand = findSkip(root, screen);
        if (cand != null) {
            if (performSkip(cand)) {
                stage = STAGE_CLICKED;
                lastActionAt = now;
                SkipLog.add(appTag() + " 点击「" + cand.label + "」");
                scheduleCheck();
            } else {
                escalate();
            }
            return;
        }

        boolean adLike = looksLikeAd(sessionCls) || looksLikeAd(str(root.getClassName()));
        if (!adLike) {
            // 没有任何广告迹象：保持安静观察，绝不盲点
            requestScan(QUIET_GAP_MS);
            return;
        }

        boolean waitedEnough = now - sessionStart >= FALLBACK_WAIT_MS
                && scans >= MIN_SCANS_BEFORE_FALLBACK;
        if (!waitedEnough || Prefs.getStrategy(this) == 0) {
            // 策略 0 只做控件点击，不启用任何盲点兜底
            requestScan(SCAN_GAP_MS);
            return;
        }
        escalate();
    }

    private void scheduleCheck() {
        scheduleCheck(CHECK_DELAY_MS);
    }

    private void scheduleCheck(long delay) {
        handler.removeCallbacks(checkTask);
        handler.postDelayed(checkTask, Math.max(0L, delay));
    }

    /** 动作之后复查：广告是否还盖在屏幕上。 */
    private void verify() {
        if (sessionPkg == null || stage == STAGE_IDLE || stage >= STAGE_DONE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!Prefs.isEnabled(this) || now - sessionStart > Prefs.getWindow(this)) {
            endSession();
            return;
        }

        AccessibilityNodeInfo root = activeRoot();
        String rootPkg = "";
        String rootCls = "";
        if (root != null) {
            rootPkg = str(root.getPackageName());
            rootCls = str(root.getClassName());
        }

        // 顶层窗口已经换了应用（桌面、浏览器等）：说明广告页确实离开了
        if (!rootPkg.isEmpty() && !rootPkg.equals(sessionPkg)) {
            finish(appTag() + " 已离开广告页");
            return;
        }

        String currentCls = rootCls.isEmpty() ? sessionCls : rootCls;
        boolean adWindow = looksLikeAd(currentCls);
        boolean skipVisible = findSkip(root, screenRect()) != null;

        if (stage == STAGE_CLICKED) {
            if (skipVisible) {
                // 跳过控件还在，说明刚才那一下没生效（或广告又弹了一层）
                if (now - lastActionAt >= 350) {
                    escalate();
                } else {
                    scheduleCheck(400);
                }
            } else {
                finish(appTag() + " 广告已跳过");
            }
            return;
        }

        if (stage == STAGE_TAPPED) {
            if (!adWindow) {
                finish(appTag() + " 广告页已关闭");
                return;
            }
            // 类名还是广告页但已看不到跳过控件：再等一会儿，避免广告刚好关掉时误按返回
            if (!skipVisible && now - lastActionAt < 1200) {
                scheduleCheck(400);
                return;
            }
            if (Prefs.getStrategy(this) >= 2 && pressBack()) {
                lastActionAt = System.currentTimeMillis();
                SkipLog.add(appTag() + " 返回键关闭广告页");
                finish(null);
            } else {
                finish(appTag() + " 未能跳过广告");
            }
        }
    }

    private void escalate() {
        int strategy = Prefs.getStrategy(this);

        if (stage == STAGE_IDLE || stage == STAGE_CLICKED) {
            if (strategy >= 1 && tapCorner()) {
                stage = STAGE_TAPPED;
                lastActionAt = System.currentTimeMillis();
                SkipLog.add(appTag() + " 坐标点击" + cornerLabel());
                scheduleCheck();
                return;
            }
            if (strategy >= 2 && looksLikeAd(sessionCls) && pressBack()) {
                lastActionAt = System.currentTimeMillis();
                SkipLog.add(appTag() + " 返回键关闭广告页");
                finish(null);
                return;
            }
            finish(appTag() + " 未能跳过广告");
        }
    }

    // ---------------------------------------------------------------- 控件识别

    /** 一次候选扫描；返回得分最高、且达到识别阈值的可点击“跳过/关闭”节点。 */
    private Candidate findSkip(AccessibilityNodeInfo root, Rect screen) {
        Candidate best = new Candidate();
        best.score = 0;
        best.label = "";
        if (root != null) {
            walk(root, screen, best, 0, new int[]{MAX_VISITS});
        }
        return best.node != null && best.score >= MIN_SCORE ? best : null;
    }

    private void walk(AccessibilityNodeInfo node, Rect screen, Candidate best,
                      int depth, int[] budget) {
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) {
            return;
        }
        budget[0]--;

        try {
            int s = score(node, screen);
            if (s > best.score) {
                Rect b = new Rect();
                if (node.getBoundsInScreen(b) && b.width() > 0 && b.height() > 0) {
                    best.score = s;
                    best.node = node;
                    best.bounds = b;
                    best.label = label(node);
                }
            }
        } catch (Exception ignored) {
            // 节点在遍历过程中失效很常见，跳过即可
        }

        int count;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < count && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
                // 同上
            }
            if (child != null) {
                walk(child, screen, best, depth + 1, budget);
            }
        }
    }

    /** 给节点打分；0 表示不是候选。 */
    private int score(AccessibilityNodeInfo n, Rect screen) {
        if (n == null) {
            return 0;
        }
        boolean visible;
        boolean editable;
        try {
            visible = n.isVisibleToUser();
            editable = n.isEditable();
        } catch (Exception e) {
            return 0;
        }
        if (!visible || editable) {
            return 0;
        }

        Rect b = new Rect();
        try {
            if (!n.getBoundsInScreen(b)) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }
        if (b.width() <= 0 || b.height() <= 0) {
            return 0;
        }
        long screenArea = (long) screen.width() * screen.height();
        if (screenArea <= 0 || (long) b.width() * b.height() * 100 > screenArea * 30) {
            return 0;   // 超过三成屏幕的“大块”是内容区，不是按钮
        }

        String text = lower(str(n.getText()));
        String desc = lower(str(n.getContentDescription()));
        String vid = lower(str(n.getViewIdResourceName()));
        String label = (text + " " + desc).trim();

        int s = 0;
        if (!label.isEmpty()) {
            if (label.contains("跳过广告")) {
                s = 100;
            } else if (label.contains("关闭广告")) {
                s = 98;
            } else if (label.contains("跳过")) {
                s = label.length() <= 12 ? 96 : 78;
            } else if (label.contains("skip ad") || label.contains("skipad")) {
                s = 97;
            } else if (label.contains("skip")) {
                s = 90;
            } else if (label.contains("close ad") || label.contains("closead")) {
                s = 94;
            } else if (COUNTDOWN.matcher(label).find() && cornered(b, screen)) {
                s = 88;
            } else if ((isCloseGlyph(text) || isCloseGlyph(desc)) && cornered(b, screen)) {
                s = 72;
            } else if (("关闭".equals(text) || "关闭".equals(desc)) && cornered(b, screen)) {
                s = 70;
            } else if (("close".equals(text) || "close".equals(desc)) && cornered(b, screen)) {
                s = 66;
            }
        }

        if (s == 0 && !vid.isEmpty()) {
            if (vid.contains("skip")) {
                s = 86;
            } else if (vid.contains("close")
                    && (vid.contains("ad") || vid.contains("splash"))) {
                s = 82;
            }
        }

        if (s == 0 || !canPress(n)) {
            return 0;
        }
        if (cornered(b, screen)) {
            s += 4;
        }
        return Math.min(s, 100);
    }

    /** 节点自身或最近的可点击祖先是否真的能点。 */
    private boolean canPress(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            try {
                if (cur.isEnabled()
                        && (cur.isClickable()
                        || (cur.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0)) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private boolean performSkip(Candidate cand) {
        AccessibilityNodeInfo cur = cand.node;
        for (int i = 0; i < 4 && cur != null; i++) {
            try {
                boolean enabled = cur.isEnabled();
                boolean clickable = cur.isClickable()
                        || (cur.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
                if (enabled && clickable
                        && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 节点失效，换父节点再试
            }
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                cur = null;
            }
        }
        // 控件点不动（自绘按钮、WebView 内元素等）时，改用手势点它的中心
        return tap(cand.bounds.centerX(), cand.bounds.centerY());
    }

    // ---------------------------------------------------------------- 手势动作

    private boolean tapCorner() {
        Rect s = screenRect();
        float fx;
        float fy;
        switch (Prefs.getCorner(this)) {
            case 1:
                fx = 0.90f;
                fy = 0.92f;
                break;
            case 2:
                fx = 0.10f;
                fy = 0.08f;
                break;
            case 3:
                fx = 0.10f;
                fy = 0.92f;
                break;
            default:
                fx = 0.90f;
                fy = 0.08f;
                break;
        }
        return tap(s.width() * fx, s.height() * fy);
    }

    private String cornerLabel() {
        switch (Prefs.getCorner(this)) {
            case 1:
                return "（右下角）";
            case 2:
                return "（左上角）";
            case 3:
                return "（左下角）";
            default:
                return "（右上角）";
        }
    }

    private boolean tap(float x, float y) {
        Rect s = screenRect();
        if (x <= 0 || y <= 0 || x >= s.width() || y >= s.height()) {
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 40);
            return dispatchGesture(
                    new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pressBack() {
        try {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 辅助

    private AccessibilityNodeInfo activeRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
    }

    private Rect screenRect() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
        int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;
        return new Rect(0, 0, w, h);
    }

    /** “跳过/关闭”按钮通常贴右上或底部；只对文字型候选做位置加分。 */
    private boolean cornered(Rect b, Rect screen) {
        int cx = b.centerX();
        int cy = b.centerY();
        int w = screen.width();
        int h = screen.height();
        boolean right = cx > w * 0.55;
        boolean edge = cy < h * 0.30 || cy > h * 0.72;
        return right && edge;
    }

    private boolean isCloseGlyph(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        for (String g : CLOSE_GLYPHS) {
            if (g.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSystemWindow(String pkg) {
        return pkg.equals(launcherPkg)
                || "android".equals(pkg)
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.permissioncontroller")
                || pkg.startsWith("com.google.android.permissioncontroller");
    }

    private boolean looksLikeAd(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        for (String hint : AD_HINTS) {
            if (c.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private boolean allowed(String pkg) {
        String raw = Prefs.getWhitelist(this).trim();
        if (raw.isEmpty()) {
            return true;
        }
        // 兼容中文逗号、分号、换行
        raw = raw.replace('，', ',')
                .replace('；', ',')
                .replace(';', ',')
                .replace('\n', ',');
        for (String s : raw.split(",")) {
            if (s.trim().equalsIgnoreCase(pkg)) {
                return true;
            }
        }
        return false;
    }

    private String resolveLauncher() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.packageName;
            }
        } catch (Exception ignored) {
            // 取不到桌面包名就只在系统界面事件上兜底
        }
        return null;
    }

    /** 日志里显示应用名，取不到就退回包名。 */
    private String appTag() {
        String pkg = sessionPkg;
        if (pkg == null) {
            return "";
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence name = pm.getApplicationLabel(info);
            if (name != null && name.length() > 0) {
                return name.toString();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return pkg;
    }

    private String label(AccessibilityNodeInfo n) {
        String t = str(n.getText()).trim();
        if (t.isEmpty()) {
            t = str(n.getContentDescription()).trim();
        }
        if (t.isEmpty()) {
            String id = str(n.getViewIdResourceName());
            int slash = id.indexOf('/');
            t = slash >= 0 ? id.substring(slash + 1) : id;
        }
        if (t.length() > 14) {
            t = t.substring(0, 14) + "…";
        }
        return t.isEmpty() ? "按钮" : t;
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** 扫描过程中得到的最佳候选。 */
    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score;
        Rect bounds;
        String label;
    }
}
