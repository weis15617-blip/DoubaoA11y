import io.github.doubaoa11y.ui.BackupFormat;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/** 在 Android app_process 中运行，仅测试内存数据，不读写用户偏好。 */
public final class BackupFormatCheck {
    private static int count;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("check " + (count + 1));
        count++;
    }
    private static BackupFormat.Data parse(String json) {
        return BackupFormat.INSTANCE.parse(json.getBytes(StandardCharsets.UTF_8));
    }
    private static String file(String settings, String extra) {
        return "{\"format\":\"DoubaoA11y-settings\",\"version\":1,\"settings\":" + settings + extra + "}";
    }
    private static void reject(String json) {
        try { parse(json); } catch (Exception expected) { count++; return; }
        throw new AssertionError("Accepted invalid backup");
    }
    public static void main(String[] args) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("nativeEnter", true);
        values.put("typeMode", 2);
        values.put("ttsVolume", 85);
        values.put("editOptions", "47,13,16");
        byte[] bytes = BackupFormat.INSTANCE.encode(values, "高\t提高\n", false);
        BackupFormat.Data result = BackupFormat.INSTANCE.parse(bytes);
        check(result.getSettings().equals(values));
        check("高\t提高\n".equals(result.getWords()));
        check(Boolean.FALSE.equals(result.getShowLauncher()));
        BackupFormat.Data partial = parse(file("{}", ""));
        check(partial.getSettings().isEmpty() && partial.getWords() == null && partial.getShowLauncher() == null);
        check(BackupFormat.INSTANCE.exportableBooleans().stream().noneMatch(i -> i.getKey().equals("runLog") || i.getKey().equals("logText") || i.getKey().equals("forceEnable")));
        reject(file("{\"nativeEnter\":\"true\"}", ""));
        reject(file("{\"typeMode\":999}", ""));
        reject(file("{\"typeMode\":1.0}", ""));
        reject(file("{\"runLog\":true}", ""));
        reject(file("{\"forceEnable\":true}", ""));
        reject(file("{\"editOptions\":\"999\"}", ""));
        reject(file("{}", ",\"showLauncher\":\"false\""));
        reject(file("{}", ",\"customWords\":\"高\\t提高\\n高\\t高低\""));
        reject(file("{}", ",\"customWords\":\"invalid\""));
        reject(file("{}", "").replace("\"version\":1", "\"version\":2"));
        reject(file("{}", "") + "trailing");
        try { BackupFormat.INSTANCE.parse(new byte[BackupFormat.MAX_BYTES + 1]); throw new AssertionError("size limit"); }
        catch (IllegalArgumentException expected) { count++; }
        try { BackupFormat.INSTANCE.parse(new byte[] {(byte)0xc3, (byte)0x28}); throw new AssertionError("UTF-8"); }
        catch (Exception expected) { check(expected instanceof java.nio.charset.CharacterCodingException); }
        System.out.println("PASS " + count + " backup format checks; user settings untouched");
    }
}
