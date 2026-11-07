package com.local.privswitch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐私开关：对已实施的系统应用隐私管控做 应用 / 恢复 / 验证（root）。
 * 顶部两个标签：设置（已适配管控项）/ 未适配（已知采集但未做管控的系统应用）。
 */
public class MainActivity extends Activity {

    static final int T_PKG = 0, T_COMP = 1, T_SETTING = 2, T_DOMAIN = 3, T_UID = 4;
    static final int PORT = 443;

    static class Item {
        final String id;
        final String title;
        final String pkg;
        final String desc;
        final String applyLabel;
        final String revertLabel;
        final int type;
        String comp, key, protectVal, normalVal;
        List<String> hosts;
        List<String> dataPaths;
        String uidPkg;
        int uid;
        TextView status;
        Button bApply, bRevert, bVerify;

        Item(String id, String title, String pkg, String desc, String applyLabel, String revertLabel, int type) {
            this.id = id;
            this.title = title;
            this.pkg = pkg;
            this.desc = desc;
            this.applyLabel = applyLabel;
            this.revertLabel = revertLabel;
            this.type = type;
        }
    }

    static class Unadapted {
        final String title;
        final String pkg;
        final String desc;

        Unadapted(String title, String pkg, String desc) {
            this.title = title;
            this.pkg = pkg;
            this.desc = desc;
        }
    }

    final List<Item> items = new ArrayList<>();
    final List<Unadapted> unadapted = new ArrayList<>();
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.US);
    final SimpleDateFormat dateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    SharedPreferences sp;
    LinearLayout list;
    TextView logView, rootView;
    ScrollView pageSettings, pageUnadapted;
    Button tabSettings, tabUnadapted;
    boolean root = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable t) {
                writeCrash(t);
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        try {
            sp = getSharedPreferences("privswitch_state", MODE_PRIVATE);
            buildItems();
            buildUnadapted();
            setContentView(buildUi());
            setupBars();
            checkRoot();
            String action = getIntent() != null ? getIntent().getStringExtra("action") : null;
            if (action != null) {
                final String a = action;
                ui.postDelayed(new Runnable() {
                    @Override public void run() { doAction(a); }
                }, 1800);
            }
        } catch (Throwable t) {
            writeCrash(t);
            throw t;
        }
    }

    void writeCrash(Throwable t) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "crash.txt");
            java.io.PrintWriter w = new java.io.PrintWriter(f);
            w.println(new Date().toString());
            t.printStackTrace(w);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    void setupBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setStatusBarColor(Color.TRANSPARENT);
                getWindow().setNavigationBarColor(Color.TRANSPARENT);
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(light, light);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ data

    static List<String> listOf(String... s) {
        return Arrays.asList(s);
    }

    void buildItems() {
        // 1. HiViewTunnel
        Item tunnel = new Item("tunnel", "HiViewTunnel", "com.hihonor.hiviewtunnel",
                "额外数据采集：HiView 打包的体验数据（应用使用/安装、设备信息），暂存在 /data/log/UserViewer\n" +
                "上传方式：logservice-drcn → 腾讯云 COS，8 分钟/熄屏/每日 08:00 触发\n" +
                "做法：禁用该包，使上传服务工作线程无法启动；HiView 打包完成后无人外传",
                "pm disable-user --user 0 com.hihonor.hiviewtunnel",
                "pm enable --user 0 com.hihonor.hiviewtunnel", T_PKG);
        tunnel.dataPaths = listOf("/data/log/UserViewer");
        items.add(tunnel);

        // 2. 主题
        Item theme = new Item("theme", "主题", "com.hihonor.android.thememanager",
                "额外数据采集：主题使用/行为采集、metrics/APM\n" +
                "上传方式：metrics-drcn、apm.yun、rms-behavior-collect-drcn\n" +
                "做法：禁用主题商店包，停止其行为采集与指标上报（已下载主题仍可应用）",
                "pm disable-user --user 0 com.hihonor.android.thememanager",
                "pm enable --user 0 com.hihonor.android.thememanager", T_PKG);
        theme.dataPaths = listOf(
                "/data/data/com.hihonor.android.thememanager/databases/MsgCenterDb",
                "/data/data/com.hihonor.android.thememanager/databases/RemoteConfig.db");
        items.add(theme);

        // 3. 快游戏
        Item quick = new Item("quickgame", "快游戏", "com.hihonor.quickgame",
                "额外数据采集：游戏中心使用记录、APM 指标、归因数据\n" +
                "上传方式：apm.yun、metrics-drcn\n" +
                "做法：禁用快游戏包，停止其指标采集与上报（游戏中心不可用）",
                "pm disable-user --user 0 com.hihonor.quickgame",
                "pm enable --user 0 com.hihonor.quickgame", T_PKG);
        quick.dataPaths = listOf(
                "/data/data/com.hihonor.quickgame/databases/AttributionSdk.db",
                "/data/data/com.hihonor.quickgame/databases/hianalytics_data.db");
        items.add(quick);

        // 4. CHR
        Item chr = new Item("chr", "CHR", "com.hihonor.android.chr",
                "额外数据采集：通话/驻网/漫游/语音质量统计（TelStatis*.bin、events.db）\n" +
                "上传方式：随 HiView 的 protocol/ 目录打包，经 logservice 上传\n" +
                "做法：禁用 CHR 包，停止通信统计采集；其上报依赖 HiView 通道",
                "pm disable-user --user 0 com.hihonor.android.chr",
                "pm enable --user 0 com.hihonor.android.chr", T_PKG);
        chr.dataPaths = listOf("/data/user_de/0/com.hihonor.android.chr/files/TelStatis0.bin");
        items.add(chr);

        // 5. HiView UE
        Item ue = new Item("ue", "HiView UE 采集", "Settings.Secure",
                "额外数据采集：应用使用时长/排行、安装事件、前台界面、Wi-Fi/蜂窝等 UE 事件\n" +
                "上传方式：HiView 打包 → logservice（8 分钟 / 60 分钟）\n" +
                "做法：把 user_experience_involved 置 0，HiView 读取后停止 UE 事件采集与打包",
                "settings put secure user_experience_involved 0",
                "settings put secure user_experience_involved 1", T_SETTING);
        ue.key = "user_experience_involved";
        ue.protectVal = "0";
        ue.normalVal = "1";
        ue.dataPaths = listOf("/data/log/UserViewer");
        items.add(ue);

        // 6. LBS 众包开关
        Item lbs = new Item("lbs_switch", "LBS 众包", "Settings.Secure",
                "额外数据采集：GPS/基站/Wi-Fi 指纹（crowdsource/eventinfo.log、OffStatistics.db）\n" +
                "上传方式：logservice-lbs-drcn、cgicol.amap.com（每日 18:00/22:00 记录点）\n" +
                "做法：把众包总开关置 0，LBS 读取后停止众包采集与打包（导航定位不受影响）",
                "settings put secure hihonor_crowdsourcing_switch_turn_on 0",
                "settings put secure hihonor_crowdsourcing_switch_turn_on 1", T_SETTING);
        lbs.key = "hihonor_crowdsourcing_switch_turn_on";
        lbs.protectVal = "0";
        lbs.normalVal = "1";
        lbs.dataPaths = listOf("/data/user_de/0/com.hihonor.lbs/files/crowdsource");
        items.add(lbs);

        // 7. LBS 端点
        Item lbsEp = new Item("lbs_ep", "LBS 端点阻断", "cgicol.amap.com / logservice-lbs-drcn",
                "额外数据采集：GPS/基站/Wi-Fi 指纹；上传方式：众包/日志端点\n" +
                "做法：按 TLS SNI 字符串匹配 iptables 规则，命中即拒绝，阻断其上传连接",
                "iptables 拒绝上述 2 域名（TCP/UDP 443，v4+v6）",
                "iptables -D 对应规则", T_DOMAIN);
        lbsEp.hosts = listOf("cgicol.amap.com", "logservice-lbs-drcn.dt.hihonorcloud.com");
        lbsEp.dataPaths = listOf("/data/user_de/0/com.hihonor.lbs/databases/OffStatistics.db");
        items.add(lbsEp);

        // 8. 应用市场指标
        Item market = new Item("market", "应用市场指标阻断", "metrics-appdistribute-drcn.dt.hihonorcloud.com",
                "额外数据采集：应用分发指标、安装归因（AttributionSdk.db）\n" +
                "上传方式：metrics-appdistribute-drcn；下载/更新走 appmarket-bgapi（保留）\n" +
                "做法：按 TLS SNI 阻断该指标域名，下载/更新等其它域名不受影响",
                "iptables 拒绝 metrics-appdistribute-drcn",
                "iptables -D 对应规则", T_DOMAIN);
        market.hosts = listOf("metrics-appdistribute-drcn.dt.hihonorcloud.com");
        market.dataPaths = listOf("/data/data/com.hihonor.appmarket/databases/AttributionSdk.db");
        items.add(market);

        // 9. 天气广告
        Item weather = new Item("weather", "天气广告阻断", "tracking.ads / ads-drcn / tracking-data / adsmind",
                "额外数据采集：广告追踪（OAID、曝光/点击）、AdSdk.db\n" +
                "上传方式：tracking.ads.honor.com、ads-drcn.platform、tracking-data.platform、adsmind.gdtimg.com\n" +
                "做法：按 TLS SNI 阻断上述广告/追踪域名，天气数据域名 weather-info 保留",
                "iptables 拒绝上述 4 个 ads/tracking 域名",
                "iptables -D 对应规则", T_DOMAIN);
        weather.hosts = listOf("tracking.ads.honor.com", "ads-drcn.platform.hihonorcloud.com",
                "tracking-data.platform.hihonorcloud.com", "adsmind.gdtimg.com");
        weather.dataPaths = listOf("/data/user_de/0/com.hihonor.android.totemweather/databases/AdSdk.db");
        items.add(weather);

        // 10. 家长控制
        Item parent = new Item("parent", "家长控制断网", "uid 动态解析：com.hihonor.parentcontrol",
                "额外数据采集：屏幕时间/使用统计（本地为主）\n" +
                "上传方式：screentimemgt-drcn；本地统计后定时上报\n" +
                "做法：运行时解析该应用实时 UID，用 iptables owner 模块拒绝其全部出网（保留 loopback）",
                "iptables REJECT --uid-owner <动态UID>（保留 loopback）",
                "iptables -D 对应规则", T_UID);
        parent.uidPkg = "com.hihonor.parentcontrol";
        parent.dataPaths = listOf("/data/data/com.hihonor.parentcontrol/databases/settings.db");
        items.add(parent);

        // 11. 系统管家
        Item sysmgr = new Item("sysmgr", "系统管家上报端点", "apigw-cn.yun.hihonor.com",
                "额外数据采集：应用权限访问记录（cloud_permission.db，889 条/172 包）\n" +
                "上传方式：apigw-cn.yun.hihonor.com/api、/api/hpcc\n" +
                "做法：按 TLS SNI 阻断 apigw 域名，使上报请求无法发出；该域名与其它应用共用，组件禁用已被系统拒绝",
                "iptables 拒绝 apigw-cn.yun.hihonor.com",
                "iptables -D 对应规则", T_DOMAIN);
        sysmgr.hosts = listOf("apigw-cn.yun.hihonor.com");
        sysmgr.dataPaths = listOf("/data/user_de/0/com.hihonor.systemmanager/databases/cloud_permission.db");
        items.add(sysmgr);
    }

    void buildUnadapted() {
        unadapted.add(new Unadapted("荣耀账号", "com.hihonor.id",
                "额外数据采集：账号标识、设备信息、metrics\n上传方式：metrics-drcn、login、hnid、apm.yun\n未适配原因：封禁会影响登录与云同步"));
        unadapted.add(new Unadapted("语音引擎", "com.hihonor.voiceengine",
                "额外数据采集：语音相关指标、APM\n上传方式：apm.yun、ai.cloud.hihonor.com\n未适配原因：影响语音助手/TTS"));
        unadapted.add(new Unadapted("信息", "com.hihonor.mms",
                "额外数据采集：AdSdk.db、nebula 遥测、metrics\n上传方式：nebula-collector、metrics-drcn\n未适配原因：核心通信不可禁用（可另做端点阻断）"));
        unadapted.add(new Unadapted("负一屏", "com.hihonor.hiboard",
                "额外数据采集：使用行为、APM\n上传方式：apm.yun、metrics-drcn\n未适配原因：需整体禁用，功能代价较大"));
        unadapted.add(new Unadapted("智慧引擎", "com.hihonor.brain",
                "额外数据采集：使用行为指标\n上传方式：metrics-drcn\n未适配原因：影响系统智能调度/推荐"));
        unadapted.add(new Unadapted("场景服务", "com.hihonor.sceneservice",
                "额外数据采集：场景识别、位置相关\n上传方式：scenesvc-drcn.platform\n未适配原因：影响场景功能（通勤/睡眠等）"));
        unadapted.add(new Unadapted("近场", "com.hihonor.nearby",
                "额外数据采集：设备信息、指标\n上传方式：grs.hihonorcloud.com、metrics\n未适配原因：影响近场分享/互联"));
        unadapted.add(new Unadapted("浏览器", "com.honor.browser",
                "已整包禁用；采集声明：浏览记录/书签同步、位置/设备信息\n上传方式：logservice（与 HiView 共用）\n未适配原因：遥测与功能共用通道，无独立开关"));
    }

    // ------------------------------------------------------------------ uid

    int uidOf(Item it) {
        if (it.uid > 0) return it.uid;
        String pkg = it.uidPkg != null ? it.uidPkg : it.pkg;
        if (pkg == null) return -1;
        String out = Shell.su("dumpsys package " + pkg + " 2>/dev/null | grep -m1 appId=").out;
        int u = parseIntAfter(out, "appId=");
        if (u <= 0) {
            out = Shell.su("pm list packages -U " + pkg + " 2>/dev/null").out;
            u = parseIntAfter(out, "uid:");
        }
        if (u > 0) it.uid = u;
        return it.uid;
    }

    static int parseIntAfter(String s, String key) {
        if (s == null) return -1;
        int i = s.indexOf(key);
        if (i < 0) return -1;
        i += key.length();
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        if (j <= i) return -1;
        try {
            return Integer.parseInt(s.substring(i, j));
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ commands

    String applyCmd(Item it) {
        switch (it.type) {
            case T_PKG: return "pm disable-user --user 0 " + it.pkg;
            case T_COMP: return "pm disable-user --user 0 " + it.pkg + "/" + it.comp;
            case T_SETTING: return "settings put secure " + it.key + " " + it.protectVal;
            case T_UID: return uidCmd(it, false) + " " + uidCmd(it, true);
            case T_DOMAIN: return domainCmd(it, false) + " " + domainCmd(it, true);
        }
        return "true";
    }

    String revertCmd(Item it) {
        switch (it.type) {
            case T_PKG: return "pm enable --user 0 " + it.pkg;
            case T_COMP: return "pm enable --user 0 " + it.pkg + "/" + it.comp;
            case T_SETTING: return "settings put secure " + it.key + " " + it.normalVal;
            case T_UID: return uidCmd(it, false) + " " + uidCmd(it, false);
            case T_DOMAIN: return domainCmd(it, false) + " " + domainCmd(it, false);
        }
        return "true";
    }

    String domainCmd(Item it, boolean add) {
        String op = add ? "-I" : "-D";
        String suf = add ? "" : " 2>/dev/null";
        StringBuilder sb = new StringBuilder();
        for (String h : it.hosts) {
            sb.append("iptables ").append(op)
              .append(" OUTPUT -p tcp --dport " + PORT + " -m string --string \"").append(h)
              .append("\" --algo bm -j REJECT --reject-with tcp-reset").append(suf).append("; ");
            sb.append("iptables ").append(op)
              .append(" OUTPUT -p udp --dport " + PORT + " -m string --string \"").append(h)
              .append("\" --algo bm -j REJECT").append(suf).append("; ");
            sb.append("ip6tables ").append(op)
              .append(" OUTPUT -p tcp --dport " + PORT + " -m string --string \"").append(h)
              .append("\" --algo bm -j REJECT --reject-with tcp-reset").append(suf).append("; ");
        }
        return sb.toString();
    }

    String uidCmd(Item it, boolean add) {
        int uid = uidOf(it);
        if (uid <= 0) return "true";
        String op = add ? "-I" : "-D";
        String suf = add ? "" : " 2>/dev/null";
        return "iptables " + op + " OUTPUT -m owner --uid-owner " + uid + " -j REJECT" + suf + "; "
                + "iptables " + op + " OUTPUT -m owner --uid-owner " + uid + " -o lo -j RETURN" + suf + "; "
                + "ip6tables " + op + " OUTPUT -m owner --uid-owner " + uid + " -j REJECT" + suf + "; "
                + "ip6tables " + op + " OUTPUT -m owner --uid-owner " + uid + " -o lo -j RETURN" + suf + ";";
    }

    // ------------------------------------------------------------------ checks

    boolean isProtected(Item it) {
        switch (it.type) {
            case T_PKG: {
                String out = Shell.su("pm list packages -d --user 0").out;
                return out.contains("package:" + it.pkg);
            }
            case T_COMP: {
                String d = Shell.su("dumpsys package " + it.pkg).out;
                Pattern p = Pattern.compile("(?s)disabledComponents:(.*?)(enabledComponents:|\\n\\s*\\n)");
                Matcher m = p.matcher(d);
                while (m.find()) {
                    if (m.group(1).contains(it.comp)) return true;
                }
                return false;
            }
            case T_SETTING: {
                String v = Shell.su("settings get secure " + it.key).out.trim();
                return v.equals(it.protectVal);
            }
            case T_DOMAIN: {
                String v4 = Shell.su("iptables -S OUTPUT").out;
                String v6 = Shell.su("ip6tables -S OUTPUT").out;
                int c4 = 0, c6 = 0;
                for (String h : it.hosts) {
                    c4 += occurrences(v4, h);
                    c6 += occurrences(v6, h);
                }
                return c4 >= it.hosts.size() * 2 && c6 >= it.hosts.size();
            }
            case T_UID: {
                int uid = uidOf(it);
                if (uid <= 0) return false;
                String v4 = Shell.su("iptables -S OUTPUT").out;
                String v6 = Shell.su("ip6tables -S OUTPUT").out;
                String needle = "--uid-owner " + uid;
                return occurrences(v4, needle) >= 2 && occurrences(v6, needle) >= 2;
            }
        }
        return false;
    }

    static int occurrences(String text, String needle) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    long counters(Item it) {
        String needle;
        if (it.type == T_UID) {
            int uid = uidOf(it);
            if (uid <= 0) return 0;
            needle = "--uid-owner " + uid;
        } else {
            needle = it.hosts.get(0);
        }
        long total = 0;
        for (String cmd : new String[]{"iptables -L OUTPUT -v -n", "ip6tables -L OUTPUT -v -n"}) {
            String out = Shell.su(cmd).out;
            for (String line : out.split("\n")) {
                if (line.contains(needle)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) {
                        try {
                            total += Long.parseLong(parts[0]);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return total;
    }

    boolean probe(Item it) {
        if (it.hosts == null || it.hosts.isEmpty()) return false;
        long before = counters(it);
        try {
            String host = it.hosts.get(0);
            InetAddress addr = InetAddress.getByName(host);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(addr, PORT), 3000);
            s.setSoTimeout(1500);
            byte[] payload = ("probe " + host).getBytes("UTF-8");
            s.getOutputStream().write(payload);
            s.getOutputStream().flush();
            try {
                s.getInputStream().read();
            } catch (Exception ignored) {
            }
            s.close();
        } catch (Exception ignored) {
        }
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
        }
        return counters(it) > before;
    }

    String dataFileText(Item it) {
        if (it.dataPaths == null || it.dataPaths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("；数据文件：");
        boolean first = true;
        for (String p : it.dataPaths) {
            String out = Shell.su("ls -ld \"" + p + "\" 2>/dev/null").out.trim();
            boolean exists = !out.isEmpty();
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (!first) sb.append("、");
            sb.append(name).append(exists ? " 存在" : " 不存在");
            first = false;
        }
        return sb.toString();
    }

    String verifyText(Item it) {
        StringBuilder sb = new StringBuilder();
        boolean st = isProtected(it);
        sb.append(st ? "已保护 ✓" : "未保护 ✗");
        if (it.type == T_DOMAIN || it.type == T_UID) {
            if (it.type == T_UID) {
                sb.append("；UID=").append(uidOf(it));
            }
            sb.append("；规则命中 ").append(counters(it)).append(" 包");
            if (it.type == T_DOMAIN && st) {
                sb.append(probe(it) ? "；主动探测命中 ✓" : "；主动探测未命中");
            }
        }
        sb.append(dataFileText(it));
        return sb.toString();
    }

    // ------------------------------------------------------------------ time marks

    void mark(Item it, boolean protectedState) {
        SharedPreferences.Editor e = sp.edit();
        if (protectedState) {
            e.putLong("p_" + it.id, System.currentTimeMillis());
            e.remove("u_" + it.id);
        } else {
            e.putLong("u_" + it.id, System.currentTimeMillis());
            e.remove("p_" + it.id);
        }
        e.apply();
    }

    String statusText(Item it, boolean st) {
        long t = st ? sp.getLong("p_" + it.id, 0L) : sp.getLong("u_" + it.id, 0L);
        String when;
        if (t > 0) when = "自 " + dateFmt.format(new Date(t));
        else when = st ? "（调整时间未知）" : "（无调整记录）";
        return (st ? "已保护 ✓  " : "未保护 ✗  ") + when;
    }

    void setStatus(Item it, boolean st) {
        it.status.setText(statusText(it, st));
        it.status.setTextColor(st ? 0xFF1B5E20 : 0xFFB71C1C);
    }

    // ------------------------------------------------------------------ actions

    void checkRoot() {
        ex.execute(new Runnable() {
            @Override public void run() {
                final Shell.R r = Shell.su("id");
                final boolean ok = r.out.contains("uid=0");
                ui.post(new Runnable() {
                    @Override public void run() {
                        root = ok;
                        rootView.setText(ok ? "root: 已授权 ✓" : "root: 未授权 ✗（请在 KernelSU 中允许本应用）");
                        rootView.setTextColor(ok ? 0xFF1B5E20 : 0xFFB71C1C);
                        log(ok ? "root 检测通过: " + r.out.trim() : "root 检测失败: " + r.all());
                        if (!ok) {
                            for (Item it : items) {
                                it.bApply.setEnabled(false);
                                it.bRevert.setEnabled(false);
                                it.bVerify.setEnabled(false);
                            }
                        } else {
                            refreshAll();
                        }
                    }
                });
            }
        });
    }

    void refreshAll() {
        for (Item it : items) refreshItem(it);
    }

    void refreshItem(Item it) {
        ex.execute(new Runnable() {
            @Override public void run() {
                final boolean st = isProtected(it);
                ui.post(new Runnable() {
                    @Override public void run() { setStatus(it, st); }
                });
            }
        });
    }

    void applyItem(final Item it) {
        log("应用 → " + it.title);
        ex.execute(new Runnable() {
            @Override public void run() {
                final Shell.R r = Shell.su(applyCmd(it));
                final boolean st = isProtected(it);
                if (st) mark(it, true);
                ui.post(new Runnable() {
                    @Override public void run() {
                        setStatus(it, st);
                        log(it.title + " 应用: " + firstLine(r.all()));
                    }
                });
            }
        });
    }

    void revertItem(final Item it) {
        log("恢复 → " + it.title);
        ex.execute(new Runnable() {
            @Override public void run() {
                final Shell.R r = Shell.su(revertCmd(it));
                final boolean st = isProtected(it);
                if (!st) mark(it, false);
                ui.post(new Runnable() {
                    @Override public void run() {
                        setStatus(it, st);
                        log(it.title + " 恢复: " + firstLine(r.all()));
                    }
                });
            }
        });
    }

    void verifyItem(final Item it) {
        log("验证 → " + it.title);
        ex.execute(new Runnable() {
            @Override public void run() {
                final String txt = verifyText(it);
                final boolean st = isProtected(it);
                ui.post(new Runnable() {
                    @Override public void run() {
                        setStatus(it, st);
                        log(it.title + "：" + txt);
                    }
                });
            }
        });
    }

    void doAction(String action) {
        if (!root) {
            ui.post(new Runnable() {
                @Override public void run() { log("未获取 root，无法执行"); }
            });
            return;
        }
        final String a = action;
        log("=== 批量：" + a + " ===");
        ex.execute(new Runnable() {
            @Override public void run() {
                for (Item it : items) {
                    if ("apply_all".equals(a)) {
                        Shell.R r = Shell.su(applyCmd(it));
                        boolean st = isProtected(it);
                        if (st) mark(it, true);
                        final String out = it.title + " 应用: " + firstLine(r.all());
                        final boolean stx = st;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                setStatus(it, stx);
                                log(out);
                            }
                        });
                    } else if ("revert_all".equals(a)) {
                        Shell.R r = Shell.su(revertCmd(it));
                        boolean st = isProtected(it);
                        if (!st) mark(it, false);
                        final String out = it.title + " 恢复: " + firstLine(r.all());
                        final boolean stx = st;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                setStatus(it, stx);
                                log(out);
                            }
                        });
                    } else if ("verify_all".equals(a)) {
                        final String txt = verifyText(it);
                        final boolean st = isProtected(it);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                setStatus(it, st);
                                log(it.title + "：" + txt);
                            }
                        });
                    } else {
                        final boolean st = isProtected(it);
                        ui.post(new Runnable() {
                            @Override public void run() { setStatus(it, st); }
                        });
                    }
                }
                ui.post(new Runnable() {
                    @Override public void run() { log("=== 批量完成 ==="); }
                });
            }
        });
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    void log(String s) {
        if (s == null) s = "";
        logView.append("[" + ts.format(new Date()) + "] " + s + "\n");
        if (logView.getLineCount() > 400) {
            CharSequence t = logView.getText();
            logView.setText(t.subSequence(t.length() / 2, t.length()));
        }
    }

    // ------------------------------------------------------------------ ui

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                    v.setPadding(0, b.top, 0, b.bottom);
                    return insets;
                }
            });
        }

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabSettings = new Button(this);
        tabSettings.setText("设置");
        tabSettings.setAllCaps(false);
        tabSettings.setTextSize(15);
        tabSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(0); }
        });
        tabUnadapted = new Button(this);
        tabUnadapted.setText("未适配");
        tabUnadapted.setAllCaps(false);
        tabUnadapted.setTextSize(15);
        tabUnadapted.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(1); }
        });
        tabs.addView(tabSettings, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabUnadapted, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabs);

        pageSettings = buildSettingsPage();
        pageUnadapted = buildUnadaptedPage();
        root.addView(pageSettings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pageUnadapted, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showTab(0);
        return root;
    }

    ScrollView buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(24));
        sv.addView(c);

        TextView title = text("隐私开关（root）", 20, Color.BLACK);
        title.setTypeface(null, Typeface.BOLD);
        c.addView(title);
        c.addView(text("应用/恢复已实施的隐私管控，并从实际状态、规则计数与数据文件验证是否生效", 12, 0xFF666666));

        rootView = text("root: 检测中…", 13, 0xFF333333);
        rootView.setPadding(0, dp(6), 0, dp(6));
        c.addView(rootView);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(8));
        bar.addView(btn("应用全部", new View.OnClickListener() {
            @Override public void onClick(View v) { doAction("apply_all"); }
        }));
        bar.addView(btn("恢复全部", new View.OnClickListener() {
            @Override public void onClick(View v) { doAction("revert_all"); }
        }));
        bar.addView(btn("全部验证", new View.OnClickListener() {
            @Override public void onClick(View v) { doAction("verify_all"); }
        }));
        bar.addView(btn("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshAll(); }
        }));
        c.addView(bar);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        c.addView(list);
        for (Item it : items) list.addView(buildRow(it));

        TextView logTitle = text("运行日志", 14, Color.BLACK);
        logTitle.setTypeface(null, Typeface.BOLD);
        logTitle.setPadding(0, dp(12), 0, dp(4));
        c.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF7CFFB2);
        logView.setBackgroundColor(0xFF101418);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setTextIsSelectable(true);
        logView.setMinHeight(dp(140));
        c.addView(logView);

        return sv;
    }

    ScrollView buildUnadaptedPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(24));
        sv.addView(c);

        TextView title = text("未适配（已知采集，未做管控）", 18, Color.BLACK);
        title.setTypeface(null, Typeface.BOLD);
        c.addView(title);
        c.addView(text("以下系统应用已确认存在额外数据采集，暂未提供开关，原因见各项说明", 12, 0xFF666666));

        for (Unadapted u : unadapted) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            card.setBackgroundColor(0xFFF7F8FA);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            card.setLayoutParams(lp);

            TextView t = text(u.title + "（" + u.pkg + "）", 15, Color.BLACK);
            t.setTypeface(null, Typeface.BOLD);
            card.addView(t);

            TextView d = text(u.desc, 12, 0xFF444444);
            d.setPadding(0, dp(4), 0, 0);
            card.addView(d);

            c.addView(card);
        }
        return sv;
    }

    void showTab(int idx) {
        pageSettings.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageUnadapted.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        styleTab(tabSettings, idx == 0);
        styleTab(tabUnadapted, idx == 1);
    }

    void styleTab(Button b, boolean selected) {
        b.setBackgroundColor(selected ? 0xFF1B5E20 : 0xFFE8EAED);
        b.setTextColor(selected ? Color.WHITE : 0xFF333333);
    }

    View buildRow(final Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(0xFFF7F8FA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);

        TextView t = text(it.title, 16, Color.BLACK);
        t.setTypeface(null, Typeface.BOLD);
        t.setLineSpacing(dp(2), 1.0f);
        row.addView(t);

        TextView pkgView = text(it.pkg, 11, 0xFF7A828A);
        pkgView.setPadding(0, 0, 0, dp(2));
        row.addView(pkgView);

        if (it.desc != null) {
            for (String line : it.desc.split("\n")) {
                if (line.trim().isEmpty()) continue;
                int idx = line.indexOf('：');
                String label = idx > 0 ? line.substring(0, idx) : "";
                String value = idx > 0 ? line.substring(idx + 1).trim() : line.trim();
                addField(row, label, value, false);
            }
        }

        if (it.dataPaths != null && !it.dataPaths.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : it.dataPaths) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
            addField(row, "数据文件", sb.toString(), true);
        }

        LinearLayout cmdBox = new LinearLayout(this);
        cmdBox.setOrientation(LinearLayout.VERTICAL);
        cmdBox.setPadding(dp(8), dp(6), dp(8), dp(6));
        cmdBox.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        clp.bottomMargin = dp(4);
        cmdBox.setLayoutParams(clp);
        addCmd(cmdBox, "保护", it.applyLabel);
        addCmd(cmdBox, "恢复", it.revertLabel);
        row.addView(cmdBox);

        it.status = text("状态：检测中…", 13, 0xFF333333);
        it.status.setPadding(0, dp(6), 0, dp(4));
        row.addView(it.status);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        it.bApply = btn("应用", new View.OnClickListener() {
            @Override public void onClick(View v) { applyItem(it); }
        });
        it.bRevert = btn("恢复", new View.OnClickListener() {
            @Override public void onClick(View v) { revertItem(it); }
        });
        it.bVerify = btn("验证", new View.OnClickListener() {
            @Override public void onClick(View v) { verifyItem(it); }
        });
        btns.addView(it.bApply);
        btns.addView(it.bRevert);
        btns.addView(it.bVerify);
        row.addView(btns);

        return row;
    }

    /** 标签一行、内容一段，内容自动换行并加大行距。 */
    void addField(LinearLayout parent, String label, String value, boolean mono) {
        if (label != null && !label.isEmpty()) {
            TextView l = text(label, 11, 0xFF1B5E20);
            l.setTypeface(null, Typeface.BOLD);
            l.setPadding(0, dp(6), 0, 0);
            parent.addView(l);
        }
        TextView v = text(value, 13, 0xFF303840);
        v.setLineSpacing(dp(2), 1.0f);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(dp(2), dp(1), 0, dp(1));
        parent.addView(v);
    }

    /** 命令：左侧“保护/恢复”标签，右侧等宽命令自动换行。 */
    void addCmd(LinearLayout parent, String label, String cmd) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 10, 0xFF1B5E20);
        l.setTypeface(null, Typeface.BOLD);
        l.setPadding(0, dp(2), dp(6), dp(2));
        TextView v = text(cmd, 10, 0xFF5A6570);
        v.setTypeface(Typeface.MONOSPACE);
        v.setLineSpacing(dp(1), 1.0f);
        v.setPadding(0, dp(2), 0, dp(2));
        row.addView(l);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
    }
}
