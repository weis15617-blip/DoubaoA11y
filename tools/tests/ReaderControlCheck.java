import io.github.doubaoa11y.hook.ReaderControlPolicy;
import io.github.doubaoa11y.hook.ReaderControlPolicy.Conditions;
import io.github.doubaoa11y.hook.ReaderDetection;
import io.github.doubaoa11y.hook.ReaderDetection.Service;
import io.github.doubaoa11y.hook.ReaderDetection.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 在 JVM 验证读屏识别与暂停恢复策略。 */
public final class ReaderControlCheck {
    private static final String BOYI = "com.bjbyhd.voiceback";
    private static final String BOYI_OLD = "com.bjbyhd.screenreader";
    private static final String DIANMING = "com.dianming.phoneapp";
    private static final String TALKBACK = "com.google.android.marvin.talkback";
    private static final String OTHER = "example.other";
    private static int count;

    private static void equal(Object expected, Object actual, String scenario) {
        if (!expected.equals(actual)) {
            throw new AssertionError(scenario + " expected " + expected + ", got " + actual);
        }
        count++;
    }

    private static void detectionChecks() {
        ReaderDetection detection = ReaderDetection.INSTANCE;
        List<Service> empty = Collections.emptyList();
        equal(Arrays.asList(
                new Service(BOYI, BOYI + ".Service", false),
                new Service(DIANMING, "example.reader.FullService", false)),
                detection.parseServices(BOYI + "/.Service::invalid:" +
                        DIANMING + "/example.reader.FullService:missing/:/Service:extra/.Service/suffix"),
                "secure setting component parsing");

        List<Service> otherServices = Arrays.asList(
                new Service(OTHER, OTHER + ".Service", false),
                new Service("example.touch", "example.touch.Service", true));
        for (String pkg : Arrays.asList(BOYI, BOYI_OLD, DIANMING)) {
            String setting = pkg + "/.Service";
            equal(new State(pkg, true), detection.detect(true, true, empty, setting),
                    "secure setting fallback for " + pkg);
            equal(new State(pkg, false), detection.detect(true, false, otherServices, setting),
                    "known reader outranks unknown services for " + pkg);
            State paused = detection.detect(true, false, empty, setting);
            equal(new State(pkg, false), paused, "paused reader package for " + pkg);
            equal(true, paused.getReaderOn(), "reader stays on after pausing " + pkg);
        }

        Service unknown = new Service(OTHER, OTHER + ".Service", false);
        Service named = new Service("example.named", "example.named.ScreenReaderService", false);
        Service touch = new Service("example.touch", "example.touch.Service", true);
        equal(new State(touch.getPkg(), false),
                detection.detect(true, false, Arrays.asList(unknown, named, touch), null),
                "touch exploration flag outranks class-name hint");
        equal(new State(named.getPkg(), false),
                detection.detect(true, false, Arrays.asList(unknown, named), null),
                "class-name hint outranks generic service");
        equal(new State(OTHER, false), detection.detect(true, false, Collections.singletonList(unknown), null),
                "generic service fallback");
        equal(new State(DIANMING, false), detection.detect(true, false,
                Collections.singletonList(new Service(BOYI, BOYI + ".Service", true)), DIANMING + "/.Service"),
                "secure setting wins against stale known reader");

        List<Service> excluded = Arrays.asList(
                new Service(TALKBACK, TALKBACK + ".SelectToSpeakService", true),
                new Service("li.songe.gkd", "li.songe.gkd.ScreenReaderService", true),
                new Service("com.android.systemui", "com.android.systemui.ScreenReaderService", true));
        String excludedSetting = TALKBACK + "/.SelectToSpeakService:hello.litiaotiao.test/.ScreenReaderService";
        State onlyExcluded = detection.detect(true, false, excluded, excludedSetting);
        equal(new State("", false), onlyExcluded, "excluded classes and package prefixes");
        equal(false, onlyExcluded.getReaderOn(), "excluded services do not count as a reader");
        equal(new State(OTHER, false), detection.detect(true, false, excluded, OTHER + "/.Service"),
                "excluded services do not outrank a generic reader");
        State disabled = detection.detect(false, true, otherServices, BOYI + "/.Service");
        equal(new State("", false), disabled, "system accessibility disabled");
        equal(false, disabled.getReaderOn(), "disabled accessibility overrides stale service and touch state");
    }

    private static Conditions fullscreen(String pkg, boolean explorationOn) {
        return new Conditions(pkg, true, true, true, false, false, explorationOn);
    }

    private static final class PolicyCheck {
        final ReaderControlPolicy policy = new ReaderControlPolicy();
        final List<String> requests = new ArrayList<>();
        long now = 1000;
        boolean sendSucceeds = true;
        String savedOwner = "";

        private boolean save(String pkg) {
            savedOwner = pkg;
            return true;
        }

        private boolean send(String pkg, boolean on) {
            requests.add((on ? "restore " : "pause ") + pkg);
            return sendSucceeds;
        }

        void sync(Conditions state) {
            policy.sync(state, () -> now, this::save, this::send);
        }

        boolean restore() {
            return policy.restore(this::save, this::send);
        }

        void owner(String expected, String scenario) {
            equal(expected, policy.getOwner(), scenario + " in-memory owner");
            equal(expected, savedOwner, scenario + " persisted owner");
        }

        void requests(String scenario, String... expected) {
            equal(Arrays.asList(expected), requests, scenario + " requests");
        }
    }

    private static void checkFullscreenPause() {
        // 快速输入开关的两种状态都映射到这里的同一组条件，脚本检查生产调用处。
        for (String pkg : Arrays.asList(BOYI, BOYI_OLD, DIANMING)) {
            String scenario = "fullscreen pause for " + pkg;
            PolicyCheck check = new PolicyCheck();
            check.sync(fullscreen(pkg, true));
            check.sync(fullscreen(pkg, false));
            check.requests(scenario, "pause " + pkg);
            check.owner(pkg, scenario);
        }
    }

    private static void checkNoOwnership() {
        PolicyCheck initiallyOff = new PolicyCheck();
        initiallyOff.sync(fullscreen(BOYI, false));
        equal(true, initiallyOff.restore(), "hide with exploration initially off");
        initiallyOff.requests("exploration initially off");
        initiallyOff.owner("", "exploration initially off");

        PolicyCheck standard = new PolicyCheck();
        standard.sync(new Conditions(BOYI, true, true, true, true, false, true));
        standard.requests("standard double-tap input");
        standard.owner("", "standard double-tap input");
    }

    private static void checkPauseRetry() {
        PolicyCheck check = new PolicyCheck();
        check.sendSucceeds = false;
        check.sync(fullscreen(BOYI, true));
        check.owner("", "failed pause");
        check.requests("failed pause", "pause " + BOYI);

        check.sendSucceeds = true;
        check.now += 4999;
        check.sync(fullscreen(BOYI, true));
        check.owner("", "pause cooldown");
        check.requests("pause cooldown", "pause " + BOYI);
        check.now++;
        check.sync(fullscreen(BOYI, true));
        check.owner(BOYI, "pause retry at five seconds");
        check.requests("pause retry", "pause " + BOYI, "pause " + BOYI);
    }

    private static void checkRestores() {
        checkRestoreOn("open panel", new Conditions(BOYI, true, true, true, false, true, false));
        checkRestoreOn("lock screen", new Conditions(BOYI, true, false, true, false, false, false));
        checkRestoreOn("exit fullscreen", new Conditions(BOYI, false, true, true, false, false, false));
        checkRestoreOn("switch to uncontrolled reader", fullscreen(TALKBACK, true));
        checkRestoreOn("enter standard input",
                new Conditions(BOYI, true, true, true, true, false, false));

        PolicyCheck hidden = new PolicyCheck();
        hidden.sync(fullscreen(BOYI, true));
        equal(true, hidden.restore(), "hide keyboard restores successfully");
        hidden.owner("", "hide keyboard");
        hidden.requests("hide keyboard", "pause " + BOYI, "restore " + BOYI);

        PolicyCheck switched = new PolicyCheck();
        switched.sync(fullscreen(BOYI, true));
        switched.sync(fullscreen(DIANMING, true));
        switched.owner(DIANMING, "switch to controlled reader");
        switched.requests("switch to controlled reader",
                "pause " + BOYI, "restore " + BOYI, "pause " + DIANMING);
    }

    private static void checkRestoreOn(String scenario, Conditions next) {
        PolicyCheck check = new PolicyCheck();
        check.sync(fullscreen(BOYI, true));
        check.sync(next);
        check.sync(next);
        check.owner("", scenario);
        check.requests(scenario, "pause " + BOYI, "restore " + BOYI);
    }

    private static void checkRestoreRetry() {
        PolicyCheck check = new PolicyCheck();
        check.sync(fullscreen(BOYI, true));
        check.sendSucceeds = false;
        check.sync(fullscreen(DIANMING, true));
        check.owner(BOYI, "failed restore keeps original owner");
        check.requests("failed restore blocks new pause", "pause " + BOYI, "restore " + BOYI);
        // 再次显示原读屏时仍要完成已经开始的恢复。
        check.sync(fullscreen(BOYI, false));
        check.owner(BOYI, "failed restore retry");
        check.sendSucceeds = true;
        check.sync(fullscreen(BOYI, false));
        check.owner("", "restore retry succeeds");
        check.sync(fullscreen(BOYI, false));
        check.requests("restore retries stop after success",
                "pause " + BOYI, "restore " + BOYI, "restore " + BOYI, "restore " + BOYI);
    }

    public static void main(String[] args) {
        detectionChecks();
        checkFullscreenPause();
        checkNoOwnership();
        checkPauseRetry();
        checkRestores();
        checkRestoreRetry();
        System.out.println("PASS " + count + " reader detection/control policy checks");
    }
}
