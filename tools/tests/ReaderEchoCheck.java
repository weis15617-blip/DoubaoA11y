import io.github.doubaoa11y.hook.CandidateOriginState;
import io.github.doubaoa11y.hook.EchoTextChange;
import io.github.doubaoa11y.hook.InputPrivacy;
import io.github.doubaoa11y.hook.Names;
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
        System.out.println("PASS " + count + " reader echo/candidate policy checks; no device or user data accessed");
    }
}
