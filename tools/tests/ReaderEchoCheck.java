import io.github.doubaoa11y.hook.CandidateOriginState;
import io.github.doubaoa11y.hook.CandidatePages;
import io.github.doubaoa11y.hook.EchoTextChange;
import io.github.doubaoa11y.hook.GestureSpeechFormat;
import io.github.doubaoa11y.hook.InputPrivacy;
import io.github.doubaoa11y.hook.Names;
import io.github.doubaoa11y.hook.RapidMoveDetector;
import io.github.doubaoa11y.hook.SelectionDelta;
import io.github.doubaoa11y.hook.SelectionOverrideTiming;
import io.github.doubaoa11y.hook.SelectionSpeechGate;
import io.github.doubaoa11y.hook.TwoFingerTapPolicy;
import io.github.doubaoa11y.hook.VoiceEchoGate;

/** 只测试内存中的真实策略类，不连接设备、不读写用户配置或输入。 */
public final class ReaderEchoCheck {
    private static int count;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("reader echo check " + (count + 1));
        count++;
    }
    private static EchoTextChange.Change change(String before, String after) {
        return EchoTextChange.INSTANCE.between(before, after);
    }
    public static void main(String[] args) {
        check(change("相同", "相同").getInserted().isEmpty());
        check(change("相同", "相同").getRemoved().isEmpty());
        check("a".equals(change("", "a").getInserted()));
        check("a".equals(change("a", "aa").getInserted())); // 两次同字都是真实插入
        check("好".equals(change("你好", "你").getRemoved()));
        check("新".equals(change("旧文本", "新文本").getInserted()));
        check("旧".equals(change("旧文本", "新文本").getRemoved()));
        check("😀".equals(change("a😀b", "ab").getRemoved()));
        check("😁".equals(change("a😀b", "a😁b").getInserted()));
        check("😀".equals(change("a😀b", "a😁b").getRemoved()));
        check("😀".equals(change("😀", "😀😀").getInserted()));
        check("\n".equals(change("a\nb", "ab").getRemoved()));

        VoiceEchoGate voice = new VoiceEchoGate();
        long first = voice.begin();
        check(voice.accept(first));
        check(!voice.accept(first)); // 同一录音组字+commit不重复
        long second = voice.begin();
        check(!voice.accept(first));
        check(voice.accept(second)); // 下一录音说相同单字仍可读
        long cancelled = voice.begin();
        voice.cancel();
        check(!voice.accept(cancelled));

        CandidateOriginState s = new CandidateOriginState();
        check(!s.snapshot(false, true, true)); // 光标联想本身不是输入来源
        s.input(CandidateOriginState.Input.KEY);
        check(!s.snapshot(false, true, null)); // 无引擎证据不猜九键
        check(s.snapshot(false, true, true));
        check(s.isOpen());
        check(s.snapshot(false, true, true)); // 无墙钟过期
        s.committed();
        check(!s.isOpen());
        check(s.snapshot(false, true, true)); // 部分组字之后引擎仍在组字
        s.committed();
        check(!s.snapshot(false, true, false));
        check(!s.snapshot(false, true, true)); // 完整提交后不复活旧来源
        s.input(CandidateOriginState.Input.HANDWRITING);
        check(s.snapshot(false, true, null));
        check(s.snapshot(false, true, false)); // 手写没有拼音，不等于引擎拼音模式
        s.cursorMoved();
        check(!s.snapshot(false, true, true));
        check(!s.snapshot(true, true, true)); // 移光标后旧快照不能抢回手势
        s.input(CandidateOriginState.Input.COMPOSING);
        check(s.snapshot(false, true, null));
        s.clear();
        check(!s.snapshot(false, true, true));
        check(s.snapshot(true, true, null)); // 原有明确拼音通路保留
        s.clear();
        s.input(CandidateOriginState.Input.HANDWRITING);
        check(!s.snapshot(false, false, null));
        check(s.snapshot(false, true, null)); // 先空后出识别结果仍属于同一笔
        s.clear();
        check(!s.snapshot(false, true, null));

        check("A逗号B".equals(Names.INSTANCE.punctuationText("A，B")));
        check("省略号破折号".equals(Names.INSTANCE.punctuationText("……——")));
        check("😀abc".equals(Names.INSTANCE.punctuationText("😀abc")));
        check(InputPrivacy.INSTANCE.isPassword(0x81));
        check(InputPrivacy.INSTANCE.isPassword(0x91));
        check(InputPrivacy.INSTANCE.isPassword(0xe1));
        check(InputPrivacy.INSTANCE.isPassword(0x12));
        check(!InputPrivacy.INSTANCE.isPassword(0x01));
        check(!InputPrivacy.INSTANCE.isPassword(0x02));

        // SelectionDelta：选区增量差分
        SelectionDelta.Result d1 = SelectionDelta.INSTANCE.between("你好世界", 4, 4, 4, 3);
        check(d1.getKind() == SelectionDelta.Kind.EXPANDED && "界".equals(d1.getText()));
        SelectionDelta.Result d2 = SelectionDelta.INSTANCE.between("你好世界", 4, 3, 4, 2); // 反向传参
        check(d2.getKind() == SelectionDelta.Kind.EXPANDED && "世".equals(d2.getText()));
        SelectionDelta.Result d2Norm = SelectionDelta.INSTANCE.between("你好世界", 3, 4, 2, 4); // 规范化传参
        check(d2Norm.getKind() == SelectionDelta.Kind.EXPANDED && "世".equals(d2Norm.getText()));
        SelectionDelta.Result d3 = SelectionDelta.INSTANCE.between("你好世界", 2, 4, 3, 4); // 缩回一字
        check(d3.getKind() == SelectionDelta.Kind.SHRUNK && "世".equals(d3.getText()));
        SelectionDelta.Result d4 = SelectionDelta.INSTANCE.between("你好世界", 3, 4, 4, 4); // 缩回起点
        check(d4.getKind() == SelectionDelta.Kind.CLEARED && d4.getText().isEmpty());
        SelectionDelta.Result d5 = SelectionDelta.INSTANCE.between("你好世界", 0, 0, 0, 1); // 向右扩选
        check(d5.getKind() == SelectionDelta.Kind.EXPANDED && "你".equals(d5.getText()));
        SelectionDelta.Result d6 = SelectionDelta.INSTANCE.between("你好世界", 0, 1, 0, 2);
        check(d6.getKind() == SelectionDelta.Kind.EXPANDED && "好".equals(d6.getText()));
        SelectionDelta.Result d7 = SelectionDelta.INSTANCE.between("你好世界", 0, 4, 0, 3); // 全选后左滑缩回
        check(d7.getKind() == SelectionDelta.Kind.SHRUNK && "界".equals(d7.getText()));
        SelectionDelta.Result d8 = SelectionDelta.INSTANCE.between("你好世界", 2, 3, 0, 2); // 跨锚点反转
        check(d8.getKind() == SelectionDelta.Kind.EXPANDED && "你好".equals(d8.getText()));
        SelectionDelta.Result d9 = SelectionDelta.INSTANCE.between("a😀b", 3, 4, 1, 4); // Emoji 代理对
        check(d9.getKind() == SelectionDelta.Kind.EXPANDED && "😀".equals(d9.getText()));
        SelectionDelta.Result dSame = SelectionDelta.INSTANCE.between("你好世界", 1, 3, 1, 3);
        check(dSame.getKind() == SelectionDelta.Kind.NONE);
        SelectionDelta.Result dClamp = SelectionDelta.INSTANCE.between("ab", -5, 10, 0, 10);
        check(dClamp.getKind() == SelectionDelta.Kind.NONE);

        // SelectionSpeechGate：一次性限时防重
        SelectionSpeechGate gate = new SelectionSpeechGate();
        check(!gate.consumeSkipNext(1000L));
        gate.markSkipNext(1000L, 500L);
        check(gate.consumeSkipNext(1200L));
        check(!gate.consumeSkipNext(1201L)); // 消费一次即清除
        gate.markSkipNext(2000L, 500L);
        check(!gate.consumeSkipNext(2500L)); // 过期自动失效
        gate.markSkipNext(3000L, 500L);
        gate.reset();
        check(!gate.consumeSkipNext(3100L));

        // SelectionOverrideTiming：方案 A 下的对齐缓冲
        check(SelectionOverrideTiming.INSTANCE.selectionDelayMs("com.nirenr.talkman") == 30L);
        check(SelectionOverrideTiming.INSTANCE.selectionDelayMs("com.bjbyhd.voiceback") == 30L);
        check(SelectionOverrideTiming.INSTANCE.cancelSelectionDelayMs("com.nirenr.talkman") >= 130L);
        check(SelectionOverrideTiming.INSTANCE.cancelSelectionDelayMs("") >= 130L);
        check(SelectionOverrideTiming.INSTANCE.cancelSelectionDelayMs("com.bjbyhd.voiceback") == 45L);
        check(SelectionOverrideTiming.INSTANCE.textChangeDelayMs("com.nirenr.talkman") == 55L);
        check(SelectionOverrideTiming.INSTANCE.fallbackMs(30L) > 30L);

        // GestureSpeechFormat：先念文字/字数，再念“已xx”
        check("界已选中".equals(GestureSpeechFormat.INSTANCE.selected("界")));
        check("界已取消".equals(GestureSpeechFormat.INSTANCE.deselected("界")));
        check("好已删除".equals(GestureSpeechFormat.INSTANCE.deleted("好", 1)));
        check("逗号已删除".equals(GestureSpeechFormat.INSTANCE.deleted("逗号", 1)));
        check("8个字已删除".equals(GestureSpeechFormat.INSTANCE.deleted("一二三四五六七八", 8)));
        check("你好已复制".equals(GestureSpeechFormat.INSTANCE.copied("你好", 2)));
        check("你好已剪切".equals(GestureSpeechFormat.INSTANCE.cut("你好", 2)));
        check("你好已粘贴".equals(GestureSpeechFormat.INSTANCE.pasted("你好", 2)));
        check("10个字已清空".equals(GestureSpeechFormat.INSTANCE.cleared(10)));

        // RapidMoveDetector：正常逐字滑动不抢麦，极速连滑（<=150ms）才补念最后一格
        RapidMoveDetector rapid = new RapidMoveDetector(150L);
        check(!rapid.record(1000L, "你"));
        check(!rapid.record(1250L, "好")); // 250ms 正常节奏
        check(!rapid.record(1550L, "世")); // 300ms 正常节奏，连续 3 格也不抢麦
        check(!rapid.record(1800L, "界")); // 250ms 正常节奏
        check(rapid.consumePending().getFirst() == 0);
        check(!rapid.record(2000L, "一"));
        check(rapid.record(2100L, "二"));  // 100ms 极速连滑：落入读屏去抖窗口
        check(rapid.record(2210L, "三"));  // 110ms 极速连滑
        kotlin.Pair<Integer, String> pending = rapid.consumePending();
        check(pending.getFirst() == 3 && "三".equals(pending.getSecond()));
        check(rapid.consumePending().getFirst() == 0); // 消费即清空

        // CandidatePages：分页追加、累计全量、滑动窗口均保留上一页以便向左翻回
        CandidatePages.Merged p1 = CandidatePages.INSTANCE.merge(
                java.util.Arrays.asList("你", "泥"),
                java.util.Arrays.asList(0, 1),
                java.util.Arrays.asList("拟", "逆"),
                java.util.Arrays.asList(2, 3),
                true);
        check(p1.getFirstNew() == 2);
        check(p1.getItems().equals(java.util.Arrays.asList("你", "泥", "拟", "逆")));
        check(p1.getEngineIdx().equals(java.util.Arrays.asList(0, 1, 2, 3)));

        CandidatePages.Merged p2 = CandidatePages.INSTANCE.merge(
                java.util.Arrays.asList("你", "泥"),
                java.util.Arrays.asList(0, 1),
                java.util.Arrays.asList("你", "泥", "拟", "逆"),
                java.util.Arrays.asList(0, 1, 2, 3),
                true);
        check(p2.getFirstNew() == 2 && p2.getItems().size() == 4);

        CandidatePages.Merged p3 = CandidatePages.INSTANCE.merge(
                java.util.Arrays.asList("你", "泥", "拟"),
                java.util.Arrays.asList(0, 1, 2),
                java.util.Arrays.asList("拟", "逆", "妮"),
                java.util.Arrays.asList(2, 3, 4),
                true);
        check(p3.getFirstNew() == 3);
        check(p3.getItems().equals(java.util.Arrays.asList("你", "泥", "拟", "逆", "妮")));
        check(p3.getEngineIdx().equals(java.util.Arrays.asList(0, 1, 2, 3, 4)));

        CandidatePages.Merged p4 = CandidatePages.INSTANCE.merge(
                java.util.Arrays.asList("你", "泥"),
                java.util.Arrays.asList(0, 1),
                java.util.Arrays.asList("你", "泥"),
                java.util.Arrays.asList(0, 1),
                true);
        check(p4.getFirstNew() == -1 && p4.getItems().size() == 2);

        // TwoFingerTapPolicy：双指双击空中间隔与滑/拖互斥策略
        check(TwoFingerTapPolicy.INSTANCE.doubleGapMs(0) == 440L);
        check(TwoFingerTapPolicy.INSTANCE.doubleGapMs(1) == 380L);
        check(TwoFingerTapPolicy.INSTANCE.doubleGapMs(2) == 340L);
        // 第 1 拍抬手到第 2 拍按下 280ms，中心点偏移 50px（容差 240px）：命中双指双击
        check(TwoFingerTapPolicy.INSTANCE.isDoubleTap(true, 1000L, 1280L, 50f, -40f, 240f, 340L));
        // 超过空中间隔或位移超容差或未挂起：拒绝
        check(!TwoFingerTapPolicy.INSTANCE.isDoubleTap(true, 1000L, 1380L, 10f, 10f, 240f, 340L));
        check(!TwoFingerTapPolicy.INSTANCE.isDoubleTap(true, 1000L, 1200L, 260f, 10f, 240f, 340L));
        check(!TwoFingerTapPolicy.INSTANCE.isDoubleTap(false, 1000L, 1200L, 10f, 10f, 240f, 340L));
        // 拖动开启且当前方向绑定了拖动动作：先挂起普通滑动，避免先触发滑动再触发拖动
        check(TwoFingerTapPolicy.INSTANCE.shouldDeferSlideForDrag(true, 41));
        check(!TwoFingerTapPolicy.INSTANCE.shouldDeferSlideForDrag(true, 0));
        check(!TwoFingerTapPolicy.INSTANCE.shouldDeferSlideForDrag(false, 41));
        check(TwoFingerTapPolicy.INSTANCE.dragEnterDelayMs(300) == 300L);
        check(TwoFingerTapPolicy.INSTANCE.dragEnterDelayMs(100) == 180L);
        check(TwoFingerTapPolicy.INSTANCE.dragEnterDelayMs(800) == 320L);

        System.out.println("PASS " + count + " reader echo/candidate policy checks; no device or user data accessed");
    }
}
